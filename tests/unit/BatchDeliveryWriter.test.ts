import { describe, it, expect, vi, beforeEach, afterEach } from 'vitest';
import { createMockDatabase } from '../utils/mocks';
import type { BufferedOperation } from '../../src/tracking/BatchDeliveryWriter';

vi.mock('ioredis');
vi.mock('../../src/db');

describe('BatchDeliveryWriter', () => {
  let BatchDeliveryWriter: typeof import('../../src/tracking/BatchDeliveryWriter').BatchDeliveryWriter;
  let mockXadd: ReturnType<typeof vi.fn>;
  let mockXreadgroup: ReturnType<typeof vi.fn>;
  let mockXgroup: ReturnType<typeof vi.fn>;
  let mockXack: ReturnType<typeof vi.fn>;
  let mockPing: ReturnType<typeof vi.fn>;
  let mockDisconnect: ReturnType<typeof vi.fn>;
  let mockOn: ReturnType<typeof vi.fn>;
  let mockDb: any;

  const flushIntervalMs = 500;
  const batchSize = 100;

  function normalizeSql(sql: string): string {
    return sql.replace(/\s+/g, ' ').trim();
  }

  beforeEach(async () => {
    vi.resetModules();
    vi.useFakeTimers();
    vi.clearAllMocks();

    mockXadd = vi.fn().mockResolvedValue('1234567890-0');
    mockXreadgroup = vi.fn().mockResolvedValue([]);
    mockXgroup = vi.fn().mockResolvedValue('OK');
    mockXack = vi.fn().mockResolvedValue(1);
    mockPing = vi.fn().mockResolvedValue('PONG');
    mockDisconnect = vi.fn().mockResolvedValue(undefined);
    mockOn = vi.fn();

    const mockRedisConstructor = vi.fn().mockImplementation(() => ({
      xadd: mockXadd,
      xreadgroup: mockXreadgroup,
      xgroup: mockXgroup,
      xack: mockXack,
      ping: mockPing,
      disconnect: mockDisconnect,
      on: mockOn,
    }));

    vi.doMock('ioredis', () => ({
      default: mockRedisConstructor,
    }));

    mockDb = createMockDatabase();

    const deliveryLogsStore: any[] = [];
    const originalQuery = mockDb.query;
    mockDb.query = vi.fn(async (sql: string, params: any[]) => {
      const normalizedSql = normalizeSql(sql);

      if (normalizedSql.startsWith('INSERT INTO delivery_logs') && normalizedSql.includes('ON CONFLICT')) {
        if (params?.length > 1) {
          const [delivery_id, tenant_id, notification_type, channel, provider, recipient, status, priority, metadata] = params;
          const existing = deliveryLogsStore.find(
            (l: any) => l.delivery_id === delivery_id && l.channel === channel
          );
          if (!existing) {
            deliveryLogsStore.push({
              delivery_id,
              tenant_id,
              notification_type,
              channel,
              provider,
              recipient,
              status,
              priority,
              metadata,
              created_at: new Date(),
              updated_at: new Date(),
            });
          }
        }
        return { rows: [], rowCount: 1 };
      }

      if (normalizedSql.startsWith('UPDATE delivery_logs SET status = $1, message_id = $2')) {
        const [status, message_id, error_message, delivery_id, channel] = params;
        const idx = deliveryLogsStore.findIndex(
          (l: any) => l.delivery_id === delivery_id && l.channel === channel
        );
        if (idx !== -1) {
          deliveryLogsStore[idx] = {
            ...deliveryLogsStore[idx],
            status,
            message_id: message_id ?? deliveryLogsStore[idx].message_id,
            error_message: error_message ?? deliveryLogsStore[idx].error_message,
            updated_at: new Date(),
          };
          return { rows: [], rowCount: 1 };
        }
        return { rows: [], rowCount: 0 };
      }

      if (normalizedSql.startsWith('UPDATE delivery_logs SET status = $1, updated_at = NOW()')) {
        const [status, message_id, metadata] = params;
        const idx = deliveryLogsStore.findIndex((l: any) => l.message_id === message_id);
        if (idx !== -1) {
          deliveryLogsStore[idx] = {
            ...deliveryLogsStore[idx],
            status,
            metadata: { ...(deliveryLogsStore[idx].metadata || {}), ...(metadata || {}) },
            updated_at: new Date(),
          };
          return { rows: [], rowCount: 1 };
        }
        return { rows: [], rowCount: 0 };
      }

      return originalQuery(sql, params);
    });

    mockDb._deliveryLogsStore = deliveryLogsStore;

    const module = await import('../../src/tracking/BatchDeliveryWriter');
    BatchDeliveryWriter = module.BatchDeliveryWriter;
  });

  afterEach(() => {
    vi.useRealTimers();
  });

  describe('1. Singleton pattern', () => {
    it('should return the same instance on multiple getInstance calls', () => {
      const instance1 = BatchDeliveryWriter.getInstance();
      const instance2 = BatchDeliveryWriter.getInstance();
      expect(instance1).toBe(instance2);
    });

    it('should use provided configuration on first call', () => {
      const customInterval = 1000;
      const customBatchSize = 50;
      const instance = BatchDeliveryWriter.getInstance(customInterval, customBatchSize);
      expect(instance).toBeDefined();
    });
  });

  describe('2. Redis available: enqueue writes to Redis Stream via XADD', () => {
    it('should call XADD when Redis is available', async () => {
      const writer = BatchDeliveryWriter.getInstance(flushIntervalMs, batchSize);
      await vi.runAllTimersAsync();

      expect(writer.isRedisAvailable()).toBe(true);

      const operation: BufferedOperation = {
        type: 'insert',
        payload: {
          delivery_id: 'del-1',
          tenant_id: 'tenant-1',
          notification_type: 'email',
          channel: 'email',
          provider: 'smtp',
          recipient: 'test@example.com',
          status: 'pending',
          priority: 'normal',
        },
        timestamp: Date.now(),
      };

      await writer.enqueue(operation);

      expect(mockXadd).toHaveBeenCalledTimes(1);
      expect(mockXadd).toHaveBeenCalledWith(
        'notify:delivery_log_stream',
        '*',
        'type',
        'insert',
        'payload',
        expect.stringContaining('"delivery_id":"del-1"'),
        'timestamp',
        expect.any(String)
      );
      expect(writer.getBufferSize()).toBe(0);
    });
  });

  describe('3. Redis unavailable: enqueue buffers in memory', () => {
    beforeEach(async () => {
      mockPing.mockRejectedValue(new Error('Redis connection failed'));
      const module = await import('../../src/tracking/BatchDeliveryWriter');
      BatchDeliveryWriter = module.BatchDeliveryWriter;
    });

    it('should buffer operations in memory when Redis is unavailable', async () => {
      const writer = BatchDeliveryWriter.getInstance(flushIntervalMs, batchSize);
      await vi.runAllTimersAsync();

      expect(writer.isRedisAvailable()).toBe(false);

      const operation: BufferedOperation = {
        type: 'insert',
        payload: {
          delivery_id: 'del-1',
          tenant_id: 'tenant-1',
          notification_type: 'email',
          channel: 'email',
          provider: 'smtp',
          recipient: 'test@example.com',
          status: 'pending',
          priority: 'normal',
        },
        timestamp: Date.now(),
      };

      await writer.enqueue(operation);

      expect(mockXadd).not.toHaveBeenCalled();
      expect(writer.getBufferSize()).toBe(1);
    });
  });

  describe('4. Buffer flush when reaching batchSize (100)', () => {
    beforeEach(async () => {
      mockPing.mockRejectedValue(new Error('Redis connection failed'));
      const module = await import('../../src/tracking/BatchDeliveryWriter');
      BatchDeliveryWriter = module.BatchDeliveryWriter;
    });

    it('should automatically flush when buffer reaches batchSize', async () => {
      const writer = BatchDeliveryWriter.getInstance(flushIntervalMs, batchSize);
      await vi.runAllTimersAsync();

      for (let i = 0; i < batchSize - 1; i++) {
        await writer.enqueue({
          type: 'insert',
          payload: {
            delivery_id: `del-${i}`,
            tenant_id: 'tenant-1',
            notification_type: 'email',
            channel: 'email',
            provider: 'smtp',
            recipient: `test${i}@example.com`,
            status: 'pending',
            priority: 'normal',
          },
          timestamp: Date.now(),
        });
      }

      expect(writer.getBufferSize()).toBe(batchSize - 1);
      const insertCallsBefore = mockDb.query.mock.calls.filter(
        (call: any[]) => normalizeSql(call[0]).startsWith('INSERT INTO delivery_logs')
      );
      expect(insertCallsBefore.length).toBe(0);

      await writer.enqueue({
        type: 'insert',
        payload: {
          delivery_id: `del-${batchSize}`,
          tenant_id: 'tenant-1',
          notification_type: 'email',
          channel: 'email',
          provider: 'smtp',
          recipient: `test${batchSize}@example.com`,
          status: 'pending',
          priority: 'normal',
        },
        timestamp: Date.now(),
      });

      await vi.runAllTimersAsync();

      expect(writer.getBufferSize()).toBe(0);
      const insertCallsAfter = mockDb.query.mock.calls.filter(
        (call: any[]) => normalizeSql(call[0]).startsWith('INSERT INTO delivery_logs')
      );
      expect(insertCallsAfter.length).toBeGreaterThan(0);
    });
  });

  describe('5. Periodic flush timer', () => {
    beforeEach(async () => {
      mockPing.mockRejectedValue(new Error('Redis connection failed'));
      const module = await import('../../src/tracking/BatchDeliveryWriter');
      BatchDeliveryWriter = module.BatchDeliveryWriter;
    });

    it('should periodically flush buffer based on flushIntervalMs', async () => {
      const setIntervalSpy = vi.spyOn(global, 'setInterval');
      const writer = BatchDeliveryWriter.getInstance(flushIntervalMs, batchSize);
      await vi.runOnlyPendingTimersAsync();

      writer.start();

      expect(setIntervalSpy).toHaveBeenCalledWith(
        expect.any(Function),
        flushIntervalMs
      );
      const flushCallback = setIntervalSpy.mock.calls[0][0];

      await writer.enqueue({
        type: 'insert',
        payload: {
          delivery_id: 'del-1',
          tenant_id: 'tenant-1',
          notification_type: 'email',
          channel: 'email',
          provider: 'smtp',
          recipient: 'test@example.com',
          status: 'pending',
          priority: 'normal',
        },
        timestamp: Date.now(),
      });

      expect(writer.getBufferSize()).toBe(1);

      await flushCallback();
      await vi.runOnlyPendingTimersAsync();
      expect(writer.getBufferSize()).toBe(0);

      writer.stop();
    });
  });

  describe('6. Redis failure during XADD triggers fallback to memory buffer', () => {
    it('should fall back to memory buffer when XADD fails', async () => {
      const writer = BatchDeliveryWriter.getInstance(flushIntervalMs, batchSize);
      await vi.runAllTimersAsync();

      expect(writer.isRedisAvailable()).toBe(true);

      mockXadd.mockRejectedValueOnce(new Error('XADD failed'));

      const operation: BufferedOperation = {
        type: 'insert',
        payload: {
          delivery_id: 'del-1',
          tenant_id: 'tenant-1',
          notification_type: 'email',
          channel: 'email',
          provider: 'smtp',
          recipient: 'test@example.com',
          status: 'pending',
          priority: 'normal',
        },
        timestamp: Date.now(),
      };

      await writer.enqueue(operation);

      expect(mockXadd).toHaveBeenCalledTimes(1);
      expect(writer.isRedisAvailable()).toBe(false);
      expect(writer.getBufferSize()).toBe(1);

      const operation2: BufferedOperation = {
        type: 'insert',
        payload: {
          delivery_id: 'del-2',
          tenant_id: 'tenant-1',
          notification_type: 'sms',
          channel: 'sms',
          provider: 'twilio',
          recipient: '+1234567890',
          status: 'pending',
          priority: 'normal',
        },
        timestamp: Date.now(),
      };

      await writer.enqueue(operation2);
      expect(mockXadd).toHaveBeenCalledTimes(1);
      expect(writer.getBufferSize()).toBe(2);
    });
  });

  describe('7. Batch insert with ON CONFLICT DO NOTHING', () => {
    beforeEach(async () => {
      mockPing.mockRejectedValue(new Error('Redis connection failed'));
      const module = await import('../../src/tracking/BatchDeliveryWriter');
      BatchDeliveryWriter = module.BatchDeliveryWriter;
    });

    it('should execute batch INSERT with ON CONFLICT DO NOTHING clause', async () => {
      const writer = BatchDeliveryWriter.getInstance(flushIntervalMs, batchSize);
      await vi.runAllTimersAsync();

      for (let i = 0; i < 3; i++) {
        await writer.enqueue({
          type: 'insert',
          payload: {
            delivery_id: `del-${i}`,
            tenant_id: 'tenant-1',
            notification_type: 'email',
            channel: 'email',
            provider: 'smtp',
            recipient: `test${i}@example.com`,
            status: 'pending',
            priority: 'normal',
          },
          timestamp: Date.now(),
        });
      }

      await writer.flush();
      await vi.runAllTimersAsync();

      expect(mockDb.query).toHaveBeenCalledWith(
        expect.stringContaining('ON CONFLICT (delivery_id, channel) DO NOTHING')
      );

      const batchInsertCall = mockDb.query.mock.calls.find(
        (call: any[]) => normalizeSql(call[0]).startsWith('INSERT INTO delivery_logs') && !call[1]
      );
      expect(batchInsertCall).toBeDefined();
      expect(batchInsertCall[0]).toContain('VALUES');
      expect(batchInsertCall[0]).toContain("('del-0'");
      expect(batchInsertCall[0]).toContain("('del-1'");
      expect(batchInsertCall[0]).toContain("('del-2'");
    });
  });

  describe('8. Batch insert failure falls back to individual inserts with RLS context', () => {
    beforeEach(async () => {
      mockPing.mockRejectedValue(new Error('Redis connection failed'));
      const module = await import('../../src/tracking/BatchDeliveryWriter');
      BatchDeliveryWriter = module.BatchDeliveryWriter;
    });

    it('should fall back to individual inserts when batch insert fails', async () => {
      const writer = BatchDeliveryWriter.getInstance(flushIntervalMs, batchSize);
      await vi.runAllTimersAsync();

      mockDb.query.mockImplementationOnce(async (sql: string) => {
        if (normalizeSql(sql).startsWith('INSERT INTO delivery_logs') && !sql.includes('$1')) {
          throw new Error('Batch insert failed');
        }
        return { rows: [], rowCount: 1 };
      });

      for (let i = 0; i < 2; i++) {
        await writer.enqueue({
          type: 'insert',
          payload: {
            delivery_id: `del-${i}`,
            tenant_id: `tenant-${i}`,
            notification_type: 'email',
            channel: 'email',
            provider: 'smtp',
            recipient: `test${i}@example.com`,
            status: 'pending',
            priority: 'normal',
          },
          timestamp: Date.now(),
        });
      }

      await writer.flush();
      await vi.runAllTimersAsync();

      expect(mockDb.withTenantContext).toHaveBeenCalledTimes(2);
      expect(mockDb.withTenantContext).toHaveBeenCalledWith(
        'tenant-0',
        expect.any(Function)
      );
      expect(mockDb.withTenantContext).toHaveBeenCalledWith(
        'tenant-1',
        expect.any(Function)
      );

      const individualInsertCalls = mockDb.query.mock.calls.filter(
        (call: any[]) => normalizeSql(call[0]).startsWith('INSERT INTO delivery_logs') && call[1]
      );
      expect(individualInsertCalls.length).toBe(2);
      expect(individualInsertCalls[0][1][0]).toBe('del-0');
      expect(individualInsertCalls[1][1][0]).toBe('del-1');
    });
  });

  describe('9. Batch updateStatus and batch handleCallback operations', () => {
    beforeEach(async () => {
      mockPing.mockRejectedValue(new Error('Redis connection failed'));
      const module = await import('../../src/tracking/BatchDeliveryWriter');
      BatchDeliveryWriter = module.BatchDeliveryWriter;
    });

    it('should execute batch updateStatus operations with RLS context', async () => {
      const writer = BatchDeliveryWriter.getInstance(flushIntervalMs, batchSize);
      await vi.runAllTimersAsync();

      for (let i = 0; i < 2; i++) {
        await writer.enqueue({
          type: 'update_status',
          payload: {
            tenant_id: `tenant-${i}`,
            delivery_id: `del-${i}`,
            channel: 'email',
            status: 'delivered',
            message_id: `msg-${i}`,
            error_message: null,
          },
          timestamp: Date.now(),
        });
      }

      await writer.flush();
      await vi.runAllTimersAsync();

      expect(mockDb.withTenantContext).toHaveBeenCalledTimes(2);
      expect(mockDb.withTenantContext).toHaveBeenCalledWith(
        'tenant-0',
        expect.any(Function)
      );
      expect(mockDb.withTenantContext).toHaveBeenCalledWith(
        'tenant-1',
        expect.any(Function)
      );

      const updateCalls = mockDb.query.mock.calls.filter(
        (call: any[]) => normalizeSql(call[0]).startsWith('UPDATE delivery_logs SET status = $1, message_id = $2')
      );
      expect(updateCalls.length).toBe(2);
      expect(updateCalls[0][1]).toEqual(['delivered', 'msg-0', null, 'del-0', 'email']);
      expect(updateCalls[1][1]).toEqual(['delivered', 'msg-1', null, 'del-1', 'email']);
    });

    it('should execute batch handleCallback operations with RLS context', async () => {
      const writer = BatchDeliveryWriter.getInstance(flushIntervalMs, batchSize);
      await vi.runAllTimersAsync();

      for (let i = 0; i < 2; i++) {
        await writer.enqueue({
          type: 'update_callback',
          payload: {
            tenant_id: `tenant-${i}`,
            message_id: `msg-${i}`,
            status: 'opened',
            metadata: { opened_at: Date.now() },
          },
          timestamp: Date.now(),
        });
      }

      await writer.flush();
      await vi.runAllTimersAsync();

      expect(mockDb.withTenantContext).toHaveBeenCalledTimes(2);
      expect(mockDb.withTenantContext).toHaveBeenCalledWith(
        'tenant-0',
        expect.any(Function)
      );
      expect(mockDb.withTenantContext).toHaveBeenCalledWith(
        'tenant-1',
        expect.any(Function)
      );

      const callbackCalls = mockDb.query.mock.calls.filter(
        (call: any[]) => normalizeSql(call[0]).startsWith('UPDATE delivery_logs SET status = $1, updated_at = NOW()')
      );
      expect(callbackCalls.length).toBe(2);
      expect(callbackCalls[0][1][0]).toBe('opened');
      expect(callbackCalls[0][1][1]).toBe('msg-0');
      expect(callbackCalls[1][1][0]).toBe('opened');
      expect(callbackCalls[1][1][1]).toBe('msg-1');
    });
  });

  describe('10. stop() flushes remaining buffer and clears timer', () => {
    beforeEach(async () => {
      mockPing.mockRejectedValue(new Error('Redis connection failed'));
      const module = await import('../../src/tracking/BatchDeliveryWriter');
      BatchDeliveryWriter = module.BatchDeliveryWriter;
    });

    it('should flush remaining buffer and clear timer on stop()', async () => {
      const writer = BatchDeliveryWriter.getInstance(flushIntervalMs, batchSize);
      await vi.runAllTimersAsync();

      writer.start();

      await writer.enqueue({
        type: 'insert',
        payload: {
          delivery_id: 'del-1',
          tenant_id: 'tenant-1',
          notification_type: 'email',
          channel: 'email',
          provider: 'smtp',
          recipient: 'test@example.com',
          status: 'pending',
          priority: 'normal',
        },
        timestamp: Date.now(),
      });

      expect(writer.getBufferSize()).toBe(1);

      const clearIntervalSpy = vi.spyOn(global, 'clearInterval');

      writer.stop();
      await vi.runAllTimersAsync();

      expect(clearIntervalSpy).toHaveBeenCalled();
      expect(writer.getBufferSize()).toBe(0);
      const insertCalls = mockDb.query.mock.calls.filter(
        (call: any[]) => normalizeSql(call[0]).startsWith('INSERT INTO delivery_logs')
      );
      expect(insertCalls.length).toBeGreaterThan(0);
    });
  });

  describe('11. Table-driven: different operation types handled correctly', () => {
    interface OperationTestCase {
      name: string;
      operation: BufferedOperation;
      expectedQueryPattern: RegExp;
      verifyDbCall: (queryCalls: any[][]) => void;
    }

    const testCases: OperationTestCase[] = [
      {
        name: 'insert operation with metadata',
        operation: {
          type: 'insert',
          payload: {
            delivery_id: 'del-1',
            tenant_id: 'tenant-1',
            notification_type: 'email',
            channel: 'email',
            provider: 'smtp',
            recipient: 'test@example.com',
            status: 'pending',
            priority: 'normal',
            metadata: { campaign: 'test' },
          },
          timestamp: Date.now(),
        },
        expectedQueryPattern: /INSERT INTO delivery_logs/,
        verifyDbCall: (queryCalls: any[][]) => {
          const insertCall = queryCalls.find(
            (call) => normalizeSql(call[0]).startsWith('INSERT INTO delivery_logs')
          );
          expect(insertCall[0]).toContain('ON CONFLICT');
        },
      },
      {
        name: 'insert operation without metadata',
        operation: {
          type: 'insert',
          payload: {
            delivery_id: 'del-2',
            tenant_id: 'tenant-1',
            notification_type: 'sms',
            channel: 'sms',
            provider: 'twilio',
            recipient: '+1234567890',
            status: 'pending',
            priority: 'high',
          },
          timestamp: Date.now(),
        },
        expectedQueryPattern: /INSERT INTO delivery_logs/,
        verifyDbCall: (queryCalls: any[][]) => {
          const insertCall = queryCalls.find(
            (call) => normalizeSql(call[0]).startsWith('INSERT INTO delivery_logs')
          );
          expect(insertCall[0]).toContain('NULL');
        },
      },
      {
        name: 'update_status with error message',
        operation: {
          type: 'update_status',
          payload: {
            tenant_id: 'tenant-2',
            delivery_id: 'del-3',
            channel: 'push',
            status: 'failed',
            message_id: 'msg-3',
            error_message: 'Timeout error',
          },
          timestamp: Date.now(),
        },
        expectedQueryPattern: /UPDATE delivery_logs SET status = \$1, message_id = \$2/,
        verifyDbCall: (queryCalls: any[][]) => {
          const updateCall = queryCalls.find(
            (call) => normalizeSql(call[0]).startsWith('UPDATE delivery_logs SET status = $1, message_id = $2')
          );
          expect(updateCall[1]).toEqual(['failed', 'msg-3', 'Timeout error', 'del-3', 'push']);
        },
      },
      {
        name: 'update_status without message_id',
        operation: {
          type: 'update_status',
          payload: {
            tenant_id: 'tenant-2',
            delivery_id: 'del-4',
            channel: 'email',
            status: 'sent',
          },
          timestamp: Date.now(),
        },
        expectedQueryPattern: /UPDATE delivery_logs SET status = \$1, message_id = \$2/,
        verifyDbCall: (queryCalls: any[][]) => {
          const updateCall = queryCalls.find(
            (call) => normalizeSql(call[0]).startsWith('UPDATE delivery_logs SET status = $1, message_id = $2')
          );
          expect(updateCall[1]).toEqual(['sent', undefined, undefined, 'del-4', 'email']);
        },
      },
      {
        name: 'update_callback with metadata',
        operation: {
          type: 'update_callback',
          payload: {
            tenant_id: 'tenant-3',
            message_id: 'msg-5',
            status: 'clicked',
            metadata: { link: 'https://example.com' },
          },
          timestamp: Date.now(),
        },
        expectedQueryPattern: /UPDATE delivery_logs SET status = \$1, updated_at = NOW\(\)/,
        verifyDbCall: (queryCalls: any[][]) => {
          const callbackCall = queryCalls.find(
            (call) => normalizeSql(call[0]).startsWith('UPDATE delivery_logs SET status = $1, updated_at = NOW()')
          );
          expect(callbackCall[1]).toEqual(['clicked', 'msg-5', { link: 'https://example.com' }]);
        },
      },
      {
        name: 'update_callback without metadata',
        operation: {
          type: 'update_callback',
          payload: {
            tenant_id: 'tenant-3',
            message_id: 'msg-6',
            status: 'bounced',
          },
          timestamp: Date.now(),
        },
        expectedQueryPattern: /UPDATE delivery_logs SET status = \$1, updated_at = NOW\(\)/,
        verifyDbCall: (queryCalls: any[][]) => {
          const callbackCall = queryCalls.find(
            (call) => normalizeSql(call[0]).startsWith('UPDATE delivery_logs SET status = $1, updated_at = NOW()')
          );
          expect(callbackCall[1]).toEqual(['bounced', 'msg-6', {}]);
        },
      },
    ];

    beforeEach(async () => {
      mockPing.mockRejectedValue(new Error('Redis connection failed'));
      const module = await import('../../src/tracking/BatchDeliveryWriter');
      BatchDeliveryWriter = module.BatchDeliveryWriter;
    });

    it.each(testCases)('$name', async ({ operation, expectedQueryPattern, verifyDbCall }) => {
      const writer = BatchDeliveryWriter.getInstance(flushIntervalMs, batchSize);
      await vi.runOnlyPendingTimersAsync();

      await writer.enqueue(operation);
      await writer.flush();
      await vi.runOnlyPendingTimersAsync();

      const allQueries = mockDb.query.mock.calls;
      const matchingQueries = allQueries.filter((call: any[]) =>
        expectedQueryPattern.test(normalizeSql(call[0]))
      );
      expect(matchingQueries.length).toBeGreaterThan(0);

      verifyDbCall(allQueries);
    });
  });

  describe('12. close() calls stop() and disconnects Redis', () => {
    it('should call stop() and disconnect Redis on close()', async () => {
      const writer = BatchDeliveryWriter.getInstance(flushIntervalMs, batchSize);
      await vi.runAllTimersAsync();

      writer.start();

      const stopSpy = vi.spyOn(writer, 'stop');

      await writer.close();
      await vi.runAllTimersAsync();

      expect(stopSpy).toHaveBeenCalled();
      expect(mockDisconnect).toHaveBeenCalled();
    });
  });

  describe('Redis Stream integration', () => {
    it('should read operations from Redis Stream during flush', async () => {
      const writer = BatchDeliveryWriter.getInstance(flushIntervalMs, batchSize);
      await vi.runAllTimersAsync();

      expect(writer.isRedisAvailable()).toBe(true);

      mockXreadgroup.mockResolvedValueOnce([
        [
          'notify:delivery_log_stream',
          [
            [
              '1234567890-0',
              [
                'type',
                'insert',
                'payload',
                JSON.stringify({
                  delivery_id: 'stream-del-1',
                  tenant_id: 'tenant-1',
                  notification_type: 'email',
                  channel: 'email',
                  provider: 'smtp',
                  recipient: 'stream@example.com',
                  status: 'pending',
                  priority: 'normal',
                }),
                'timestamp',
                Date.now().toString(),
              ],
            ],
          ],
        ],
      ]);

      await writer.flush();
      await vi.runAllTimersAsync();

      expect(mockXreadgroup).toHaveBeenCalledWith(
        'GROUP',
        'delivery-writer',
        expect.stringContaining('writer-'),
        'COUNT',
        batchSize,
        'BLOCK',
        0,
        'STREAMS',
        'notify:delivery_log_stream',
        '>'
      );

      const insertCalls = mockDb.query.mock.calls.filter(
        (call: any[]) => normalizeSql(call[0]).startsWith('INSERT INTO delivery_logs')
      );
      expect(insertCalls.length).toBeGreaterThan(0);

      expect(mockXack).toHaveBeenCalledWith(
        'notify:delivery_log_stream',
        'delivery-writer',
        '1234567890-0'
      );
    });

    it('should handle empty stream results gracefully', async () => {
      const writer = BatchDeliveryWriter.getInstance(flushIntervalMs, batchSize);
      await vi.runOnlyPendingTimersAsync();

      mockXreadgroup.mockResolvedValueOnce([]);

      await writer.flush();
      await vi.runOnlyPendingTimersAsync();

      expect(mockXreadgroup).toHaveBeenCalled();
      const insertCalls = mockDb.query.mock.calls.filter(
        (call: any[]) => normalizeSql(call[0]).startsWith('INSERT INTO delivery_logs')
      );
      expect(insertCalls.length).toBe(0);
      expect(mockXack).not.toHaveBeenCalled();
    });

    it('should handle stream read errors gracefully', async () => {
      const writer = BatchDeliveryWriter.getInstance(flushIntervalMs, batchSize);
      await vi.runOnlyPendingTimersAsync();

      expect(writer.isRedisAvailable()).toBe(true);

      mockXreadgroup.mockRejectedValueOnce(new Error('Stream read failed'));

      await writer.flush();
      await vi.runOnlyPendingTimersAsync();

      expect(writer.isRedisAvailable()).toBe(false);
    });
  });

  describe('Edge cases', () => {
    it('should handle concurrent flush calls gracefully', async () => {
      mockPing.mockRejectedValue(new Error('Redis connection failed'));
      const module = await import('../../src/tracking/BatchDeliveryWriter');
      BatchDeliveryWriter = module.BatchDeliveryWriter;

      const writer = BatchDeliveryWriter.getInstance(flushIntervalMs, batchSize);
      await vi.runAllTimersAsync();

      await writer.enqueue({
        type: 'insert',
        payload: {
          delivery_id: 'del-1',
          tenant_id: 'tenant-1',
          notification_type: 'email',
          channel: 'email',
          provider: 'smtp',
          recipient: 'test@example.com',
          status: 'pending',
          priority: 'normal',
        },
        timestamp: Date.now(),
      });

      const flush1 = writer.flush();
      const flush2 = writer.flush();

      await Promise.all([flush1, flush2]);
      await vi.runAllTimersAsync();

      expect(writer.getBufferSize()).toBe(0);
    });

    it('should not fail when flushing empty buffer', async () => {
      mockPing.mockRejectedValue(new Error('Redis connection failed'));
      const module = await import('../../src/tracking/BatchDeliveryWriter');
      BatchDeliveryWriter = module.BatchDeliveryWriter;

      const writer = BatchDeliveryWriter.getInstance(flushIntervalMs, batchSize);
      await vi.runAllTimersAsync();

      expect(writer.getBufferSize()).toBe(0);

      await expect(writer.flush()).resolves.not.toThrow();
      await vi.runAllTimersAsync();
    });

    it('should handle special characters in metadata during batch insert', async () => {
      mockPing.mockRejectedValue(new Error('Redis connection failed'));
      const module = await import('../../src/tracking/BatchDeliveryWriter');
      BatchDeliveryWriter = module.BatchDeliveryWriter;

      const writer = BatchDeliveryWriter.getInstance(flushIntervalMs, batchSize);
      await vi.runAllTimersAsync();

      await writer.enqueue({
        type: 'insert',
        payload: {
          delivery_id: 'del-1',
          tenant_id: 'tenant-1',
          notification_type: 'email',
          channel: 'email',
          provider: 'smtp',
          recipient: 'test@example.com',
          status: 'pending',
          priority: 'normal',
          metadata: { quote: "He said 'hello'", special: "O'Neil" },
        },
        timestamp: Date.now(),
      });

      await writer.flush();
      await vi.runAllTimersAsync();

      const insertCall = mockDb.query.mock.calls.find(
        (call: any[]) => normalizeSql(call[0]).startsWith('INSERT INTO delivery_logs')
      );
      expect(insertCall[0]).toContain("''hello''");
      expect(insertCall[0]).toContain("O''Neil");
    });
  });
});
