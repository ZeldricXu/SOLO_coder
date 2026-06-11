import type { FastifyRequest, FastifyReply } from 'fastify';
import { featureStoreService } from './service';
import type {
  FeatureSetCreateRequest,
  FeatureListRequest,
  FeatureGetRequest,
  FeatureIngestRequest,
} from '@mlops/shared';

export async function registerFeatureStoreRoutes(fastify: any): Promise<void> {
  const service = featureStoreService;

  fastify.post('/api/v1/feature-sets', async (request: FastifyRequest, reply: FastifyReply) => {
    const result = await service.createFeatureSet(request.body as FeatureSetCreateRequest);
    return reply.status(201).send(result);
  });

  fastify.get('/api/v1/feature-sets/:id', async (request: FastifyRequest<{ Params: { id: string } }>, reply: FastifyReply) => {
    const result = await service.getFeatureSet(request.params.id);
    if (!result) return reply.status(404).send({ error: 'Feature set not found' });
    return result;
  });

  fastify.get('/api/v1/feature-sets', async (request: FastifyRequest) => {
    return service.listFeatureSets(request.query as FeatureListRequest);
  });

  fastify.post('/api/v1/feature-sets/:id/versions', async (request: FastifyRequest<{ Params: { id: string } }>, reply: FastifyReply) => {
    const result = await service.createVersion({
      ...(request.body as any),
      featureSetId: request.params.id,
    });
    return reply.status(201).send(result);
  });

  fastify.post('/api/v1/features/get', async (request: FastifyRequest) => {
    return service.getOnlineFeatures(request.body as FeatureGetRequest);
  });

  fastify.post('/api/v1/features/ingest', async (request: FastifyRequest) => {
    return service.ingestFeatures(request.body as FeatureIngestRequest);
  });

  fastify.get('/api/v1/feature-sets/:id/features/:name/distribution', async (
    request: FastifyRequest<{ Params: { id: string; name: string } }>,
    reply: FastifyReply
  ) => {
    try {
      return service.getFeatureDistribution(request.params.id, request.params.name);
    } catch (error) {
      return reply.status(404).send({ error: error instanceof Error ? error.message : 'Not found' });
    }
  });
}
