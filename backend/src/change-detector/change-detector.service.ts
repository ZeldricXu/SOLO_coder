import {
  Injectable,
  Logger,
  NotFoundException,
  OnModuleDestroy,
  OnModuleInit,
} from '@nestjs/common';
import { DataSourceType } from '@prisma/client';
import { PrismaService } from '../prisma/prisma.service';
import { DataSourceService } from '../data-source/data-source.service';
import { RealtimeService } from '../realtime/realtime.service';
import { BaseChangeDetector, ChangeEvent } from './base-detector';
import { DetectorFactory } from './detector-factory';

@Injectable()
export class ChangeDetectorService implements OnModuleInit, OnModuleDestroy {
  private readonly logger = new Logger(ChangeDetectorService.name);
  private detectors = new Map<string, BaseChangeDetector>();
  private dataSourceBusinessLineMap = new Map<string, string>();

  constructor(
    private readonly prisma: PrismaService,
    private readonly dataSourceService: DataSourceService,
    private readonly realtimeService: RealtimeService,
  ) {}

  async onModuleInit() {
    this.logger.log('Initializing ChangeDetectorService...');

    try {
      const allDataSources = await this.dataSourceService.findAll();
      const supportedTypes: DataSourceType[] = [DataSourceType.MYSQL, DataSourceType.CLICKHOUSE];
      const eligibleDataSources = allDataSources.filter(
        (ds) => supportedTypes.includes(ds.type as DataSourceType) && (ds as any).isActive !== false,
      );

      this.logger.log(
        `Found ${eligibleDataSources.length} eligible data sources (${supportedTypes.join('/')}) for change detection`,
      );

      for (const ds of eligibleDataSources) {
        try {
          await this.startDetector(ds.id);
        } catch (err: any) {
          this.logger.error(
            `Failed to start detector for dataSource ${ds.id} (${ds.name}): ${err?.message ?? err}`,
          );
        }
      }
    } catch (err: any) {
      this.logger.error(`Failed to initialize change detectors: ${err?.message ?? err}`);
    }
  }

  async onModuleDestroy() {
    this.logger.log('Shutting down all change detectors...');
    const stopPromises: Promise<void>[] = [];
    for (const [dataSourceId, detector] of this.detectors.entries()) {
      this.logger.debug(`Stopping detector for dataSource ${dataSourceId}`);
      stopPromises.push(detector.stop());
      this.detectors.delete(dataSourceId);
    }
    await Promise.all(stopPromises);
    this.dataSourceBusinessLineMap.clear();
    this.logger.log('All change detectors stopped');
  }

  async startDetector(dataSourceId: string): Promise<void> {
    if (this.detectors.has(dataSourceId)) {
      this.logger.warn(`Detector already running for dataSource ${dataSourceId}`);
      return;
    }

    const ds = await this.dataSourceService.findOne(dataSourceId);
    const supportedTypes: DataSourceType[] = [DataSourceType.MYSQL, DataSourceType.CLICKHOUSE];
    if (!supportedTypes.includes(ds.type as DataSourceType)) {
      this.logger.warn(
        `Change detection not supported for dataSource ${dataSourceId} type ${ds.type}, skipping`,
      );
      return;
    }

    this.dataSourceBusinessLineMap.set(dataSourceId, ds.businessLineId);

    const detector = DetectorFactory.create(
      ds.type as DataSourceType,
      dataSourceId,
      ds.config as Record<string, any>,
    );

    detector.onEvent((event) => this.handleChangeEvent(event));

    this.detectors.set(dataSourceId, detector);
    await detector.start();

    this.logger.log(
      `Started detector for dataSource ${dataSourceId} (${ds.name}, type=${ds.type})`,
    );
  }

  async stopDetector(dataSourceId: string): Promise<void> {
    const detector = this.detectors.get(dataSourceId);
    if (!detector) {
      throw new NotFoundException(`No detector running for dataSource ${dataSourceId}`);
    }
    await detector.stop();
    this.detectors.delete(dataSourceId);
    this.dataSourceBusinessLineMap.delete(dataSourceId);
    this.logger.log(`Stopped detector for dataSource ${dataSourceId}`);
  }

  getDetectorStatus(dataSourceId: string): { running: boolean } {
    const detector = this.detectors.get(dataSourceId);
    if (!detector) {
      return { running: false };
    }
    return { running: (detector as any).isRunning ?? false };
  }

  getAllDetectorStatuses(): Array<{ dataSourceId: string; running: boolean }> {
    const result: Array<{ dataSourceId: string; running: boolean }> = [];
    for (const [dataSourceId, detector] of this.detectors.entries()) {
      result.push({
        dataSourceId,
        running: (detector as any).isRunning ?? false,
      });
    }
    return result;
  }

  private async handleChangeEvent(event: ChangeEvent): Promise<void> {
    try {
      const businessLineId = this.dataSourceBusinessLineMap.get(event.dataSourceId);
      const prismaAny = this.prisma as any;

      const created = await prismaAny.dataChangeEvent.create({
        data: {
          dataSourceId: event.dataSourceId,
          tableName: event.tableName,
          operation: event.operation,
          pk: event.pk ?? null,
          beforeData: event.beforeData ?? null,
          afterData: event.afterData ?? null,
          timestamp: event.timestamp,
          delivered: false,
        },
      });

      if (businessLineId) {
        await this.realtimeService.onDataChange(businessLineId, {
          eventId: created.id,
          dataSourceId: event.dataSourceId,
          tableName: event.tableName,
          operation: event.operation,
          pk: event.pk,
          beforeData: event.beforeData,
          afterData: event.afterData,
          timestamp: event.timestamp,
        });

        await prismaAny.dataChangeEvent.update({
          where: { id: created.id },
          data: { delivered: true },
        });
      }

      this.logger.debug(
        `Change event handled: [${event.operation}] ${event.dataSourceId}.${event.tableName}`,
      );
    } catch (err: any) {
      this.logger.error(
        `Failed to handle change event for dataSource ${event.dataSourceId}.${event.tableName}: ${err?.message ?? err}`,
      );
    }
  }
}
