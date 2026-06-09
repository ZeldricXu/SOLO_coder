import { FastifyPluginAsync, FastifyRequest } from 'fastify';
import { webhookService, CreateWebhookInput, UpdateWebhookInput } from './webhook-service';
import { TenantContext } from '@types/index';
import { z } from 'zod';

const webhookRoutes: FastifyPluginAsync = async (fastify) => {
  fastify.addHook('onRequest', async (request, reply) => {
    if (!request.tenant) {
      reply.status(401).send({ error: 'Unauthorized' });
      return;
    }
    if (!request.tenant.limits.enableWebhooks) {
      reply.status(403).send({ error: 'Webhooks not enabled' });
      return;
    }
  });

  fastify.post(
    '/webhooks',
    {
      schema: {
        body: z.object({
          name: z.string().min(1),
          url: z.string().url(),
          events: z.array(z.string()).min(1),
          secret: z.string().min(8),
          active: z.boolean().optional(),
          timeout: z.number().int().positive().optional(),
          maxRetries: z.number().int().min(1).max(10).optional(),
        }),
        tags: ['Webhook'],
        summary: 'Create webhook configuration',
      },
    },
    async (
      request: FastifyRequest<{ Body: CreateWebhookInput }> & { tenant: TenantContext }
    ) => {
      return webhookService.createWebhook(request.tenant, request.body);
    }
  );

  fastify.get(
    '/webhooks',
    {
      schema: {
        querystring: z.object({
          active: z.coerce.boolean().optional(),
          page: z.coerce.number().int().positive().default(1),
          pageSize: z.coerce.number().int().positive().max(100).default(20),
        }),
        tags: ['Webhook'],
        summary: 'List webhook configurations',
      },
    },
    async (
      request: FastifyRequest<{
        Querystring: { active?: boolean; page: number; pageSize: number };
      }> & { tenant: TenantContext }
    ) => {
      return webhookService.listWebhooks(request.tenant.tenantId, request.query);
    }
  );

  fastify.get(
    '/webhooks/:id',
    {
      schema: {
        params: z.object({ id: z.string() }),
        tags: ['Webhook'],
        summary: 'Get webhook configuration',
      },
    },
    async (
      request: FastifyRequest<{ Params: { id: string } }> & { tenant: TenantContext },
      reply
    ) => {
      const webhook = await webhookService.getWebhook(
        request.tenant.tenantId,
        request.params.id
      );
      if (!webhook) {
        reply.status(404).send({ error: 'Webhook not found' });
        return;
      }
      return webhook;
    }
  );

  fastify.put(
    '/webhooks/:id',
    {
      schema: {
        params: z.object({ id: z.string() }),
        body: z.object({
          name: z.string().min(1).optional(),
          url: z.string().url().optional(),
          events: z.array(z.string()).min(1).optional(),
          secret: z.string().min(8).optional(),
          active: z.boolean().optional(),
          timeout: z.number().int().positive().optional(),
          maxRetries: z.number().int().min(1).max(10).optional(),
        }),
        tags: ['Webhook'],
        summary: 'Update webhook configuration',
      },
    },
    async (
      request: FastifyRequest<{
        Params: { id: string };
        Body: UpdateWebhookInput;
      }> & { tenant: TenantContext }
    ) => {
      return webhookService.updateWebhook(
        request.tenant.tenantId,
        request.params.id,
        request.body
      );
    }
  );

  fastify.delete(
    '/webhooks/:id',
    {
      schema: {
        params: z.object({ id: z.string() }),
        tags: ['Webhook'],
        summary: 'Delete webhook configuration',
      },
    },
    async (
      request: FastifyRequest<{ Params: { id: string } }> & { tenant: TenantContext },
      reply
    ) => {
      await webhookService.deleteWebhook(request.tenant.tenantId, request.params.id);
      reply.status(204).send();
    }
  );

  fastify.post(
    '/webhooks/:id/test',
    {
      schema: {
        params: z.object({ id: z.string() }),
        tags: ['Webhook'],
        summary: 'Test webhook delivery',
      },
    },
    async (
      request: FastifyRequest<{ Params: { id: string } }> & { tenant: TenantContext }
    ) => {
      return webhookService.testWebhook(request.tenant.tenantId, request.params.id);
    }
  );

  fastify.get(
    '/webhooks/deliveries',
    {
      schema: {
        querystring: z.object({
          webhookId: z.string().optional(),
          event: z.string().optional(),
          status: z.string().optional(),
          page: z.coerce.number().int().positive().default(1),
          pageSize: z.coerce.number().int().positive().max(100).default(20),
        }),
        tags: ['Webhook'],
        summary: 'List webhook deliveries',
      },
    },
    async (
      request: FastifyRequest<{
        Querystring: {
          webhookId?: string;
          event?: string;
          status?: string;
          page: number;
          pageSize: number;
        };
      }> & { tenant: TenantContext }
    ) => {
      return webhookService.listDeliveries(request.tenant.tenantId, request.query);
    }
  );

  fastify.get(
    '/webhooks/deliveries/:id',
    {
      schema: {
        params: z.object({ id: z.string() }),
        tags: ['Webhook'],
        summary: 'Get webhook delivery details',
      },
    },
    async (
      request: FastifyRequest<{ Params: { id: string } }> & { tenant: TenantContext },
      reply
    ) => {
      const delivery = await webhookService.getDelivery(
        request.tenant.tenantId,
        request.params.id
      );
      if (!delivery) {
        reply.status(404).send({ error: 'Delivery not found' });
        return;
      }
      return delivery;
    }
  );

  fastify.post(
    '/webhooks/deliveries/:id/retry',
    {
      schema: {
        params: z.object({ id: z.string() }),
        tags: ['Webhook'],
        summary: 'Retry failed webhook delivery',
      },
    },
    async (
      request: FastifyRequest<{ Params: { id: string } }> & { tenant: TenantContext },
      reply
    ) => {
      await webhookService.retryDelivery(request.tenant.tenantId, request.params.id);
      reply.status(202).send({ message: 'Retry queued' });
    }
  );

  fastify.get(
    '/webhooks/stats',
    {
      schema: {
        querystring: z.object({
          startDate: z.coerce.date().optional(),
          endDate: z.coerce.date().optional(),
        }),
        tags: ['Webhook'],
        summary: 'Get webhook statistics',
      },
    },
    async (
      request: FastifyRequest<{
        Querystring: { startDate?: Date; endDate?: Date };
      }> & { tenant: TenantContext }
    ) => {
      return webhookService.getWebhookStats(request.tenant, request.query);
    }
  );
};

export default webhookRoutes;
