import { EventEmitter } from 'events';
import cron from 'node-cron';
import { RunInstance, Task } from '../types';
import { generateId, nowISO, sleep, withTimeout, retry } from '../shared/utils';
import { logger } from '../logging';
import { monitoring } from '../monitoring';

interface TaskHandler {
  name: string;
  handler: (payload: unknown, context: TaskContext) => Promise<unknown>;
  timeout_ms?: number;
  retry_count?: number;
}

interface TaskContext {
  trace_id: string;
  task_id: string;
  run_id: string;
  metadata: Record<string, unknown>;
  start_time: number;
  setProgress: (progress: number) => void;
  emit: (event: string, data: unknown) => void;
}

interface ScheduledTask {
  task_id: string;
  cron_expression: string;
  task_type: string;
  config: Record<string, unknown>;
  enabled: boolean;
  last_run_at: string | null;
  next_run_at: string | null;
  timezone?: string;
}

interface ExecutionResult {
  success: boolean;
  data?: unknown;
  error?: string;
  duration_ms: number;
  run_id: string;
}

type TaskStatus = 'pending' | 'queued' | 'running' | 'completed' | 'failed' | 'cancelled' | 'rollback';

class TaskQueue {
  private queue: Array<{ task: Task; added_at: number; priority: number }> = [];
  private maxSize = 1000;

  enqueue(task: Task, priority: number = 0): boolean {
    if (this.queue.length >= this.maxSize) {
      return false;
    }
    this.queue.push({ task, added_at: Date.now(), priority });
    this.queue.sort((a, b) => b.priority - a.priority || a.added_at - b.added_at);
    return true;
  }

  dequeue(): Task | null {
    const item = this.queue.shift();
    return item ? item.task : null;
  }

  size(): number {
    return this.queue.length;
  }

  clear(): void {
    this.queue = [];
  }

  peek(): Task | null {
    return this.queue.length > 0 ? this.queue[0].task : null;
  }
}

class CoreProcessor extends EventEmitter {
  private handlers: Map<string, TaskHandler> = new Map();
  private tasks: Map<string, Task> = new Map();
  private runs: Map<string, RunInstance> = new Map();
  private scheduledTasks: Map<string, ScheduledTask> = new Map();
  private cronJobs: Map<string, cron.ScheduledTask> = new Map();
  private queue: TaskQueue = new TaskQueue();
  private taskStatus: Map<string, TaskStatus> = new Map();
  private isRunning = false;
  private maxConcurrentTasks = 5;
  private activeTaskCount = 0;

  constructor() {
    super();
    this.registerDefaultHandlers();
  }

  private registerDefaultHandlers(): void {
    this.registerHandler({
      name: 'echo',
      handler: async (payload) => payload,
      timeout_ms: 5000,
    });

    this.registerHandler({
      name: 'delay',
      handler: async (payload: unknown) => {
        const ms = (payload as { ms?: number })?.ms || 1000;
        await sleep(ms);
        return { delayed: ms };
      },
      timeout_ms: 30000,
    });

    this.registerHandler({
      name: 'health_check',
      handler: async () => {
        return {
          status: 'healthy',
          timestamp: nowISO(),
          active_tasks: this.activeTaskCount,
          queue_size: this.queue.size(),
        };
      },
      timeout_ms: 3000,
    });
  }

  registerHandler(handler: TaskHandler): void {
    this.handlers.set(handler.name, handler);
    logger.info('Task handler registered', { handler_name: handler.name });
    this.emit('handler.registered', handler.name);
  }

  unregisterHandler(handlerName: string): boolean {
    const existed = this.handlers.has(handlerName);
    if (existed) {
      this.handlers.delete(handlerName);
      logger.info('Task handler unregistered', { handler_name: handlerName });
      this.emit('handler.unregistered', handlerName);
    }
    return existed;
  }

  createTask(type: string, config: Record<string, unknown>, labels: Record<string, string> = {}): Task {
    const task: Task = {
      id: generateId('task'),
      type,
      config,
      labels,
      status: 'provisioning',
      created_at: nowISO(),
      updated_at: nowISO(),
    };

    this.tasks.set(task.id, task);
    this.taskStatus.set(task.id, 'pending');
    logger.info('Task created', { task_id: task.id, type });
    this.emit('task.created', task);

    return task;
  }

  async executeTask(
    taskId: string,
    payload: unknown,
    traceId?: string
  ): Promise<ExecutionResult> {
    const task = this.tasks.get(taskId);
    if (!task) {
      return {
        success: false,
        error: `Task ${taskId} not found`,
        duration_ms: 0,
        run_id: generateId('run'),
      };
    }

    const handler = this.handlers.get(task.type);
    if (!handler) {
      return {
        success: false,
        error: `No handler registered for task type: ${task.type}`,
        duration_ms: 0,
        run_id: generateId('run'),
      };
    }

    const runId = generateId('run');
    const actualTraceId = traceId || generateId('trace');
    const startTime = Date.now();

    const run: RunInstance = {
      run_id: runId,
      entity_id: taskId,
      phase: 'executing',
      progress: 0,
      started_at: nowISO(),
      completed_at: null,
      error_detail: null,
      metadata: {
        task_type: task.type,
        trace_id: actualTraceId,
      },
    };

    this.runs.set(runId, run);
    this.taskStatus.set(taskId, 'running');
    task.status = 'running';
    task.updated_at = nowISO();

    const context: TaskContext = {
      trace_id: actualTraceId,
      task_id: taskId,
      run_id: runId,
      metadata: {},
      start_time: startTime,
      setProgress: (progress: number) => {
        run.progress = Math.max(0, Math.min(1, progress));
        this.emit('task.progress', taskId, runId, run.progress);
      },
      emit: (event: string, data: unknown) => {
        this.emit(`task.event.${event}`, taskId, runId, data);
      },
    };

    logger.info('Task execution started', { task_id: taskId, run_id: runId, type: task.type }, actualTraceId);
    this.emit('task.started', taskId, runId);

    const timerId = monitoring.startTimer('task_duration', { task_type: task.type });

    try {
      const timeout = handler.timeout_ms || 30000;
      const retries = handler.retry_count || 0;

      const result = await retry(
        () => withTimeout(handler.handler(payload, context), timeout, `Task ${task.type} timed out after ${timeout}ms`),
        retries + 1,
        1000,
        2
      );

      run.phase = 'completed';
      run.progress = 1;
      run.completed_at = nowISO();
      task.status = 'completed';
      task.updated_at = nowISO();
      this.taskStatus.set(taskId, 'completed');

      const duration = Date.now() - startTime;
      monitoring.stopTimer('task_duration', timerId, { task_type: task.type, status: 'success' });
      monitoring.incrementCounter('tasks_completed', 1, { task_type: task.type });

      logger.info('Task execution completed', { task_id: taskId, run_id: runId, duration_ms: duration }, actualTraceId);
      this.emit('task.completed', taskId, runId, result);

      return {
        success: true,
        data: result,
        duration_ms: duration,
        run_id: runId,
      };
    } catch (error) {
      run.phase = 'failed';
      run.error_detail = (error as Error).message;
      run.completed_at = nowISO();
      task.status = 'failed';
      task.updated_at = nowISO();
      this.taskStatus.set(taskId, 'failed');

      const duration = Date.now() - startTime;
      monitoring.stopTimer('task_duration', timerId, { task_type: task.type, status: 'failed' });
      monitoring.incrementCounter('tasks_failed', 1, { task_type: task.type });

      logger.error('Task execution failed', {
        task_id: taskId,
        run_id: runId,
        error: (error as Error).message,
        duration_ms: duration,
      }, actualTraceId);
      this.emit('task.failed', taskId, runId, error);

      return {
        success: false,
        error: (error as Error).message,
        duration_ms: duration,
        run_id: runId,
      };
    }
  }

  queueTask(taskId: string, payload: unknown, priority: number = 0, traceId?: string): boolean {
    const task = this.tasks.get(taskId);
    if (!task) return false;

    const queued = this.queue.enqueue(task, priority);
    if (queued) {
      this.taskStatus.set(taskId, 'queued');
      task.status = 'provisioning';
      task.updated_at = nowISO();
      logger.info('Task queued', { task_id: taskId, priority, queue_size: this.queue.size() });
      this.emit('task.queued', taskId);
      this.processQueue();
    }
    return queued;
  }

  private async processQueue(): Promise<void> {
    if (this.isRunning) return;
    this.isRunning = true;

    while (this.queue.size() > 0 && this.activeTaskCount < this.maxConcurrentTasks) {
      const task = this.queue.dequeue();
      if (task) {
        this.activeTaskCount++;
        this.executeTask(task.id, task.config)
          .finally(() => {
            this.activeTaskCount--;
            setImmediate(() => this.processQueue());
          });
      }
    }

    this.isRunning = false;
  }

  scheduleTask(
    taskType: string,
    cronExpression: string,
    config: Record<string, unknown>,
    timezone?: string
  ): ScheduledTask {
    const taskId = generateId('sched');
    const scheduled: ScheduledTask = {
      task_id: taskId,
      cron_expression: cronExpression,
      task_type: taskType,
      config,
      enabled: true,
      last_run_at: null,
      next_run_at: null,
      timezone,
    };

    this.scheduledTasks.set(taskId, scheduled);

    const job = cron.schedule(cronExpression, () => {
      if (!scheduled.enabled) return;
      const task = this.createTask(taskType, config);
      scheduled.last_run_at = nowISO();
      this.queueTask(task.id, config, 1);
    }, {
      scheduled: true,
      timezone,
    });

    this.cronJobs.set(taskId, job);

    try {
      scheduled.next_run_at = null;
    } catch {
      scheduled.next_run_at = null;
    }

    logger.info('Task scheduled', { task_id: taskId, cron: cronExpression, task_type: taskType });
    this.emit('task.scheduled', scheduled);

    return scheduled;
  }

  cancelScheduledTask(taskId: string): boolean {
    const job = this.cronJobs.get(taskId);
    if (job) {
      job.stop();
      this.cronJobs.delete(taskId);
    }
    const scheduled = this.scheduledTasks.get(taskId);
    if (scheduled) {
      scheduled.enabled = false;
      logger.info('Scheduled task cancelled', { task_id: taskId });
      this.emit('task.unscheduled', taskId);
      return true;
    }
    return false;
  }

  getTask(taskId: string): Task | null {
    return this.tasks.get(taskId) || null;
  }

  getRun(runId: string): RunInstance | null {
    return this.runs.get(runId) || null;
  }

  getTaskRuns(taskId: string): RunInstance[] {
    return Array.from(this.runs.values()).filter((r) => r.entity_id === taskId);
  }

  listTasks(status?: TaskStatus): Task[] {
    const tasks = Array.from(this.tasks.values());
    if (status) {
      return tasks.filter((t) => t.status === status);
    }
    return tasks;
  }

  listScheduledTasks(): ScheduledTask[] {
    return Array.from(this.scheduledTasks.values());
  }

  getTaskStatus(taskId: string): TaskStatus | null {
    return this.taskStatus.get(taskId) || null;
  }

  cancelTask(taskId: string): boolean {
    const status = this.taskStatus.get(taskId);
    if (status && (status === 'pending' || status === 'queued')) {
      this.taskStatus.set(taskId, 'cancelled');
      const task = this.tasks.get(taskId);
      if (task) {
        task.status = 'stopped';
        task.updated_at = nowISO();
      }
      logger.info('Task cancelled', { task_id: taskId });
      this.emit('task.cancelled', taskId);
      return true;
    }
    return false;
  }

  setMaxConcurrentTasks(max: number): void {
    this.maxConcurrentTasks = Math.max(1, max);
    logger.info('Max concurrent tasks updated', { max });
  }

  getQueueSize(): number {
    return this.queue.size();
  }

  getActiveTaskCount(): number {
    return this.activeTaskCount;
  }

  getHandlerNames(): string[] {
    return Array.from(this.handlers.keys());
  }

  shutdown(): void {
    for (const [taskId, job] of this.cronJobs.entries()) {
      job.stop();
      logger.info('Cron job stopped', { task_id: taskId });
    }
    this.queue.clear();
    logger.info('Core processor shutdown complete');
    this.emit('processor.shutdown');
  }
}

export const coreProcessor = new CoreProcessor();
export { CoreProcessor, TaskHandler, TaskContext, ScheduledTask, ExecutionResult, TaskStatus, TaskQueue };
