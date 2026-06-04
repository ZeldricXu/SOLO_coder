import { FastifyRequest, FastifyReply } from 'fastify';
import { WebhookManager } from '../webhook/WebhookManager';
import { logger } from '../utils/logger';
import { z } from 'zod';

const WebhookEndpointSchema = z.object({
  url: z.string().url(),
  signing_secret: z.string(),
  event_types: z.array(z.string()),
  retry_config: z.object({
    max_retries: z.number().default(3),
    backoff_base: z.number().default(1000),
    backoff_multiplier: z.number().default(2),
  }),
  enabled: z.boolean().default(true),
});

export async function createWebhookEndpoint(
  request: FastifyRequest<{
    Body: z.infer<typeof WebhookEndpointSchema>;
    Headers: { 'x-tenant-id': string; 'x-actor': string };
  }>,
  reply: FastifyReply
) {
  try {
    const tenantId = request.headers['x-tenant-id'];
    const actor = request.headers['x-actor'] || 'system';
    if (!tenantId) {
      return reply.status(400).send({ error: 'Missing x-tenant-id header' });
    }

    const validation = WebhookEndpointSchema.safeParse(request.body);
    if (!validation.success) {
      return reply.status(400).send({
        error: 'Invalid request',
        details: validation.error.errors,
      });
    }

    const manager = WebhookManager.getInstance();
    const endpoint = await manager.createEndpoint(tenantId, validation.data as any, actor);

    return reply.status(201).send(endpoint);
  } catch (error: any) {
    logger.error('Failed to create webhook endpoint', error);
    return reply.status(500).send({ error: 'Internal server error' });
  }
}

export async function getWebhookEndpoints(
  request: FastifyRequest<{
    Headers: { 'x-tenant-id': string };
  }>,
  reply: FastifyReply
) {
  try {
    const tenantId = request.headers['x-tenant-id'];
    if (!tenantId) {
      return reply.status(400).send({ error: 'Missing x-tenant-id header' });
    }

    const manager = WebhookManager.getInstance();
    const endpoints = await manager.getEndpoints(tenantId);

    return reply.send({ endpoints });
  } catch (error: any) {
    logger.error('Failed to get webhook endpoints', error);
    return reply.status(500).send({ error: 'Internal server error' });
  }
}

export async function getWebhookEndpoint(
  request: FastifyRequest<{
    Params: { id: string };
    Headers: { 'x-tenant-id': string };
  }>,
  reply: FastifyReply
) {
  try {
    const tenantId = request.headers['x-tenant-id'];
    if (!tenantId) {
      return reply.status(400).send({ error: 'Missing x-tenant-id header' });
    }

    const manager = WebhookManager.getInstance();
    const endpoint = await manager.getEndpoint(tenantId, request.params.id);

    if (!endpoint) {
      return reply.status(404).send({ error: 'Webhook endpoint not found' });
    }

    return reply.send(endpoint);
  } catch (error: any) {
    logger.error('Failed to get webhook endpoint', error);
    return reply.status(500).send({ error: 'Internal server error' });
  }
}

export async function updateWebhookEndpoint(
  request: FastifyRequest<{
    Params: { id: string };
    Body: Partial<z.infer<typeof WebhookEndpointSchema>>;
    Headers: { 'x-tenant-id': string; 'x-actor': string };
  }>,
  reply: FastifyReply
) {
  try {
    const tenantId = request.headers['x-tenant-id'];
    const actor = request.headers['x-actor'] || 'system';
    if (!tenantId) {
      return reply.status(400).send({ error: 'Missing x-tenant-id header' });
    }

    const manager = WebhookManager.getInstance();
    const endpoint = await manager.updateEndpoint(tenantId, request.params.id, request.body as any, actor);

    if (!endpoint) {
      return reply.status(404).send({ error: 'Webhook endpoint not found' });
    }

    return reply.send(endpoint);
  } catch (error: any) {
    logger.error('Failed to update webhook endpoint', error);
    return reply.status(500).send({ error: 'Internal server error' });
  }
}

export async function deleteWebhookEndpoint(
  request: FastifyRequest<{
    Params: { id: string };
    Headers: { 'x-tenant-id': string; 'x-actor': string };
  }>,
  reply: FastifyReply
) {
  try {
    const tenantId = request.headers['x-tenant-id'];
    const actor = request.headers['x-actor'] || 'system';
    if (!tenantId) {
      return reply.status(400).send({ error: 'Missing x-tenant-id header' });
    }

    const manager = WebhookManager.getInstance();
    await manager.deleteEndpoint(tenantId, request.params.id, actor);

    return reply.send({ success: true });
  } catch (error: any) {
    logger.error('Failed to delete webhook endpoint', error);
    return reply.status(500).send({ error: 'Internal server error' });
  }
}

export async function getWebhookLogs(
  request: FastifyRequest<{
    Params: { id: string };
    Querystring: { limit?: string; offset?: string };
    Headers: { 'x-tenant-id': string };
  }>,
  reply: FastifyReply
) {
  try {
    const tenantId = request.headers['x-tenant-id'];
    if (!tenantId) {
      return reply.status(400).send({ error: 'Missing x-tenant-id header' });
    }

    const limit = parseInt(request.query.limit || '100');
    const offset = parseInt(request.query.offset || '0');

    const manager = WebhookManager.getInstance();
    const logs = await manager.getWebhookLogs(tenantId, request.params.id, limit, offset);

    return reply.send({ logs });
  } catch (error: any) {
    logger.error('Failed to get webhook logs', error);
    return reply.status(500).send({ error: 'Internal server error' });
  }
}
