import { FastifyPluginAsync, FastifyRequest } from 'fastify';
import { usageService } from './usage-service';
import { TenantContext } from '@types/index';
import { z } from 'zod';

const usageRoutes: FastifyPluginAsync = async (fastify) => {
  fastify.addHook('onRequest', async (request, reply) => {
    if (!request.tenant) {
      reply.status(401).send({ error: 'Unauthorized' });
      return;
    }
  });

  fastify.get(
    '/usage',
    {
      schema: {
        querystring: z.object({
          period: z.enum(['day', 'week', 'month', 'year']).default('month'),
        }),
        tags: ['Usage'],
        summary: 'Get usage summary with limits',
      },
    },
    async (
      request: FastifyRequest<{
        Querystring: { period: 'day' | 'week' | 'month' | 'year' };
      }> & { tenant: TenantContext }
    ) => {
      return usageService.getUsageSummary(request.tenant, request.query.period);
    }
  );

  fastify.get(
    '/usage/history',
    {
      schema: {
        querystring: z.object({
          period: z.enum(['day', 'week', 'month', 'year']).default('month'),
        }),
        tags: ['Usage'],
        summary: 'Get detailed usage history',
      },
    },
    async (
      request: FastifyRequest<{
        Querystring: { period: 'day' | 'week' | 'month' | 'year' };
      }> & { tenant: TenantContext }
    ) => {
      const history = await usageService.getCurrentUsage(
        request.tenant.tenantId,
        request.query.period
      );
      return { period: request.query.period, history };
    }
  );

  fastify.get(
    '/usage/rate-limit',
    {
      schema: {
        tags: ['Usage'],
        summary: 'Get current rate limit status',
      },
    },
    async (
      request: FastifyRequest & { tenant: TenantContext }
    ) => {
      return usageService.getRateLimitStatus(request.tenant);
    }
  );

  fastify.get(
    '/usage/quotas',
    {
      schema: {
        tags: ['Usage'],
        summary: 'Check all quota limits',
      },
    },
    async (
      request: FastifyRequest & { tenant: TenantContext }
    ) => {
      const [storage, entries, models, workflows] = await Promise.all([
        usageService.checkQuota(request.tenant, 'storage'),
        usageService.checkQuota(request.tenant, 'entries'),
        usageService.checkQuota(request.tenant, 'models'),
        usageService.checkQuota(request.tenant, 'workflows'),
      ]);

      return {
        storage,
        entries,
        models,
        workflows,
      };
    }
  );

  fastify.post(
    '/usage/refresh-storage',
    {
      schema: {
        tags: ['Usage'],
        summary: 'Refresh and recalculate storage usage',
      },
    },
    async (
      request: FastifyRequest & { tenant: TenantContext }
    ) => {
      const storageBytes = await usageService.updateStorageUsage(request.tenant.tenantId);
      return {
        storageBytes,
        storageMB: storageBytes / 1024 / 1024,
        storageGB: storageBytes / 1024 / 1024 / 1024,
        limitGB: request.tenant.limits.maxStorageBytes / 1024 / 1024 / 1024,
      };
    }
  );

  fastify.get(
    '/usage/analytics/api-trend',
    {
      schema: {
        querystring: z.object({
          startDate: z.coerce.date(),
          endDate: z.coerce.date(),
          granularity: z.enum(['hour', 'day', 'week', 'month']).default('day'),
          endpoint: z.string().optional(),
          statusCode: z.coerce.number().int().optional(),
        }),
        tags: ['Usage'],
        summary: 'Get API call trend with time series data',
      },
    },
    async (
      request: FastifyRequest<{
        Querystring: {
          startDate: Date;
          endDate: Date;
          granularity: 'hour' | 'day' | 'week' | 'month';
          endpoint?: string;
          statusCode?: number;
        };
      }> & { tenant: TenantContext }
    ) => {
      return usageService.getApiCallTrend(request.tenant, request.query);
    }
  );

  fastify.get(
    '/usage/analytics/storage-growth',
    {
      schema: {
        querystring: z.object({
          startDate: z.coerce.date(),
          endDate: z.coerce.date(),
          granularity: z.enum(['day', 'week', 'month']).default('day'),
        }),
        tags: ['Usage'],
        summary: 'Get storage growth curve over time',
      },
    },
    async (
      request: FastifyRequest<{
        Querystring: {
          startDate: Date;
          endDate: Date;
          granularity: 'day' | 'week' | 'month';
        };
      }> & { tenant: TenantContext }
    ) => {
      return usageService.getStorageGrowth(request.tenant, request.query);
    }
  );

  fastify.get(
    '/usage/analytics/content-ranking',
    {
      schema: {
        querystring: z.object({
          startDate: z.coerce.date().optional(),
          endDate: z.coerce.date().optional(),
          limit: z.coerce.number().int().positive().max(100).default(20),
          sortBy: z.enum(['views', 'edits', 'versions', 'workflow_runs']).default('views'),
          modelId: z.string().optional(),
        }),
        tags: ['Usage'],
        summary: 'Get content ranking by activity',
      },
    },
    async (
      request: FastifyRequest<{
        Querystring: {
          startDate?: Date;
          endDate?: Date;
          limit: number;
          sortBy: 'views' | 'edits' | 'versions' | 'workflow_runs';
          modelId?: string;
        };
      }> & { tenant: TenantContext }
    ) => {
      return usageService.getContentRanking(request.tenant, request.query);
    }
  );

  fastify.post(
    '/usage/analytics/multi-dimensional',
    {
      schema: {
        body: z.object({
          startDate: z.coerce.date(),
          endDate: z.coerce.date(),
          dimensions: z.array(z.enum(['time', 'content_type', 'metric', 'region'])).min(1),
          metrics: z.array(z.enum([
            'api_calls', 'storage_bytes', 'content_entries', 'content_models',
            'versions_count', 'workflow_runs', 'search_queries', 'cdn_publishes',
            'webhook_deliveries', 'bandwidth_bytes'
          ])).min(1),
          contentTypes: z.array(z.string()).optional(),
          regions: z.array(z.string()).optional(),
        }),
        tags: ['Usage'],
        summary: 'Multi-dimensional usage aggregation',
      },
    },
    async (
      request: FastifyRequest<{
        Body: {
          startDate: Date;
          endDate: Date;
          dimensions: Array<'time' | 'content_type' | 'metric' | 'region'>;
          metrics: Array<'api_calls' | 'storage_bytes' | 'content_entries' | 'content_models' |
            'versions_count' | 'workflow_runs' | 'search_queries' | 'cdn_publishes' |
            'webhook_deliveries' | 'bandwidth_bytes'>;
          contentTypes?: string[];
          regions?: string[];
        };
      }> & { tenant: TenantContext }
    ) => {
      return usageService.getMultiDimensionalAggregation(request.tenant, request.body);
    }
  );

  fastify.get(
    '/usage/analytics/tenant-ranking',
    {
      schema: {
        querystring: z.object({
          startDate: z.coerce.date().optional(),
          endDate: z.coerce.date().optional(),
          limit: z.coerce.number().int().positive().max(100).default(10),
          sortBy: z.enum(['api_calls', 'storage_bytes', 'content_entries', 'workflow_runs']).default('api_calls'),
        }),
        tags: ['Usage'],
        summary: 'Get tenant activity ranking (admin only)',
      },
    },
    async (
      request: FastifyRequest<{
        Querystring: {
          startDate?: Date;
          endDate?: Date;
          limit: number;
          sortBy: 'api_calls' | 'storage_bytes' | 'content_entries' | 'workflow_runs';
        };
      }> & { tenant: TenantContext }
    ) => {
      if (!request.tenant.isAdmin) {
        return { error: 'Admin access required' };
      }
      return usageService.getTenantActivityRank(request.query);
    }
  );
};

export default usageRoutes;
