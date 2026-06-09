import { CDNPublishStatus } from '@prisma/client';
import { Queue, Worker } from 'bullmq';
import { connectionPool } from '../tenant/connection-pool';
import { redisManager } from '../tenant/redis-manager';
import { generateId } from '@utils/crypto';
import { logger } from '@utils/logger';
import { config } from '@config/index';
import { TenantContext } from '@types/index';

export type CDNProvider = 'aliyun' | 'qiniu' | 'cloudflare' | 'aws';

export interface CDNRegion {
  code: string;
  name: string;
  provider: CDNProvider;
  baseUrl: string;
}

export interface PublishContentInput {
  contentId: string;
  modelId: string;
  regions: string[];
  paths: string[];
  cacheTtl?: number;
}

export interface InvalidateCacheInput {
  contentId?: string;
  modelId: string;
  paths: string[];
  regions: string[];
}

export interface PreWarmCacheInput {
  modelId: string;
  paths: string[];
  regions: string[];
}

export class CDNService {
  private prisma = connectionPool.getPlatformPrisma();
  private publishQueue: Queue;
  private invalidateQueue: Queue;
  private prewarmQueue: Queue;
  private workers: Worker[] = [];

  private regions: CDNRegion[] = [
    { code: 'cn-hangzhou', name: '华东1(杭州)', provider: 'aliyun', baseUrl: 'https://cdn-hz.example.com' },
    { code: 'cn-beijing', name: '华北2(北京)', provider: 'aliyun', baseUrl: 'https://cdn-bj.example.com' },
    { code: 'cn-guangzhou', name: '华南1(广州)', provider: 'aliyun', baseUrl: 'https://cdn-gz.example.com' },
    { code: 'cn-shanghai', name: '华东2(上海)', provider: 'qiniu', baseUrl: 'https://cdn-sh.example.com' },
    { code: 'us-west-1', name: '美西1(硅谷)', provider: 'cloudflare', baseUrl: 'https://cdn-us.example.com' },
    { code: 'eu-west-1', name: '欧西1(爱尔兰)', provider: 'aws', baseUrl: 'https://cdn-eu.example.com' },
    { code: 'ap-southeast-1', name: '亚太1(新加坡)', provider: 'aws', baseUrl: 'https://cdn-sg.example.com' },
  ];

  constructor() {
    const redis = redisManager.getDefaultClient();
    const connection = { host: config.redisHost, port: config.redisPort };

    this.publishQueue = new Queue('cdn-publish', { connection });
    this.invalidateQueue = new Queue('cdn-invalidate', { connection });
    this.prewarmQueue = new Queue('cdn-prewarm', { connection });

    this.startWorkers();
  }

  getRegions(): CDNRegion[] {
    return this.regions;
  }

  getRegion(code: string): CDNRegion | undefined {
    return this.regions.find(r => r.code === code);
  }

  async publishContent(
    tenant: TenantContext,
    input: PublishContentInput
  ): Promise<CDNPublishStatus[]> {
    if (!tenant.limits.enableCDN) {
      throw new Error('CDN is not enabled for this tenant');
    }

    const publishStatuses: CDNPublishStatus[] = [];

    for (const regionCode of input.regions) {
      const region = this.getRegion(regionCode);
      if (!region) {
        throw new Error(`Invalid region: ${regionCode}`);
      }

      for (const path of input.paths) {
        const status = await this.prisma.cDNPublishStatus.create({
          data: {
            id: generateId('cdn'),
            tenantId: tenant.tenantId,
            contentId: input.contentId,
            modelId: input.modelId,
            region: regionCode,
            path,
            status: 'publishing',
            provider: region.provider,
            cacheTtl: input.cacheTtl || 3600,
            publishedAt: null,
            failedReason: null,
            retryCount: 0,
          },
        });

        publishStatuses.push(status);

        await this.publishQueue.add(
          `publish:${status.id}`,
          {
            statusId: status.id,
            tenantId: tenant.tenantId,
            contentId: input.contentId,
            modelId: input.modelId,
            region: regionCode,
            path,
            baseUrl: region.baseUrl,
            provider: region.provider,
            cacheTtl: input.cacheTtl || 3600,
          },
          {
            attempts: 3,
            backoff: {
              type: 'exponential',
              delay: 1000,
            },
          }
        );
      }
    }

    logger.info(
      { tenantId: tenant.tenantId, contentId: input.contentId, count: publishStatuses.length },
      'Started CDN publish'
    );

    return publishStatuses;
  }

  async getPublishStatus(
    tenantId: string,
    contentId: string
  ): Promise<CDNPublishStatus[]> {
    return this.prisma.cDNPublishStatus.findMany({
      where: { tenantId, contentId },
      orderBy: { createdAt: 'desc' },
    });
  }

  async getPublishStatusById(
    tenantId: string,
    statusId: string
  ): Promise<CDNPublishStatus | null> {
    return this.prisma.cDNPublishStatus.findFirst({
      where: { id: statusId, tenantId },
    });
  }

  async listPublishStatuses(
    tenantId: string,
    options: {
      modelId?: string;
      status?: string;
      region?: string;
      page?: number;
      pageSize?: number;
    }
  ): Promise<{ total: number; items: CDNPublishStatus[] }> {
    const { modelId, status, region, page = 1, pageSize = 20 } = options;

    const where: any = { tenantId };
    if (modelId) where.modelId = modelId;
    if (status) where.status = status;
    if (region) where.region = region;

    const [total, items] = await Promise.all([
      this.prisma.cDNPublishStatus.count({ where }),
      this.prisma.cDNPublishStatus.findMany({
        where,
        orderBy: { createdAt: 'desc' },
        skip: (page - 1) * pageSize,
        take: pageSize,
      }),
    ]);

    return { total, items };
  }

  async invalidateCache(
    tenant: TenantContext,
    input: InvalidateCacheInput
  ): Promise<{ jobIds: string[] }> {
    if (!tenant.limits.enableCDN) {
      throw new Error('CDN is not enabled for this tenant');
    }

    const jobIds: string[] = [];

    for (const regionCode of input.regions) {
      const region = this.getRegion(regionCode);
      if (!region) {
        throw new Error(`Invalid region: ${regionCode}`);
      }

      const job = await this.invalidateQueue.add(
        `invalidate:${tenant.tenantId}:${Date.now()}`,
        {
          tenantId: tenant.tenantId,
          contentId: input.contentId,
          modelId: input.modelId,
          region: regionCode,
          paths: input.paths,
          provider: region.provider,
        },
        {
          attempts: 3,
          backoff: {
            type: 'exponential',
            delay: 1000,
          },
        }
      );

      jobIds.push(job.id as string);

      if (input.contentId) {
        await this.prisma.cDNPublishStatus.updateMany({
          where: {
            tenantId: tenant.tenantId,
            contentId: input.contentId,
            region: regionCode,
          },
          data: {
            status: 'invalidating',
          },
        });
      }
    }

    logger.info(
      { tenantId: tenant.tenantId, paths: input.paths, regions: input.regions },
      'Started CDN cache invalidation'
    );

    return { jobIds };
  }

  async preWarmCache(
    tenant: TenantContext,
    input: PreWarmCacheInput
  ): Promise<{ jobIds: string[] }> {
    if (!tenant.limits.enableCDN) {
      throw new Error('CDN is not enabled for this tenant');
    }

    const jobIds: string[] = [];

    for (const regionCode of input.regions) {
      const region = this.getRegion(regionCode);
      if (!region) {
        throw new Error(`Invalid region: ${regionCode}`);
      }

      const job = await this.prewarmQueue.add(
        `prewarm:${tenant.tenantId}:${Date.now()}`,
        {
          tenantId: tenant.tenantId,
          modelId: input.modelId,
          region: regionCode,
          paths: input.paths,
          baseUrl: region.baseUrl,
          provider: region.provider,
        },
        {
          attempts: 3,
          backoff: {
            type: 'exponential',
            delay: 2000,
          },
        }
      );

      jobIds.push(job.id as string);
    }

    logger.info(
      { tenantId: tenant.tenantId, paths: input.paths, regions: input.regions },
      'Started CDN cache pre-warming'
    );

    return { jobIds };
  }

  async getRegionStatus(
    tenant: TenantContext,
    contentId: string
  ): Promise<Array<{
    region: string;
    provider: string;
    status: string;
    url: string;
    publishedAt: Date | null;
    lastCheckedAt: Date | null;
  }>> {
    const statuses = await this.getPublishStatus(tenant.tenantId, contentId);

    return this.regions.map(region => {
      const latest = statuses
        .filter(s => s.region === region.code)
        .sort((a, b) => b.createdAt.getTime() - a.createdAt.getTime())[0];

      return {
        region: region.code,
        provider: region.provider,
        status: latest?.status || 'unpublished',
        url: latest ? `${region.baseUrl}${latest.path}` : `${region.baseUrl}/`,
        publishedAt: latest?.publishedAt || null,
        lastCheckedAt: latest?.updatedAt || null,
      };
    });
  }

  async deletePublishStatus(
    tenantId: string,
    statusId: string
  ): Promise<void> {
    await this.prisma.cDNPublishStatus.deleteMany({
      where: { id: statusId, tenantId },
    });
  }

  async getCDNStats(
    tenant: TenantContext,
    options: { startDate?: Date; endDate?: Date }
  ): Promise<{
    totalPublished: number;
    byRegion: Record<string, number>;
    byStatus: Record<string, number>;
    averagePublishTime: number;
  }> {
    const { startDate, endDate } = options;
    const where: any = { tenantId: tenant.tenantId };

    if (startDate) where.createdAt = { gte: startDate };
    if (endDate) where.createdAt = { ...where.createdAt, lte: endDate };

    const statuses = await this.prisma.cDNPublishStatus.findMany({ where });

    const byRegion: Record<string, number> = {};
    const byStatus: Record<string, number> = {};
    let totalPublishTime = 0;
    let publishedWithTime = 0;

    for (const s of statuses) {
      byRegion[s.region] = (byRegion[s.region] || 0) + 1;
      byStatus[s.status] = (byStatus[s.status] || 0) + 1;

      if (s.publishedAt && s.status === 'published') {
        totalPublishTime += s.publishedAt.getTime() - s.createdAt.getTime();
        publishedWithTime++;
      }
    }

    return {
      totalPublished: statuses.length,
      byRegion,
      byStatus,
      averagePublishTime: publishedWithTime > 0 ? totalPublishTime / publishedWithTime : 0,
    };
  }

  private startWorkers(): void {
    const connection = { host: config.redisHost, port: config.redisPort };

    const publishWorker = new Worker('cdn-publish', async (job) => {
      const { statusId, tenantId, contentId, region, path, baseUrl, provider, cacheTtl } = job.data;

      logger.debug(
        { jobId: job.id, statusId, contentId, region },
        'Processing CDN publish job'
      );

      try {
        await this.simulateProviderCall(provider, 'publish', path, cacheTtl);

        await this.prisma.cDNPublishStatus.update({
          where: { id: statusId },
          data: {
            status: 'published',
            publishedAt: new Date(),
            retryCount: job.attemptsMade,
          },
        });

        logger.info({ statusId, contentId, region }, 'CDN publish completed');
      } catch (error: any) {
        logger.error({ error, statusId, contentId, region }, 'CDN publish failed');

        await this.prisma.cDNPublishStatus.update({
          where: { id: statusId },
          data: {
            status: 'failed',
            failedReason: error.message,
            retryCount: job.attemptsMade,
          },
        });

        throw error;
      }
    }, { connection });

    const invalidateWorker = new Worker('cdn-invalidate', async (job) => {
      const { tenantId, contentId, modelId, region, paths, provider } = job.data;

      logger.debug(
        { jobId: job.id, contentId, region, paths },
        'Processing CDN invalidate job'
      );

      try {
        await this.simulateProviderCall(provider, 'invalidate', paths);

        if (contentId) {
          await this.prisma.cDNPublishStatus.updateMany({
            where: { tenantId, contentId, region },
            data: { status: 'published' },
          });
        }

        logger.info({ jobId: job.id, region, paths }, 'CDN invalidate completed');
      } catch (error: any) {
        logger.error({ error, jobId: job.id, region, paths }, 'CDN invalidate failed');
        throw error;
      }
    }, { connection });

    const prewarmWorker = new Worker('cdn-prewarm', async (job) => {
      const { tenantId, modelId, region, paths, baseUrl, provider } = job.data;

      logger.debug(
        { jobId: job.id, region, paths },
        'Processing CDN pre-warm job'
      );

      try {
        await this.simulateProviderCall(provider, 'prewarm', paths, baseUrl);

        logger.info({ jobId: job.id, region, paths }, 'CDN pre-warm completed');
      } catch (error: any) {
        logger.error({ error, jobId: job.id, region, paths }, 'CDN pre-warm failed');
        throw error;
      }
    }, { connection });

    this.workers = [publishWorker, invalidateWorker, prewarmWorker];

    for (const worker of this.workers) {
      worker.on('failed', (job, error) => {
        logger.error({ jobId: job?.id, error }, 'CDN job failed');
      });
    }
  }

  private async simulateProviderCall(
    provider: CDNProvider,
    action: string,
    paths: string | string[],
    extra?: unknown
  ): Promise<void> {
    const delay = 500 + Math.random() * 1500;
    await new Promise(resolve => setTimeout(resolve, delay));

    if (Math.random() < 0.02) {
      throw new Error(`${provider} ${action} failed: simulated network error`);
    }

    logger.debug(
      { provider, action, paths: Array.isArray(paths) ? paths.length : 1, extra },
      'CDN provider call simulated'
    );
  }

  async close(): Promise<void> {
    logger.info('Closing CDN service workers');
    for (const worker of this.workers) {
      await worker.close();
    }
    await Promise.all([
      this.publishQueue.close(),
      this.invalidateQueue.close(),
      this.prewarmQueue.close(),
    ]);
  }
}

export const cdnService = new CDNService();
