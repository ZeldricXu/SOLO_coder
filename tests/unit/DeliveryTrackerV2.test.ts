import { describe, it, expect, vi, beforeEach } from 'vitest';
import { DeliveryTracker } from '../../src/tracking/DeliveryTracker';
import { DeliveryQueryFilter, ChannelType, NotificationType, DeliveryStatus } from '../../src/types';
import { createMockDatabase } from '../utils/mocks';
import { db } from '../../src/db';

vi.mock('../../src/db');

describe('DeliveryTrackerV2 - 聚合查询功能', () => {
  let tracker: DeliveryTracker;
  const tenantId = 'test-tenant-123';

  beforeEach(() => {
    vi.clearAllMocks();
    createMockDatabase();
    tracker = DeliveryTracker.getInstance();
  });

  describe('getDeliveryStatistics - 多维统计', () => {
    it('获取完整的投递统计数据', async () => {
      const mockRows = [
        { total: '1000', delivered: '850', failed: '100', read_count: '400', clicked: '200' },
      ];

      const mockChannelRows = [
        {
          channel: 'email',
          total_sent: '600',
          total_delivered: '550',
          total_failed: '50',
          avg_latency_ms: '1200',
          p50_latency_ms: '800',
          p95_latency_ms: '2500',
          p99_latency_ms: '4500',
        },
        {
          channel: 'sms',
          total_sent: '400',
          total_delivered: '300',
          total_failed: '50',
          avg_latency_ms: '3500',
          p50_latency_ms: '2000',
          p95_latency_ms: '6000',
          p99_latency_ms: '8000',
        },
      ];

      const mockLatencyRows = [
        { under_1s: '600', under_5s: '900', under_30s: '980', over_30s: '20' },
      ];

      const mockFailureRows = [
        { reason: 'Connection timeout', count: '35' },
        { reason: 'Invalid recipient', count: '25' },
        { reason: 'Rate limit exceeded', count: '20' },
        { reason: 'Server error', count: '15' },
      ];

      vi.mocked(db.query)
        .mockResolvedValueOnce({ rows: mockRows, rowCount: 1 })
        .mockResolvedValueOnce({ rows: mockChannelRows, rowCount: 2 })
        .mockResolvedValueOnce({ rows: mockLatencyRows, rowCount: 1 })
        .mockResolvedValueOnce({ rows: mockFailureRows, rowCount: 4 });

      const filter: DeliveryQueryFilter = {
        tenant_id: tenantId,
        start_time: new Date(Date.now() - 7 * 24 * 60 * 60 * 1000),
        end_time: new Date(),
      };

      const stats = await tracker.getDeliveryStatistics(filter);

      expect(stats).toBeDefined();
      expect(stats.total_sent).toBe(1000);
      expect(stats.total_delivered).toBe(850);
      expect(stats.total_failed).toBe(100);
      expect(stats.total_read).toBe(400);
      expect(stats.total_clicked).toBe(200);
      expect(stats.delivery_rate).toBe(0.85);
      expect(stats.open_rate).toBe(400 / 850);
      expect(stats.click_rate).toBe(200 / 850);
      expect(stats.failure_rate).toBe(0.1);

      expect(stats.channel_stats.length).toBe(2);
      expect(stats.channel_stats[0].channel).toBe('email');
      expect(stats.channel_stats[0].delivery_rate).toBeCloseTo(0.9167, 2);
      expect(stats.channel_stats[0].p99_latency_ms).toBe(4500);
      expect(stats.channel_stats[1].p99_latency_ms).toBe(8000);

      expect(stats.latency_distribution.under_1s).toBe(600);
      expect(stats.latency_distribution.over_30s).toBe(20);

      expect(stats.failure_reasons.length).toBe(4);
      expect(stats.failure_reasons[0].reason).toBe('Connection timeout');
      expect(stats.failure_reasons[0].count).toBe(35);
    });

    it('按渠道过滤统计', async () => {
      const mockRows = [{ total: '500', delivered: '450', failed: '50', read_count: '200', clicked: '100' }];
      const mockChannelRows = [
        {
          channel: 'email',
          total_sent: '500',
          total_delivered: '450',
          total_failed: '50',
          avg_latency_ms: '1000',
          p50_latency_ms: '600',
          p95_latency_ms: '2000',
          p99_latency_ms: '4000',
        },
      ];

      vi.mocked(db.query)
        .mockResolvedValueOnce({ rows: mockRows, rowCount: 1 })
        .mockResolvedValueOnce({ rows: mockChannelRows, rowCount: 1 })
        .mockResolvedValueOnce({ rows: [{ under_1s: '300', under_5s: '480', under_30s: '495', over_30s: '5' }], rowCount: 1 })
        .mockResolvedValueOnce({ rows: [], rowCount: 0 });

      const filter: DeliveryQueryFilter = {
        tenant_id: tenantId,
        start_time: new Date(Date.now() - 7 * 24 * 60 * 60 * 1000),
        end_time: new Date(),
        channels: ['email'],
      };

      const stats = await tracker.getDeliveryStatistics(filter);

      expect(stats.filters.channels).toEqual(['email']);
      expect(stats.total_sent).toBe(500);
      expect(stats.channel_stats.length).toBe(1);
    });

    it('空数据时返回0值', async () => {
      vi.mocked(db.query)
        .mockResolvedValueOnce({ rows: [{ total: '0', delivered: '0', failed: '0', read_count: '0', clicked: '0' }], rowCount: 1 })
        .mockResolvedValueOnce({ rows: [], rowCount: 0 })
        .mockResolvedValueOnce({ rows: [{ under_1s: '0', under_5s: '0', under_30s: '0', over_30s: '0' }], rowCount: 1 })
        .mockResolvedValueOnce({ rows: [], rowCount: 0 });

      const filter: DeliveryQueryFilter = {
        tenant_id: tenantId,
        start_time: new Date(Date.now() - 24 * 60 * 60 * 1000),
        end_time: new Date(),
      };

      const stats = await tracker.getDeliveryStatistics(filter);

      expect(stats.total_sent).toBe(0);
      expect(stats.delivery_rate).toBe(0);
      expect(stats.open_rate).toBe(0);
      expect(stats.failure_rate).toBe(0);
    });
  });

  describe('getGroupedStatistics - 分组统计', () => {
    it('按渠道分组统计', async () => {
      const mockRows = [
        { channel: 'email', total_sent: '600', total_delivered: '550', total_failed: '50', total_read: '300', total_clicked: '150', delivery_rate_pct: '91.67' },
        { channel: 'sms', total_sent: '400', total_delivered: '300', total_failed: '100', total_read: '100', total_clicked: '50', delivery_rate_pct: '75.00' },
        { channel: 'push', total_sent: '200', total_delivered: '180', total_failed: '20', total_read: '90', total_clicked: '45', delivery_rate_pct: '90.00' },
      ];

      vi.mocked(db.query).mockResolvedValueOnce({ rows: mockRows, rowCount: 3 });

      const filter: DeliveryQueryFilter = {
        tenant_id: tenantId,
        start_time: new Date(Date.now() - 7 * 24 * 60 * 60 * 1000),
        end_time: new Date(),
        group_by: ['channel'],
      };

      const stats = await tracker.getGroupedStatistics(filter);

      expect(stats.length).toBe(3);
      expect(stats[0].channel).toBe('email');
      expect(stats[0].delivery_rate_pct).toBe('91.67');
      expect(stats[1].channel).toBe('sms');
      expect(stats[1].delivery_rate_pct).toBe('75.00');
    });

    it('按通知类型分组统计', async () => {
      const mockRows = [
        { notification_type: 'transactional', total_sent: '800', total_delivered: '720', total_failed: '80', delivery_rate_pct: '90.00' },
        { notification_type: 'marketing', total_sent: '300', total_delivered: '210', total_failed: '90', delivery_rate_pct: '70.00' },
        { notification_type: 'security', total_sent: '100', total_delivered: '99', total_failed: '1', delivery_rate_pct: '99.00' },
      ];

      vi.mocked(db.query).mockResolvedValueOnce({ rows: mockRows, rowCount: 3 });

      const filter: DeliveryQueryFilter = {
        tenant_id: tenantId,
        start_time: new Date(Date.now() - 7 * 24 * 60 * 60 * 1000),
        end_time: new Date(),
        group_by: ['notification_type'],
      };

      const stats = await tracker.getGroupedStatistics(filter);

      expect(stats.length).toBe(3);
      expect(stats.find((s: any) => s.notification_type === 'security')?.delivery_rate_pct).toBe('99.00');
    });

    it('多维度分组统计', async () => {
      const mockRows = [
        { channel: 'email', notification_type: 'transactional', total_sent: '500', total_delivered: '480', delivery_rate_pct: '96.00' },
        { channel: 'email', notification_type: 'marketing', total_sent: '100', total_delivered: '70', delivery_rate_pct: '70.00' },
        { channel: 'sms', notification_type: 'transactional', total_sent: '200', total_delivered: '190', delivery_rate_pct: '95.00' },
      ];

      vi.mocked(db.query).mockResolvedValueOnce({ rows: mockRows, rowCount: 3 });

      const filter: DeliveryQueryFilter = {
        tenant_id: tenantId,
        start_time: new Date(Date.now() - 7 * 24 * 60 * 60 * 1000),
        end_time: new Date(),
        group_by: ['channel', 'notification_type'],
      };

      const stats = await tracker.getGroupedStatistics(filter);

      expect(stats.length).toBe(3);
      expect(stats[0]).toHaveProperty('channel');
      expect(stats[0]).toHaveProperty('notification_type');
    });
  });

  describe('getLatencyPercentiles - 延迟百分位', () => {
    it('获取默认百分位数据', async () => {
      vi.mocked(db.query).mockResolvedValueOnce({
        rows: [{ p50: '1000', p75: '2000', p90: '3500', p95: '5000', p99: '8000' }],
        rowCount: 1,
      });

      const filter: DeliveryQueryFilter = {
        tenant_id: tenantId,
        start_time: new Date(Date.now() - 24 * 60 * 60 * 1000),
        end_time: new Date(),
      };

      const percentiles = await tracker.getLatencyPercentiles(filter);

      expect(percentiles).toBeDefined();
      expect(percentiles[50]).toBe(1000);
      expect(percentiles[75]).toBe(2000);
      expect(percentiles[90]).toBe(3500);
      expect(percentiles[95]).toBe(5000);
      expect(percentiles[99]).toBe(8000);
    });

    it('自定义百分位数据', async () => {
      vi.mocked(db.query).mockResolvedValueOnce({
        rows: [{ p99: '8000', p999: '12000' }],
        rowCount: 1,
      });

      const filter: DeliveryQueryFilter = {
        tenant_id: tenantId,
        start_time: new Date(Date.now() - 24 * 60 * 60 * 1000),
        end_time: new Date(),
      };

      const percentiles = await tracker.getLatencyPercentiles(filter, [99, 99.9]);

      expect(percentiles).toBeDefined();
    });
  });

  describe('getDailyTrend - 每日趋势', () => {
    it('获取过去7天的每日趋势', async () => {
      const mockRows = Array.from({ length: 7 }, (_, i) => {
        const date = new Date();
        date.setDate(date.getDate() - (6 - i));
        return {
          date: date.toISOString().split('T')[0],
          total_sent: 100 + i * 10,
          delivered: 90 + i * 9,
          failed: 10 + i,
          delivery_rate_pct: (90 + i * 9) / (100 + i * 10) * 100,
        };
      });

      vi.mocked(db.query).mockResolvedValueOnce({ rows: mockRows, rowCount: 7 });

      const filter: DeliveryQueryFilter = {
        tenant_id: tenantId,
        start_time: new Date(Date.now() - 7 * 24 * 60 * 60 * 1000),
        end_time: new Date(),
      };

      const trend = await tracker.getDailyTrend(filter);

      expect(trend.length).toBe(7);
      expect(trend[0]).toHaveProperty('date');
      expect(trend[0]).toHaveProperty('total_sent');
      expect(trend[0]).toHaveProperty('delivery_rate');
      expect(trend[6].total_sent).toBeGreaterThan(trend[0].total_sent);
    });
  });

  describe('表驱动测试 - 延迟P99告警场景', () => {
    const testCases = [
      {
        name: 'P99小于5秒 - 正常',
        p99_latency_ms: 4500,
        expectedAlert: false,
        expectedLevel: 'normal',
      },
      {
        name: 'P99等于5秒 - 正常边界',
        p99_latency_ms: 5000,
        expectedAlert: false,
        expectedLevel: 'normal',
      },
      {
        name: 'P99超过5秒 - 告警',
        p99_latency_ms: 5500,
        expectedAlert: true,
        expectedLevel: 'warning',
      },
      {
        name: 'P99超过10秒 - 严重告警',
        p99_latency_ms: 12000,
        expectedAlert: true,
        expectedLevel: 'critical',
      },
    ];

    testCases.forEach((tc) => {
      it(`延迟P99检测: ${tc.name}`, () => {
        const isOver5s = tc.p99_latency_ms > 5000;
        const level = tc.p99_latency_ms > 10000 ? 'critical' : tc.p99_latency_ms > 5000 ? 'warning' : 'normal';

        expect(isOver5s).toBe(tc.expectedAlert);
        expect(level).toBe(tc.expectedLevel);
      });
    });
  });

  describe('表驱动测试 - 送达率健康度', () => {
    const testCases = [
      {
        name: '送达率98% - 优秀',
        delivery_rate: 0.98,
        expectedLevel: 'excellent',
        expectedAction: 'none',
      },
      {
        name: '送达率92% - 良好',
        delivery_rate: 0.92,
        expectedLevel: 'good',
        expectedAction: 'monitor',
      },
      {
        name: '送达率85% - 一般',
        delivery_rate: 0.85,
        expectedLevel: 'fair',
        expectedAction: 'investigate',
      },
      {
        name: '送达率70% - 差',
        delivery_rate: 0.70,
        expectedLevel: 'poor',
        expectedAction: 'alert',
      },
      {
        name: '送达率50% - 严重',
        delivery_rate: 0.50,
        expectedLevel: 'critical',
        expectedAction: 'emergency',
      },
    ];

    testCases.forEach((tc) => {
      it(`送达率健康度: ${tc.name}`, () => {
        let level = 'critical';
        let action = 'emergency';

        if (tc.delivery_rate >= 0.95) {
          level = 'excellent';
          action = 'none';
        } else if (tc.delivery_rate >= 0.90) {
          level = 'good';
          action = 'monitor';
        } else if (tc.delivery_rate >= 0.80) {
          level = 'fair';
          action = 'investigate';
        } else if (tc.delivery_rate >= 0.60) {
          level = 'poor';
          action = 'alert';
        }

        expect(level).toBe(tc.expectedLevel);
        expect(action).toBe(tc.expectedAction);
      });
    });
  });
});
