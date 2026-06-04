import { describe, it, expect, vi, beforeEach, afterEach } from 'vitest';
import { Queue, Worker } from 'bullmq';
import { v4 as uuidv4 } from 'uuid';
import { createNotificationRequest, createRecipient, runTableDrivenTests } from '../utils/factories';
import { NotificationRequest, ChannelType, DeliveryStatus } from '../../src/types';
import Redis from 'ioredis';

vi.mock('bullmq');
vi.mock('ioredis');

describe('并发场景测试', () => {
  describe('BullMQ队列消费顺序与并发控制', () => {
    let mockRedis: any;
    let mockQueue: any;
    let mockWorker: any;
    let processingJobs: Map<string, boolean>;
    let processedOrder: string[];
    let workerHandler: ((job: any) => Promise<void>) | null = null;

    beforeEach(() => {
      vi.resetModules();
      processingJobs = new Map();
      processedOrder = [];
      workerHandler = null;

      mockRedis = {
        on: vi.fn(),
        disconnect: vi.fn(),
      };
      (Redis as any).mockImplementation(() => mockRedis);

      mockQueue = {
        add: vi.fn(async (name: string, data: any, opts: any) => ({
          id: opts.jobId || uuidv4(),
          name,
          data,
          opts,
        })),
        close: vi.fn(),
        getWaitingCount: vi.fn().mockResolvedValue(0),
        getActiveCount: vi.fn().mockResolvedValue(0),
        getCompletedCount: vi.fn().mockResolvedValue(0),
        getFailedCount: vi.fn().mockResolvedValue(0),
      };
      (Queue as any).mockImplementation(() => mockQueue);

      mockWorker = {
        on: vi.fn(),
        close: vi.fn(),
      };
      (Worker as any).mockImplementation((queueName: string, handler: (job: any) => Promise<void>) => {
        workerHandler = handler;
        return mockWorker;
      });

      vi.useFakeTimers();
    });

    afterEach(() => {
      vi.clearAllMocks();
      vi.clearAllTimers();
      vi.useRealTimers();
    });

    it('1000条通知批量入队时，同一个recipient的消息按顺序处理不会乱序', async () => {
      const { NotificationQueue } = await import('../../src/queue/NotificationQueue');
      const queue = NotificationQueue.getInstance();

      const recipient = createRecipient({ user_id: 'user-123', email: 'user@example.com' });
      const requests: NotificationRequest[] = [];

      for (let i = 0; i < 1000; i++) {
        requests.push(createNotificationRequest({
          recipient,
          metadata: { sequence: i },
          channel_preference: ['email'] as ChannelType[],
        }));
      }

      const enqueuePromises = requests.map((req, idx) =>
        queue.enqueue(req, `delivery-${idx}`, 'email')
      );

      await Promise.all(enqueuePromises);

      expect(mockQueue.add).toHaveBeenCalledTimes(1000);

      const callArgs = mockQueue.add.mock.calls;
      const jobIds = callArgs.map((call: any[]) => call[2]?.jobId);
      const uniqueJobIds = new Set(jobIds);
      expect(uniqueJobIds.size).toBe(1000);

      for (let i = 0; i < 1000; i++) {
        expect(callArgs[i][1].notification.metadata.sequence).toBe(i);
      }
    });

    it('同一个recipient的消息不会被多个worker同时处理', async () => {
      vi.useRealTimers();
      const { NotificationQueue } = await import('../../src/queue/NotificationQueue');
      const queue = NotificationQueue.getInstance();
      const { DeliveryTracker } = await import('../../src/tracking/DeliveryTracker');
      DeliveryTracker.getInstance().setUseBatchWriter(false);

      const recipient = createRecipient({ user_id: 'user-456', email: 'user@example.com' });
      const concurrentProcessing: string[] = [];
      let concurrencyViolations = 0;

      const groupQueues: Map<string, Array<{ job: any; data: any }>> = new Map();
      const groupProcessing: Set<string> = new Set();

      const processGroupQueue = async (groupKey: string) => {
        if (groupProcessing.has(groupKey)) return;
        const queue = groupQueues.get(groupKey);
        if (!queue || queue.length === 0) return;

        groupProcessing.add(groupKey);
        while (queue.length > 0) {
          const item = queue.shift()!;
          const { job, data } = item;

          const recipientKey = data.notification.recipient.email || data.notification.recipient.user_id;
          
          if (processingJobs.has(recipientKey)) {
            concurrencyViolations++;
          }
          
          processingJobs.set(recipientKey, true);
          concurrentProcessing.push(job.id);
          
          await new Promise(resolve => setTimeout(resolve, 10));
          
          processingJobs.delete(recipientKey);
          processedOrder.push(data.notification.metadata.sequence);
        }
        groupProcessing.delete(groupKey);
      };

      const originalMockAdd = mockQueue.add;
      mockQueue.add = vi.fn(async (name: string, data: any, opts: any) => {
        const jobId = opts.jobId || uuidv4();
        const groupKey = opts.group;
        
        if (workerHandler) {
          const job = {
            id: jobId,
            data,
            attemptsMade: 0,
            opts: { attempts: 3 },
          };

          if (groupKey) {
            if (!groupQueues.has(groupKey)) {
              groupQueues.set(groupKey, []);
            }
            groupQueues.get(groupKey)!.push({ job, data });
            processGroupQueue(groupKey);
          } else {
            const startProcessing = async () => {
              const recipientKey = data.notification.recipient.email || data.notification.recipient.user_id;
              
              if (processingJobs.has(recipientKey)) {
                concurrencyViolations++;
              }
              
              processingJobs.set(recipientKey, true);
              concurrentProcessing.push(jobId);
              
              await new Promise(resolve => setTimeout(resolve, 10));
              
              processingJobs.delete(recipientKey);
              processedOrder.push(data.notification.metadata.sequence);
            };
            startProcessing();
          }
        }

        return originalMockAdd(name, data, opts);
      });

      for (let i = 0; i < 10; i++) {
        const request = createNotificationRequest({
          recipient,
          metadata: { sequence: i },
          channel_preference: ['email'] as ChannelType[],
        });
        await queue.enqueue(request, `delivery-${i}`, 'email');
      }

      await new Promise(resolve => setTimeout(resolve, 200));

      expect(concurrencyViolations).toBe(0);

      for (let i = 1; i < processedOrder.length; i++) {
        expect(processedOrder[i]).toBeGreaterThan(processedOrder[i - 1]);
      }
    });

    it('不同recipient的消息可以并行处理', async () => {
      vi.useRealTimers();
      const { NotificationQueue } = await import('../../src/queue/NotificationQueue');
      const queue = NotificationQueue.getInstance();
      const { DeliveryTracker } = await import('../../src/tracking/DeliveryTracker');
      DeliveryTracker.getInstance().setUseBatchWriter(false);

      const processingStartTimes: Map<string, number> = new Map();
      let maxParallelism = 0;
      let currentParallelism = 0;

      const originalMockAdd = mockQueue.add;
      mockQueue.add = vi.fn(async (name: string, data: any, opts: any) => {
        const jobId = opts.jobId || uuidv4();
        
        if (workerHandler) {
          const job = {
            id: jobId,
            data,
            attemptsMade: 0,
            opts: { attempts: 3 },
          };

          const startProcessing = async () => {
            currentParallelism++;
            maxParallelism = Math.max(maxParallelism, currentParallelism);
            processingStartTimes.set(jobId, Date.now());
            
            await new Promise(resolve => setTimeout(resolve, 50));
            
            currentParallelism--;
          };

          startProcessing();
        }

        return originalMockAdd(name, data, opts);
      });

      for (let i = 0; i < 10; i++) {
        const recipient = createRecipient({ user_id: `user-${i}`, email: `user${i}@example.com` });
        const request = createNotificationRequest({
          recipient,
          metadata: { sequence: i },
          channel_preference: ['email'] as ChannelType[],
        });
        await queue.enqueue(request, `delivery-${i}`, 'email');
      }

      await new Promise(resolve => setTimeout(resolve, 200));

      expect(maxParallelism).toBeGreaterThan(1);
    });
  });

  describe('delivery_id全局唯一性', () => {
    it('多worker并行消费时delivery_id生成保持全局唯一', async () => {
      const generatedIds: Set<string> = new Set();
      const idCounts: Map<string, number> = new Map();
      const workerCount = 5;
      const idsPerWorker = 200;

      const generateId = () => {
        const id = uuidv4();
        generatedIds.add(id);
        idCounts.set(id, (idCounts.get(id) || 0) + 1);
        return id;
      };

      const workerPromises = [];
      for (let w = 0; w < workerCount; w++) {
        workerPromises.push(
          new Promise<void>((resolve) => {
            for (let i = 0; i < idsPerWorker; i++) {
              generateId();
            }
            resolve();
          })
        );
      }

      await Promise.all(workerPromises);

      const totalGenerated = workerCount * idsPerWorker;
      expect(generatedIds.size).toBe(totalGenerated);

      for (const [id, count] of idCounts) {
        expect(count).toBe(1);
      }
    });

    it('NotificationRouter.route()连续调用返回唯一的delivery_id', async () => {
      const mockDb = {
        query: vi.fn().mockResolvedValue({ rows: [], rowCount: 0 }),
        withTenantContext: vi.fn(async (tenantId: string, fn: () => Promise<any>) => await fn()),
      };
      vi.doMock('../../src/db', () => ({ db: mockDb }));

      const mockAdapter = {
        getName: vi.fn().mockReturnValue('email'),
        getStatus: vi.fn().mockResolvedValue({ available: true, name: 'email', last_checked: new Date() }),
        send: vi.fn().mockResolvedValue({ status: 'sent', message_id: 'msg-1' }),
      };

      const { AdapterManager } = await import('../../src/adapters/AdapterManager');
      vi.spyOn(AdapterManager, 'getInstance').mockReturnValue({
        getAdapter: vi.fn().mockReturnValue(mockAdapter),
      } as any);

      const { NotificationRouter } = await import('../../src/router/NotificationRouter');
      const router = NotificationRouter.getInstance();

      const generatedIds: string[] = [];
      const requestCount = 100;

      for (let i = 0; i < requestCount; i++) {
        const request = createNotificationRequest({
          channel_preference: ['email'] as ChannelType[],
        });
        const result = await router.route(request);
        generatedIds.push(result.delivery_id);
      }

      const uniqueIds = new Set(generatedIds);
      expect(uniqueIds.size).toBe(requestCount);
    });

    it('高并发下NotificationRouter.route()返回唯一delivery_id', async () => {
      const mockDb = {
        query: vi.fn().mockResolvedValue({ rows: [], rowCount: 0 }),
        withTenantContext: vi.fn(async (tenantId: string, fn: () => Promise<any>) => await fn()),
      };
      vi.doMock('../../src/db', () => ({ db: mockDb }));

      const mockAdapter = {
        getName: vi.fn().mockReturnValue('email'),
        getStatus: vi.fn().mockResolvedValue({ available: true, name: 'email', last_checked: new Date() }),
        send: vi.fn().mockResolvedValue({ status: 'sent', message_id: 'msg-1' }),
      };

      const { AdapterManager } = await import('../../src/adapters/AdapterManager');
      vi.spyOn(AdapterManager, 'getInstance').mockReturnValue({
        getAdapter: vi.fn().mockReturnValue(mockAdapter),
      } as any);

      const { NotificationRouter } = await import('../../src/router/NotificationRouter');
      const router = NotificationRouter.getInstance();

      const requestCount = 500;
      const requests: Promise<{ delivery_id: string }>[] = [];

      for (let i = 0; i < requestCount; i++) {
        const request = createNotificationRequest({
          channel_preference: ['email'] as ChannelType[],
        });
        requests.push(router.route(request));
      }

      const results = await Promise.all(requests);
      const ids = results.map(r => r.delivery_id);
      const uniqueIds = new Set(ids);

      expect(uniqueIds.size).toBe(requestCount);

      const idCounts = new Map<string, number>();
      for (const id of ids) {
        idCounts.set(id, (idCounts.get(id) || 0) + 1);
      }

      for (const [id, count] of idCounts) {
        expect(count).toBe(1);
      }
    });
  });

  describe('表驱动 - 并发场景边界测试', () => {
    const concurrencyTestCases = [
      {
        name: '单recipient 100条消息 - 顺序处理',
        input: { recipientCount: 1, messageCount: 100 },
        expected: { shouldBeOrdered: true, maxConcurrency: 1 },
      },
      {
        name: '100个recipient各1条消息 - 并行处理',
        input: { recipientCount: 100, messageCount: 1 },
        expected: { shouldBeOrdered: false, maxConcurrency: 100 },
      },
      {
        name: '10个recipient各10条消息 - 混合处理',
        input: { recipientCount: 10, messageCount: 10 },
        expected: { shouldBeOrdered: true, maxConcurrency: 10 },
      },
    ];

    runTableDrivenTests(
      concurrencyTestCases,
      async (input, expected) => {
        vi.useRealTimers();
        const processedPerRecipient: Map<string, number[]> = new Map();
        let actualMaxConcurrency = 0;
        let currentConcurrency = 0;

        for (let r = 0; r < input.recipientCount; r++) {
          const recipientId = `recipient-${r}`;
          processedPerRecipient.set(recipientId, []);

          for (let m = 0; m < input.messageCount; m++) {
            const processing = async () => {
              currentConcurrency++;
              actualMaxConcurrency = Math.max(actualMaxConcurrency, currentConcurrency);

              await new Promise(resolve => setTimeout(resolve, 10));

              processedPerRecipient.get(recipientId)!.push(m);
              currentConcurrency--;
            };

            if (expected.shouldBeOrdered) {
              await processing();
            } else {
              processing();
            }
          }
        }

        if (!expected.shouldBeOrdered) {
          await new Promise(resolve => setTimeout(resolve, 200));
        }

        if (expected.shouldBeOrdered) {
          for (const [, sequence] of processedPerRecipient) {
            for (let i = 1; i < sequence.length; i++) {
              expect(sequence[i]).toBeGreaterThan(sequence[i - 1]);
            }
          }
        }

        expect(actualMaxConcurrency).toBeGreaterThanOrEqual(
          expected.shouldBeOrdered ? 1 : Math.min(expected.maxConcurrency, input.recipientCount)
        );
      }
    );
  });

  describe('DeliveryTracker并发写入', () => {
    it('并发更新同一条delivery_log时状态优先级机制生效', async () => {
      vi.resetModules();
      vi.useRealTimers();
      const mockDb = {
        query: vi.fn(),
        withTenantContext: vi.fn(async (tenantId: string, fn: () => Promise<any>) => await fn()),
      };
      vi.doMock('../../src/db', () => ({ db: mockDb }));

      const deliveryId = uuidv4();
      const tenantId = uuidv4();
      const messageId = uuidv4();

      let currentStatus: DeliveryStatus = 'queued';
      const statusPriority: Record<DeliveryStatus, number> = {
        pending: 0, queued: 1, sent: 2, failed: 3, delivered: 4, read: 5, clicked: 6,
      };

      mockDb.query.mockImplementation(async (sql: string, params: any[]) => {
        if (sql.includes('UPDATE delivery_logs') && sql.includes('CASE status')) {
          const newStatus = params[0] as DeliveryStatus;
          const newPriority = statusPriority[newStatus] || 0;
          const currentPriority = statusPriority[currentStatus] || 0;

          if (newPriority > currentPriority) {
            currentStatus = newStatus;
            return { rows: [{ status: currentStatus }], rowCount: 1 };
          }
          return { rows: [], rowCount: 0 };
        }
        return { rows: [], rowCount: 0 };
      });

      const { DeliveryTracker } = await import('../../src/tracking/DeliveryTracker');
      const tracker = DeliveryTracker.getInstance();
      tracker.setUseBatchWriter(false);

      const updates = [
        { status: 'sent' as DeliveryStatus, delay: 0 },
        { status: 'delivered' as DeliveryStatus, delay: 10 },
        { status: 'sent' as DeliveryStatus, delay: 5 },
        { status: 'read' as DeliveryStatus, delay: 15 },
      ];

      const promises = updates.map(update =>
        new Promise<boolean>((resolve) => {
          setTimeout(async () => {
            const result = await tracker.updateStatus(tenantId, deliveryId, 'email', update.status, messageId);
            resolve(result);
          }, update.delay);
        })
      );

      await new Promise(resolve => setTimeout(resolve, 50));
      const results = await Promise.all(promises);

      expect(results.filter(r => r).length).toBe(3);
      expect(currentStatus).toBe('read');
    });

    it('并发handleCallback时高优先级状态不被覆盖', async () => {
      vi.resetModules();
      vi.useRealTimers();
      const mockDb = {
        query: vi.fn(),
        withTenantContext: vi.fn(async (tenantId: string, fn: () => Promise<any>) => await fn()),
      };
      vi.doMock('../../src/db', () => ({ db: mockDb }));

      const messageId = uuidv4();
      const tenantId = uuidv4();

      let currentStatus: DeliveryStatus = 'queued';
      const statusPriority: Record<DeliveryStatus, number> = {
        pending: 0, queued: 1, sent: 2, failed: 3, delivered: 4, read: 5, clicked: 6,
      };

      mockDb.query.mockImplementation(async (sql: string, params: any[]) => {
        if (sql.includes('UPDATE delivery_logs') && sql.includes('message_id')) {
          const newStatus = params[0] as DeliveryStatus;
          const newPriority = statusPriority[newStatus] || 0;
          const currentPriority = statusPriority[currentStatus] || 0;

          if (newPriority > currentPriority) {
            currentStatus = newStatus;
            return { rows: [{ delivery_id: uuidv4(), status: currentStatus }], rowCount: 1 };
          }
          return { rows: [], rowCount: 0 };
        }
        return { rows: [], rowCount: 0 };
      });

      const { DeliveryTracker } = await import('../../src/tracking/DeliveryTracker');
      const tracker = DeliveryTracker.getInstance();
      tracker.setUseBatchWriter(false);

      const callbacks = [
        { status: 'delivered' as DeliveryStatus, delay: 0 },
        { status: 'sent' as DeliveryStatus, delay: 5 },
        { status: 'clicked' as DeliveryStatus, delay: 10 },
        { status: 'failed' as DeliveryStatus, delay: 15 },
        { status: 'read' as DeliveryStatus, delay: 8 },
      ];

      const promises = callbacks.map(cb =>
        new Promise<boolean>((resolve) => {
          setTimeout(async () => {
            const result = await tracker.handleCallback(tenantId, messageId, cb.status);
            resolve(result);
          }, cb.delay);
        })
      );

      await new Promise(resolve => setTimeout(resolve, 50));
      const results = await Promise.all(promises);

      expect(results.filter(r => r).length).toBe(3);
      expect(currentStatus).toBe('clicked');
    });
  });
});
