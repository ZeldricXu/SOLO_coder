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
};

export default usageRoutes;
