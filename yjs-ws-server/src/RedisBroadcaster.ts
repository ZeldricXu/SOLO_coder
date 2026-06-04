import Redis from 'ioredis';
import { BroadcastMessage, ServerConfig } from './types';

export class RedisBroadcaster {
  private publisher: Redis.Redis | null = null;
  private subscriber: Redis.Redis | null = null;
  private config: ServerConfig;
  private messageHandlers: Map<
    string,
    Set<(message: BroadcastMessage) => void>
  > = new Map();
  private serverId: string;

  constructor(config: ServerConfig) {
    this.config = config;
    this.serverId = Math.random().toString(36).substring(2, 10);
  }

  async connect(): Promise<void> {
    if (!this.config.redisUrl) {
      console.log('[RedisBroadcaster] No Redis URL provided, running in standalone mode');
      return;
    }

    this.publisher = new Redis(this.config.redisUrl, { lazyConnect: true });
    this.subscriber = new Redis(this.config.redisUrl, { lazyConnect: true });

    await Promise.all([
      this.publisher.connect(),
      this.subscriber.connect(),
    ]);

    await this.subscriber.subscribe(this.config.redisChannel);
    this.subscriber.on('message', this.handleRedisMessage.bind(this));

    console.log(`[RedisBroadcaster] Connected to Redis, channel: ${this.config.redisChannel}`);
  }

  private handleRedisMessage(channel: string, message: string): void {
    if (channel !== this.config.redisChannel) return;

    try {
      const parsed: BroadcastMessage & { serverId: string } = JSON.parse(message);
      
      if (parsed.serverId === this.serverId) {
        return;
      }

      const handlers = this.messageHandlers.get(parsed.documentId);
      if (handlers) {
        handlers.forEach((handler) => handler(parsed));
      }
    } catch (error) {
      console.error('[RedisBroadcaster] Failed to parse message:', error);
    }
  }

  broadcast(message: BroadcastMessage): void {
    if (!this.publisher) {
      return;
    }

    const messageWithServerId = {
      ...message,
      serverId: this.serverId,
    };

    this.publisher.publish(
      this.config.redisChannel,
      JSON.stringify(messageWithServerId, (_, value) => {
        if (value instanceof Uint8Array) {
          return { __type: 'Uint8Array', data: Array.from(value) };
        }
        return value;
      })
    );
  }

  subscribe(
    documentId: string,
    handler: (message: BroadcastMessage) => void
  ): () => void {
    if (!this.messageHandlers.has(documentId)) {
      this.messageHandlers.set(documentId, new Set());
    }

    const handlers = this.messageHandlers.get(documentId)!;
    handlers.add(handler);

    return () => {
      handlers.delete(handler);
      if (handlers.size === 0) {
        this.messageHandlers.delete(documentId);
      }
    };
  }

  async disconnect(): Promise<void> {
    if (this.publisher) {
      await this.publisher.quit();
      this.publisher = null;
    }
    if (this.subscriber) {
      await this.subscriber.quit();
      this.subscriber = null;
    }
    this.messageHandlers.clear();
  }
}

export function reviveUint8Arrays(key: string, value: any): any {
  if (value && value.__type === 'Uint8Array') {
    return new Uint8Array(value.data);
  }
  return value;
}
