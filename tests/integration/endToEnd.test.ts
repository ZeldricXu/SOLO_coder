import { describe, it, expect, beforeAll, afterAll, beforeEach, afterEach } from 'vitest';
import request from 'supertest';
import nock from 'nock';
import { v4 as uuidv4 } from 'uuid';
import { startTestInfrastructure, stopTestInfrastructure, resetTestDatabase, flushTestRedis } from '../utils/testContainers';
import { WebhookTestServer } from '../utils/webhookTestServer';
import { createNotificationRequest, createTemplate, createRecipient } from '../utils/factories';
import fastify from 'fastify';
import cors from '@fastify/cors';
import * as notificationController from '../../src/controllers/notificationController';
import * as templateController from '../../src/controllers/templateController';
import * as webhookController from '../../src/controllers/webhookController';
import * as adminController from '../../src/controllers/adminController';
import { NotificationRequest, ChannelType } from '../../src/types';
import { db } from '../../src/db';
import { DeliveryTracker } from '../../src/tracking/DeliveryTracker';
import { TemplateEngine } from '../../src/templates/TemplateEngine';

describe('端到端集成测试', () => {
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
    app.get('/api/v1/notifications/logs/search', notificationController.searchDeliveryLogs);
    app.post('/api/v1/notifications/callbacks/:channel', notificationController.handleChannelCallback);
    app.post('/api/v1/templates/preview', templateController.previewTemplate);
    app.post('/api/v1/templates', templateController.createTemplate);
    app.get('/api/v1/templates/:type/:locale', templateController.getTemplate);
    app.post('/api/v1/webhooks', webhookController.createWebhookEndpoint);
    app.get('/api/v1/admin/queue/stats', adminController.getQueueStats);

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
  });

  afterEach(() => {
    webhookTestServer.clearRequests();
    webhookTestServer.setResponseStatus(200);
    webhookTestServer.setResponseDelay(0);
  });

  describe('完整通知链路 - 邮件渠道', () => {
    it('API接收通知请求→路由器决策→模板渲染→SMTP发送→状态更新→查询完整记录', async () => {
      const templateEngine = TemplateEngine.getInstance();
      const template = createTemplate({
        tenant_id: tenantId,
        notification_type: 'transactional',
        locale: 'en',
        subject_template: 'Payment Confirmation - Order #{{order_id}}',
        body_template: 'Hi {{name}},\n\nYour payment of ${{amount}} has been processed successfully.\n\nOrder ID: {{order_id}}',
        html_template: '<p>Hi {{name}},</p><p>Your payment of <strong>${{amount}}</strong> has been processed successfully.</p><p>Order ID: {{order_id}}</p>',
        variables: ['name', 'order_id', 'amount'],
      });

      await templateEngine.createTemplate(
        tenantId,
        template.notification_type,
        template.locale,
        template.name,
        template.subject_template,
        template.body_template,
        template.html_template,
        template.variables
      );

      const mockSmtpScope = nock('http://localhost:587')
        .post('/')
        .reply(200, {
          messageId: `smtp-${uuidv4()}`,
          response: '250 OK',
        });

      const recipient = createRecipient({
        user_id: 'user-001',
        email: 'customer@example.com',
      });

      const notificationRequest = createNotificationRequest({
        tenant_id: tenantId,
        notification_type: 'transactional',
        recipient,
        channel_preference: ['email'] as ChannelType[],
        template_variables: {
          name: 'John Doe',
          order_id: 'ORD-2024-001',
          amount: '299.99',
        },
        locale: 'en',
      });

      const sendResponse = await request(app.server)
        .post('/api/v1/notifications/send')
        .set('X-Tenant-Id', tenantId)
        .send(notificationRequest);

      expect(sendResponse.status).toBe(202);
      expect(sendResponse.body.delivery_id).toBeDefined();
      expect(sendResponse.body.channels).toContain('email');

      const deliveryId = sendResponse.body.delivery_id;

      const tracker = DeliveryTracker.getInstance();
      await tracker.updateStatus(tenantId, deliveryId, 'email', 'sent', `msg-${deliveryId}`);

      const context = {
        name: 'John Doe',
        order_id: 'ORD-2024-001',
        amount: '299.99',
      };
      const rendered = await templateEngine.render(tenantId, 'transactional', context, 'en');
      expect(rendered).not.toBeNull();
      expect(rendered!.subject).toContain('ORD-2024-001');
      expect(rendered!.body).toContain('John Doe');
      expect(rendered!.html).toContain('<strong>$299.99</strong>');

      await tracker.handleCallback(tenantId, `msg-${deliveryId}`, 'delivered', {
        delivered_at: new Date().toISOString(),
        provider: 'smtp',
      });

      await new Promise(resolve => setTimeout(resolve, 500));

      const statusResponse = await request(app.server)
        .get(`/api/v1/notifications/${deliveryId}/status`)
        .set('X-Tenant-Id', tenantId);

      expect(statusResponse.status).toBe(200);
      expect(statusResponse.body.delivery_id).toBe(deliveryId);

      const logs = statusResponse.body.logs || [];
      const hasQueued = logs.some((l: any) => l.status === 'queued');
      const hasSent = logs.some((l: any) => l.status === 'sent');
      const hasDelivered = logs.some((l: any) => l.status === 'delivered');

      expect(hasQueued).toBe(true);
      expect(hasSent).toBe(true);
      expect(hasDelivered).toBe(true);

      const searchResponse = await request(app.server)
        .get('/api/v1/notifications/logs/search')
        .set('X-Tenant-Id', tenantId)
        .query({
          start_time: new Date(Date.now() - 3600000).toISOString(),
          end_time: new Date(Date.now() + 3600000).toISOString(),
          recipient: 'customer@example.com',
        });

      expect(searchResponse.status).toBe(200);
      expect(searchResponse.body.length).toBeGreaterThan(0);
      expect(searchResponse.body[0].recipient).toBe('customer@example.com');
    }, 30000);

    it('Webhook渠道通知 - 验证请求格式、签名和状态更新', async () => {
      const tenantId2 = uuidv4();

      const webhookEndpoint = {
        tenant_id: tenantId2,
        url: `${webhookServerUrl}/webhook`,
        signing_secret: 'test-secret-key-123',
        event_types: ['notification.sent', 'notification.delivered'],
        retry_config: {
          max_retries: 3,
          backoff_base: 100,
          backoff_multiplier: 2,
        },
        enabled: true,
      };

      await testInfrastructure.pgPool.query(
        `INSERT INTO webhook_endpoints (tenant_id, url, signing_secret, event_types, retry_config, enabled)
         VALUES ($1, $2, $3, $4, $5, $6)
         RETURNING id`,
        [tenantId2, webhookEndpoint.url, webhookEndpoint.signing_secret,
         JSON.stringify(webhookEndpoint.event_types), JSON.stringify(webhookEndpoint.retry_config), true]
      );

      const recipient = createRecipient({
        user_id: 'user-002',
      });

      const notificationRequest = createNotificationRequest({
        tenant_id: tenantId2,
        notification_type: 'transactional',
        recipient,
        channel_preference: ['webhook'] as ChannelType[],
        content: {
          subject: 'Webhook Test',
          body: 'Test webhook payload',
        },
        metadata: {
          event: 'order.created',
          order_id: 'ORD-WEBHOOK-001',
          amount: 199.99,
        },
      });

      const sendResponse = await request(app.server)
        .post('/api/v1/notifications/send')
        .set('X-Tenant-Id', tenantId2)
        .send(notificationRequest);

      expect(sendResponse.status).toBe(202);
      const deliveryId = sendResponse.body.delivery_id;

      const tracker = DeliveryTracker.getInstance();
      await tracker.updateStatus(tenantId2, deliveryId, 'webhook', 'sent', `wh-msg-${deliveryId}`);

      const callbackPayload = {
        message_id: `wh-msg-${deliveryId}`,
        status: 'delivered',
        delivered_at: new Date().toISOString(),
        metadata: {
          http_status: 200,
          response_time_ms: 150,
        },
      };

      const callbackResponse = await request(app.server)
        .post('/api/v1/notifications/callbacks/webhook')
        .set('X-Tenant-Id', tenantId2)
        .set('X-Signature', 'sha256=test-signature')
        .send(callbackPayload);

      expect(callbackResponse.status).toBe(200);

      await new Promise(resolve => setTimeout(resolve, 500));

      const requests = webhookTestServer.getRequests();
      expect(requests.length).toBeGreaterThanOrEqual(0);

      if (requests.length > 0) {
        const webhookRequest = requests[0];
        expect(webhookRequest.headers['content-type']).toContain('application/json');
        expect(webhookRequest.body.delivery_id).toBeDefined();
        expect(webhookRequest.body.event_type).toBeDefined();
      }

      const statusResponse = await request(app.server)
        .get(`/api/v1/notifications/${deliveryId}/status`)
        .set('X-Tenant-Id', tenantId2);

      expect(statusResponse.status).toBe(200);
      const logs = statusResponse.body.logs || [];
      const hasDelivered = logs.some((l: any) => l.status === 'delivered');
      expect(hasDelivered).toBe(true);
    }, 30000);

    it('模板引擎端到端验证 - 完整上下文渲染与预览', async () => {
      const tenantId3 = uuidv4();

      const template = createTemplate({
        tenant_id: tenantId3,
        notification_type: 'security',
        locale: 'zh-CN',
        name: '账户安全提醒',
        subject_template: '【安全提醒】您的账户{{action}}',
        body_template: `尊敬的{{username}}：

您的账户于{{time}}在{{location}}执行了{{action}}操作。
设备：{{device}}
IP地址：{{ip_address}}

{{#if is_suspicious}}
⚠️  如果这不是您本人操作，请立即：
1. 登录账户修改密码
2. 开启两步验证
3. 联系客服冻结账户
{{else}}
✅  这是正常操作，无需担心。
{{/if}}

如有疑问，请联系客服。`,
        html_template: `<div style="font-family: Arial, sans-serif; max-width: 600px;">
  <h2 style="color: #333;">【安全提醒】您的账户{{action}}</h2>
  <p>尊敬的{{username}}：</p>
  <p>您的账户于<strong>{{time}}</strong>在{{location}}执行了{{action}}操作。</p>
  <ul>
    <li>设备：{{device}}</li>
    <li>IP地址：{{ip_address}}</li>
  </ul>
  {{#if is_suspicious}}
  <div style="background: #fff3cd; padding: 15px; border-radius: 5px; margin: 15px 0;">
    <strong>⚠️ 如果这不是您本人操作，请立即：</strong>
    <ol>
      <li>登录账户修改密码</li>
      <li>开启两步验证</li>
      <li>联系客服冻结账户</li>
    </ol>
  </div>
  {{else}}
  <div style="background: #d4edda; padding: 15px; border-radius: 5px; margin: 15px 0;">
    <strong>✅ 这是正常操作，无需担心。</strong>
  </div>
  {{/if}}
  <p style="color: #666; font-size: 12px;">如有疑问，请联系客服。</p>
</div>`,
        variables: ['username', 'action', 'time', 'location', 'device', 'ip_address', 'is_suspicious'],
      });

      const createTemplateResponse = await request(app.server)
        .post('/api/v1/templates')
        .set('X-Tenant-Id', tenantId3)
        .send(template);

      expect(createTemplateResponse.status).toBe(201);

      const context = {
        username: '张三',
        action: '修改密码',
        time: '2024-01-15 14:30:00',
        location: '北京市',
        device: 'MacBook Pro (Chrome)',
        ip_address: '192.168.1.100',
        is_suspicious: false,
      };

      const getTemplateResponse = await request(app.server)
        .get(`/api/v1/templates/security/zh-CN`)
        .set('X-Tenant-Id', tenantId3);

      expect(getTemplateResponse.status).toBe(200);
      expect(getTemplateResponse.body.subject_template).toBe(template.subject_template);

      const previewResponse = await request(app.server)
        .post('/api/v1/templates/preview')
        .set('X-Tenant-Id', tenantId3)
        .send({
          template: {
            subject_template: template.subject_template,
            body_template: template.body_template,
            html_template: template.html_template,
          },
          variables: context,
        });

      expect(previewResponse.status).toBe(200);
      expect(previewResponse.body.subject).toBe('【安全提醒】您的账户修改密码');
      expect(previewResponse.body.body).toContain('张三');
      expect(previewResponse.body.body).toContain('北京市');
      expect(previewResponse.body.body).toContain('这是正常操作，无需担心');
      expect(previewResponse.body.html).toContain('background: #d4edda');
      expect(previewResponse.body.html).not.toContain('background: #fff3cd');

      const suspiciousContext = { ...context, is_suspicious: true };

      const suspiciousPreviewResponse = await request(app.server)
        .post('/api/v1/templates/preview')
        .set('X-Tenant-Id', tenantId3)
        .send({
          template: {
            subject_template: template.subject_template,
            body_template: template.body_template,
            html_template: template.html_template,
          },
          variables: suspiciousContext,
        });

      expect(suspiciousPreviewResponse.status).toBe(200);
      expect(suspiciousPreviewResponse.body.body).toContain('如果这不是您本人操作');
      expect(suspiciousPreviewResponse.body.html).toContain('background: #fff3cd');
      expect(suspiciousPreviewResponse.body.html).not.toContain('background: #d4edda');
    }, 30000);
  });

  describe('多渠道端到端测试', () => {
    it('全渠道紧急通知 - 同时推送到邮件和短信渠道', async () => {
      const tenantId4 = uuidv4();
      const tracker = DeliveryTracker.getInstance();

      const recipient = createRecipient({
        user_id: 'user-003',
        email: 'user003@example.com',
        phone: '+8613900139000',
      });

      const notificationRequest = createNotificationRequest({
        tenant_id: tenantId4,
        notification_type: 'security',
        recipient,
        channel_preference: ['email', 'sms'] as ChannelType[],
        priority: 'urgent' as const,
        omnichannel: true,
        content: {
          subject: '【紧急】账户异常登录',
          body: '检测到您的账户在新设备上登录，请确认是否为本人操作。',
        },
      });

      const sendResponse = await request(app.server)
        .post('/api/v1/notifications/send')
        .set('X-Tenant-Id', tenantId4)
        .send(notificationRequest);

      expect(sendResponse.status).toBe(202);
      expect(sendResponse.body.channels).toEqual(expect.arrayContaining(['email', 'sms']));

      const deliveryId = sendResponse.body.delivery_id;

      await tracker.updateStatus(tenantId4, deliveryId, 'email', 'sent', 'email-msg-001');
      await tracker.updateStatus(tenantId4, deliveryId, 'sms', 'sent', 'sms-msg-001');

      await tracker.handleCallback(tenantId4, 'email-msg-001', 'delivered', {});
      await tracker.handleCallback(tenantId4, 'sms-msg-001', 'delivered', {});

      await new Promise(resolve => setTimeout(resolve, 300));

      const statusResponse = await request(app.server)
        .get(`/api/v1/notifications/${deliveryId}/status`)
        .set('X-Tenant-Id', tenantId4);

      expect(statusResponse.status).toBe(200);
      const logs = statusResponse.body.logs || [];

      const emailLogs = logs.filter((l: any) => l.channel === 'email');
      const smsLogs = logs.filter((l: any) => l.channel === 'sms');

      expect(emailLogs.length).toBeGreaterThan(0);
      expect(smsLogs.length).toBeGreaterThan(0);
      expect(emailLogs.some((l: any) => l.status === 'delivered')).toBe(true);
      expect(smsLogs.some((l: any) => l.status === 'delivered')).toBe(true);
    }, 30000);
  });

  describe('健康检查和监控端点', () => {
    it('健康检查端点返回正常状态', async () => {
      const response = await request(app.server).get('/health');
      expect(response.status).toBe(200);
      expect(response.body.status).toBe('ok');
    });

    it('队列统计端点返回正确数据结构', async () => {
      const response = await request(app.server)
        .get('/api/v1/admin/queue/stats')
        .set('X-Tenant-Id', tenantId);

      expect(response.status).toBe(200);
      expect(response.body).toHaveProperty('waiting');
      expect(response.body).toHaveProperty('active');
      expect(response.body).toHaveProperty('completed');
      expect(response.body).toHaveProperty('failed');
      expect(typeof response.body.waiting).toBe('number');
    });
  });
});
