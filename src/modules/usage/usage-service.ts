import { TenantUsage } from '@prisma/client';
import { Queue, Worker } from 'bullmq';
import { connectionPool } from '../tenant/connection-pool';
import { redisManager } from '../tenant/redis-manager';
import { logger } from '@utils/logger';
import { config } from '@config/index';
import { TenantContext } from '@types/index';

export type UsageMetric =
  | 'api_calls'
  | 'storage_bytes'
  | 'content_entries'
  | 'content_models'
  | 'versions_count'
  | 'workflow_runs'
  | 'search_queries'
  | 'cdn_publishes'
  | 'webhook_deliveries'
  | 'bandwidth_bytes';

export interface IncrementUsageInput {
  tenantId: string;
  metric: UsageMetric;
  value?: number;
  date?: Date;
}

export interface RateLimitCheckResult {
  allowed: boolean;
  remaining: number;
  limit: number;
  resetAt: Date;
}

export class UsageService {
  private prisma = connectionPool.getPlatformPrisma();
  private usageQueue: Queue;
  private worker: Worker | null = null;

  private readonly RATE_LIMIT_PREFIX = 'rate_limit';
  private readonly USAGE_CACHE_PREFIX = 'usage_cache';

  constructor() {
    const connection = { host: config.redisHost, port: config.redisPort };
    this.usageQueue = new Queue('usage-tracking', { connection });
    this.startWorker();
  }

  async checkRateLimit(
    tenant: TenantContext,
    endpoint: string
  ): Promise<RateLimitCheckResult> {
    const redis = redisManager.getTenantClient(tenant.tenantId);
    const now = Date.now();
    const windowStart = Math.floor(now / 60000) * 60000;
    const key = `${this.RATE_LIMIT_PREFIX}:${tenant.tenantId}:${endpoint}:${windowStart}`;

    const limit = tenant.limits.rateLimitPerMinute;

    const current = await redis.incr(key);
    if (current === 1) {
      await redis.expireat(key, Math.ceil((windowStart + 60000) / 1000));
    }

    const remaining = Math.max(0, limit - current);

    return {
      allowed: current <= limit,
      remaining,
      limit,
      resetAt: new Date(windowStart + 60000),
    };
  }

  async checkDailyRateLimit(
    tenant: TenantContext
  ): Promise<RateLimitCheckResult> {
    const redis = redisManager.getTenantClient(tenant.tenantId);
    const now = new Date();
    const dayStart = new Date(now.getFullYear(), now.getMonth(), now.getDate()).getTime();
    const key = `${this.RATE_LIMIT_PREFIX}:daily:${tenant.tenantId}:${dayStart}`;

    const limit = tenant.limits.maxApiCallsPerDay;

    const current = await redis.incr(key);
    if (current === 1) {
      await redis.expireat(key, Math.ceil((dayStart + 86400000) / 1000));
    }

    const remaining = Math.max(0, limit - current);

    return {
      allowed: current <= limit,
      remaining,
      limit,
      resetAt: new Date(dayStart + 86400000),
    };
  }

  async trackApiCall(tenantId: string, endpoint: string, statusCode: number): Promise<void> {
    await this.usageQueue.add('track', {
      tenantId,
      metric: 'api_calls',
      value: 1,
      endpoint,
      statusCode,
      timestamp: Date.now(),
    });
  }

  async incrementUsage(input: IncrementUsageInput): Promise<void> {
    await this.usageQueue.add('increment', {
      ...input,
      timestamp: Date.now(),
    });
  }

  async getCurrentUsage(
    tenantId: string,
    period: 'day' | 'week' | 'month' | 'year' = 'month'
  ): Promise<TenantUsage[]> {
    const now = new Date();
    let startDate: Date;

    switch (period) {
      case 'day':
        startDate = new Date(now.getFullYear(), now.getMonth(), now.getDate());
        break;
      case 'week':
        startDate = new Date(now);
        startDate.setDate(now.getDate() - 7);
        break;
      case 'month':
        startDate = new Date(now.getFullYear(), now.getMonth(), 1);
        break;
      case 'year':
        startDate = new Date(now.getFullYear(), 0, 1);
        break;
    }

    return this.prisma.tenantUsage.findMany({
      where: {
        tenantId,
        date: { gte: startDate },
      },
      orderBy: { date: 'desc' },
    });
  }

  async getUsageSummary(
    tenant: TenantContext,
    period: 'day' | 'week' | 'month' | 'year' = 'month'
  ): Promise<{
    metrics: Record<UsageMetric, number>;
    limits: Record<string, number>;
    usagePercent: Record<string, number>;
  }> {
    const usage = await this.getCurrentUsage(tenant.tenantId, period);

    const metrics: Record<UsageMetric, number> = {
      api_calls: 0,
      storage_bytes: 0,
      content_entries: 0,
      content_models: 0,
      versions_count: 0,
      workflow_runs: 0,
      search_queries: 0,
      cdn_publishes: 0,
      webhook_deliveries: 0,
      bandwidth_bytes: 0,
    };

    for (const u of usage) {
      metrics.api_calls += u.apiCalls;
      metrics.storage_bytes = Math.max(metrics.storage_bytes, u.storageBytes);
      metrics.content_entries = Math.max(metrics.content_entries, u.contentEntries);
      metrics.content_models = Math.max(metrics.content_models, u.contentModels);
      metrics.versions_count += u.versionsCount;
      metrics.workflow_runs += u.workflowRuns;
      metrics.search_queries += u.searchQueries;
      metrics.cdn_publishes += u.cdnPublishes;
      metrics.webhook_deliveries += u.webhookDeliveries;
      metrics.bandwidth_bytes += u.bandwidthBytes;
    }

    const limits: Record<string, number> = {
      api_calls: tenant.limits.maxApiCallsPerDay,
      storage_bytes: tenant.limits.maxStorageBytes,
      content_entries: tenant.limits.maxContentEntries,
      content_models: tenant.limits.maxContentModels,
      versions_count: tenant.limits.maxVersionsPerContent,
      workflow_runs: tenant.limits.maxWorkflowRunsPerMonth,
      webhooks: tenant.limits.maxWebhooks,
    };

    const usagePercent: Record<string, number> = {
      api_calls: (metrics.api_calls / (tenant.limits.maxApiCallsPerDay * 30)) * 100,
      storage_bytes: (metrics.storage_bytes / tenant.limits.maxStorageBytes) * 100,
      content_entries: (metrics.content_entries / tenant.limits.maxContentEntries) * 100,
      content_models: (metrics.content_models / tenant.limits.maxContentModels) * 100,
      webhooks: ((await this.prisma.webhookConfig.count({
        where: { tenantId: tenant.tenantId, deletedAt: null },
      })) / tenant.limits.maxWebhooks) * 100,
    };

    return {
      metrics,
      limits,
      usagePercent,
    };
  }

  async updateStorageUsage(tenantId: string): Promise<number> {
    const [contentCount, versionCount] = await Promise.all([
      this.prisma.contentEntry.count({
        where: { tenantId, deletedAt: null },
      }),
      this.prisma.contentVersion.count({
        where: { tenantId },
      }),
    ]);

    const avgSizePerEntry = 2048;
    const avgSizePerVersion = 1024;
    const estimatedBytes = contentCount * avgSizePerEntry + versionCount * avgSizePerVersion;

    await this.updateDailyUsage(tenantId, new Date(), {
      storageBytes: estimatedBytes,
      contentEntries: contentCount,
    });

    return estimatedBytes;
  }

  async checkQuota(
    tenant: TenantContext,
    metric: 'storage' | 'entries' | 'models' | 'versions' | 'workflows'
  ): Promise<{ allowed: boolean; current: number; limit: number }> {
    let current = 0;
    let limit = 0;

    switch (metric) {
      case 'storage':
        current = await this.getCurrentStorage(tenant.tenantId);
        limit = tenant.limits.maxStorageBytes;
        break;
      case 'entries':
        current = await this.prisma.contentEntry.count({
          where: { tenantId: tenant.tenantId, deletedAt: null },
        });
        limit = tenant.limits.maxContentEntries;
        break;
      case 'models':
        current = await this.prisma.contentModel.count({
          where: { tenantId: tenant.tenantId, deletedAt: null },
        });
        limit = tenant.limits.maxContentModels;
        break;
      case 'versions':
        current = 0;
        limit = tenant.limits.maxVersionsPerContent;
        break;
      case 'workflows':
        current = await this.getWorkflowRunsThisMonth(tenant.tenantId);
        limit = tenant.limits.maxWorkflowRunsPerMonth;
        break;
    }

    return {
      allowed: current < limit,
      current,
      limit,
    };
  }

  private async getCurrentStorage(tenantId: string): Promise<number> {
    const today = new Date();
    today.setHours(0, 0, 0, 0);

    const usage = await this.prisma.tenantUsage.findFirst({
      where: { tenantId, date: { gte: today } },
      orderBy: { date: 'desc' },
    });

    if (usage) {
      return usage.storageBytes;
    }

    return this.updateStorageUsage(tenantId);
  }

  private async getWorkflowRunsThisMonth(tenantId: string): Promise<number> {
    const now = new Date();
    const monthStart = new Date(now.getFullYear(), now.getMonth(), 1);

    return this.prisma.workflowInstance.count({
      where: {
        tenantId,
        createdAt: { gte: monthStart },
      },
    });
  }

  private async updateDailyUsage(
    tenantId: string,
    date: Date,
    increments: Partial<{
      apiCalls: number;
      storageBytes: number;
      contentEntries: number;
      contentModels: number;
      versionsCount: number;
      workflowRuns: number;
      searchQueries: number;
      cdnPublishes: number;
      webhookDeliveries: number;
      bandwidthBytes: number;
    }>
  ): Promise<void> {
    const dayDate = new Date(date.getFullYear(), date.getMonth(), date.getDate());

    const existing = await this.prisma.tenantUsage.findFirst({
      where: { tenantId, date: dayDate },
    });

    if (existing) {
      await this.prisma.tenantUsage.update({
        where: { id: existing.id },
        data: {
          apiCalls: existing.apiCalls + (increments.apiCalls || 0),
          storageBytes: increments.storageBytes ?? existing.storageBytes,
          contentEntries: increments.contentEntries ?? existing.contentEntries,
          contentModels: increments.contentModels ?? existing.contentModels,
          versionsCount: existing.versionsCount + (increments.versionsCount || 0),
          workflowRuns: existing.workflowRuns + (increments.workflowRuns || 0),
          searchQueries: existing.searchQueries + (increments.searchQueries || 0),
          cdnPublishes: existing.cdnPublishes + (increments.cdnPublishes || 0),
          webhookDeliveries: existing.webhookDeliveries + (increments.webhookDeliveries || 0),
          bandwidthBytes: existing.bandwidthBytes + (increments.bandwidthBytes || 0),
        },
      });
    } else {
      await this.prisma.tenantUsage.create({
        data: {
          id: `usage_${tenantId}_${dayDate.toISOString().split('T')[0]}`,
          tenantId,
          date: dayDate,
          apiCalls: increments.apiCalls || 0,
          storageBytes: increments.storageBytes || 0,
          contentEntries: increments.contentEntries || 0,
          contentModels: increments.contentModels || 0,
          versionsCount: increments.versionsCount || 0,
          workflowRuns: increments.workflowRuns || 0,
          searchQueries: increments.searchQueries || 0,
          cdnPublishes: increments.cdnPublishes || 0,
          webhookDeliveries: increments.webhookDeliveries || 0,
          bandwidthBytes: increments.bandwidthBytes || 0,
        },
      });
    }
  }

  private startWorker(): void {
    const connection = { host: config.redisHost, port: config.redisPort };

    this.worker = new Worker('usage-tracking', async (job) => {
      const { name, data } = job;

      if (name === 'track' || name === 'increment') {
        const { tenantId, metric, value = 1, timestamp } = data;
        const date = new Date(timestamp);

        const increments: any = {};
        const metricMap: Record<string, string> = {
          api_calls: 'apiCalls',
          storage_bytes: 'storageBytes',
          content_entries: 'contentEntries',
          content_models: 'contentModels',
          versions_count: 'versionsCount',
          workflow_runs: 'workflowRuns',
          search_queries: 'searchQueries',
          cdn_publishes: 'cdnPublishes',
          webhook_deliveries: 'webhookDeliveries',
          bandwidth_bytes: 'bandwidthBytes',
        };

        const field = metricMap[metric];
        if (field) {
          increments[field] = value;
        }

        await this.updateDailyUsage(tenantId, date, increments);
      }
    }, { connection });

    this.worker.on('failed', (job, error) => {
      logger.error({ jobId: job?.id, error }, 'Usage tracking job failed');
    });
  }

  async getRateLimitStatus(
    tenant: TenantContext
  ): Promise<{
    minute: RateLimitCheckResult;
    daily: RateLimitCheckResult;
  }> {
    const [minute, daily] = await Promise.all([
      this.checkRateLimit(tenant, 'status'),
      this.checkDailyRateLimit(tenant),
    ]);

    return { minute, daily };
  }

  async close(): Promise<void> {
    logger.info('Closing usage service');
    if (this.worker) {
      await this.worker.close();
    }
    await this.usageQueue.close();
  }
}

export const usageService = new UsageService();
