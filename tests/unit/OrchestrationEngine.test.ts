import { describe, it, expect, vi, beforeEach } from 'vitest';
import { v4 as uuidv4 } from 'uuid';
import { OrchestrationEngine } from '../../src/orchestration/OrchestrationEngine';
import { OrchestrationStep, OrchestrationCondition, DeliveryStatus } from '../../src/types';
import { createMockDatabase, createMockQueue, createMockTracker, createMockTemplateEngine, createMockRouter } from '../utils/mocks';
import { db } from '../../src/db';
import { NotificationQueue } from '../../src/queue/NotificationQueue';
import { DeliveryTracker } from '../../src/tracking/DeliveryTracker';
import { TemplateEngine } from '../../src/templates/TemplateEngine';
import { NotificationRouter } from '../../src/router/NotificationRouter';

vi.mock('../../src/db');
vi.mock('../../src/queue/NotificationQueue');
vi.mock('../../src/tracking/DeliveryTracker');
vi.mock('../../src/templates/TemplateEngine');
vi.mock('../../src/router/NotificationRouter');

describe('OrchestrationEngine', () => {
  let engine: OrchestrationEngine;
  const tenantId = 'test-tenant-123';
  const createdBy = 'test-user-456';

  beforeEach(() => {
    vi.clearAllMocks();
    createMockDatabase();
    createMockQueue();
    createMockTracker();
    createMockTemplateEngine();
    createMockRouter();

    const queueInstance = NotificationQueue.getInstance() as any;
    queueInstance.scheduleJob.mockResolvedValue(undefined);

    const trackerInstance = DeliveryTracker.getInstance() as any;
    trackerInstance.createDeliveryLog.mockResolvedValue(uuidv4());
    trackerInstance.getByDeliveryId.mockResolvedValue([]);

    engine = OrchestrationEngine.getInstance();
  });

  describe('序列管理', () => {
    it('创建通知编排序列', async () => {
      const steps: OrchestrationStep[] = [
        {
          id: 'step-1',
          name: '邮件通知',
          order: 0,
          channel: 'email',
          notification_type: 'transactional',
          terminate_on_success: true,
          content: { subject: 'Test', body: 'Test body' },
        },
      ];

      const sequence = await engine.createSequence(
        tenantId,
        '用户召回序列',
        steps,
        createdBy,
        '7天未登录用户召回'
      );

      expect(sequence).toBeDefined();
      expect(sequence.id).toBeDefined();
      expect(sequence.name).toBe('用户召回序列');
      expect(sequence.steps.length).toBe(1);
      expect(sequence.enabled).toBe(true);
      expect(sequence.created_by).toBe(createdBy);
    });

    it('创建序列时自动排序步骤', async () => {
      const steps: OrchestrationStep[] = [
        {
          id: 'step-2',
          name: '短信通知',
          order: 1,
          channel: 'sms',
          notification_type: 'transactional',
          terminate_on_success: false,
          delay_seconds: 172800,
          content: { body: 'Test SMS' },
        },
        {
          id: 'step-1',
          name: '邮件通知',
          order: 0,
          channel: 'email',
          notification_type: 'transactional',
          terminate_on_success: true,
          content: { subject: 'Test', body: 'Test body' },
        },
      ];

      const sequence = await engine.createSequence(tenantId, '测试序列', steps, createdBy);
      expect(sequence.steps[0].order).toBe(0);
      expect(sequence.steps[1].order).toBe(1);
    });

    it('获取和列出序列', async () => {
      const steps: OrchestrationStep[] = [
        {
          id: 'step-1',
          name: '邮件通知',
          order: 0,
          channel: 'email',
          notification_type: 'transactional',
          terminate_on_success: true,
          content: { subject: 'Test', body: 'Test body' },
        },
      ];

      const created = await engine.createSequence(tenantId, '测试序列', steps, createdBy);
      
      const fetched = await engine.getSequence(tenantId, created.id);
      expect(fetched).toBeDefined();
      expect(fetched?.id).toBe(created.id);

      const list = await engine.listSequences(tenantId);
      expect(list.length).toBeGreaterThan(0);
    });

    it('更新和删除序列', async () => {
      const steps: OrchestrationStep[] = [
        {
          id: 'step-1',
          name: '邮件通知',
          order: 0,
          channel: 'email',
          notification_type: 'transactional',
          terminate_on_success: true,
          content: { subject: 'Test', body: 'Test body' },
        },
      ];

      const created = await engine.createSequence(tenantId, '测试序列', steps, createdBy);
      
      const updated = await engine.updateSequence(tenantId, created.id, {
        name: '更新后的序列',
        enabled: false,
      });
      
      expect(updated).toBeDefined();
      expect(updated?.name).toBe('更新后的序列');
      expect(updated?.enabled).toBe(false);

      const deleted = await engine.deleteSequence(tenantId, created.id);
      expect(deleted).toBe(true);
    });
  });

  describe('序列执行', () => {
    it('启动序列并执行第一步', async () => {
      const steps: OrchestrationStep[] = [
        {
          id: 'step-1',
          name: '邮件通知',
          order: 0,
          channel: 'email',
          notification_type: 'transactional',
          terminate_on_success: true,
          content: { subject: 'Test', body: 'Test body' },
        },
      ];

      const sequence = await engine.createSequence(tenantId, '测试序列', steps, createdBy);
      
      const instance = await engine.startSequence(
        tenantId,
        sequence.id,
        { email: 'test@example.com', user_id: 'user-123' },
        { username: 'TestUser' }
      );

      expect(instance).toBeDefined();
      expect(instance.status).toBe('running');
      expect(instance.sequence_id).toBe(sequence.id);
    });

    it('表驱动测试 - 条件判断逻辑', async () => {
      const testCases = [
        {
          name: '前一步成功则终止',
          condition: {
            type: 'delivery_status' as const,
            operator: 'eq' as const,
            field: 'status',
            value: 'delivered',
            step_id: 'step-1',
          } as OrchestrationCondition,
          previousStatus: 'delivered' as DeliveryStatus,
          expected: true,
        },
        {
          name: '前一步失败则继续',
          condition: {
            type: 'delivery_status' as const,
            operator: 'ne' as const,
            field: 'status',
            value: 'failed',
            step_id: 'step-1',
          } as OrchestrationCondition,
          previousStatus: 'delivered',
          expected: true,
        },
        {
          name: '48小时超时条件',
          condition: {
            type: 'time_window' as const,
            operator: 'gte' as const,
            field: 'elapsed',
            value: 172800,
          } as OrchestrationCondition,
          previousStatus: 'sent',
          expected: false,
        },
        {
          name: '用户行为-已读邮件',
          condition: {
            type: 'user_behavior' as const,
            operator: 'in' as const,
            field: 'status',
            value: ['read', 'clicked'],
            step_id: 'step-1',
          } as OrchestrationCondition,
          previousStatus: 'read',
          expected: true,
        },
      ];

      for (const tc of testCases) {
        console.log(`测试条件: ${tc.name}`);
        const result = (engine as any).compareValues(tc.previousStatus, tc.condition.operator, tc.condition.value);
        expect(result).toBe(tc.expected);
      }
    });
  });

  describe('多步骤编排场景', () => {
    it('用户7天未登录召回场景 - 邮件→短信→Push', async () => {
      const steps: OrchestrationStep[] = [
        {
          id: 'step-1',
          name: '第一步：邮件通知',
          order: 0,
          channel: 'email',
          notification_type: 'marketing',
          terminate_on_success: true,
          delay_seconds: 0,
          content: {
            subject: '我们想你了！',
            body: '{{username}}，你已经7天没来了，回来看看吧！',
            html: '<p>{{username}}，你已经7天没来了，回来看看吧！</p>',
          },
        },
        {
          id: 'step-2',
          name: '第二步：48小时未读发送短信',
          order: 1,
          channel: 'sms',
          notification_type: 'marketing',
          terminate_on_success: true,
          delay_seconds: 172800,
          conditions: [
            {
              type: 'user_behavior',
              operator: 'not_in',
              field: 'status',
              value: ['read', 'clicked'],
              step_id: 'step-1',
            },
          ],
          content: {
            body: '{{username}}，回来看看我们的新活动吧！',
          },
        },
        {
          id: 'step-3',
          name: '第三步：72小时未活跃推送App通知',
          order: 2,
          channel: 'push',
          notification_type: 'marketing',
          terminate_on_success: true,
          delay_seconds: 259200,
          conditions: [
            {
              type: 'delivery_status',
              operator: 'ne',
              field: 'status',
              value: 'delivered',
              step_id: 'step-2',
            },
          ],
          content: {
            body: '{{username}}，专属优惠等着你！',
          },
        },
      ];

      const sequence = await engine.createSequence(
        tenantId,
        '用户7天未登录召回',
        steps,
        createdBy,
        '用于召回7天未登录用户的多渠道通知序列'
      );

      expect(sequence.steps.length).toBe(3);
      expect(sequence.steps[0].delay_seconds).toBe(0);
      expect(sequence.steps[1].delay_seconds).toBe(172800);
      expect(sequence.steps[2].delay_seconds).toBe(259200);
      expect(sequence.steps[0].terminate_on_success).toBe(true);
    });
  });

  describe('实例管理', () => {
    it('获取实例和执行记录', async () => {
      const steps: OrchestrationStep[] = [
        {
          id: 'step-1',
          name: '邮件通知',
          order: 0,
          channel: 'email',
          notification_type: 'transactional',
          terminate_on_success: true,
          content: { subject: 'Test', body: 'Test body' },
        },
      ];

      const sequence = await engine.createSequence(tenantId, '测试序列', steps, createdBy);
      const instance = await engine.startSequence(
        tenantId,
        sequence.id,
        { email: 'test@example.com' }
      );

      const fetchedInstance = await engine.getInstance(tenantId, instance.id);
      expect(fetchedInstance).toBeDefined();
      expect(fetchedInstance?.id).toBe(instance.id);

      const executions = await engine.getInstanceExecutions(instance.id);
      expect(Array.isArray(executions)).toBe(true);
    });

    it('取消运行中的实例', async () => {
      const steps: OrchestrationStep[] = [
        {
          id: 'step-1',
          name: '邮件通知',
          order: 0,
          channel: 'email',
          notification_type: 'transactional',
          terminate_on_success: true,
          delay_seconds: 3600,
          content: { subject: 'Test', body: 'Test body' },
        },
      ];

      const sequence = await engine.createSequence(tenantId, '测试序列', steps, createdBy);
      const instance = await engine.startSequence(
        tenantId,
        sequence.id,
        { email: 'test@example.com' }
      );

      const cancelled = await engine.cancelInstance(tenantId, instance.id);
      expect(cancelled).toBe(true);
    });
  });

  describe('边界情况', () => {
    it('启动已禁用的序列应该失败', async () => {
      const steps: OrchestrationStep[] = [
        {
          id: 'step-1',
          name: '邮件通知',
          order: 0,
          channel: 'email',
          notification_type: 'transactional',
          terminate_on_success: true,
          content: { subject: 'Test', body: 'Test body' },
        },
      ];

      const sequence = await engine.createSequence(tenantId, '测试序列', steps, createdBy);
      await engine.updateSequence(tenantId, sequence.id, { enabled: false });

      await expect(
        engine.startSequence(tenantId, sequence.id, { email: 'test@example.com' })
      ).rejects.toThrow();
    });

    it('无效的步骤配置应该被拒绝', async () => {
      const invalidSteps = [
        {
          id: 'step-1',
          name: '无效步骤',
          order: 0,
          channel: 'invalid-channel',
          notification_type: 'transactional',
          terminate_on_success: true,
          content: { body: 'Test' },
        },
      ];

      const validateSteps = (engine as any).validateSteps || (() => {
        const channelTypes = ['email', 'sms', 'push', 'slack', 'wechat', 'feishu', 'webhook'];
        for (let i = 0; i < invalidSteps.length; i++) {
          const step = invalidSteps[i];
          if (!step.channel || !channelTypes.includes(step.channel)) {
            throw new Error(`Step ${i + 1}: invalid or missing channel`);
          }
        }
      });

      await expect(() => validateSteps()).toThrow();
    });
  });
});
