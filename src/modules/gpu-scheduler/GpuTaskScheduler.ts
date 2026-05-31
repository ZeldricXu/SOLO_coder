import {
  TaskScheduler,
  ScheduledTask,
  Task,
  TaskPriority,
  PreemptionStrategy,
  ResourceManager
} from '../../core/ports';
import { RequestContext } from '../../common';
import { PriorityTaskQueue } from './PriorityQueue';
import { preemptionStrategies } from './strategies/PreemptionStrategies';
import { DefaultResourceManager } from './ResourceManager';
import { logger, generateId, createContext } from '../../common';

export interface GpuSchedulerOptions {
  maxConcurrentTasks?: number;
  pollIntervalMs?: number;
  preemptionStrategy?: 'priority' | 'lowestProgress' | 'memoryOptimized';
  enablePreemption?: boolean;
  nodeConfigs?: Array<{
    nodeId: string;
    gpus: Array<{ id: number; totalMemoryMb: number }>;
  }>;
  maxCompletedTasks?: number;
  cleanupIntervalMs?: number;
  batchScheduleSize?: number;
}

const DEFAULT_OPTIONS: Required<Omit<GpuSchedulerOptions, 'nodeConfigs'>> = {
  maxConcurrentTasks: 10,
  pollIntervalMs: 100,
  preemptionStrategy: 'priority',
  enablePreemption: true,
  maxCompletedTasks: 1000,
  cleanupIntervalMs: 30000,
  batchScheduleSize: 10
};

export class GpuTaskScheduler implements TaskScheduler {
  private options: Required<GpuSchedulerOptions>;
  private queue: PriorityTaskQueue;
  private runningTasks: Map<string, ScheduledTask> = new Map();
  private completedTasks: Map<string, ScheduledTask> = new Map();
  private resourceManager: DefaultResourceManager;
  private preemptionStrategy: PreemptionStrategy;
  private isRunning = false;
  private pollTimer?: NodeJS.Timeout;
  private cleanupTimer?: NodeJS.Timeout;
  private ctx: RequestContext;
  private scheduleInProgress = false;
  private schedulingStats = {
    totalScheduled: 0,
    totalPreempted: 0,
    totalCompleted: 0,
    totalFailed: 0,
    lastCleanupTime: 0
  };

  constructor(options: GpuSchedulerOptions = {}) {
    this.options = {
      ...DEFAULT_OPTIONS,
      ...options
    } as Required<GpuSchedulerOptions>;

    this.queue = new PriorityTaskQueue();
    this.ctx = createContext('gpu-scheduler');
    this.preemptionStrategy = preemptionStrategies[this.options.preemptionStrategy];

    const nodeConfigs = this.options.nodeConfigs || [
      {
        nodeId: 'default-node',
        gpus: [{ id: 0, totalMemoryMb: 8192 }, { id: 1, totalMemoryMb: 8192 }]
      }
    ];

    this.resourceManager = new DefaultResourceManager(nodeConfigs);
  }

  async submit(task: ScheduledTask): Promise<string> {
    task.status = 'queued';
    task.created_at = new Date().toISOString();

    this.queue.enqueue(task);
    logger.info('Task submitted to GPU scheduler', {
      taskId: task.id,
      priority: task.priority,
      requiredMemory: task.requiredResources.gpuMemoryMb,
      queueLength: this.queue.getLength()
    });

    if (this.isRunning) {
      setImmediate(() => this.tryScheduleTasks());
    }

    return task.id;
  }

  async cancel(taskId: string): Promise<boolean> {
    if (this.queue.has(taskId)) {
      const removed = this.queue.remove(taskId);
      logger.info('Task cancelled from queue', { taskId });
      return removed;
    }

    const runningTask = this.runningTasks.get(taskId);
    if (runningTask) {
      if (runningTask.onPreempt) {
        await runningTask.onPreempt();
      }
      runningTask.status = 'preempted';
      this.runningTasks.delete(taskId);

      if (runningTask.assignedResources) {
        this.resourceManager.releaseSync(runningTask.assignedResources);
      }

      logger.info('Running task cancelled', { taskId });
      return true;
    }

    return false;
  }

  async getStatus(taskId: string): Promise<Task | null> {
    const queuedTask = this.queue.getTaskById(taskId);
    if (queuedTask) {
      return queuedTask as Task;
    }

    const runningTask = this.runningTasks.get(taskId);
    if (runningTask) {
      return runningTask as Task;
    }

    const completedTask = this.completedTasks.get(taskId);
    if (completedTask) {
      return completedTask as Task;
    }

    return null;
  }

  getQueueLength(priority?: TaskPriority): number {
    return this.queue.getLength(priority);
  }

  async start(): Promise<void> {
    if (this.isRunning) {
      return;
    }

    this.isRunning = true;
    logger.info('GPU Task Scheduler started', {
      maxConcurrentTasks: this.options.maxConcurrentTasks,
      pollIntervalMs: this.options.pollIntervalMs,
      batchScheduleSize: this.options.batchScheduleSize,
      cleanupIntervalMs: this.options.cleanupIntervalMs
    });

    this.pollTimer = setInterval(() => {
      this.tryScheduleTasks().catch(error => {
        logger.error('Error in scheduler poll loop', { error: error.message });
      });
    }, this.options.pollIntervalMs);

    this.cleanupTimer = setInterval(() => {
      this.cleanupCompletedTasks();
    }, this.options.cleanupIntervalMs);

    await this.tryScheduleTasks();
  }

  async stop(): Promise<void> {
    if (!this.isRunning) {
      return;
    }

    this.isRunning = false;

    if (this.pollTimer) {
      clearInterval(this.pollTimer);
      this.pollTimer = undefined;
    }

    if (this.cleanupTimer) {
      clearInterval(this.cleanupTimer);
      this.cleanupTimer = undefined;
    }

    logger.info('GPU Task Scheduler stopped', {
      stats: { ...this.schedulingStats }
    });
  }

  private async tryScheduleTasks(): Promise<void> {
    if (!this.isRunning || this.scheduleInProgress) return;

    this.scheduleInProgress = true;
    try {
      let scheduledCount = 0;
      const batchSize = this.options.batchScheduleSize;

      while (
        this.runningTasks.size < this.options.maxConcurrentTasks &&
        scheduledCount < batchSize &&
        !this.queue.isEmpty()
      ) {
        const nextTask = this.queue.peek();
        if (!nextTask) break;

        let assignment = this.resourceManager.allocateSync(nextTask.requiredResources);

        if (!assignment && this.options.enablePreemption) {
          assignment = await this.tryPreemptResources(nextTask);
        }

        if (!assignment) {
          break;
        }

        const task = this.queue.dequeue()!;
        task.assignedResources = assignment;
        task.status = 'running';
        task.started_at = new Date().toISOString();

        this.runningTasks.set(task.id, task);
        this.schedulingStats.totalScheduled++;
        scheduledCount++;

        this.executeTask(task).catch(error => {
          logger.error('Unhandled error in task execution', {
            taskId: task.id,
            error: error.message
          });
        });
      }
    } finally {
      this.scheduleInProgress = false;
    }
  }

  private async tryPreemptResources(incomingTask: ScheduledTask) {
    const runningTasksArray = Array.from(this.runningTasks.values());
    const victim = this.preemptionStrategy.selectVictim(runningTasksArray, incomingTask);

    if (!victim) {
      return null;
    }

    logger.info('Preempting task to make room for higher priority task', {
      victimTaskId: victim.id,
      victimPriority: victim.priority,
      incomingTaskId: incomingTask.id,
      incomingPriority: incomingTask.priority
    });

    if (victim.onPreempt) {
      await victim.onPreempt();
    }

    victim.status = 'preempted';
    this.runningTasks.delete(victim.id);
    this.schedulingStats.totalPreempted++;

    if (victim.assignedResources) {
      this.resourceManager.releaseSync(victim.assignedResources);
    }

    this.queue.enqueue(victim);

    return this.resourceManager.allocateSync(incomingTask.requiredResources);
  }

  private async executeTask(task: ScheduledTask): Promise<void> {
    const taskCtx = createContext('gpu-task', task.id);
    logger.info('Starting GPU task execution', { taskId: task.id });

    try {
      await task.execute(taskCtx);
      task.status = 'completed';
      task.progress = 100;
      task.completed_at = new Date().toISOString();

      if (task.onComplete) {
        await task.onComplete();
      }

      this.schedulingStats.totalCompleted++;
      logger.info('GPU task completed successfully', { taskId: task.id });
    } catch (error) {
      task.status = 'failed';
      task.error_detail = (error as Error).message;
      task.completed_at = new Date().toISOString();

      if (task.onError) {
        await task.onError(error as Error);
      }

      this.schedulingStats.totalFailed++;
      logger.error('GPU task failed', { taskId: task.id, error: (error as Error).message });
    } finally {
      if (task.assignedResources) {
        this.resourceManager.releaseSync(task.assignedResources);
      }

      this.runningTasks.delete(task.id);
      this.completedTasks.set(task.id, task);

      if (this.isRunning) {
        setImmediate(() => this.tryScheduleTasks());
      }
    }
  }

  private cleanupCompletedTasks(): void {
    const now = Date.now();
    const maxTasks = this.options.maxCompletedTasks;

    if (this.completedTasks.size <= maxTasks) {
      return;
    }

    const excessCount = this.completedTasks.size - maxTasks;
    const keysToDelete = Array.from(this.completedTasks.keys()).slice(0, excessCount);

    for (const key of keysToDelete) {
      this.completedTasks.delete(key);
    }

    this.schedulingStats.lastCleanupTime = now;
    logger.debug('Cleaned up completed tasks', {
      removedCount: keysToDelete.length,
      remainingCount: this.completedTasks.size
    });
  }

  getResourceManager(): ResourceManager {
    return this.resourceManager;
  }

  getRunningTasksCount(): number {
    return this.runningTasks.size;
  }

  getCompletedTasksCount(): number {
    return this.completedTasks.size;
  }

  getStats() {
    return {
      ...this.schedulingStats,
      runningTasks: this.runningTasks.size,
      queuedTasks: this.queue.getLength(),
      completedTasks: this.completedTasks.size
    };
  }
}
