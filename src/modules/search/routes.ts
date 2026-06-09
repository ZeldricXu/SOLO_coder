import { FastifyPluginAsync, FastifyRequest } from 'fastify';
import { searchService, ConfigureSearchInput, SearchInput } from './search-service';
import { TenantContext } from '@types/index';
import { z } from 'zod';

const searchRoutes: FastifyPluginAsync = async (fastify) => {
  fastify.addHook('onRequest', async (request, reply) => {
    if (!request.tenant) {
      reply.status(401).send({ error: 'Unauthorized' });
      return;
    }
    if (!request.tenant.limits.enableElasticsearch) {
      reply.status(403).send({ error: 'Search not enabled' });
      return;
    }
  });

  fastify.post(
    '/search/configure',
    {
      schema: {
        body: z.object({
          modelId: z.string(),
          fieldWeights: z.record(z.string(), z.number().min(0.1).max(10)),
          defaultOperator: z.enum(['AND', 'OR']).optional(),
          fuzziness: z.number().int().min(0).max(2).optional(),
          analyzer: z.string().optional(),
        }),
        tags: ['Search'],
        summary: 'Configure search for a content model',
      },
    },
    async (
      request: FastifyRequest<{ Body: ConfigureSearchInput }> & { tenant: TenantContext }
    ) => {
      return searchService.configureSearch(request.tenant, request.body);
    }
  );

  fastify.get(
    '/search/config/:modelId',
    {
      schema: {
        params: z.object({ modelId: z.string() }),
        tags: ['Search'],
        summary: 'Get search configuration',
      },
    },
    async (
      request: FastifyRequest<{ Params: { modelId: string } }> & { tenant: TenantContext },
      reply
    ) => {
      const config = await searchService.getSearchConfig(
        request.tenant.tenantId,
        request.params.modelId
      );
      if (!config) {
        reply.status(404).send({ error: 'Search config not found' });
        return;
      }
      return config;
    }
  );

  fastify.delete(
    '/search/config/:modelId',
    {
      schema: {
        params: z.object({ modelId: z.string() }),
        tags: ['Search'],
        summary: 'Delete search configuration and index',
      },
    },
    async (
      request: FastifyRequest<{ Params: { modelId: string } }> & { tenant: TenantContext },
      reply
    ) => {
      await searchService.deleteSearchConfig(
        request.tenant.tenantId,
        request.params.modelId,
        request.tenant.elasticIndexPrefix
      );
      reply.status(204).send();
    }
  );

  fastify.post(
    '/search',
    {
      schema: {
        body: z.object({
          modelId: z.string(),
          query: z.string().min(1),
          page: z.coerce.number().int().positive().default(1),
          pageSize: z.coerce.number().int().positive().max(100).default(20),
          filters: z.record(z.string(), z.any()).optional(),
          sortBy: z.string().optional(),
          sortOrder: z.enum(['asc', 'desc']).default('desc'),
          highlight: z.boolean().default(true),
        }),
        tags: ['Search'],
        summary: 'Search content',
      },
    },
    async (
      request: FastifyRequest<{ Body: SearchInput }> & { tenant: TenantContext }
    ) => {
      return searchService.search(request.tenant, request.body);
    }
  );

  fastify.post(
    '/search/index/:modelId/bulk',
    {
      schema: {
        params: z.object({ modelId: z.string() }),
        tags: ['Search'],
        summary: 'Bulk index all content for a model',
      },
    },
    async (
      request: FastifyRequest<{ Params: { modelId: string } }> & { tenant: TenantContext }
    ) => {
      return searchService.bulkIndexModel(request.tenant, request.params.modelId);
    }
  );

  fastify.get(
    '/search/stats/:modelId',
    {
      schema: {
        params: z.object({ modelId: z.string() }),
        tags: ['Search'],
        summary: 'Get search index stats',
      },
    },
    async (
      request: FastifyRequest<{ Params: { modelId: string } }> & { tenant: TenantContext }
    ) => {
      return searchService.getSearchStats(request.tenant, request.params.modelId);
    }
  );

  fastify.get(
    '/search/suggest',
    {
      schema: {
        querystring: z.object({
          modelId: z.string(),
          query: z.string().min(1),
          field: z.string(),
          size: z.coerce.number().int().positive().max(20).default(10),
        }),
        tags: ['Search'],
        summary: 'Get autocomplete suggestions',
      },
    },
    async (
      request: FastifyRequest<{
        Querystring: { modelId: string; query: string; field: string; size: number };
      }> & { tenant: TenantContext }
    ) => {
      const suggestions = await searchService.suggest(
        request.tenant,
        request.query.modelId,
        request.query.query,
        request.query.field,
        request.query.size
      );
      return { suggestions };
    }
  );
};

export default searchRoutes;
