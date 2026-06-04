import { FastifyRequest, FastifyReply } from 'fastify';
import { NotificationRequest, ChannelType } from '../types';
import { NotificationRouter } from '../router/NotificationRouter';
import { NotificationQueue } from '../queue/NotificationQueue';
import { DeliveryTracker } from '../tracking/DeliveryTracker';
import { SlidingWindowRateLimiter } from '../ratelimit/RateLimiter';
import { TemplateEngine } from '../templates/TemplateEngine';
import { AdapterManager } from '../adapters/AdapterManager';
import { logger } from '../utils/logger';
import { z } from 'zod';

const NotificationRequestSchema = z.object({
  tenant_id: z.string(),
  notification_type: z.enum(['transactional', 'marketing', 'security', 'system', 'password_reset', 'account_verification']),
  recipient: z.object({
    user_id: z.string().optional(),
    email: z.string().email().optional(),
    phone: z.string().optional(),
    push_token: z.string().optional(),
    slack_id: z.string().optional(),
    wechat_id: z.string().optional(),
    feishu_id: z.string().optional(),
  }),
  content: z.object({
    subject: z.string().optional(),
    body: z.string(),
    html: z.string().optional(),
    attachments: z.array(z.any()).optional(),
  }),
  channel_preference: z.array(z.enum(['email', 'sms', 'push', 'slack', 'wechat', 'feishu', 'webhook'])).optional(),
  priority: z.enum(['low', 'medium', 'high', 'urgent']).optional().default('medium'),
  omnichannel: z.boolean().optional().default(false),
  metadata: z.any().optional(),
  template_id: z.string().optional(),
  template_variables: z.any().optional(),
  locale: z.string().optional().default('en'),
});

export async function sendNotification(
  request: FastifyRequest<{ Body: NotificationRequest }>,
  reply: FastifyReply
) {
  try {
    const validation = NotificationRequestSchema.safeParse(request.body);
    if (!validation.success) {
      return reply.status(400).send({
        error: 'Invalid request',
        details: validation.error.errors,
      });
    }

    const notification = validation.data;
    const router = NotificationRouter.getInstance();
    const queue = NotificationQueue.getInstance();
    const tracker = DeliveryTracker.getInstance();
    const rateLimiter = SlidingWindowRateLimiter.getInstance();
    const adapterManager = AdapterManager.getInstance();
    const templateEngine = TemplateEngine.getInstance();

    if (notification.template_id || notification.template_variables) {
      const rendered = await templateEngine.render(
        notification.tenant_id,
        notification.notification_type,
        notification.template_variables || {},
        notification.locale
      );
      if (rendered) {
        notification.content = rendered;
      }
    }

    const { delivery_id, channels, results } = await router.route(notification);

    for (const channel of channels) {
      const rateLimitResult = await rateLimiter.checkAllLimits(
        notification.tenant_id,
        notification.recipient.user_id,
        channel
      );

      if (!rateLimitResult.allowed) {
        logger.warn('Rate limit exceeded, delaying message', {
          deliveryId: delivery_id,
          channel,
          reason: rateLimitResult.reason,
        });
        
        await queue.enqueue(
          notification,
          delivery_id,
          channel,
          (rateLimitResult.retryAfter || 60) * 1000
        );
        continue;
      }

      const adapter = adapterManager.getAdapter(channel);
      if (adapter) {
        const status = await adapter.getStatus();
        await tracker.createDeliveryLog(
          delivery_id,
          notification.tenant_id,
          notification.notification_type,
          channel,
          status.name,
          notification.recipient.email || notification.recipient.phone || notification.recipient.user_id || 'unknown',
          notification.priority,
          notification.metadata
        );
      }

      if (notification.omnichannel && notification.priority === 'urgent') {
        logger.info('Omnichannel urgent notification processed synchronously', { delivery_id });
      } else {
        await queue.enqueue(notification, delivery_id, channel);
      }
    }

    return reply.status(202).send({
      delivery_id,
      channels,
      status: channels.length > 0 ? 'queued' : 'no_channels_available',
      results,
    });
  } catch (error: any) {
    logger.error('Failed to send notification', error);
    return reply.status(500).send({
      error: 'Internal server error',
      message: error.message,
    });
  }
}

export async function getDeliveryStatus(
  request: FastifyRequest<{ Params: { id: string }; Headers: { 'x-tenant-id': string } }>,
  reply: FastifyReply
) {
  try {
    const tenantId = request.headers['x-tenant-id'];
    if (!tenantId) {
      return reply.status(400).send({ error: 'Missing x-tenant-id header' });
    }

    const tracker = DeliveryTracker.getInstance();
    const logs = await tracker.getByDeliveryId(tenantId, request.params.id);

    if (logs.length === 0) {
      return reply.status(404).send({ error: 'Delivery not found' });
    }

    return reply.send({
      delivery_id: request.params.id,
      logs,
    });
  } catch (error: any) {
    logger.error('Failed to get delivery status', error);
    return reply.status(500).send({ error: 'Internal server error' });
  }
}

export async function searchDeliveryLogs(
  request: FastifyRequest<{
    Querystring: {
      recipient?: string;
      start_time?: string;
      end_time?: string;
      channel?: ChannelType;
      status?: string;
      notification_type?: string;
      limit?: string;
      offset?: string;
    };
    Headers: { 'x-tenant-id': string };
  }>,
  reply: FastifyReply
) {
  try {
    const tenantId = request.headers['x-tenant-id'];
    if (!tenantId) {
      return reply.status(400).send({ error: 'Missing x-tenant-id header' });
    }

    const tracker = DeliveryTracker.getInstance();
    const { recipient, start_time, end_time, channel, status, notification_type, limit = '100', offset = '0' } = request.query;

    let logs;

    if (recipient) {
      logs = await tracker.getByRecipient(tenantId, recipient, parseInt(limit), parseInt(offset));
    } else if (start_time && end_time) {
      logs = await tracker.getByTimeRange(
        tenantId,
        new Date(start_time),
        new Date(end_time),
        {
          channel: channel as ChannelType,
          status: status as any,
          notification_type: notification_type as any,
        },
        parseInt(limit),
        parseInt(offset)
      );
    } else {
      return reply.status(400).send({ error: 'Either recipient or start_time/end_time must be provided' });
    }

    return reply.send({ logs });
  } catch (error: any) {
    logger.error('Failed to search delivery logs', error);
    return reply.status(500).send({ error: 'Internal server error' });
  }
}

export async function handleChannelCallback(
  request: FastifyRequest<{
    Params: { channel: string };
    Headers: { 'x-tenant-id': string };
    Body: any;
  }>,
  reply: FastifyReply
) {
  try {
    const tenantId = request.headers['x-tenant-id'];
    if (!tenantId) {
      return reply.status(400).send({ error: 'Missing x-tenant-id header' });
    }

    const tracker = DeliveryTracker.getInstance();
    const body = request.body as Record<string, any>;

    let messageId: string | undefined;
    let status: any = 'delivered';

    if (request.params.channel === 'email') {
      messageId = body.message_id || body.sg_message_id;
      if (body.event === 'open') status = 'read';
      if (body.event === 'click') status = 'clicked';
      if (body.event === 'bounce' || body.event === 'dropped') status = 'failed';
    } else if (request.params.channel === 'sms') {
      messageId = body.message_id || body.MessageId;
      if (body.status === 'delivered' || body.DeliveryStatus === 'SUCCESS') {
        status = 'delivered';
      } else if (body.status === 'failed') {
        status = 'failed';
      }
    }

    if (messageId) {
      await tracker.handleCallback(tenantId, messageId, status, body);
      return reply.send({ success: true });
    }

    return reply.status(400).send({ error: 'Could not extract message ID from callback' });
  } catch (error: any) {
    logger.error('Failed to handle channel callback', error);
    return reply.status(500).send({ error: 'Internal server error' });
  }
}
