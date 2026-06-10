import { FastifyPluginAsync, FastifyRequest } from 'fastify';
import { cdnService, PublishContentInput, InvalidateCacheInput, PreWarmCacheInput } from './cdn-service';
import { TenantContext } from '@types/index';
import { z } from 'zod';

const cdnRoutes: FastifyPluginAsync = async (fastify) => {
  fastify.addHook('onRequest', async (request, reply) => {
    if (!request.tenant) {
      reply.status(401).send({ error: 'Unauthorized' });
      return;
    }
    if (!request.tenant.limits.enableCDN) {
      reply.status(403).send({ error: 'CDN not enabled' });
      return;
    }
  });

  fastify.get(
    '/cdn/regions',
    {
      schema: {
        tags: ['CDN'],
        summary: 'List available CDN regions',
      },
    },
    async () => {
      return { regions: cdnService.getRegions() };
    }
  );

  fastify.post(
    '/cdn/publish',
    {
      schema: {
        body: z.object({
          contentId: z.string(),
          modelId: z.string(),
          regions: z.array(z.string()).min(1),
          paths: z.array(z.string()).min(1),
          cacheTtl: z.number().int().positive().optional(),
        }),
        tags: ['CDN'],
        summary: 'Publish content to CDN',
      },
    },
    async (
      request: FastifyRequest<{ Body: PublishContentInput }> & { tenant: TenantContext }
    ) => {
      return cdnService.publishContent(request.tenant, request.body);
    }
  );

  fastify.get(
    '/cdn/publish/:contentId',
    {
      schema: {
        params: z.object({ contentId: z.string() }),
        tags: ['CDN'],
        summary: 'Get publish status for content',
      },
    },
    async (
      request: FastifyRequest<{ Params: { contentId: string } }> & { tenant: TenantContext }
    ) => {
      return cdnService.getPublishStatus(request.tenant.tenantId, request.params.contentId);
    }
  );

  fastify.get(
    '/cdn/publish/detail/:statusId',
    {
      schema: {
        params: z.object({ statusId: z.string() }),
        tags: ['CDN'],
        summary: 'Get publish status by ID',
      },
    },
    async (
      request: FastifyRequest<{ Params: { statusId: string } }> & { tenant: TenantContext },
      reply
    ) => {
      const status = await cdnService.getPublishStatusById(
        request.tenant.tenantId,
        request.params.statusId
      );
      if (!status) {
        reply.status(404).send({ error: 'Publish status not found' });
        return;
      }
      return status;
    }
  );

  fastify.get(
    '/cdn/publish',
    {
      schema: {
        querystring: z.object({
          modelId: z.string().optional(),
          status: z.string().optional(),
          region: z.string().optional(),
          page: z.coerce.number().int().positive().default(1),
          pageSize: z.coerce.number().int().positive().max(100).default(20),
        }),
        tags: ['CDN'],
        summary: 'List publish statuses',
      },
    },
    async (
      request: FastifyRequest<{
        Querystring: {
          modelId?: string;
          status?: string;
          region?: string;
          page: number;
          pageSize: number;
        };
      }> & { tenant: TenantContext }
    ) => {
      return cdnService.listPublishStatuses(request.tenant.tenantId, request.query);
    }
  );

  fastify.post(
    '/cdn/invalidate',
    {
      schema: {
        body: z.object({
          contentId: z.string().optional(),
          modelId: z.string(),
          paths: z.array(z.string()).min(1),
          regions: z.array(z.string()).min(1),
        }),
        tags: ['CDN'],
        summary: 'Invalidate CDN cache',
      },
    },
    async (
      request: FastifyRequest<{ Body: InvalidateCacheInput }> & { tenant: TenantContext }
    ) => {
      return cdnService.invalidateCache(request.tenant, request.body);
    }
  );

  fastify.post(
    '/cdn/prewarm',
    {
      schema: {
        body: z.object({
          modelId: z.string(),
          paths: z.array(z.string()).min(1),
          regions: z.array(z.string()).min(1),
        }),
        tags: ['CDN'],
        summary: 'Pre-warm CDN cache',
      },
    },
    async (
      request: FastifyRequest<{ Body: PreWarmCacheInput }> & { tenant: TenantContext }
    ) => {
      return cdnService.preWarmCache(request.tenant, request.body);
    }
  );

  fastify.get(
    '/cdn/status/:contentId/regions',
    {
      schema: {
        params: z.object({ contentId: z.string() }),
        tags: ['CDN'],
        summary: 'Get publish status by region for content',
      },
    },
    async (
      request: FastifyRequest<{ Params: { contentId: string } }> & { tenant: TenantContext }
    ) => {
      return cdnService.getRegionStatus(request.tenant, request.params.contentId);
    }
  );

  fastify.delete(
    '/cdn/publish/:statusId',
    {
      schema: {
        params: z.object({ statusId: z.string() }),
        tags: ['CDN'],
        summary: 'Delete publish status record',
      },
    },
    async (
      request: FastifyRequest<{ Params: { statusId: string } }> & { tenant: TenantContext },
      reply
    ) => {
      await cdnService.deletePublishStatus(request.tenant.tenantId, request.params.statusId);
      reply.status(204).send();
    }
  );

  fastify.get(
    '/cdn/stats',
    {
      schema: {
        querystring: z.object({
          startDate: z.coerce.date().optional(),
          endDate: z.coerce.date().optional(),
        }),
        tags: ['CDN'],
        summary: 'Get CDN usage statistics',
      },
    },
    async (
      request: FastifyRequest<{
        Querystring: { startDate?: Date; endDate?: Date };
      }> & { tenant: TenantContext }
    ) => {
      return cdnService.getCDNStats(request.tenant, request.query);
    }
  );

  fastify.post(
    '/cdn/publish/all-regions',
    {
      schema: {
        body: z.object({
          contentId: z.string(),
          modelId: z.string(),
          paths: z.array(z.string()).min(1),
          cacheTtl: z.number().int().positive().optional(),
        }),
        tags: ['CDN'],
        summary: 'Publish content to all available CDN regions',
      },
    },
    async (
      request: FastifyRequest<{ Body: Omit<PublishContentInput, 'regions'> }> & { tenant: TenantContext }
    ) => {
      return cdnService.publishToAllRegions(request.tenant, request.body);
    }
  );

  fastify.post(
    '/cdn/publish/multi-status',
    {
      schema: {
        body: z.object({
          contentIds: z.array(z.string()).min(1).max(100),
        }),
        tags: ['CDN'],
        summary: 'Get multi-region publish status for multiple contents',
      },
    },
    async (
      request: FastifyRequest<{ Body: { contentIds: string[] } }> & { tenant: TenantContext }
    ) => {
      return cdnService.getMultiRegionStatus(request.tenant, request.body.contentIds);
    }
  );

  fastify.post(
    '/cdn/publish/:contentId/retry/:region',
    {
      schema: {
        params: z.object({
          contentId: z.string(),
          region: z.string(),
        }),
        tags: ['CDN'],
        summary: 'Retry failed publish for a specific region',
      },
    },
    async (
      request: FastifyRequest<{ Params: { contentId: string; region: string } }> & { tenant: TenantContext },
      reply
    ) => {
      const result = await cdnService.retryFailedRegion(
        request.tenant,
        request.params.contentId,
        request.params.region
      );
      if (!result) {
        reply.status(404).send({ error: 'No failed publish found for this content and region' });
        return;
      }
      return result;
    }
  );

  fastify.post(
    '/cdn/invalidate/region',
    {
      schema: {
        body: z.object({
          contentId: z.string().optional(),
          modelId: z.string(),
          paths: z.array(z.string()).min(1),
          region: z.string(),
        }),
        tags: ['CDN'],
        summary: 'Invalidate cache for a specific region',
      },
    },
    async (
      request: FastifyRequest<{ Body: Omit<InvalidateCacheInput, 'regions'> & { region: string } }> & { tenant: TenantContext }
    ) => {
      return cdnService.invalidateRegionCache(request.tenant, request.body);
    }
  );

  fastify.post(
    '/cdn/prewarm/region',
    {
      schema: {
        body: z.object({
          modelId: z.string(),
          paths: z.array(z.string()).min(1),
          region: z.string(),
        }),
        tags: ['CDN'],
        summary: 'Pre-warm cache for a specific region',
      },
    },
    async (
      request: FastifyRequest<{ Body: Omit<PreWarmCacheInput, 'regions'> & { region: string } }> & { tenant: TenantContext }
    ) => {
      return cdnService.prewarmRegionCache(request.tenant, request.body);
    }
  );

  fastify.get(
    '/cdn/queue/status',
    {
      schema: {
        tags: ['CDN'],
        summary: 'Get CDN queue status by region',
      },
    },
    async (
      request: FastifyRequest & { tenant: TenantContext }
    ) => {
      return cdnService.getRegionQueueStatus(request.tenant);
    }
  );

  fastify.get(
    '/cdn/status/:contentId/summary',
    {
      schema: {
        params: z.object({ contentId: z.string() }),
        tags: ['CDN'],
        summary: 'Get content regions summary with counts',
      },
    },
    async (
      request: FastifyRequest<{ Params: { contentId: string } }> & { tenant: TenantContext }
    ) => {
      return cdnService.getContentRegionsSummary(request.tenant, request.params.contentId);
    }
  );
};

export default cdnRoutes;
