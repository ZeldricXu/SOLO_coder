import { GpuTaskScheduler } from '../../../modules/gpu-scheduler';
import { GpuTaskBuilder } from '../../builders';
import { ScheduledTask, TaskPriority } from '../../../core/ports';
import { createContext } from '../../../common';

describe('GpuTaskScheduler', () => {
  let scheduler: GpuTaskScheduler;
  let ctx: ReturnType<typeof createContext>;

  const createTestTask = (
    overrides: Partial<ScheduledTask> = {},
    executionTimeMs: number = 100
  ): ScheduledTask => {
    const baseTask = GpuTaskBuilder.createInferenceTask();
    return {
      ...baseTask,
      priority: baseTask.priority as TaskPriority,
      status: 'pending' as const,
      progress: 0,
      requiredResources: {
        gpuMemoryMb: baseTask.requiredMemoryMb,
        gpuComputeUnits: 1
      },
      created_at: new Date().toISOString(),
      execute: jest.fn().mockImplementation(async () => {
        await new Promise(resolve => setTimeout(resolve, executionTimeMs));
      }),
      ...overrides
    };
  };

  beforeEach(() => {
    scheduler = new GpuTaskScheduler({
      maxConcurrentTasks: 5,
      pollIntervalMs: 50,
      nodeConfigs: [
        {
          nodeId: 'test-node-1',
          gpus: [
            { id: 0, totalMemoryMb: 8192 },
            { id: 1, totalMemoryMb: 8192 }
          ]
        }
      ]
    });
    ctx = createContext('test-namespace');
  });

  afterEach(async () => {
    await scheduler.stop();
    jest.clearAllMocks();
  });

  describe('Resource Allocation and Release', () => {
    it('should properly allocate and release resources for completed tasks', async () => {
      await scheduler.start();

      const initialResources = scheduler.getResourceManager().getAvailableResources();
      const task = createTestTask({}, 50);

      const taskId = await scheduler.submit(task);
      expect(taskId).toBeDefined();

      await new Promise(resolve => setTimeout(resolve, 200));

      const status = await scheduler.getStatus(taskId);
      expect(status?.status).toBe('completed');

      const finalResources = scheduler.getResourceManager().getAvailableResources();
      expect(finalResources.availableGpuMemoryMb).toBe(initialResources.totalGpuMemoryMb);
      expect(finalResources.availableGpus).toBe(initialResources.totalGpus);
    });

    it('should release resources when task fails', async () => {
      await scheduler.start();

      const initialResources = scheduler.getResourceManager().getAvailableResources();

      const failingTask = createTestTask({
        execute: jest.fn().mockRejectedValue(new Error('GPU execution failed'))
      }, 10);

      const taskId = await scheduler.submit(failingTask);
      await new Promise(resolve => setTimeout(resolve, 200));

      const status = await scheduler.getStatus(taskId);
      expect(status?.status).toBe('failed');

      const finalResources = scheduler.getResourceManager().getAvailableResources();
      expect(finalResources.availableGpuMemoryMb).toBe(initialResources.totalGpuMemoryMb);
    });

    it('should release resources when task is cancelled while running', async () => {
      await scheduler.start();

      const initialResources = scheduler.getResourceManager().getAvailableResources();

      const longRunningTask = createTestTask({}, 1000);
      const taskId = await scheduler.submit(longRunningTask);

      await new Promise(resolve => setTimeout(resolve, 100));

      const cancelResult = await scheduler.cancel(taskId);
      expect(cancelResult).toBe(true);

      await new Promise(resolve => setTimeout(resolve, 100));

      const finalResources = scheduler.getResourceManager().getAvailableResources();
      expect(finalResources.availableGpuMemoryMb).toBe(initialResources.totalGpuMemoryMb);
    });

    it.skip('should release resources for preempted tasks', async () => {
      scheduler = new GpuTaskScheduler({
        maxConcurrentTasks: 1,
        pollIntervalMs: 50,
        enablePreemption: true,
        nodeConfigs: [
          {
            nodeId: 'test-node-1',
            gpus: [{ id: 0, totalMemoryMb: 4096 }]
          }
        ]
      });

      await scheduler.start();

      const lowPriorityTask = createTestTask({
        priority: 'low',
        requiredResources: { gpuMemoryMb: 2048 }
      }, 500);

      const highPriorityTask = createTestTask({
        priority: 'high',
        requiredResources: { gpuMemoryMb: 2048 }
      }, 300);

      await scheduler.submit(lowPriorityTask);
      await new Promise(resolve => setTimeout(resolve, 100));

      const initialResources = scheduler.getResourceManager().getAvailableResources();
      expect(initialResources.availableGpuMemoryMb).toBe(2048);

      await scheduler.submit(highPriorityTask);
      await new Promise(resolve => setTimeout(resolve, 150));

      const duringPreemptionResources = scheduler.getResourceManager().getAvailableResources();
      expect(duringPreemptionResources.availableGpuMemoryMb).toBe(2048);

      await new Promise(resolve => setTimeout(resolve, 500));

      const finalResources = scheduler.getResourceManager().getAvailableResources();
      expect(finalResources.availableGpuMemoryMb).toBe(4096);
    });

    it('should not leak resources after multiple task executions', async () => {
      await scheduler.start();

      const initialResources = scheduler.getResourceManager().getAvailableResources();
      const taskCount = 20;

      const tasks = Array.from({ length: taskCount }, (_, i) =>
        createTestTask({
          requiredResources: { gpuMemoryMb: 512 }
        }, 20)
      );

      await Promise.all(tasks.map(task => scheduler.submit(task)));

      await new Promise(resolve => setTimeout(resolve, 2000));

      const finalResources = scheduler.getResourceManager().getAvailableResources();
      expect(finalResources.availableGpuMemoryMb).toBe(initialResources.totalGpuMemoryMb);
      expect(finalResources.availableGpus).toBe(initialResources.totalGpus);
      expect(scheduler.getRunningTasksCount()).toBe(0);
    });

    it('should properly release resources when onPreempt callback is provided', async () => {
      await scheduler.start();

      const onPreemptMock = jest.fn().mockResolvedValue(undefined);
      const task = createTestTask({
        onPreempt: onPreemptMock,
        requiredResources: { gpuMemoryMb: 1024 }
      }, 1000);

      const taskId = await scheduler.submit(task);
      await new Promise(resolve => setTimeout(resolve, 100));

      await scheduler.cancel(taskId);
      await new Promise(resolve => setTimeout(resolve, 100));

      expect(onPreemptMock).toHaveBeenCalled();

      const resources = scheduler.getResourceManager().getAvailableResources();
      expect(resources.availableGpuMemoryMb).toBe(16384);
    });
  });

  describe('Task Submission and Queue Management', () => {
    it('should submit task to queue', async () => {
      const task = createTestTask();
      const taskId = await scheduler.submit(task);

      expect(taskId).toBeDefined();
      expect(scheduler.getQueueLength()).toBe(1);
    });

    it('should return correct status for queued tasks', async () => {
      const task = createTestTask();
      const taskId = await scheduler.submit(task);

      const status = await scheduler.getStatus(taskId);
      expect(status).not.toBeNull();
      expect(status?.status).toBe('queued');
    });

    it('should remove task from queue when cancelled', async () => {
      const task = createTestTask();
      const taskId = await scheduler.submit(task);

      expect(scheduler.getQueueLength()).toBe(1);

      const result = await scheduler.cancel(taskId);
      expect(result).toBe(true);
      expect(scheduler.getQueueLength()).toBe(0);
    });

    it('should return false when cancelling non-existent task', async () => {
      const result = await scheduler.cancel('non-existent-id');
      expect(result).toBe(false);
    });

    it('should return null status for non-existent task', async () => {
      const status = await scheduler.getStatus('non-existent-id');
      expect(status).toBeNull();
    });
  });

  describe('Task Execution', () => {
    it('should execute task successfully', async () => {
      await scheduler.start();

      const task = createTestTask({}, 50);
      const taskId = await scheduler.submit(task);

      await new Promise(resolve => setTimeout(resolve, 200));

      const status = await scheduler.getStatus(taskId);
      expect(status?.status).toBe('completed');
      expect(status?.progress).toBe(100);
      expect(status?.completed_at).toBeDefined();
      expect(task.execute).toHaveBeenCalled();
    });

    it('should handle task execution errors', async () => {
      await scheduler.start();

      const onErrorMock = jest.fn().mockResolvedValue(undefined);
      const errorTask = createTestTask({
        execute: jest.fn().mockRejectedValue(new Error('Test error')),
        onError: onErrorMock
      }, 10);

      const taskId = await scheduler.submit(errorTask);
      await new Promise(resolve => setTimeout(resolve, 200));

      const status = await scheduler.getStatus(taskId);
      expect(status?.status).toBe('failed');
      expect(status?.error_detail).toBe('Test error');
      expect(onErrorMock).toHaveBeenCalledWith(expect.any(Error));
    });

    it('should call onComplete callback when task completes', async () => {
      await scheduler.start();

      const onCompleteMock = jest.fn().mockResolvedValue(undefined);
      const task = createTestTask({ onComplete: onCompleteMock }, 50);

      await scheduler.submit(task);
      await new Promise(resolve => setTimeout(resolve, 200));

      expect(onCompleteMock).toHaveBeenCalled();
    });

    it('should respect max concurrent tasks limit', async () => {
      scheduler = new GpuTaskScheduler({
        maxConcurrentTasks: 2,
        pollIntervalMs: 50,
        nodeConfigs: [
          {
            nodeId: 'test-node',
            gpus: [
              { id: 0, totalMemoryMb: 8192 },
              { id: 1, totalMemoryMb: 8192 },
              { id: 2, totalMemoryMb: 8192 }
            ]
          }
        ]
      });

      await scheduler.start();

      const tasks = Array.from({ length: 5 }, () => createTestTask({}, 200));
      await Promise.all(tasks.map(task => scheduler.submit(task)));

      await new Promise(resolve => setTimeout(resolve, 100));
      expect(scheduler.getRunningTasksCount()).toBeLessThanOrEqual(2);

      await new Promise(resolve => setTimeout(resolve, 1500));
      expect(scheduler.getRunningTasksCount()).toBe(0);
    });
  });

  describe('Priority Queue', () => {
    it('should process higher priority tasks first', async () => {
      scheduler = new GpuTaskScheduler({
        maxConcurrentTasks: 1,
        pollIntervalMs: 50,
        nodeConfigs: [
          { nodeId: 'test', gpus: [{ id: 0, totalMemoryMb: 8192 }] }
        ]
      });

      await scheduler.start();

      const executionOrder: string[] = [];

      const lowTask = createTestTask({
        priority: 'low',
        requiredResources: { gpuMemoryMb: 1024 },
        execute: jest.fn().mockImplementation(async () => {
          executionOrder.push('low');
          await new Promise(resolve => setTimeout(resolve, 50));
        })
      }, 0);

      const highTask = createTestTask({
        priority: 'high',
        requiredResources: { gpuMemoryMb: 1024 },
        execute: jest.fn().mockImplementation(async () => {
          executionOrder.push('high');
          await new Promise(resolve => setTimeout(resolve, 50));
        })
      }, 0);

      const mediumTask = createTestTask({
        priority: 'medium',
        requiredResources: { gpuMemoryMb: 1024 },
        execute: jest.fn().mockImplementation(async () => {
          executionOrder.push('medium');
          await new Promise(resolve => setTimeout(resolve, 50));
        })
      }, 0);

      await scheduler.submit(lowTask);
      await new Promise(resolve => setTimeout(resolve, 100));
      await scheduler.submit(highTask);
      await scheduler.submit(mediumTask);

      await new Promise(resolve => setTimeout(resolve, 500));

      expect(executionOrder[0]).toBe('low');
      expect(executionOrder[1]).toBe('high');
      expect(executionOrder[2]).toBe('medium');
    });

    it('should queue tasks by priority level', async () => {
      await scheduler.submit(createTestTask({ priority: 'low' }));
      await scheduler.submit(createTestTask({ priority: 'high' }));
      await scheduler.submit(createTestTask({ priority: 'medium' }));
      await scheduler.submit(createTestTask({ priority: 'critical' }));

      expect(scheduler.getQueueLength('critical')).toBe(1);
      expect(scheduler.getQueueLength('high')).toBe(1);
      expect(scheduler.getQueueLength('medium')).toBe(1);
      expect(scheduler.getQueueLength('low')).toBe(1);
      expect(scheduler.getQueueLength()).toBe(4);
    });
  });

  describe('Preemption', () => {
    it.skip('should preempt lower priority tasks for critical tasks', async () => {
      scheduler = new GpuTaskScheduler({
        maxConcurrentTasks: 1,
        pollIntervalMs: 50,
        enablePreemption: true,
        nodeConfigs: [
          { nodeId: 'test', gpus: [{ id: 0, totalMemoryMb: 4096 }] }
        ]
      });

      await scheduler.start();

      const onPreemptMock = jest.fn().mockResolvedValue(undefined);
      const lowPriorityTask = createTestTask({
        priority: 'low',
        requiredResources: { gpuMemoryMb: 2048 },
        onPreempt: onPreemptMock
      }, 1000);

      const criticalTask = createTestTask({
        priority: 'critical',
        requiredResources: { gpuMemoryMb: 2048 }
      }, 50);

      await scheduler.submit(lowPriorityTask);
      await new Promise(resolve => setTimeout(resolve, 150));

      await scheduler.submit(criticalTask);
      await new Promise(resolve => setTimeout(resolve, 800));

      expect(onPreemptMock).toHaveBeenCalled();

      const criticalStatus = await scheduler.getStatus(criticalTask.id);
      expect(criticalStatus?.status).toBe('completed');

      const lowStatus = await scheduler.getStatus(lowPriorityTask.id);
      expect(lowStatus?.status).toBe('queued');
    });

    it('should not preempt if preemption is disabled', async () => {
      scheduler = new GpuTaskScheduler({
        maxConcurrentTasks: 1,
        pollIntervalMs: 50,
        enablePreemption: false,
        nodeConfigs: [
          { nodeId: 'test', gpus: [{ id: 0, totalMemoryMb: 4096 }] }
        ]
      });

      await scheduler.start();

      const lowPriorityTask = createTestTask({
        priority: 'low',
        requiredResources: { gpuMemoryMb: 2048 }
      }, 300);

      const criticalTask = createTestTask({
        priority: 'critical',
        requiredResources: { gpuMemoryMb: 2048 }
      }, 50);

      await scheduler.submit(lowPriorityTask);
      await new Promise(resolve => setTimeout(resolve, 50));

      await scheduler.submit(criticalTask);
      expect(scheduler.getQueueLength('critical')).toBe(1);

      await new Promise(resolve => setTimeout(resolve, 400));

      const lowStatus = await scheduler.getStatus(lowPriorityTask.id);
      expect(lowStatus?.status).toBe('completed');
    });
  });

  describe('Resource Manager', () => {
    it('should correctly report available resources', () => {
      const resources = scheduler.getResourceManager().getAvailableResources();

      expect(resources.totalGpuMemoryMb).toBe(16384);
      expect(resources.availableGpuMemoryMb).toBe(16384);
      expect(resources.totalGpus).toBe(2);
      expect(resources.availableGpus).toBe(2);
      expect(resources.nodes.length).toBe(1);
      expect(resources.nodes[0].gpus.length).toBe(2);
    });

    it('should correctly allocate memory across multiple GPUs', async () => {
      scheduler = new GpuTaskScheduler({
        maxConcurrentTasks: 10,
        pollIntervalMs: 50,
        nodeConfigs: [
          {
            nodeId: 'multi-gpu-node',
            gpus: [
              { id: 0, totalMemoryMb: 8192 },
              { id: 1, totalMemoryMb: 8192 },
              { id: 2, totalMemoryMb: 8192 }
            ]
          }
        ]
      });

      await scheduler.start();

      const task1 = createTestTask({ requiredResources: { gpuMemoryMb: 6144 } }, 200);
      const task2 = createTestTask({ requiredResources: { gpuMemoryMb: 6144 } }, 200);

      await scheduler.submit(task1);
      await scheduler.submit(task2);

      await new Promise(resolve => setTimeout(resolve, 100));

      const resources = scheduler.getResourceManager().getAvailableResources();
      expect(resources.availableGpuMemoryMb).toBe(24576 - 6144 - 6144);
    });
  });

  describe('Scheduler Lifecycle', () => {
    it('should start and stop scheduler correctly', async () => {
      expect(scheduler.getRunningTasksCount()).toBe(0);

      await scheduler.start();
      const task = createTestTask({}, 50);
      await scheduler.submit(task);

      await new Promise(resolve => setTimeout(resolve, 150));
      expect(scheduler.getCompletedTasksCount()).toBe(1);

      await scheduler.stop();

      await scheduler.submit(createTestTask());
      expect(scheduler.getRunningTasksCount()).toBe(0);
    });

    it('should handle multiple start/stop calls gracefully', async () => {
      await scheduler.start();
      await scheduler.start();

      await scheduler.stop();
      await scheduler.stop();
    });
  });

  describe('Completed Tasks Management', () => {
    it('should store completed tasks', async () => {
      await scheduler.start();

      const task = createTestTask({}, 50);
      await scheduler.submit(task);

      await new Promise(resolve => setTimeout(resolve, 150));

      expect(scheduler.getCompletedTasksCount()).toBe(1);
      const status = await scheduler.getStatus(task.id);
      expect(status).not.toBeNull();
      expect(status?.status).toBe('completed');
    });

    it('should evict old completed tasks when limit is exceeded', async () => {
      await scheduler.start();

      const taskCount = 100;
      const tasks = Array.from({ length: taskCount }, () => createTestTask({}, 10));

      for (const task of tasks) {
        await scheduler.submit(task);
      }

      await new Promise(resolve => setTimeout(resolve, 2000));

      expect(scheduler.getCompletedTasksCount()).toBeLessThanOrEqual(1000);
    });
  });

  describe('Edge Cases', () => {
    it('should handle tasks with zero execution time', async () => {
      await scheduler.start();

      const instantTask = createTestTask({}, 0);
      const taskId = await scheduler.submit(instantTask);

      await new Promise(resolve => setTimeout(resolve, 100));

      const status = await scheduler.getStatus(taskId);
      expect(status?.status).toBe('completed');
    });

    it('should handle very large memory requirements', async () => {
      scheduler = new GpuTaskScheduler({
        maxConcurrentTasks: 5,
        nodeConfigs: [
          { nodeId: 'test', gpus: [{ id: 0, totalMemoryMb: 16384 }] }
        ]
      });

      const largeTask = createTestTask({
        requiredResources: { gpuMemoryMb: 32768 }
      }, 100);

      const taskId = await scheduler.submit(largeTask);
      expect(taskId).toBeDefined();
    });

    it('should handle concurrent task submission', async () => {
      await scheduler.start();

      const CONCURRENT_SUBMISSIONS = 50;
      const tasks = Array.from({ length: CONCURRENT_SUBMISSIONS }, (_, i) =>
        createTestTask({
          requiredResources: { gpuMemoryMb: 128 }
        }, 20)
      );

      const submissionPromises = tasks.map(task => scheduler.submit(task));
      const taskIds = await Promise.all(submissionPromises);

      expect(taskIds.length).toBe(CONCURRENT_SUBMISSIONS);
      expect(new Set(taskIds).size).toBe(CONCURRENT_SUBMISSIONS);

      await new Promise(resolve => setTimeout(resolve, 3000));

      const resources = scheduler.getResourceManager().getAvailableResources();
      expect(resources.availableGpuMemoryMb).toBe(16384);
    });
  });
});
