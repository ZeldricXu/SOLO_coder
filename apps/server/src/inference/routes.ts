import type { FastifyRequest, FastifyReply } from 'fastify';
import { logger } from '../config/logger';
import { inferenceGateway } from './gateway';
import type { InferenceRequest, BatchInferenceRequest } from '@mlops/shared';

export async function registerInferenceRoutes(fastify: any): Promise<void> {
  const gateway = inferenceGateway;

  fastify.post('/api/v1/inference', async (request: FastifyRequest, reply: FastifyReply) => {
    try {
      const result = await gateway.infer(request.body as InferenceRequest);
      return result;
    } catch (error) {
      logger.error({ error }, 'Inference failed');
      return reply.status(500).send({
        error: error instanceof Error ? error.message : 'Inference failed',
      });
    }
  });

  fastify.post('/api/v1/inference/batch', async (request: FastifyRequest, reply: FastifyReply) => {
    try {
      const result = await gateway.batchInfer(request.body as BatchInferenceRequest);
      return result;
    } catch (error) {
      logger.error({ error }, 'Batch inference failed');
      return reply.status(500).send({
        error: error instanceof Error ? error.message : 'Batch inference failed',
      });
    }
  });

  fastify.get('/api/v1/inference/status', async () => {
    return gateway.getStatus();
  });

  fastify.post('/api/v1/models/:id/load', async (request: FastifyRequest<{ Params: { id: string }; Body: { versionId?: string } }>, reply: FastifyReply) => {
    try {
      const result = await gateway.loadModel(request.params.id, request.body.versionId);
      return reply.status(200).send({
        modelId: result.modelId,
        version: result.version,
        status: 'loaded',
        memoryUsageBytes: result.memoryUsageBytes,
      });
    } catch (error) {
      return reply.status(500).send({ error: error instanceof Error ? error.message : 'Failed to load model' });
    }
  });

  fastify.post('/api/v1/models/:id/unload', async (request: FastifyRequest<{ Params: { id: string }; Body: { versionId: string } }>, reply: FastifyReply) => {
    try {
      await gateway.unloadModel(request.params.id, request.body.versionId);
      return reply.status(200).send({ status: 'unloaded' });
    } catch (error) {
      return reply.status(500).send({ error: error instanceof Error ? error.message : 'Failed to unload model' });
    }
  });
}
