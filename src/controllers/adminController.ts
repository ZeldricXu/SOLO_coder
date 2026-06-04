import { FastifyRequest, FastifyReply } from 'fastify';
import { AdapterManager } from '../adapters/AdapterManager';
import { NotificationQueue } from '../queue/NotificationQueue';
import { logger } from '../utils/logger';

export async function getHealthStatus(
  request: FastifyRequest,
  reply: FastifyReply
) {
  try {
    const adapterManager = AdapterManager.getInstance();
    const statuses = await adapterManager.getAllStatuses();

    return reply.send({
      status: 'healthy',
      timestamp: new Date().toISOString(),
      channels: statuses,
    });
  } catch (error: any) {
    logger.error('Failed to get health status', error);
    return reply.status(500).send({ error: 'Internal server error' });
  }
}

export async function getQueueStats(
  request: FastifyRequest,
  reply: FastifyReply
) {
  try {
    const queue = NotificationQueue.getInstance();
    const stats = await queue.getQueueStats();

    return reply.send({
      stats,
      timestamp: new Date().toISOString(),
    });
  } catch (error: any) {
    logger.error('Failed to get queue stats', error);
    return reply.status(500).send({ error: 'Internal server error' });
  }
}

export async function getDlqJobs(
  request: FastifyRequest<{
    Querystring: { limit?: string };
  }>,
  reply: FastifyReply
) {
  try {
    const limit = parseInt(request.query.limit || '100');
    const queue = NotificationQueue.getInstance();
    const jobs = await queue.getDlqJobs(limit);

    return reply.send({ jobs });
  } catch (error: any) {
    logger.error('Failed to get DLQ jobs', error);
    return reply.status(500).send({ error: 'Internal server error' });
  }
}

export async function retryDlqJob(
  request: FastifyRequest<{
    Params: { job_id: string };
  }>,
  reply: FastifyReply
) {
  try {
    const queue = NotificationQueue.getInstance();
    await queue.retryDlqJob(request.params.job_id);

    return reply.send({ success: true });
  } catch (error: any) {
    logger.error('Failed to retry DLQ job', error);
    return reply.status(500).send({ error: 'Internal server error' });
  }
}
