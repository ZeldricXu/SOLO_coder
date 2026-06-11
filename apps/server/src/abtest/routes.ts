import type { FastifyRequest, FastifyReply } from 'fastify';
import { abTestEngine } from './engine';
import type {
  ABTestCreateRequest,
  ABTestUpdateRequest,
  ABTestListRequest,
  AssignmentRequest,
  TrackEventRequest,
} from '@mlops/shared';

export async function registerABTestRoutes(fastify: any): Promise<void> {
  const engine = abTestEngine;

  fastify.post('/api/v1/ab-tests', async (request: FastifyRequest, reply: FastifyReply) => {
    const result = await engine.createExperiment(request.body as ABTestCreateRequest);
    return reply.status(201).send(result);
  });

  fastify.get('/api/v1/ab-tests/:id', async (request: FastifyRequest<{ Params: { id: string } }>, reply: FastifyReply) => {
    const result = await engine.getExperiment(request.params.id);
    if (!result) return reply.status(404).send({ error: 'Experiment not found' });
    return result;
  });

  fastify.get('/api/v1/ab-tests', async (request: FastifyRequest) => {
    return engine.listExperiments(request.query as ABTestListRequest);
  });

  fastify.patch('/api/v1/ab-tests/:id', async (request: FastifyRequest<{ Params: { id: string } }>, reply: FastifyReply) => {
    const result = await engine.updateExperiment(request.params.id, request.body as ABTestUpdateRequest);
    if (!result) return reply.status(404).send({ error: 'Experiment not found' });
    return result;
  });

  fastify.post('/api/v1/ab-tests/assign', async (request: FastifyRequest) => {
    return engine.getAssignment(request.body as AssignmentRequest);
  });

  fastify.post('/api/v1/ab-tests/track', async (request: FastifyRequest) => {
    return engine.trackEvent(request.body as TrackEventRequest);
  });

  fastify.post('/api/v1/ab-tests/:id/results', async (request: FastifyRequest<{ Params: { id: string } }>) => {
    return engine.calculateResults(request.params.id);
  });

  fastify.get('/api/v1/ab-tests/:id/stats', async (request: FastifyRequest<{ Params: { id: string } }>) => {
    return engine.getRealTimeStats(request.params.id);
  });
}
