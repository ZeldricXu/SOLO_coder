import { describe, it, expect, vi, beforeEach } from 'vitest';
import { DeliveryTracker } from '../../src/tracking/DeliveryTracker';
import { db } from '../../src/db';
import { createDeliveryLog, runTableDrivenTests } from '../utils/factories';
import { v4 as uuidv4 } from 'uuid';
import type { DeliveryStatus, ChannelType, NotificationType, NotificationPriority } from '../../src/types';

vi.mock('../../src/db');

describe('DeliveryTracker', () => {
  let tracker: DeliveryTracker;
  const tenantId = uuidv4();
  const deliveryId = uuidv4();

  beforeEach(() => {
    vi.clearAllMocks();
    vi.useRealTimers();

    (DeliveryTracker as any).instance = null;
    vi.mocked(db.withTenantContext).mockImplementation(async (tid, fn) => fn());
    vi.mocked(db.query).mockResolvedValue({ rows: [], rowCount: 0 });

    tracker = DeliveryTracker.getInstance();
    tracker.setUseBatchWriter(false);
  });

  describe('正常路径测试 - 完整生命周期', () => {
    it('模拟一条通知经过发送→回调→状态更新的完整生命周期', async () => {
      const messageId = uuidv4();
      const events: { status: DeliveryStatus; timestamp: Date }[] = [];

      vi.mocked(db.query).mockImplementation(async (sql: string, params: any[]) => {
        if (sql.includes('INSERT INTO delivery_logs')) {
          events.push({ status: 'queued', timestamp: new Date() });
          return { rows: [], rowCount: 1 };
        }
        if (sql.includes('UPDATE delivery_logs')) {
          const status = params[0] as DeliveryStatus;
          events.push({ status, timestamp: new Date() });
          return { rows: [], rowCount: 1 };
        }
        if (sql.includes('SELECT * FROM delivery_logs WHERE delivery_id')) {
          return {
            rows: [...events].reverse().map((e, i) => ({
              id: uuidv4(),
              delivery_id: deliveryId,
              tenant_id: tenantId,
              notification_type: 'transactional',
              channel: 'email',
              provider: 'smtp',
              recipient: 'test@example.com',
              status: e.status,
              priority: 'medium',
              message_id: events.length - i > 1 ? messageId : undefined,
              created_at: e.timestamp,
              updated_at: e.timestamp,
            })),
            rowCount: events.length,
          };
        }
        return { rows: [], rowCount: 0 };
      });

      await tracker.createDeliveryLog(
        deliveryId,
        tenantId,
        'transactional',
        'email',
        'smtp',
        'test@example.com',
        'medium'
      );
      expect(events[0].status).toBe('queued');

      await tracker.updateStatus(tenantId, deliveryId, 'email', 'sent', messageId);
      expect(events[1].status).toBe('sent');

      await tracker.handleCallback(tenantId, messageId, 'delivered', {
        delivered_at: new Date().toISOString(),
      });
      expect(events[2].status).toBe('delivered');

      const logs = await tracker.getByDeliveryId(tenantId, deliveryId);
      expect(logs.length).toBe(3);
      expect(logs.map(l => l.status)).toEqual(['delivered', 'sent', 'queued']);
      expect(logs[0].updated_at.getTime()).toBeGreaterThanOrEqual(logs[2].created_at.getTime());
    });

    it('每一步状态转换和时间戳正确记录', async () => {
      const timestamps: { status: DeliveryStatus; time: number }[] = [];
      const baseTime = Date.now();

      vi.useFakeTimers();
      vi.setSystemTime(baseTime);

      vi.mocked(db.query).mockImplementation(async (sql: string, params: any[]) => {
        if (sql.includes('INSERT INTO delivery_logs')) {
          timestamps.push({ status: 'queued', time: Date.now() });
          return { rows: [], rowCount: 1 };
        }
        if (sql.includes('UPDATE delivery_logs') && params[0] === 'sent') {
          timestamps.push({ status: 'sent', time: Date.now() });
          return { rows: [], rowCount: 1 };
        }
        if (sql.includes('UPDATE delivery_logs') && sql.includes('metadata')) {
          timestamps.push({ status: 'delivered', time: Date.now() });
          return { rows: [{ delivery_id: deliveryId }], rowCount: 1 };
        }
        return { rows: [], rowCount: 0 };
      });

      await tracker.createDeliveryLog(
        deliveryId,
        tenantId,
        'transactional',
        'email',
        'smtp',
        'test@example.com',
        'medium'
      );

      vi.advanceTimersByTime(1000);
      await tracker.updateStatus(tenantId, deliveryId, 'email', 'sent', 'msg-123');

      vi.advanceTimersByTime(5000);
      await tracker.handleCallback(tenantId, 'msg-123', 'delivered');

      expect(timestamps[0].status).toBe('queued');
      expect(timestamps[1].status).toBe('sent');
      expect(timestamps[2].status).toBe('delivered');
      expect(timestamps[1].time - timestamps[0].time).toBe(1000);
      expect(timestamps[2].time - timestamps[1].time).toBe(5000);

      vi.useRealTimers();
    });
  });

  describe('异常路径测试 - 回调乱序', () => {
    it('渠道回调乱序到达时保留终态delivered而不被后到的sent覆盖', async () => {
      const messageId = 'msg-xxx-123';
      let currentStatus: DeliveryStatus | null = null;
      let updateCount = 0;

      vi.mocked(db.query).mockImplementation(async (sql: string, params: any[]) => {
        if (sql.includes('INSERT INTO delivery_logs')) {
          currentStatus = 'queued';
          return { rows: [], rowCount: 1 };
        }
        if (sql.includes('UPDATE delivery_logs') && !sql.includes('metadata')) {
          const newStatus = params[0] as DeliveryStatus;
          const statusPriority: Record<DeliveryStatus, number> = {
            pending: 0,
            queued: 1,
            sent: 2,
            delivered: 4,
            read: 5,
            clicked: 6,
            failed: 3,
          };
          
          const currentPriority = currentStatus ? statusPriority[currentStatus] : -1;
          const newPriority = statusPriority[newStatus];
          
          if (newPriority >= currentPriority) {
            currentStatus = newStatus;
            updateCount++;
          }
          
          return { rows: [], rowCount: 1 };
        }
        if (sql.includes('UPDATE delivery_logs') && sql.includes('metadata')) {
          const newStatus = params[0] as DeliveryStatus;
          const statusPriority: Record<DeliveryStatus, number> = {
            pending: 0,
            queued: 1,
            sent: 2,
            delivered: 4,
            read: 5,
            clicked: 6,
            failed: 3,
          };
          
          const currentPriority = currentStatus ? statusPriority[currentStatus] : -1;
          const newPriority = statusPriority[newStatus];
          
          if (newPriority >= currentPriority) {
            currentStatus = newStatus;
            updateCount++;
          }
          
          return { rows: [{ delivery_id: deliveryId }], rowCount: 1 };
        }
        return { rows: [], rowCount: 0 };
      });

      await tracker.createDeliveryLog(
        deliveryId,
        tenantId,
        'transactional',
        'email',
        'smtp',
        'test@example.com',
        'medium'
      );
      expect(currentStatus).toBe('queued');

      await tracker.handleCallback(tenantId, messageId, 'delivered', { delivered_at: new Date().toISOString() });
      expect(currentStatus).toBe('delivered');

      await tracker.updateStatus(tenantId, deliveryId, 'email', 'sent', messageId);

      expect(currentStatus).toBe('delivered');
    });

    it('回调message_id不存在时不报错', async () => {
      vi.mocked(db.query).mockResolvedValueOnce({ rows: [], rowCount: 0 });

      await expect(
        tracker.handleCallback(tenantId, 'non-existent-msg-id', 'delivered')
      ).resolves.not.toThrow();
    });

    it('数据库异常时记录错误但不抛出', async () => {
      vi.mocked(db.withTenantContext).mockRejectedValueOnce(new Error('DB connection lost'));

      await expect(
        tracker.createDeliveryLog(
          deliveryId,
          tenantId,
          'transactional',
          'email',
          'smtp',
          'test@example.com',
          'medium'
        )
      ).resolves.not.toThrow();
    });
  });

  describe('表驱动测试 - 状态转换矩阵', () => {
    interface StatusTransitionTestCase {
      initialStatus: DeliveryStatus;
      newStatus: DeliveryStatus;
      shouldAllow: boolean;
      reason: string;
    }

    const statusPriority: Record<DeliveryStatus, number> = {
      pending: 0,
      queued: 1,
      sent: 2,
      delivered: 4,
      read: 5,
      clicked: 6,
      failed: 3,
    };

    const allStatuses: DeliveryStatus[] = ['pending', 'queued', 'sent', 'delivered', 'read', 'clicked', 'failed'];

    const testCases: { name: string; input: StatusTransitionTestCase; expected: boolean }[] = [];

    for (const initial of allStatuses) {
      for (const final of allStatuses) {
        const shouldAllow = statusPriority[final] >= statusPriority[initial];
        testCases.push({
          name: `${initial} → ${final}: ${shouldAllow ? '允许' : '拒绝'}`,
          input: {
            initialStatus: initial,
            newStatus: final,
            shouldAllow,
            reason: shouldAllow ? '终态优先级更高或相等' : '终态优先级更低',
          },
          expected: shouldAllow,
        });
      }
    }

    runTableDrivenTests<StatusTransitionTestCase, boolean>(testCases, async (input, expected) => {
      const messageId = uuidv4();
      let currentStatus = input.initialStatus;

      vi.mocked(db.query).mockImplementation(async (sql: string, params: any[]) => {
        if (sql.includes('INSERT INTO delivery_logs')) {
          return { rows: [], rowCount: 1 };
        }
        if (sql.includes('UPDATE delivery_logs') && !sql.includes('metadata')) {
          const newStatus = params[0] as DeliveryStatus;
          if (statusPriority[newStatus] >= statusPriority[currentStatus]) {
            currentStatus = newStatus;
          }
          return { rows: [], rowCount: 1 };
        }
        if (sql.includes('UPDATE delivery_logs') && sql.includes('metadata')) {
          const newStatus = params[0] as DeliveryStatus;
          if (statusPriority[newStatus] >= statusPriority[currentStatus]) {
            currentStatus = newStatus;
          }
          return { rows: [{ delivery_id: deliveryId }], rowCount: 1 };
        }
        return { rows: [], rowCount: 0 };
      });

      await tracker.createDeliveryLog(
        deliveryId,
        tenantId,
        'transactional',
        'email',
        'smtp',
        'test@example.com',
        'medium'
      );

      vi.mocked(db.query).mockImplementation(async (sql: string, params: any[]) => {
        if (sql.includes('UPDATE delivery_logs') && !sql.includes('metadata')) {
          const newStatus = params[0] as DeliveryStatus;
          if (statusPriority[newStatus] >= statusPriority[currentStatus]) {
            currentStatus = newStatus;
          }
          return { rows: [], rowCount: 1 };
        }
        if (sql.includes('UPDATE delivery_logs') && sql.includes('metadata')) {
          const newStatus = params[0] as DeliveryStatus;
          if (statusPriority[newStatus] >= statusPriority[currentStatus]) {
            currentStatus = newStatus;
          }
          return { rows: [{ delivery_id: deliveryId }], rowCount: 1 };
        }
        return { rows: [], rowCount: 0 };
      });

      await tracker.updateStatus(tenantId, deliveryId, 'email', input.initialStatus, messageId);

      await tracker.handleCallback(tenantId, messageId, input.newStatus);

      if (expected) {
        expect(currentStatus).toBe(input.newStatus);
      } else {
        expect(currentStatus).toBe(input.initialStatus);
      }
    });
  });

  describe('查询API测试', () => {
    it('按delivery_id查询返回完整日志', async () => {
      const expectedLogs = [
        createDeliveryLog({ delivery_id: deliveryId, tenant_id: tenantId, status: 'delivered', channel: 'email' }),
        createDeliveryLog({ delivery_id: deliveryId, tenant_id: tenantId, status: 'sent', channel: 'email' }),
        createDeliveryLog({ delivery_id: deliveryId, tenant_id: tenantId, status: 'queued', channel: 'email' }),
      ];

      vi.mocked(db.query).mockResolvedValueOnce({ rows: expectedLogs, rowCount: 3 });

      const logs = await tracker.getByDeliveryId(tenantId, deliveryId);

      expect(logs.length).toBe(3);
      expect(logs[0].status).toBe('delivered');
      expect(logs[2].status).toBe('queued');
    });

    it('按recipient查询支持分页', async () => {
      vi.mocked(db.query).mockResolvedValueOnce({ rows: [], rowCount: 0 });

      await tracker.getByRecipient(tenantId, 'test@example.com', 50, 100);

      expect(db.query).toHaveBeenCalledWith(
        expect.stringContaining('LIMIT $2 OFFSET $3'),
        ['test@example.com', 50, 100]
      );
    });

    it('按时间范围查询支持多维度过滤', async () => {
      const startTime = new Date('2024-01-01');
      const endTime = new Date('2024-01-31');

      vi.mocked(db.query).mockResolvedValueOnce({ rows: [], rowCount: 0 });

      await tracker.getByTimeRange(
        tenantId,
        startTime,
        endTime,
        { channel: 'email', status: 'delivered', notification_type: 'transactional' }
      );

      const call = vi.mocked(db.query).mock.calls[0];
      const sql = call[0] as string;

      expect(sql).toContain('channel = $3');
      expect(sql).toContain('status = $4');
      expect(sql).toContain('notification_type = $5');
      expect(call[1]).toEqual(expect.arrayContaining([startTime, endTime, 'email', 'delivered', 'transactional']));
    });

    it('getStats按渠道和状态聚合统计', async () => {
      const mockStats = [
        { channel: 'email', status: 'sent', count: 100 },
        { channel: 'email', status: 'delivered', count: 95 },
        { channel: 'sms', status: 'sent', count: 50 },
      ];

      vi.mocked(db.query).mockResolvedValueOnce({ rows: mockStats, rowCount: 3 });

      const stats = await tracker.getStats(tenantId, new Date('2024-01-01'), new Date('2024-01-31'));

      expect(stats.length).toBe(3);
      expect(stats.find((s: any) => s.channel === 'email' && s.status === 'delivered').count).toBe(95);
    });
  });

  describe('元数据合并测试', () => {
    it('多次回调的metadata正确合并而不覆盖', async () => {
      const messageId = uuidv4();
      const mergedMetadata: Record<string, any> = {};

      vi.mocked(db.query).mockImplementation(async (sql: string, params: any[]) => {
        if (sql.includes('INSERT INTO delivery_logs')) {
          return { rows: [], rowCount: 1 };
        }
        if (sql.includes('UPDATE delivery_logs') && !sql.includes('metadata')) {
          return { rows: [], rowCount: 1 };
        }
        if (sql.includes('UPDATE delivery_logs') && sql.includes('metadata')) {
          const newMetadata = params[2] as Record<string, any>;
          Object.assign(mergedMetadata, newMetadata);
          return { rows: [{ delivery_id: deliveryId, metadata: mergedMetadata }], rowCount: 1 };
        }
        return { rows: [], rowCount: 0 };
      });

      await tracker.createDeliveryLog(
        deliveryId,
        tenantId,
        'transactional',
        'email',
        'smtp',
        'test@example.com',
        'medium'
      );
      await tracker.updateStatus(tenantId, deliveryId, 'email', 'sent', messageId);

      await tracker.handleCallback(tenantId, messageId, 'delivered', {
        delivered_at: '2024-01-01T10:00:00Z',
        ip: '192.168.1.1',
      });

      await tracker.handleCallback(tenantId, messageId, 'read', {
        read_at: '2024-01-01T10:30:00Z',
        user_agent: 'Mozilla/5.0',
      });

      expect(mergedMetadata.delivered_at).toBe('2024-01-01T10:00:00Z');
      expect(mergedMetadata.ip).toBe('192.168.1.1');
      expect(mergedMetadata.read_at).toBe('2024-01-01T10:30:00Z');
      expect(mergedMetadata.user_agent).toBe('Mozilla/5.0');
    });
  });

  describe('失败场景处理', () => {
    it('记录失败原因和错误信息', async () => {
      vi.mocked(db.query).mockResolvedValue({ rows: [], rowCount: 1 });

      await tracker.updateStatus(
        tenantId,
        deliveryId,
        'email',
        'failed',
        undefined,
        'Connection timeout after 30000ms'
      );

      const call = vi.mocked(db.query).mock.calls.find(
        c => (c[0] as string).includes('UPDATE delivery_logs') && !(c[0] as string).includes('metadata')
      );

      expect(call![1][0]).toBe('failed');
      expect(call![1][2]).toBe('Connection timeout after 30000ms');
    });

    it('不同渠道的状态独立更新', async () => {
      const calls: { channel: ChannelType; status: DeliveryStatus }[] = [];

      vi.mocked(db.query).mockImplementation(async (sql: string, params: any[]) => {
        if (sql.includes('UPDATE delivery_logs') && !sql.includes('metadata')) {
          calls.push({ channel: params[4] as ChannelType, status: params[0] as DeliveryStatus });
        }
        return { rows: [], rowCount: 1 };
      });

      await tracker.updateStatus(tenantId, deliveryId, 'email', 'sent', 'email-msg-1');
      await tracker.updateStatus(tenantId, deliveryId, 'sms', 'failed', undefined, 'Invalid phone number');

      expect(calls).toEqual([
        { channel: 'email', status: 'sent' },
        { channel: 'sms', status: 'failed' },
      ]);
    });
  });
});
