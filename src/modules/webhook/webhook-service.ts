import { WebhookConfig, WebhookDelivery } from '@prisma/client';
import { Queue, Worker } from 'bullmq';
import { connectionPool } from '../tenant/connection-pool';
import { redisManager } from '../tenant/redis-manager';
import { generateId } from '@utils/crypto';
import { generateWebhookSignature, verifyWebhookSignature, createWebhookSigner, SignAlgorithm } from '@utils/webhook-signer';
import { logger } from '@utils/logger';
import { config } from '@config/index';
import { TenantContext } from '@types/index';

export type WebhookEventType =
  | 'content.created'
  | 'content.updated'
  | 'content.deleted'
  | 'content.published'
  | 'content.workflow.started'
  | 'content.workflow.approved'
  | 'content.workflow.rejected'
  | 'content.version.created'
  | 'content.search.updated'
  | 'cdn.published'
  | 'cdn.invalidated';

export interface WebhookPayload {
  event: WebhookEventType;
  tenantId: string;
  timestamp: number;
  data: Record<string, unknown>;
}

export interface CreateWebhookInput {
  name: string;
  url: string;
  events: WebhookEventType[];
  secret: string;
  active?: boolean;
  timeout?: number;
  maxRetries?: number;
}

export interface UpdateWebhookInput {
  name?: string;
  url?: string;
  events?: WebhookEventType[];
  secret?: string;
  active?: boolean;
  timeout?: number;
  maxRetries?: number;
}

export class WebhookService {
  private prisma = connectionPool.getPlatformPrisma();
  private deliveryQueue: Queue;
  private worker: Worker | null = null;

  constructor() {
    const connection = { host: config.redisHost, port: config.redisPort };
    this.deliveryQueue = new Queue('webhook-delivery', { connection });
    this.startWorker();
  }

  async createWebhook(
    tenant: TenantContext,
    input: CreateWebhookInput
  ): Promise<WebhookConfig> {
    if (!tenant.limits.enableWebhooks) {
      throw new Error('Webhooks are not enabled for this tenant');
    }

    const existing = await this.prisma.webhookConfig.findFirst({
      where: { tenantId: tenant.tenantId, url: input.url, deletedAt: null },
    });

    if (existing) {
      throw new Error('Webhook with this URL already exists');
    }

    const count = await this.prisma.webhookConfig.count({
      where: { tenantId: tenant.tenantId, deletedAt: null },
    });

    if (count >= tenant.limits.maxWebhooks) {
      throw new Error(`Maximum ${tenant.limits.maxWebhooks} webhooks allowed`);
    }

    const webhook = await this.prisma.webhookConfig.create({
      data: {
        id: generateId('wh'),
        tenantId: tenant.tenantId,
        name: input.name,
        url: input.url,
        events: input.events,
        secret: input.secret,
        active: input.active ?? true,
        timeout: input.timeout || 10000,
        maxRetries: input.maxRetries || 5,
        deletedAt: null,
      },
    });

    logger.info({ tenantId: tenant.tenantId, webhookId: webhook.id }, 'Created webhook');
    return webhook;
  }

  async updateWebhook(
    tenantId: string,
    webhookId: string,
    input: UpdateWebhookInput
  ): Promise<WebhookConfig> {
    const webhook = await this.prisma.webhookConfig.findFirst({
      where: { id: webhookId, tenantId, deletedAt: null },
    });

    if (!webhook) {
      throw new Error('Webhook not found');
    }

    const data: any = {};
    if (input.name !== undefined) data.name = input.name;
    if (input.url !== undefined) data.url = input.url;
    if (input.events !== undefined) data.events = input.events;
    if (input.secret !== undefined) data.secret = input.secret;
    if (input.active !== undefined) data.active = input.active;
    if (input.timeout !== undefined) data.timeout = input.timeout;
    if (input.maxRetries !== undefined) data.maxRetries = input.maxRetries;

    const updated = await this.prisma.webhookConfig.update({
      where: { id: webhookId },
      data,
    });

    logger.info({ tenantId, webhookId }, 'Updated webhook');
    return updated;
  }

  async deleteWebhook(tenantId: string, webhookId: string): Promise<void> {
    await this.prisma.webhookConfig.updateMany({
      where: { id: webhookId, tenantId, deletedAt: null },
      data: { deletedAt: new Date() },
    });

    logger.info({ tenantId, webhookId }, 'Deleted webhook');
  }

  async getWebhook(tenantId: string, webhookId: string): Promise<WebhookConfig | null> {
    return this.prisma.webhookConfig.findFirst({
      where: { id: webhookId, tenantId, deletedAt: null },
    });
  }

  async listWebhooks(
    tenantId: string,
    options: { active?: boolean; page?: number; pageSize?: number }
  ): Promise<{ total: number; items: WebhookConfig[] }> {
    const { active, page = 1, pageSize = 20 } = options;

    const where: any = { tenantId, deletedAt: null };
    if (active !== undefined) where.active = active;

    const [total, items] = await Promise.all([
      this.prisma.webhookConfig.count({ where }),
      this.prisma.webhookConfig.findMany({
        where,
        orderBy: { createdAt: 'desc' },
        skip: (page - 1) * pageSize,
        take: pageSize,
      }),
    ]);

    return { total, items };
  }

  async dispatchEvent(
    tenantId: string,
    event: WebhookEventType,
    data: Record<string, unknown>
  ): Promise<void> {
    const webhooks = await this.prisma.webhookConfig.findMany({
      where: {
        tenantId,
        active: true,
        deletedAt: null,
        events: {
          hasSome: [event],
        },
      },
    });

    if (webhooks.length === 0) {
      return;
    }

    const payload: WebhookPayload = {
      event,
      tenantId,
      timestamp: Date.now(),
      data,
    };

    for (const webhook of webhooks) {
      await this.enqueueDelivery(webhook, payload);
    }

    logger.debug(
      { tenantId, event, webhookCount: webhooks.length },
      'Dispatched webhook event'
    );
  }

  private async enqueueDelivery(
    webhook: WebhookConfig,
    payload: WebhookPayload
  ): Promise<void> {
    const delivery = await this.prisma.webhookDelivery.create({
      data: {
        id: generateId('whd'),
        tenantId: webhook.tenantId,
        webhookId: webhook.id,
        event: payload.event,
        url: webhook.url,
        payload: payload as any,
        status: 'pending',
        attempts: 0,
        lastAttemptAt: null,
        response: null,
        responseStatus: null,
        deletedAt: null,
      },
    });

    const signature = generateWebhookSignature(
      JSON.stringify(payload),
      webhook.secret,
      (webhook.signatureAlgorithm as SignAlgorithm) || 'HMAC-SHA256'
    );

    await this.deliveryQueue.add(
      `deliver:${delivery.id}`,
      {
        deliveryId: delivery.id,
        url: webhook.url,
        payload,
        signature,
        signatureAlgorithm: (webhook.signatureAlgorithm as SignAlgorithm) || 'HMAC-SHA256',
        timeout: webhook.timeout,
        maxRetries: webhook.maxRetries,
      },
      {
        attempts: webhook.maxRetries,
        backoff: {
          type: 'exponential',
          delay: 1000,
        },
        removeOnComplete: { age: 3600, count: 1000 },
        removeOnFail: { age: 86400, count: 5000 },
      }
    );
  }

  async getDelivery(
    tenantId: string,
    deliveryId: string
  ): Promise<WebhookDelivery | null> {
    return this.prisma.webhookDelivery.findFirst({
      where: { id: deliveryId, tenantId, deletedAt: null },
    });
  }

  async listDeliveries(
    tenantId: string,
    options: {
      webhookId?: string;
      event?: string;
      status?: string;
      page?: number;
      pageSize?: number;
    }
  ): Promise<{ total: number; items: WebhookDelivery[] }> {
    const { webhookId, event, status, page = 1, pageSize = 20 } = options;

    const where: any = { tenantId, deletedAt: null };
    if (webhookId) where.webhookId = webhookId;
    if (event) where.event = event;
    if (status) where.status = status;

    const [total, items] = await Promise.all([
      this.prisma.webhookDelivery.count({ where }),
      this.prisma.webhookDelivery.findMany({
        where,
        orderBy: { createdAt: 'desc' },
        skip: (page - 1) * pageSize,
        take: pageSize,
      }),
    ]);

    return { total, items };
  }

  async retryDelivery(tenantId: string, deliveryId: string): Promise<void> {
    const delivery = await this.prisma.webhookDelivery.findFirst({
      where: { id: deliveryId, tenantId, deletedAt: null },
    });

    if (!delivery) {
      throw new Error('Delivery not found');
    }

    const webhook = await this.prisma.webhookConfig.findFirst({
      where: { id: delivery.webhookId, deletedAt: null },
    });

    if (!webhook) {
      throw new Error('Webhook configuration not found');
    }

    const payload = delivery.payload as unknown as WebhookPayload;
    const signature = generateWebhookSignature(
      JSON.stringify(payload),
      webhook.secret,
      (webhook.signatureAlgorithm as SignAlgorithm) || 'HMAC-SHA256'
    );

    await this.deliveryQueue.add(
      `retry:${delivery.id}`,
      {
        deliveryId: delivery.id,
        url: webhook.url,
        payload,
        signature,
        signatureAlgorithm: (webhook.signatureAlgorithm as SignAlgorithm) || 'HMAC-SHA256',
        timeout: webhook.timeout,
        maxRetries: webhook.maxRetries,
      },
      {
        attempts: webhook.maxRetries,
        backoff: {
          type: 'exponential',
          delay: 1000,
        },
      }
    );

    await this.prisma.webhookDelivery.update({
      where: { id: deliveryId },
      data: {
        status: 'pending',
        attempts: 0,
        lastAttemptAt: null,
        response: null,
        responseStatus: null,
      },
    });

    logger.info({ tenantId, deliveryId }, 'Retrying webhook delivery');
  }

  async testWebhook(
    tenantId: string,
    webhookId: string
  ): Promise<{ success: boolean; response?: string; error?: string }> {
    const webhook = await this.prisma.webhookConfig.findFirst({
      where: { id: webhookId, tenantId, deletedAt: null },
    });

    if (!webhook) {
      throw new Error('Webhook not found');
    }

    const testPayload: WebhookPayload = {
      event: 'content.created',
      tenantId,
      timestamp: Date.now(),
      data: {
        test: true,
        message: 'This is a test webhook delivery',
      },
    };

    const signature = generateWebhookSignature(
      JSON.stringify(testPayload),
      webhook.secret,
      (webhook.signatureAlgorithm as SignAlgorithm) || 'HMAC-SHA256'
    );

    try {
      const result = await this.deliverWebhook(
        webhook.url,
        testPayload,
        signature,
        webhook.timeout
      );
      return result;
    } catch (error: any) {
      return { success: false, error: error.message };
    }
  }

  verifyWebhookSignature(
    payload: string,
    signatureHeader: string,
    secret: string,
    algorithm: SignAlgorithm = 'HMAC-SHA256'
  ): boolean {
    const result = verifyWebhookSignature(payload, signatureHeader, secret, algorithm);
    return result.valid;
  }

  private async deliverWebhook(
    url: string,
    payload: WebhookPayload,
    signature: string,
    timeout: number
  ): Promise<{ success: boolean; response?: string; responseStatus?: number }> {
    const controller = new AbortController();
    const timeoutId = setTimeout(() => controller.abort(), timeout);

    try {
      const response = await fetch(url, {
        method: 'POST',
        headers: {
          'Content-Type': 'application/json',
          'X-Webhook-Signature': signature,
          'X-Webhook-Timestamp': payload.timestamp.toString(),
          'X-Webhook-Event': payload.event,
        },
        body: JSON.stringify(payload),
        signal: controller.signal,
      });

      clearTimeout(timeoutId);

      const responseBody = await response.text();

      return {
        success: response.status >= 200 && response.status < 300,
        response: responseBody,
        responseStatus: response.status,
      };
    } catch (error: any) {
      clearTimeout(timeoutId);
      return {
        success: false,
        response: error.message,
        responseStatus: 0,
      };
    }
  }

  private startWorker(): void {
    const connection = { host: config.redisHost, port: config.redisPort };

    this.worker = new Worker('webhook-delivery', async (job) => {
      const { deliveryId, url, payload, signature, timeout, maxRetries } = job.data;

      logger.debug(
        { jobId: job.id, deliveryId, url, event: payload.event },
        'Processing webhook delivery'
      );

      const result = await this.deliverWebhook(url, payload, signature, timeout);

      const updateData: any = {
        attempts: job.attemptsMade + 1,
        lastAttemptAt: new Date(),
        response: result.response || null,
        responseStatus: result.responseStatus || null,
      };

      if (result.success) {
        updateData.status = 'delivered';
        logger.info({ deliveryId, event: payload.event }, 'Webhook delivered');
      } else if (job.attemptsMade >= maxRetries) {
        updateData.status = 'failed';
        logger.error(
          { deliveryId, event: payload.event, response: result.response },
          'Webhook delivery failed permanently'
        );
      } else {
        updateData.status = 'retrying';
        logger.warn(
          { deliveryId, event: payload.event, attempt: job.attemptsMade + 1 },
          'Webhook delivery failed, will retry'
        );
        throw new Error(result.response || 'Webhook delivery failed');
      }

      await this.prisma.webhookDelivery.update({
        where: { id: deliveryId },
        data: updateData,
      });
    }, { connection });

    this.worker.on('failed', (job, error) => {
      logger.error({ jobId: job?.id, error }, 'Webhook delivery worker failed');
    });

    this.worker.on('stalled', (jobId) => {
      logger.warn({ jobId }, 'Webhook delivery job stalled');
    });
  }

  async getWebhookStats(
    tenant: TenantContext,
    options: { startDate?: Date; endDate?: Date }
  ): Promise<{
    totalDeliveries: number;
    byStatus: Record<string, number>;
    byEvent: Record<string, number>;
    averageLatency: number;
    successRate: number;
  }> {
    const { startDate, endDate } = options;
    const where: any = { tenantId: tenant.tenantId, deletedAt: null };

    if (startDate) where.createdAt = { gte: startDate };
    if (endDate) where.createdAt = { ...where.createdAt, lte: endDate };

    const deliveries = await this.prisma.webhookDelivery.findMany({ where });

    const byStatus: Record<string, number> = {};
    const byEvent: Record<string, number> = {};
    let totalLatency = 0;
    let deliveriesWithTime = 0;
    let successCount = 0;

    for (const d of deliveries) {
      byStatus[d.status] = (byStatus[d.status] || 0) + 1;
      byEvent[d.event] = (byEvent[d.event] || 0) + 1;

      if (d.status === 'delivered') {
        successCount++;
      }

      if (d.lastAttemptAt && d.createdAt) {
        totalLatency += d.lastAttemptAt.getTime() - d.createdAt.getTime();
        deliveriesWithTime++;
      }
    }

    return {
      totalDeliveries: deliveries.length,
      byStatus,
      byEvent,
      averageLatency: deliveriesWithTime > 0 ? totalLatency / deliveriesWithTime : 0,
      successRate: deliveries.length > 0 ? successCount / deliveries.length : 0,
    };
  }

  async close(): Promise<void> {
    logger.info('Closing webhook service');
    if (this.worker) {
      await this.worker.close();
    }
    await this.deliveryQueue.close();
  }
}

export const webhookService = new WebhookService();
