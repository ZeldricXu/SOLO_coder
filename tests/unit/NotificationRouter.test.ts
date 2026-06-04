import { describe, it, expect, vi, beforeEach } from 'vitest';
import { NotificationRouter } from '../../src/router/NotificationRouter';
import { AdapterManager } from '../../src/adapters/AdapterManager';
import { db } from '../../src/db';
import { createNotificationRequest, runTableDrivenTests } from '../utils/factories';
import { createMockChannelAdapter, createMockAdapterManager } from '../utils/mocks';
import type { ChannelType, RoutingRule, NotificationPriority } from '../../src/types';

vi.mock('../../src/adapters/AdapterManager');
vi.mock('../../src/db');

describe('NotificationRouter', () => {
  let router: NotificationRouter;
  let mockEmailAdapter: ReturnType<typeof createMockChannelAdapter>;
  let mockSmsAdapter: ReturnType<typeof createMockChannelAdapter>;
  let mockPushAdapter: ReturnType<typeof createMockChannelAdapter>;
  let mockAdapterManager: ReturnType<typeof createMockAdapterManager>;

  beforeEach(() => {
    vi.clearAllMocks();
    vi.useRealTimers();

    mockEmailAdapter = createMockChannelAdapter('email', true);
    mockSmsAdapter = createMockChannelAdapter('sms', true);
    mockPushAdapter = createMockChannelAdapter('push', true);

    mockAdapterManager = createMockAdapterManager({
      email: mockEmailAdapter,
      sms: mockSmsAdapter,
      push: mockPushAdapter,
    });

    vi.mocked(AdapterManager.getInstance).mockReturnValue(mockAdapterManager as any);
    vi.mocked(db.withTenantContext).mockImplementation(async (tenantId, fn) => fn());
    vi.mocked(db.query).mockResolvedValue({ rows: [], rowCount: 0 });

    (NotificationRouter as any).instance = null;
    router = NotificationRouter.getInstance();
  });

  describe('正常路径测试', () => {
    it('在用户偏好和渠道健康检查都正常时，正确选择主渠道并按优先级排序', async () => {
      const request = createNotificationRequest({
        channel_preference: ['email', 'sms', 'push'] as ChannelType[],
      });

      vi.mocked(db.query).mockResolvedValueOnce({
        rows: [{
          id: 'rule-1',
          tenant_id: request.tenant_id,
          name: 'Default',
          conditions: [],
          actions: [],
          priority: 1,
          enabled: true,
        }],
        rowCount: 1,
      });

      vi.mocked(db.query).mockResolvedValueOnce({
        rows: [],
        rowCount: 0,
      });

      const result = await router.route(request);

      expect(result.delivery_id).toBeDefined();
      expect(result.channels).toEqual(['email', 'sms', 'push']);
      expect(mockEmailAdapter.getStatus).toHaveBeenCalled();
      expect(mockSmsAdapter.getStatus).toHaveBeenCalled();
      expect(mockPushAdapter.getStatus).toHaveBeenCalled();
    });

    it('紧急全渠道通知应并行推送到所有可用渠道', async () => {
      const request = createNotificationRequest({
        channel_preference: ['email', 'sms'] as ChannelType[],
        priority: 'urgent' as NotificationPriority,
        omnichannel: true,
      });

      vi.mocked(db.query).mockResolvedValue({ rows: [], rowCount: 0 });

      const result = await router.route(request);

      expect(result.delivery_id).toBeDefined();
      expect(result.channels).toEqual(['email', 'sms']);
      expect(result.results).toBeDefined();
      expect(result.results!.length).toBe(2);
      expect(mockEmailAdapter.send).toHaveBeenCalled();
      expect(mockSmsAdapter.send).toHaveBeenCalled();
    });

    it('根据优先级路由规则提升通知优先级', async () => {
      const request = createNotificationRequest({
        channel_preference: ['email'] as ChannelType[],
        priority: 'medium' as NotificationPriority,
        notification_type: 'security' as any,
      });

      vi.mocked(db.query).mockResolvedValueOnce({
        rows: [{
          id: 'rule-1',
          tenant_id: request.tenant_id,
          name: 'Security notifications are high priority',
          conditions: [{ field: 'notification_type', operator: 'eq', value: 'security' }],
          actions: [{ type: 'set_priority', params: { priority: 'high' } }],
          priority: 100,
          enabled: true,
        }],
        rowCount: 1,
      });

      vi.mocked(db.query).mockResolvedValueOnce({ rows: [], rowCount: 0 });

      await router.route(request);

      expect(request.priority).toBe('high');
    });
  });

  describe('表驱动测试 - 路由规则条件评估', () => {
    interface ConditionTestCase {
      conditions: any[];
      requestOverrides: any;
    }

    const testCases: { name: string; input: ConditionTestCase; expected: boolean }[] = [
      {
        name: '条件字段等于目标值时匹配',
        input: {
          conditions: [{ field: 'priority', operator: 'eq', value: 'high' }],
          requestOverrides: { priority: 'high' as NotificationPriority },
        },
        expected: true,
      },
      {
        name: '条件字段不等于目标值时不匹配',
        input: {
          conditions: [{ field: 'priority', operator: 'ne', value: 'low' }],
          requestOverrides: { priority: 'high' as NotificationPriority },
        },
        expected: true,
      },
      {
        name: '嵌套字段条件匹配',
        input: {
          conditions: [{ field: 'metadata.amount', operator: 'gt', value: 1000 }],
          requestOverrides: { metadata: { amount: 1500 } },
        },
        expected: true,
      },
      {
        name: '字符串包含匹配',
        input: {
          conditions: [{ field: 'content.subject', operator: 'contains', value: '验证码' }],
          requestOverrides: { content: { subject: '您的验证码是 123456' } },
        },
        expected: true,
      },
      {
        name: '值在数组中匹配',
        input: {
          conditions: [{ field: 'notification_type', operator: 'in', value: ['security', 'system'] }],
          requestOverrides: { notification_type: 'security' as any },
        },
        expected: true,
      },
      {
        name: '多个条件全部满足才匹配',
        input: {
          conditions: [
            { field: 'priority', operator: 'eq', value: 'high' },
            { field: 'notification_type', operator: 'eq', value: 'security' },
          ],
          requestOverrides: { priority: 'high' as NotificationPriority, notification_type: 'security' as any },
        },
        expected: true,
      },
    ];

    runTableDrivenTests<ConditionTestCase, boolean>(testCases, async (input, expected) => {
      const request = createNotificationRequest(input.requestOverrides);
      
      vi.mocked(db.query).mockResolvedValueOnce({
        rows: [{
          id: 'test-rule',
          tenant_id: request.tenant_id,
          name: 'Test Rule',
          conditions: input.conditions,
          actions: [{ type: 'set_channel', params: { channels: ['email'] } }],
          priority: 1,
          enabled: true,
        }],
        rowCount: 1,
      });

      vi.mocked(db.query).mockResolvedValueOnce({ rows: [], rowCount: 0 });

      const result = await router.route(request);
      
      if (expected) {
        expect(result.channels).toContain('email');
      }
    });
  });

  describe('异常路径测试', () => {
    it('当用户偏好的渠道全部不可用时快速失败返回空数组', async () => {
      const unavailableEmailAdapter = createMockChannelAdapter('email', false);
      const unavailableSmsAdapter = createMockChannelAdapter('sms', false);
      const unavailableMockAdapterManager = createMockAdapterManager({
        email: unavailableEmailAdapter,
        sms: unavailableSmsAdapter,
      });
      vi.mocked(AdapterManager.getInstance).mockReturnValue(unavailableMockAdapterManager as any);
      
      (NotificationRouter as any).instance = null;
      const testRouter = NotificationRouter.getInstance();

      const request = createNotificationRequest({
        channel_preference: ['email', 'sms'] as ChannelType[],
      });

      vi.mocked(db.query).mockResolvedValue({ rows: [], rowCount: 0 });

      const result = await testRouter.route(request);

      expect(result.channels).toEqual([]);
      expect(unavailableEmailAdapter.getStatus).toHaveBeenCalled();
      expect(unavailableSmsAdapter.getStatus).toHaveBeenCalled();
    });

    it('当用户设置免打扰且非紧急通知时，所有渠道被过滤', async () => {
      const request = createNotificationRequest({
        channel_preference: ['email', 'sms'] as ChannelType[],
        priority: 'medium' as NotificationPriority,
        recipient: { user_id: 'test-user-123' },
      });

      vi.mocked(db.query).mockResolvedValueOnce({ rows: [], rowCount: 0 });
      vi.mocked(db.query).mockResolvedValueOnce({
        rows: [{
          channel_preferences: [],
          do_not_disturb: {
            enabled: true,
            start_time: '00:00',
            end_time: '23:59',
            timezone: 'Asia/Shanghai',
            allow_urgent: true,
          },
        }],
        rowCount: 1,
      });

      const result = await router.route(request);

      expect(result.channels).toEqual([]);
    });

    it('紧急通知在免打扰时段仍可送达', async () => {
      const request = createNotificationRequest({
        channel_preference: ['email'] as ChannelType[],
        priority: 'urgent' as NotificationPriority,
        recipient: { user_id: 'test-user-123' },
      });

      vi.mocked(db.query).mockResolvedValueOnce({ rows: [], rowCount: 0 });
      vi.mocked(db.query).mockResolvedValueOnce({
        rows: [{
          channel_preferences: [],
          do_not_disturb: {
            enabled: true,
            start_time: '00:00',
            end_time: '23:59',
            timezone: 'Asia/Shanghai',
            allow_urgent: true,
          },
        }],
        rowCount: 1,
      });

      const result = await router.route(request);

      expect(result.channels).toContain('email');
    });

    it('用户opt-out的渠道被过滤', async () => {
      const request = createNotificationRequest({
        channel_preference: ['email', 'sms'] as ChannelType[],
        notification_type: 'marketing' as any,
        recipient: { user_id: 'test-user-123' },
      });

      vi.mocked(db.query).mockResolvedValueOnce({ rows: [], rowCount: 0 });
      vi.mocked(db.query).mockResolvedValueOnce({
        rows: [{
          channel_preferences: [
            { channel: 'email', notification_type: 'marketing', opted_in: false },
            { channel: 'sms', notification_type: 'marketing', opted_in: true },
          ],
          do_not_disturb: { enabled: false },
        }],
        rowCount: 1,
      });

      const result = await router.route(request);

      expect(result.channels).toEqual(['sms']);
      expect(result.channels).not.toContain('email');
    });

    it('数据库查询失败时不影响路由决策', async () => {
      const request = createNotificationRequest({
        channel_preference: ['email'] as ChannelType[],
      });

      vi.mocked(db.withTenantContext).mockRejectedValueOnce(new Error('DB connection failed'));

      const result = await router.route(request);

      expect(result.channels).toContain('email');
    });
  });

  describe('A/B测试与灰度发布', () => {
    it('A/B测试按比例分流到测试组和控制组', async () => {
      const originalRandom = Math.random;
      
      Math.random = () => 0.3;
      
      const request = createNotificationRequest({
        channel_preference: ['email'] as ChannelType[],
      });

      vi.mocked(db.query).mockResolvedValueOnce({
        rows: [{
          id: 'ab-test-1',
          tenant_id: request.tenant_id,
          name: 'SMS vs Email A/B Test',
          conditions: [],
          actions: [{
            type: 'ab_test',
            params: {
              ratio: 0.5,
              test_channels: ['sms'],
              control_channels: ['email'],
            },
          }],
          priority: 1,
          enabled: true,
        }],
        rowCount: 1,
      });
      vi.mocked(db.query).mockResolvedValueOnce({ rows: [], rowCount: 0 });

      const result1 = await router.route(request);
      expect(result1.channels).toEqual(['sms']);

      Math.random = () => 0.7;
      
      (NotificationRouter as any).instance = null;
      const router2 = NotificationRouter.getInstance();
      const result2 = await router2.route(request);
      expect(result2.channels).toEqual(['email']);

      Math.random = originalRandom;
    });

    it('灰度发布按百分比推送新渠道', async () => {
      const originalRandom = Math.random;
      
      Math.random = () => 0.05;
      
      const request = createNotificationRequest({
        channel_preference: ['email'] as ChannelType[],
      });

      vi.mocked(db.query).mockResolvedValueOnce({
        rows: [{
          id: 'gray-1',
          tenant_id: request.tenant_id,
          name: 'Push notification gray release',
          conditions: [],
          actions: [{
            type: 'gray_release',
            params: {
              percentage: 10,
              new_channels: ['push', 'email'],
            },
          }],
          priority: 1,
          enabled: true,
        }],
        rowCount: 1,
      });
      vi.mocked(db.query).mockResolvedValueOnce({ rows: [], rowCount: 0 });

      const result1 = await router.route(request);
      expect(result1.channels).toEqual(['push', 'email']);

      Math.random = () => 0.5;
      
      (NotificationRouter as any).instance = null;
      const router2 = NotificationRouter.getInstance();
      const result2 = await router2.route(request);
      expect(result2.channels).toEqual(['email']);

      Math.random = originalRandom;
    });
  });

  describe('全渠道紧急通知', () => {
    it('并行推送结果包含成功和失败的结果', async () => {
      mockSmsAdapter.send.mockRejectedValueOnce(new Error('SMS service down'));
      
      const request = createNotificationRequest({
        channel_preference: ['email', 'sms'] as ChannelType[],
        priority: 'urgent' as NotificationPriority,
        omnichannel: true,
      });

      vi.mocked(db.query).mockResolvedValue({ rows: [], rowCount: 0 });

      const result = await router.route(request);

      expect(result.results).toBeDefined();
      expect(result.results!.length).toBe(2);
      
      const emailResult = result.results!.find(r => r.channel === 'email');
      const smsResult = result.results!.find(r => r.channel === 'sms');
      
      expect(emailResult!.status).toBe('sent');
      expect(smsResult!.status).toBe('failed');
      expect(smsResult!.error).toBe('SMS service down');
    });
  });
});
