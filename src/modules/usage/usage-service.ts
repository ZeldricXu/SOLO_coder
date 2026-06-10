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

  async getApiCallTrend(
    tenant: TenantContext,
    options: {
      startDate: Date;
      endDate: Date;
      granularity: 'hour' | 'day' | 'week' | 'month';
      endpoint?: string;
      statusCode?: number;
    }
  ): Promise<{
    dataPoints: Array<{
      timestamp: Date;
      totalCalls: number;
      successCalls: number;
      errorCalls: number;
      avgLatencyMs?: number;
    }>;
    summary: {
      totalCalls: number;
      successRate: number;
      avgCallsPerPeriod: number;
      peakCalls: number;
      peakTimestamp: Date | null;
    };
  }> {
    const { startDate, endDate, granularity, endpoint, statusCode } = options;

    const usageRecords = await this.prisma.tenantUsage.findMany({
      where: {
        tenantId: tenant.tenantId,
        date: {
          gte: startDate,
          lte: endDate,
        },
      },
      orderBy: { date: 'asc' },
    });

    const dataPoints = this.aggregateByGranularity(usageRecords, granularity, startDate, endDate);

    let totalCalls = 0;
    let successCalls = 0;
    let errorCalls = 0;
    let peakCalls = 0;
    let peakTimestamp: Date | null = null;

    for (const dp of dataPoints) {
      totalCalls += dp.totalCalls;
      successCalls += dp.successCalls;
      errorCalls += dp.errorCalls;
      if (dp.totalCalls > peakCalls) {
        peakCalls = dp.totalCalls;
        peakTimestamp = dp.timestamp;
      }
    }

    return {
      dataPoints,
      summary: {
        totalCalls,
        successRate: totalCalls > 0 ? (successCalls / totalCalls) * 100 : 100,
        avgCallsPerPeriod: dataPoints.length > 0 ? totalCalls / dataPoints.length : 0,
        peakCalls,
        peakTimestamp,
      },
    };
  }

  async getStorageGrowth(
    tenant: TenantContext,
    options: {
      startDate: Date;
      endDate: Date;
      granularity: 'day' | 'week' | 'month';
    }
  ): Promise<{
    dataPoints: Array<{
      timestamp: Date;
      storageBytes: number;
      contentEntries: number;
      contentModels: number;
      versionsCount: number;
      growthFromPrevious: number;
    }>;
    summary: {
      startStorage: number;
      endStorage: number;
      totalGrowth: number;
      growthPercentage: number;
      avgGrowthPerPeriod: number;
    };
  }> {
    const { startDate, endDate, granularity } = options;

    const usageRecords = await this.prisma.tenantUsage.findMany({
      where: {
        tenantId: tenant.tenantId,
        date: {
          gte: startDate,
          lte: endDate,
        },
      },
      orderBy: { date: 'asc' },
    });

    const aggregated = this.aggregateStorageByGranularity(usageRecords, granularity, startDate, endDate);

    const dataPoints = aggregated.map((dp, index) => ({
      ...dp,
      growthFromPrevious: index > 0 ? dp.storageBytes - aggregated[index - 1].storageBytes : 0,
    }));

    const startStorage = dataPoints.length > 0 ? dataPoints[0].storageBytes : 0;
    const endStorage = dataPoints.length > 0 ? dataPoints[dataPoints.length - 1].storageBytes : 0;
    const totalGrowth = endStorage - startStorage;

    return {
      dataPoints,
      summary: {
        startStorage,
        endStorage,
        totalGrowth,
        growthPercentage: startStorage > 0 ? (totalGrowth / startStorage) * 100 : 0,
        avgGrowthPerPeriod: dataPoints.length > 1 ? totalGrowth / (dataPoints.length - 1) : 0,
      },
    };
  }

  async getContentRanking(
    tenant: TenantContext,
    options: {
      startDate?: Date;
      endDate?: Date;
      limit?: number;
      sortBy: 'views' | 'edits' | 'versions' | 'workflow_runs';
      modelId?: string;
    }
  ): Promise<{
    ranking: Array<{
      contentId: string;
      modelId: string;
      title: string;
      views: number;
      edits: number;
      versions: number;
      workflowRuns: number;
      score: number;
      lastActivity: Date | null;
    }>;
    totalContents: number;
    rankingPeriod: { startDate: Date | null; endDate: Date | null };
  }> {
    const { startDate, endDate, limit = 20, sortBy, modelId } = options;

    const whereClause: any = {
      tenantId: tenant.tenantId,
      deletedAt: null,
    };
    if (modelId) {
      whereClause.modelId = modelId;
    }

    const contents = await this.prisma.contentEntry.findMany({
      where: whereClause,
      include: {
        model: {
          select: { name: true },
        },
      },
      orderBy: { createdAt: 'desc' },
      take: 1000,
    });

    const ranking = [];

    for (const content of contents) {
      const activityWhere: any = {
        tenantId: tenant.tenantId,
        contentId: content.id,
      };
      if (startDate) activityWhere.createdAt = { ...activityWhere.createdAt, gte: startDate };
      if (endDate) activityWhere.createdAt = { ...activityWhere.createdAt, lte: endDate };

      const [versions, workflowRuns] = await Promise.all([
        this.prisma.contentVersion.count({
          where: {
            tenantId: tenant.tenantId,
            contentId: content.id,
            ...(startDate || endDate ? { createdAt: activityWhere.createdAt } : {}),
          },
        }),
        this.prisma.workflowInstance.count({
          where: {
            tenantId: tenant.tenantId,
            contentId: content.id,
            ...(startDate || endDate ? { createdAt: activityWhere.createdAt } : {}),
          },
        }),
      ]);

      const contentData = content.data as Record<string, unknown>;
      const views = (contentData.views as number) || 0;
      const edits = (contentData.editCount as number) || 0;

      let score = 0;
      switch (sortBy) {
        case 'views':
          score = views;
          break;
        case 'edits':
          score = edits;
          break;
        case 'versions':
          score = versions;
          break;
        case 'workflow_runs':
          score = workflowRuns;
          break;
      }

      ranking.push({
        contentId: content.id,
        modelId: content.modelId,
        title: (contentData.title as string) || 'Untitled',
        views,
        edits,
        versions,
        workflowRuns,
        score,
        lastActivity: content.updatedAt,
      });
    }

    ranking.sort((a, b) => b.score - a.score);

    return {
      ranking: ranking.slice(0, limit),
      totalContents: contents.length,
      rankingPeriod: { startDate: startDate || null, endDate: endDate || null },
    };
  }

  async getMultiDimensionalAggregation(
    tenant: TenantContext,
    options: {
      startDate: Date;
      endDate: Date;
      dimensions: Array<'time' | 'content_type' | 'metric' | 'region'>;
      metrics: UsageMetric[];
      contentTypes?: string[];
      regions?: string[];
    }
  ): Promise<{
    dimensions: string[];
    data: Array<Record<string, unknown>>;
    summary: Record<string, number>;
  }> {
    const { startDate, endDate, dimensions, metrics, contentTypes, regions } = options;

    const whereClause: any = {
      tenantId: tenant.tenantId,
      date: {
        gte: startDate,
        lte: endDate,
      },
    };

    const usageRecords = await this.prisma.tenantUsage.findMany({
      where: whereClause,
      orderBy: { date: 'asc' },
    });

    const contentModels = contentTypes ? await this.prisma.contentModel.findMany({
      where: {
        tenantId: tenant.tenantId,
        id: { in: contentTypes },
        deletedAt: null,
      },
    }) : await this.prisma.contentModel.findMany({
      where: { tenantId: tenant.tenantId, deletedAt: null },
    });

    const result: Array<Record<string, unknown>> = [];
    const summary: Record<string, number> = {};

    for (const metric of metrics) {
      summary[metric] = 0;
    }

    for (const record of usageRecords) {
      const row: Record<string, unknown> = {};

      if (dimensions.includes('time')) {
        row.date = record.date;
      }

      for (const metric of metrics) {
        const metricMap: Record<string, keyof typeof record> = {
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
          const value = record[field] as number;
          row[metric] = value;
          summary[metric] += value;
        }
      }

      if (dimensions.includes('content_type')) {
        row.contentTypes = contentModels.map(cm => ({ id: cm.id, name: cm.name }));
      }

      if (dimensions.includes('region')) {
        row.regions = regions || ['cn-hangzhou', 'us-west-1', 'eu-west-1'];
      }

      result.push(row);
    }

    return {
      dimensions,
      data: result,
      summary,
    };
  }

  async getTenantActivityRank(
    options: {
      startDate?: Date;
      endDate?: Date;
      limit?: number;
      sortBy: 'api_calls' | 'storage_bytes' | 'content_entries' | 'workflow_runs';
    }
  ): Promise<{
    ranking: Array<{
      tenantId: string;
      tenantName: string;
      apiCalls: number;
      storageBytes: number;
      contentEntries: number;
      workflowRuns: number;
      score: number;
      lastActive: Date | null;
    }>;
    totalTenants: number;
  }> {
    const { startDate, endDate, limit = 10, sortBy } = options;

    const tenants = await this.prisma.tenant.findMany({
      where: { deletedAt: null },
      take: 1000,
    });

    const ranking = [];

    for (const tenant of tenants) {
      const whereClause: any = {
        tenantId: tenant.id,
      };
      if (startDate) whereClause.date = { ...whereClause.date, gte: startDate };
      if (endDate) whereClause.date = { ...whereClause.date, lte: endDate };

      const usageRecords = await this.prisma.tenantUsage.findMany({
        where: whereClause,
      });

      let apiCalls = 0;
      let storageBytes = 0;
      let contentEntries = 0;
      let workflowRuns = 0;
      let lastActive: Date | null = null;

      for (const record of usageRecords) {
        apiCalls += record.apiCalls;
        storageBytes = Math.max(storageBytes, record.storageBytes);
        contentEntries = Math.max(contentEntries, record.contentEntries);
        workflowRuns += record.workflowRuns;
        if (!lastActive || record.date > lastActive) {
          lastActive = record.date;
        }
      }

      let score = 0;
      switch (sortBy) {
        case 'api_calls':
          score = apiCalls;
          break;
        case 'storage_bytes':
          score = storageBytes;
          break;
        case 'content_entries':
          score = contentEntries;
          break;
        case 'workflow_runs':
          score = workflowRuns;
          break;
      }

      ranking.push({
        tenantId: tenant.id,
        tenantName: tenant.name,
        apiCalls,
        storageBytes,
        contentEntries,
        workflowRuns,
        score,
        lastActive,
      });
    }

    ranking.sort((a, b) => b.score - a.score);

    return {
      ranking: ranking.slice(0, limit),
      totalTenants: tenants.length,
    };
  }

  private aggregateByGranularity(
    records: any[],
    granularity: 'hour' | 'day' | 'week' | 'month',
    startDate: Date,
    endDate: Date
  ): Array<{
    timestamp: Date;
    totalCalls: number;
    successCalls: number;
    errorCalls: number;
  }> {
    const buckets = new Map<string, {
      timestamp: Date;
      totalCalls: number;
      successCalls: number;
      errorCalls: number;
    }>();

    const current = new Date(startDate);
    while (current <= endDate) {
      const bucketKey = this.getBucketKey(current, granularity);
      const bucketDate = this.getBucketDate(current, granularity);
      buckets.set(bucketKey, {
        timestamp: bucketDate,
        totalCalls: 0,
        successCalls: 0,
        errorCalls: 0,
      });
      this.incrementBucketDate(current, granularity);
    }

    for (const record of records) {
      const bucketKey = this.getBucketKey(record.date, granularity);
      const bucket = buckets.get(bucketKey);
      if (bucket) {
        bucket.totalCalls += record.apiCalls;
        bucket.successCalls += Math.floor(record.apiCalls * 0.98);
        bucket.errorCalls += Math.floor(record.apiCalls * 0.02);
      }
    }

    return Array.from(buckets.values()).sort((a, b) => a.timestamp.getTime() - b.timestamp.getTime());
  }

  private aggregateStorageByGranularity(
    records: any[],
    granularity: 'day' | 'week' | 'month',
    startDate: Date,
    endDate: Date
  ): Array<{
    timestamp: Date;
    storageBytes: number;
    contentEntries: number;
    contentModels: number;
    versionsCount: number;
  }> {
    const buckets = new Map<string, {
      timestamp: Date;
      storageBytes: number;
      contentEntries: number;
      contentModels: number;
      versionsCount: number;
    }>();

    const current = new Date(startDate);
    while (current <= endDate) {
      const bucketKey = this.getBucketKey(current, granularity);
      const bucketDate = this.getBucketDate(current, granularity);
      buckets.set(bucketKey, {
        timestamp: bucketDate,
        storageBytes: 0,
        contentEntries: 0,
        contentModels: 0,
        versionsCount: 0,
      });
      this.incrementBucketDate(current, granularity);
    }

    for (const record of records) {
      const bucketKey = this.getBucketKey(record.date, granularity);
      const bucket = buckets.get(bucketKey);
      if (bucket) {
        bucket.storageBytes = Math.max(bucket.storageBytes, record.storageBytes);
        bucket.contentEntries = Math.max(bucket.contentEntries, record.contentEntries);
        bucket.contentModels = Math.max(bucket.contentModels, record.contentModels);
        bucket.versionsCount += record.versionsCount;
      }
    }

    return Array.from(buckets.values()).sort((a, b) => a.timestamp.getTime() - b.timestamp.getTime());
  }

  private getBucketKey(date: Date, granularity: string): string {
    const d = new Date(date);
    switch (granularity) {
      case 'hour':
        return `${d.getFullYear()}-${d.getMonth()}-${d.getDate()}-${d.getHours()}`;
      case 'day':
        return `${d.getFullYear()}-${d.getMonth()}-${d.getDate()}`;
      case 'week':
        const weekStart = new Date(d);
        weekStart.setDate(d.getDate() - d.getDay());
        return `${weekStart.getFullYear()}-${weekStart.getMonth()}-${weekStart.getDate()}`;
      case 'month':
        return `${d.getFullYear()}-${d.getMonth()}`;
      default:
        return `${d.getFullYear()}-${d.getMonth()}-${d.getDate()}`;
    }
  }

  private getBucketDate(date: Date, granularity: string): Date {
    const d = new Date(date);
    switch (granularity) {
      case 'hour':
        return new Date(d.getFullYear(), d.getMonth(), d.getDate(), d.getHours());
      case 'day':
        return new Date(d.getFullYear(), d.getMonth(), d.getDate());
      case 'week':
        const weekStart = new Date(d);
        weekStart.setDate(d.getDate() - d.getDay());
        return new Date(weekStart.getFullYear(), weekStart.getMonth(), weekStart.getDate());
      case 'month':
        return new Date(d.getFullYear(), d.getMonth(), 1);
      default:
        return new Date(d.getFullYear(), d.getMonth(), d.getDate());
    }
  }

  private incrementBucketDate(date: Date, granularity: string): void {
    switch (granularity) {
      case 'hour':
        date.setHours(date.getHours() + 1);
        break;
      case 'day':
        date.setDate(date.getDate() + 1);
        break;
      case 'week':
        date.setDate(date.getDate() + 7);
        break;
      case 'month':
        date.setMonth(date.getMonth() + 1);
        break;
    }
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
