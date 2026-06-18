import {
  Injectable,
  OnModuleInit,
  OnModuleDestroy,
  Inject,
  forwardRef,
  Logger,
} from '@nestjs/common';
import { ConfigService } from '@nestjs/config';
import Redis from 'ioredis';
import { Server } from 'socket.io';
import { RealtimeGateway } from './realtime.gateway';

const DEFAULT_THROTTLE_MS = 1000;
const REDIS_CHANNELS = ['metric:update', 'alert:trigger', 'data:change'] as const;

interface ThrottleEntry {
  lastPush: number;
  queue: unknown[];
  timer: ReturnType<typeof setTimeout> | null;
}

@Injectable()
export class RealtimeService implements OnModuleInit, OnModuleDestroy {
  private readonly logger = new Logger(RealtimeService.name);
  private publisher: Redis;
  private subscriber: Redis;
  private throttleMs: number;
  private throttleMap = new Map<string, ThrottleEntry>();

  constructor(
    private readonly configService: ConfigService,
    @Inject(forwardRef(() => RealtimeGateway))
    private readonly gateway: RealtimeGateway,
  ) {
    const host = this.configService.get<string>('REDIS_HOST', 'localhost');
    const port = this.configService.get<number>('REDIS_PORT', 6379);

    this.publisher = new Redis({ host, port });
    this.subscriber = new Redis({ host, port });

    this.throttleMs = this.configService.get<number>(
      'REALTIME_THROTTLE_MS',
      DEFAULT_THROTTLE_MS,
    );
  }

  async onModuleInit() {
    await this.subscriber.subscribe(...REDIS_CHANNELS);

    this.subscriber.on('message', (channel: string, message: string) => {
      try {
        const data = JSON.parse(message);

        switch (channel) {
          case 'metric:update':
            this.pushToRoom(`metric:${data.metricId}`, 'metric:update', data);
            break;
          case 'alert:trigger':
            this.pushToRoom(
              `businessLine:${data.businessLineId}`,
              'alert:trigger',
              data,
            );
            break;
          case 'data:change':
            this.pushToRoom(
              `businessLine:${data.businessLineId}`,
              'data:change',
              data,
            );
            break;
        }
      } catch (error) {
        this.logger.error(`Failed to process Redis message on ${channel}: ${error}`);
      }
    });

    this.logger.log('RealtimeService initialized with Redis subscriber');
  }

  async onModuleDestroy() {
    for (const [, entry] of this.throttleMap) {
      if (entry.timer) {
        clearTimeout(entry.timer);
      }
    }
    this.throttleMap.clear();

    await this.subscriber.unsubscribe(...REDIS_CHANNELS);
    await this.subscriber.quit();
    await this.publisher.quit();

    this.logger.log('RealtimeService destroyed');
  }

  private getServer(): Server {
    return this.gateway.server;
  }

  private pushToRoom(room: string, event: string, data: unknown) {
    const key = `${room}:${event}`;
    const now = Date.now();
    const entry = this.throttleMap.get(key);

    if (!entry) {
      this.throttleMap.set(key, {
        lastPush: now,
        queue: [],
        timer: null,
      });
      this.getServer().to(room).emit(event, data);
      return;
    }

    if (now - entry.lastPush >= this.throttleMs) {
      entry.lastPush = now;
      entry.queue = [];
      if (entry.timer) {
        clearTimeout(entry.timer);
        entry.timer = null;
      }
      this.getServer().to(room).emit(event, data);
    } else {
      entry.queue.push(data);
      if (!entry.timer) {
        const delay = this.throttleMs - (now - entry.lastPush);
        entry.timer = setTimeout(() => {
          const queued = entry.queue;
          entry.queue = [];
          entry.timer = null;
          entry.lastPush = Date.now();
          const merged = this.mergeUpdates(queued);
          this.getServer().to(room).emit(event, merged);
        }, delay);
      }
    }
  }

  private mergeUpdates(updates: unknown[]): unknown {
    if (updates.length === 0) return null;
    if (updates.length === 1) return updates[0];

    const latest = updates[updates.length - 1] as Record<string, unknown>;
    if (typeof latest === 'object' && latest !== null) {
      return {
        ...latest,
        _merged: true,
        _mergedCount: updates.length,
      };
    }

    return latest;
  }

  async onMetricUpdate(metricId: string, data: Record<string, unknown>) {
    const payload = { metricId, ...data };
    await this.publisher.publish(
      'metric:update',
      JSON.stringify(payload),
    );
  }

  async onAlertTrigger(alertData: Record<string, unknown> & { businessLineId: string }) {
    await this.publisher.publish(
      'alert:trigger',
      JSON.stringify(alertData),
    );
  }

  async onDataChange(businessLineId: string, data: Record<string, unknown>) {
    const payload = { businessLineId, ...data };
    await this.publisher.publish(
      'data:change',
      JSON.stringify(payload),
    );
  }

  broadcastToDashboard(dashboardId: string, event: string, data: unknown) {
    this.pushToRoom(`dashboard:${dashboardId}`, event, data);
  }

  broadcastToBusinessLine(businessLineId: string, event: string, data: unknown) {
    this.pushToRoom(`businessLine:${businessLineId}`, event, data);
  }
}
