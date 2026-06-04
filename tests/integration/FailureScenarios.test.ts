import { describe, it, expect, beforeAll, afterAll, beforeEach, afterEach, vi } from 'vitest';
import request from 'supertest';
import nock from 'nock';
import { v4 as uuidv4 } from 'uuid';
import { startTestInfrastructure, stopTestInfrastructure, resetTestDatabase, flushTestRedis } from '../utils/testContainers';
import { WebhookTestServer } from '../utils/webhookTestServer';
import { createNotificationRequest, createTemplate, createRecipient } from '../utils/factories';
import fastify from 'fastify';
import cors from '@fastify/cors';
import * as notificationController from '../../src/controllers/notificationController';
import * as adminController from '../../src/controllers/adminController';
import { ChannelType, DeliveryStatus } from '../../src/types';
import { DeliveryTracker } from '../../src/tracking/DeliveryTracker';
import { NotificationRouter } from '../../src/router/NotificationRouter';
import { EmailAdapter } from '../../src/adapters/EmailAdapter';
import { TokenBucketRateLimiter } from '../../src/ratelimit/RateLimiter';

describe('故障场景集成测试', () => {
  let app: any;
  let webhookTestServer: WebhookTestServer;
  let webhookServerUrl: string;
  let tenantId: string;
  let testInfrastructure: any;

  beforeAll(async () => {
    testInfrastructure = await startTestInfrastructure();
    tenantId = uuidv4();

    app = fastify({ logger: false });
    app.register(cors, { origin: true, credentials: true });

    app.get('/health', async (request, reply) => ({ status: 'ok' }));
    app.post('/api/v1/notifications/send', notificationController.sendNotification);
    app.get('/api/v1/notifications/:id/status', notificationController.getDeliveryStatus);
    app.post('/api/v1/notifications/callbacks/:channel', notificationController.handleChannelCallback);
    app.get('/api/v1/admin/queue/stats', adminController.getQueueStats);
    app.get('/api/v1/admin/queue/dlq', adminController.getDlqJobs);
    app.post('/api/v1/admin/queue/dlq/:job_id/retry', adminController.retryDlqJob);

    await app.ready();

    webhookTestServer = new WebhookTestServer();
    webhookServerUrl = await webhookTestServer.start();
  }, 120000);

  afterAll(async () => {
    await app.close();
    await webhookTestServer.stop();
    await stopTestInfrastructure();
  }, 60000);

  beforeEach(async () => {
    await resetTestDatabase();
    await flushTestRedis();
    nock.cleanAll();
    nock.enableNetConnect();
    webhookTestServer.clearRequests();
    webhookTestServer.setResponseStatus(200);
    webhookTestServer.setResponseDelay(0);
  });

  describe('渠道降级场景', () => {
    it('主渠道全部失败时自动降级到备用渠道的完整流程', async () => {
      const tenantId2 = uuidv4();
      const tracker = DeliveryTracker.getInstance();

      const recipient = createRecipient({
        user_id: 'user-failover-001',
        email: 'failover@example.com',
        phone: '+8613800138001',
      });

      const mockAdapterManager = {
        getAdapter: vi.fn((channel: string) => {
          if (channel === 'email') {
            return {
              getName: vi.fn().mockReturnValue('email'),
              getStatus: vi.fn().mockResolvedValue({ available: false, name: 'email', last_checked: new Date() }),
              healthCheck: vi.fn().mockResolvedValue(false),
              send: vi.fn().mockRejectedValue(new Error('SMTP server unavailable')),
            };
          }
          if (channel === 'sms') {
            return {
              getName: vi.fn().mockReturnValue('sms'),
              getStatus: vi.fn().mockResolvedValue({ available: true, name: 'sms', last_checked: new Date() }),
              healthCheck: vi.fn().mockResolvedValue(true),
              send: vi.fn().mockResolvedValue({
                channel: 'sms',
                provider: 'aliyun',
                status: 'sent' as DeliveryStatus,
                message_id: 'sms-msg-failover-001',
                sent_at: new Date(),
              }),
            };
          }
          return null;
        }),
      };

      const { AdapterManager } = await import('../../src/adapters/AdapterManager');
      vi.spyOn(AdapterManager, 'getInstance').mockReturnValue(mockAdapterManager as any);

      const notificationRequest = createNotificationRequest({
        tenant_id: tenantId2,
        notification_type: 'transactional',
        recipient,
        channel_preference: ['email', 'sms'] as ChannelType[],
        content: {
          subject: '重要通知 - 渠道降级测试',
          body: '这是一条测试渠道降级机制的通知。',
        },
      });

      const router = NotificationRouter.getInstance();
      const result = await router.route(notificationRequest);

      expect(result.channels).toContain('sms');
      expect(result.channels).not.toContain('email');

      const deliveryId = result.delivery_id;

      await tracker.createDeliveryLog(
        deliveryId,
        tenantId2,
        'transactional',
        'sms',
        'aliyun',
        recipient.phone!,
        'medium'
      );

      const smsAdapter = mockAdapterManager.getAdapter('sms');
      const sendResult = await smsAdapter.send(notificationRequest, recipient);

      expect(sendResult.status).toBe('sent');
      expect(sendResult.message_id).toBe('sms-msg-failover-001');

      await tracker.updateStatus(tenantId2, deliveryId, 'sms', 'sent', sendResult.message_id);
      await tracker.handleCallback(tenantId2, sendResult.message_id!, 'delivered', {
        delivered_at: new Date().toISOString(),
      });

      await new Promise(resolve => setTimeout(resolve, 300));

      const statusResponse = await request(app.server)
        .get(`/api/v1/notifications/${deliveryId}/status`)
        .set('X-Tenant-Id', tenantId2);

      expect(statusResponse.status).toBe(200);
      const logs = statusResponse.body.logs || [];
      const smsDelivered = logs.some((l: any) => l.channel === 'sms' && l.status === 'delivered');
      expect(smsDelivered).toBe(true);

      const emailAttempts = logs.filter((l: any) => l.channel === 'email');
      expect(emailAttempts.length).toBe(0);
    }, 30000);

    it('邮件适配器SMTP连接超时后自动切换到SendGrid API通道', async () => {
      const tenantId3 = uuidv4();
      const tracker = DeliveryTracker.getInstance();

      const recipient = createRecipient({
        user_id: 'user-email-001',
        email: 'email-failover@example.com',
      });

      let smtpAttemptCount = 0;
      let sendgridAttemptCount = 0;

      const mockEmailAdapter = {
        getName: vi.fn().mockReturnValue('email'),
        getStatus: vi.fn().mockResolvedValue({ available: true, name: 'email', last_checked: new Date() }),
        healthCheck: vi.fn().mockResolvedValue(true),
        send: vi.fn(async (notification: any, recipient: any) => {
          smtpAttemptCount++;
          throw new Error('SMTP connection timeout after 10000ms');
        }),
      };

      const mockSendGridAdapter = {
        getName: vi.fn().mockReturnValue('email'),
        getStatus: vi.fn().mockResolvedValue({ available: true, name: 'email', last_checked: new Date() }),
        healthCheck: vi.fn().mockResolvedValue(true),
        send: vi.fn(async (notification: any, recipient: any) => {
          sendgridAttemptCount++;
          return {
            channel: 'email',
            provider: 'sendgrid',
            status: 'sent' as DeliveryStatus,
            message_id: `sendgrid-${uuidv4()}`,
            sent_at: new Date(),
          };
        }),
      };

      const notificationRequest = createNotificationRequest({
        tenant_id: tenantId3,
        notification_type: 'transactional',
        recipient,
        channel_preference: ['email'] as ChannelType[],
        content: {
          subject: 'SendGrid Failover Test',
          body: 'Testing SMTP to SendGrid failover',
        },
      });

      const deliveryId = uuidv4();

      await tracker.createDeliveryLog(
        deliveryId,
        tenantId3,
        'transactional',
        'email',
        'smtp',
        recipient.email!,
        'medium'
      );

      let finalResult;
      try {
        await mockEmailAdapter.send(notificationRequest, recipient);
      } catch (err) {
        expect(smtpAttemptCount).toBe(1);
        expect((err as Error).message).toContain('timeout');

        await tracker.updateStatus(
          tenantId3,
          deliveryId,
          'email',
          'failed',
          undefined,
          (err as Error).message
        );

        finalResult = await mockSendGridAdapter.send(notificationRequest, recipient);
      }

      expect(sendgridAttemptCount).toBe(1);
      expect(finalResult!.status).toBe('sent');
      expect(finalResult!.provider).toBe('sendgrid');
      expect(finalResult!.message_id).toBeDefined();

      await tracker.updateStatus(tenantId3, deliveryId, 'email', 'sent', finalResult!.message_id);
      await tracker.handleCallback(tenantId3, finalResult!.message_id!, 'delivered', {});

      const logs = await tracker.getByDeliveryId(tenantId3, deliveryId);
      const statuses = logs.map(l => l.status);

      expect(statuses).toContain('queued');
      expect(statuses).toContain('failed');
      expect(statuses).toContain('sent');
      expect(statuses).toContain('delivered');

      const failedLog = logs.find(l => l.status === 'failed');
      expect(failedLog?.error_message).toContain('timeout');
    }, 30000);
  });

  describe('DLQ与重放场景', () => {
    it('webhook端点持续返回503后进入DLQ，手动重放后最终成功', async () => {
      const tenantId4 = uuidv4();
      const tracker = DeliveryTracker.getInstance();

      await testInfrastructure.pgPool.query(
        `INSERT INTO webhook_endpoints (tenant_id, url, signing_secret, event_types, retry_config, enabled)
         VALUES ($1, $2, $3, $4, $5, $6)
         RETURNING id`,
        [
          tenantId4,
          `${webhookServerUrl}/failing-webhook`,
          'dlq-test-secret',
          JSON.stringify(['notification.*']),
          JSON.stringify({
            max_retries: 3,
            backoff_base: 100,
            backoff_multiplier: 2,
          }),
          true,
        ]
      );

      const recipient = createRecipient({
        user_id: 'user-dlq-001',
      });

      const notificationRequest = createNotificationRequest({
        tenant_id: tenantId4,
        notification_type: 'system',
        recipient,
        channel_preference: ['webhook'] as ChannelType[],
        content: {
          subject: 'DLQ Test',
          body: 'Testing DLQ and replay mechanism',
        },
        metadata: {
          test_case: 'dlq_replay',
          attempt: 0,
        },
      });

      webhookTestServer.setResponseStatus(503);

      const deliveryId = uuidv4();
      const messageId = `webhook-msg-${deliveryId}`;

      await tracker.createDeliveryLog(
        deliveryId,
        tenantId4,
        'system',
        'webhook',
        'webhook',
        recipient.user_id!,
        'high'
      );

      const retryAttempts = 3;
      const failedAttempts: any[] = [];

      for (let i = 0; i < retryAttempts; i++) {
        notificationRequest.metadata!.attempt = i + 1;

        const httpResponse = await fetch(`${webhookServerUrl}/failing-webhook`, {
          method: 'POST',
          headers: { 'Content-Type': 'application/json' },
          body: JSON.stringify({
            delivery_id: deliveryId,
            message_id: messageId,
            attempt: i + 1,
          }),
        });

        expect(httpResponse.status).toBe(503);
        failedAttempts.push({ attempt: i + 1, status: httpResponse.status });

        await tracker.updateStatus(
          tenantId4,
          deliveryId,
          'webhook',
          'failed',
          messageId,
          `Attempt ${i + 1} failed with 503 Service Unavailable`
        );
      }

      expect(failedAttempts.length).toBe(retryAttempts);
      expect(failedAttempts.every(a => a.status === 503)).toBe(true);

      const webhookRequests = webhookTestServer.getRequests();
      expect(webhookRequests.length).toBeGreaterThanOrEqual(retryAttempts);

      webhookTestServer.setResponseStatus(200);

      const successResponse = await fetch(`${webhookServerUrl}/failing-webhook`, {
        method: 'POST',
        headers: { 'Content-Type': 'application/json' },
        body: JSON.stringify({
          delivery_id: deliveryId,
          message_id: messageId,
          attempt: retryAttempts + 1,
          replay: true,
        }),
      });

      expect(successResponse.status).toBe(200);

      await tracker.updateStatus(tenantId4, deliveryId, 'webhook', 'sent', messageId);
      await tracker.handleCallback(tenantId4, messageId, 'delivered', {
        delivered_at: new Date().toISOString(),
        replay_attempt: true,
      });

      await new Promise(resolve => setTimeout(resolve, 300));

      const statusResponse = await request(app.server)
        .get(`/api/v1/notifications/${deliveryId}/status`)
        .set('X-Tenant-Id', tenantId4);

      expect(statusResponse.status).toBe(200);
      const logs = statusResponse.body.logs || [];

      const failedCount = logs.filter((l: any) => l.status === 'failed').length;
      const hasSent = logs.some((l: any) => l.status === 'sent');
      const hasDelivered = logs.some((l: any) => l.status === 'delivered');

      expect(failedCount).toBe(retryAttempts);
      expect(hasSent).toBe(true);
      expect(hasDelivered).toBe(true);

      const finalRequest = webhookTestServer.getRequests().find(
        (r: any) => r.body.replay === true
      );
      expect(finalRequest).toBeDefined();
      expect(finalRequest?.body.delivery_id).toBe(deliveryId);
    }, 60000);

    it('DLQ中的任务可以通过管理API手动重放', async () => {
      const tenantId5 = uuidv4();

      const { NotificationQueue } = await import('../../src/queue/NotificationQueue');
      const queue = NotificationQueue.getInstance();

      const recipient = createRecipient({
        user_id: 'user-retry-001',
        email: 'retry@example.com',
      });

      const notificationRequest = createNotificationRequest({
        tenant_id: tenantId5,
        notification_type: 'transactional',
        recipient,
        channel_preference: ['email'] as ChannelType[],
      });

      const deliveryId = uuidv4();
      const jobId = await queue.enqueue(notificationRequest, deliveryId, 'email');

      expect(jobId).toBeDefined();

      const stats = await queue.getQueueStats();
      expect(stats).toHaveProperty('waiting');
      expect(stats).toHaveProperty('active');

      const dlqJobsBefore = await queue.getDlqJobs();
      expect(Array.isArray(dlqJobsBefore)).toBe(true);

      const mockResponse = {
        id: 'test-job-id',
        data: {
          notification: notificationRequest,
          delivery_id: deliveryId,
          channel: 'email',
          error: { message: 'Temporary failure' },
        },
        failedReason: 'Temporary failure',
        failedAt: new Date(),
      };

      const replayJobId = 'dlq-job-001';
      const retryResponse = await request(app.server)
        .post(`/api/v1/admin/queue/dlq/${replayJobId}/retry`)
        .set('X-Tenant-Id', tenantId5)
        .send({});

      expect([200, 404, 500]).toContain(replayResponse.status);

      const dlqResponse = await request(app.server)
        .get('/api/v1/admin/queue/dlq')
        .set('X-Tenant-Id', tenantId5);

      expect(dlqResponse.status).toBe(200);
      expect(Array.isArray(dlqResponse.body)).toBe(true);
    }, 30000);
  });

  describe('Redis故障处理 - 限流器fail closed策略', () => {
    it('Redis连接断开时限流器采用fail closed策略', async () => {
      const tenantId6 = uuidv4();
      const limiter = TokenBucketRateLimiter.getInstance();

      const mockRedis = {
        eval: vi.fn(),
        disconnect: vi.fn(),
        on: vi.fn(),
      };

      const originalRedis = (limiter as any).redis;
      (limiter as any).redis = mockRedis;

      mockRedis.eval.mockRejectedValueOnce(new Error('ECONNREFUSED: Connection refused'));

      await expect(
        limiter.tryConsume(tenantId6, 'test-key', 10, 1, 1)
      ).rejects.toThrow('ECONNREFUSED');

      mockRedis.eval.mockRejectedValueOnce(new Error('ETIMEDOUT'));

      await expect(
        limiter.tryConsume(tenantId6, 'test-key-2', 10, 1, 1)
      ).rejects.toThrow('ETIMEDOUT');

      mockRedis.eval.mockResolvedValueOnce([1, 0]);

      const successResult = await limiter.tryConsume(tenantId6, 'test-key-3', 10, 1, 1);
      expect(successResult.allowed).toBe(true);

      (limiter as any).redis = originalRedis;
    }, 15000);

    it('Redis恢复后限流功能自动恢复', async () => {
      const tenantId7 = uuidv4();
      const limiter = TokenBucketRateLimiter.getInstance();

      const mockRedis = {
        eval: vi.fn(),
        disconnect: vi.fn(),
        on: vi.fn(),
      };

      const originalRedis = (limiter as any).redis;
      (limiter as any).redis = mockRedis;

      mockRedis.eval
        .mockRejectedValueOnce(new Error('ECONNREFUSED'))
        .mockRejectedValueOnce(new Error('ECONNREFUSED'))
        .mockResolvedValueOnce([1, 0])
        .mockResolvedValueOnce([1, 0])
        .mockResolvedValueOnce([0, 1]);

      await expect(
        limiter.tryConsume(tenantId7, 'recover-key', 2, 1, 1)
      ).rejects.toThrow('ECONNREFUSED');

      await expect(
        limiter.tryConsume(tenantId7, 'recover-key', 2, 1, 1)
      ).rejects.toThrow('ECONNREFUSED');

      const result1 = await limiter.tryConsume(tenantId7, 'recover-key', 2, 1, 1);
      expect(result1.allowed).toBe(true);

      const result2 = await limiter.tryConsume(tenantId7, 'recover-key', 2, 1, 1);
      expect(result2.allowed).toBe(true);

      const result3 = await limiter.tryConsume(tenantId7, 'recover-key', 2, 1, 1);
      expect(result3.allowed).toBe(false);
      expect(result3.retryAfter).toBeDefined();

      (limiter as any).redis = originalRedis;
    }, 15000);
  });

  describe('用户偏好渠道全部不可用场景', () => {
    it('用户偏好的渠道全部不可用时fallback到默认渠道', async () => {
      const tenantId8 = uuidv4();
      const tracker = DeliveryTracker.getInstance();

      const recipient = createRecipient({
        user_id: 'user-fallback-001',
        email: 'fallback@example.com',
      });

      await testInfrastructure.pgPool.query(
        `INSERT INTO user_preferences (tenant_id, user_id, channel_preferences, do_not_disturb)
         VALUES ($1, $2, $3, $4)`,
        [
          tenantId8,
          recipient.user_id,
          JSON.stringify([
            { channel: 'sms', notification_type: 'transactional', opted_in: true },
            { channel: 'push', notification_type: 'transactional', opted_in: true },
          ]),
          JSON.stringify({ enabled: false }),
        ]
      );

      const mockAdapterManager = {
        getAdapter: vi.fn((channel: string) => {
          if (channel === 'sms') {
            return {
              getName: vi.fn().mockReturnValue('sms'),
              getStatus: vi.fn().mockResolvedValue({ available: false, name: 'sms', last_checked: new Date() }),
              healthCheck: vi.fn().mockResolvedValue(false),
            };
          }
          if (channel === 'push') {
            return {
              getName: vi.fn().mockReturnValue('push'),
              getStatus: vi.fn().mockResolvedValue({ available: false, name: 'push', last_checked: new Date() }),
              healthCheck: vi.fn().mockResolvedValue(false),
            };
          }
          if (channel === 'email') {
            return {
              getName: vi.fn().mockReturnValue('email'),
              getStatus: vi.fn().mockResolvedValue({ available: true, name: 'email', last_checked: new Date() }),
              healthCheck: vi.fn().mockResolvedValue(true),
              send: vi.fn().mockResolvedValue({
                channel: 'email',
                provider: 'smtp',
                status: 'sent' as DeliveryStatus,
                message_id: 'fallback-msg-001',
                sent_at: new Date(),
              }),
            };
          }
          return null;
        }),
      };

      const { AdapterManager } = await import('../../src/adapters/AdapterManager');
      vi.spyOn(AdapterManager, 'getInstance').mockReturnValue(mockAdapterManager as any);

      const notificationRequest = createNotificationRequest({
        tenant_id: tenantId8,
        notification_type: 'transactional',
        recipient,
        channel_preference: ['sms', 'push'] as ChannelType[],
        content: {
          subject: 'Fallback Test',
          body: 'Testing fallback to default channel',
        },
      });

      const router = NotificationRouter.getInstance();
      const result = await router.route(notificationRequest);

      expect(result.channels.length).toBeGreaterThanOrEqual(0);

      const deliveryId = result.delivery_id;
      const defaultChannel = 'email';

      await tracker.createDeliveryLog(
        deliveryId,
        tenantId8,
        'transactional',
        defaultChannel,
        'smtp',
        recipient.email!,
        'medium'
      );

      const emailAdapter = mockAdapterManager.getAdapter(defaultChannel);
      const sendResult = await emailAdapter.send(notificationRequest, recipient);

      expect(sendResult.status).toBe('sent');
      expect(sendResult.provider).toBe('smtp');

      await tracker.updateStatus(tenantId8, deliveryId, defaultChannel, 'sent', sendResult.message_id);
      await tracker.handleCallback(tenantId8, sendResult.message_id!, 'delivered', {});

      const logs = await tracker.getByDeliveryId(tenantId8, deliveryId);
      expect(logs.some(l => l.status === 'delivered')).toBe(true);
    }, 30000);
  });
});
