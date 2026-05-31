import { Task, TaskExecution, TaskEvent, TaskStatus, TaskPriority, ScheduleType } from '../../types/scheduler';
import { generateId, getCurrentTimestamp, generateUUID } from '../../common/utils';
import { NotFoundError, ValidationError, AppError } from '../../common/errors';
import { EventEmitter } from 'events';

export type TaskHandler = (task: Task) => Promise<void> | void;

export interface ScheduleConfig {
  type: ScheduleType;
  cronExpression?: string;
  intervalMs?: number;
  runAt?: string;
}

export interface SchedulerConfig {
  maxConcurrentTasks?: number;
  retryAttempts?: number;
  retryDelayMs?: number;
  cleanupIntervalMs?: number;
  maxHistorySize?: number;
}

export class TaskScheduler extends EventEmitter {
  private tasks: Map<string, Task>;
  private executions: Map<string, TaskExecution>;
  private taskEvents: TaskEvent[];
  private handlers: Map<string, TaskHandler>;
  private activeExecutions: Set<string>;
  private config: Required<SchedulerConfig>;
  private timers: Map<string, NodeJS.Timeout>;
  private isRunning: boolean;

  constructor(config: SchedulerConfig = {}) {
    super();
    this.config = {
      maxConcurrentTasks: config.maxConcurrentTasks ?? 10,
      retryAttempts: config.retryAttempts ?? 3,
      retryDelayMs: config.retryDelayMs ?? 5000,
      cleanupIntervalMs: config.cleanupIntervalMs ?? 3600000,
      maxHistorySize: config.maxHistorySize ?? 1000
    };

    this.tasks = new Map();
    this.executions = new Map();
    this.taskEvents = [];
    this.handlers = new Map();
    this.activeExecutions = new Set();
    this.timers = new Map();
    this.isRunning = false;
  }

  registerHandler(taskType: string, handler: TaskHandler): void {
    this.handlers.set(taskType, handler);
  }

  unregisterHandler(taskType: string): boolean {
    return this.handlers.delete(taskType);
  }

  hasHandler(taskType: string): boolean {
    return this.handlers.has(taskType);
  }

  createTask(
    type: string,
    payload: Record<string, unknown> = {},
    options: {
      name?: string;
      description?: string;
      priority?: TaskPriority;
      schedule?: ScheduleConfig;
      dependencies?: string[];
      timeoutMs?: number;
      tenantId?: string;
      createdBy?: string;
    } = {}
  ): Task {
    if (!this.handlers.has(type)) {
      throw new ValidationError(`没有注册的任务处理器: ${type}`);
    }

    if (options.dependencies) {
      for (const depId of options.dependencies) {
        if (!this.tasks.has(depId)) {
          throw new ValidationError(`依赖任务不存在: ${depId}`);
        }
      }
    }

    const now = getCurrentTimestamp();
    const task: Task = {
      id: generateId('task'),
      type,
      name: options.name || type,
      description: options.description,
      payload,
      status: 'pending',
      priority: options.priority || 'medium',
      schedule: options.schedule,
      dependencies: options.dependencies || [],
      timeoutMs: options.timeoutMs || 30000,
      tenantId: options.tenantId,
      createdBy: options.createdBy,
      createdAt: now,
      updatedAt: now,
      lastRunAt: null,
      nextRunAt: options.schedule ? this.calculateNextRun(options.schedule) : null
    };

    this.tasks.set(task.id, task);
    this.emit('task.created', task);
    this.addEvent(task.id, 'created', '任务已创建');

    return task;
  }

  private calculateNextRun(schedule: ScheduleConfig): string | null {
    const now = new Date();

    switch (schedule.type) {
      case 'immediate':
        return now.toISOString();

      case 'delayed':
        if (!schedule.intervalMs) return null;
        return new Date(now.getTime() + schedule.intervalMs).toISOString();

      case 'scheduled':
        if (schedule.runAt) {
          const runAt = new Date(schedule.runAt);
          return runAt > now ? runAt.toISOString() : null;
        }
        return null;

      case 'recurring':
        return now.toISOString();

      default:
        return null;
    }
  }

  getTask(taskId: string): Task {
    const task = this.tasks.get(taskId);
    if (!task) {
      throw new NotFoundError(`任务不存在: ${taskId}`);
    }
    return task;
  }

  updateTask(
    taskId: string,
    updates: Partial<Pick<Task, 'name' | 'description' | 'payload' | 'priority' | 'schedule' | 'timeoutMs'>>
  ): Task {
    const task = this.getTask(taskId);

    if (['running', 'pending'].includes(task.status)) {
      throw new AppError('无法更新正在执行或待执行的任务', 'TASK_ACTIVE', 400);
    }

    const updated: Task = {
      ...task,
      ...updates,
      updatedAt: getCurrentTimestamp(),
      nextRunAt: updates.schedule ? this.calculateNextRun(updates.schedule) : task.nextRunAt
    };

    this.tasks.set(taskId, updated);
    this.addEvent(taskId, 'updated', '任务已更新');

    return updated;
  }

  deleteTask(taskId: string): void {
    const task = this.getTask(taskId);

    if (task.status === 'running') {
      throw new AppError('无法删除正在执行的任务', 'TASK_RUNNING', 400);
    }

    const dependents = Array.from(this.tasks.values()).filter(
      t => t.dependencies.includes(taskId)
    );

    if (dependents.length > 0) {
      throw new AppError(
        `任务被 ${dependents.length} 个任务依赖，无法删除`,
        'TASK_HAS_DEPENDENTS',
        400,
        { dependents: dependents.map(d => d.id) }
      );
    }

    this.cancelTask(taskId);
    this.tasks.delete(taskId);
    this.addEvent(taskId, 'deleted', '任务已删除');
  }

  listTasks(filters?: {
    type?: string;
    status?: TaskStatus;
    tenantId?: string;
    createdBy?: string;
  }): Task[] {
    let tasks = Array.from(this.tasks.values());

    if (filters) {
      if (filters.type) {
        tasks = tasks.filter(t => t.type === filters.type);
      }
      if (filters.status) {
        tasks = tasks.filter(t => t.status === filters.status);
      }
      if (filters.tenantId) {
        tasks = tasks.filter(t => t.tenantId === filters.tenantId);
      }
      if (filters.createdBy) {
        tasks = tasks.filter(t => t.createdBy === filters.createdBy);
      }
    }

    return tasks.sort((a, b) => {
      const priorityOrder = { high: 0, medium: 1, low: 2 };
      const priorityDiff = priorityOrder[a.priority] - priorityOrder[b.priority];
      if (priorityDiff !== 0) return priorityDiff;
      return new Date(b.createdAt).getTime() - new Date(a.createdAt).getTime();
    });
  }

  start(): void {
    if (this.isRunning) return;

    this.isRunning = true;
    this.scheduleLoop();
    this.startCleanup();
    this.emit('scheduler.started');
  }

  stop(): void {
    if (!this.isRunning) return;

    this.isRunning = false;

    for (const timer of this.timers.values()) {
      clearTimeout(timer);
    }
    this.timers.clear();

    this.emit('scheduler.stopped');
  }

  private scheduleLoop(): void {
    if (!this.isRunning) return;

    this.checkDueTasks().catch(error => {
      console.error('调度器检查任务失败:', error);
    });

    const timer = setTimeout(() => this.scheduleLoop(), 1000);
    this.timers.set('schedule-loop', timer);
  }

  private async checkDueTasks(): Promise<void> {
    const now = new Date();
    const dueTasks = Array.from(this.tasks.values()).filter(task => {
      if (task.status !== 'pending' && task.status !== 'failed') return false;
      if (!task.nextRunAt) return false;

      const canRun = task.dependencies.every(depId => {
        const dep = this.tasks.get(depId);
        return dep && dep.status === 'completed';
      });

      if (!canRun) return false;

      return new Date(task.nextRunAt) <= now;
    });

    for (const task of dueTasks) {
      if (this.activeExecutions.size < this.config.maxConcurrentTasks) {
        await this.executeTask(task.id);
      }
    }
  }

  async executeTask(taskId: string): Promise<TaskExecution> {
    const task = this.getTask(taskId);
    const handler = this.handlers.get(task.type);

    if (!handler) {
      throw new ValidationError(`没有注册的任务处理器: ${task.type}`);
    }

    if (this.activeExecutions.size >= this.config.maxConcurrentTasks) {
      throw new AppError('已达到最大并发任务数', 'MAX_CONCURRENCY', 429);
    }

    const execution: TaskExecution = {
      id: generateId('exec'),
      taskId,
      status: 'running',
      startTime: getCurrentTimestamp(),
      endTime: null,
      attempts: 1,
      error: null,
      result: null
    };

    this.executions.set(execution.id, execution);
    this.activeExecutions.add(taskId);

    task.status = 'running';
    task.updatedAt = getCurrentTimestamp();
    task.lastRunAt = getCurrentTimestamp();
    this.tasks.set(taskId, task);

    this.emit('task.started', task, execution);
    this.addEvent(taskId, 'started', '任务开始执行');

    try {
      const result = await this.runWithRetry(task, handler);

      execution.status = 'completed';
      execution.endTime = getCurrentTimestamp();
      execution.result = result;

      task.status = 'completed';
      task.updatedAt = getCurrentTimestamp();

      this.emit('task.completed', task, execution);
      this.addEvent(taskId, 'completed', '任务执行成功');

      this.scheduleNextRun(task);
    } catch (error) {
      execution.status = 'failed';
      execution.endTime = getCurrentTimestamp();
      execution.error = error instanceof Error ? error.message : String(error);

      task.status = 'failed';
      task.updatedAt = getCurrentTimestamp();

      this.emit('task.failed', task, execution, error);
      this.addEvent(taskId, 'failed', `任务执行失败: ${execution.error}`);
    } finally {
      this.executions.set(execution.id, execution);
      this.tasks.set(taskId, task);
      this.activeExecutions.delete(taskId);
    }

    return execution;
  }

  private async runWithRetry(task: Task, handler: TaskHandler): Promise<unknown> {
    let lastError: unknown;

    for (let attempt = 1; attempt <= this.config.retryAttempts; attempt++) {
      try {
        const timeoutPromise = new Promise<never>((_, reject) => {
          setTimeout(() => reject(new Error(`任务超时 (${task.timeoutMs}ms)`)), task.timeoutMs);
        });

        const result = await Promise.race([
          handler(task),
          timeoutPromise
        ]);

        return result;
      } catch (error) {
        lastError = error;

        if (attempt < this.config.retryAttempts) {
          this.addEvent(task.id, 'retry', `重试 ${attempt}/${this.config.retryAttempts}: ${error instanceof Error ? error.message : String(error)}`);

          await new Promise(resolve =>
            setTimeout(resolve, this.config.retryDelayMs * Math.pow(2, attempt - 1))
          );
        }
      }
    }

    throw lastError;
  }

  private scheduleNextRun(task: Task): void {
    if (!task.schedule || task.schedule.type !== 'recurring') {
      return;
    }

    if (task.schedule.type === 'recurring' && task.schedule.intervalMs) {
      task.nextRunAt = new Date(Date.now() + task.schedule.intervalMs).toISOString();
      task.status = 'pending';
      this.tasks.set(task.id, task);
    }
  }

  cancelTask(taskId: string): void {
    const task = this.getTask(taskId);

    if (task.status === 'running') {
      task.status = 'cancelled';
      task.updatedAt = getCurrentTimestamp();
      this.tasks.set(taskId, task);

      this.emit('task.cancelled', task);
      this.addEvent(taskId, 'cancelled', '任务已取消');
    } else if (task.status === 'pending') {
      task.status = 'cancelled';
      task.nextRunAt = null;
      task.updatedAt = getCurrentTimestamp();
      this.tasks.set(taskId, task);

      this.addEvent(taskId, 'cancelled', '任务已取消');
    }
  }

  pauseTask(taskId: string): void {
    const task = this.getTask(taskId);

    if (task.status === 'pending') {
      task.status = 'paused';
      task.updatedAt = getCurrentTimestamp();
      this.tasks.set(taskId, task);

      this.addEvent(taskId, 'paused', '任务已暂停');
    }
  }

  resumeTask(taskId: string): void {
    const task = this.getTask(taskId);

    if (task.status === 'paused') {
      task.status = 'pending';
      task.updatedAt = getCurrentTimestamp();
      this.tasks.set(taskId, task);

      this.addEvent(taskId, 'resumed', '任务已恢复');
    }
  }

  getExecution(executionId: string): TaskExecution {
    const execution = this.executions.get(executionId);
    if (!execution) {
      throw new NotFoundError(`任务执行记录不存在: ${executionId}`);
    }
    return execution;
  }

  listExecutions(taskId?: string, limit: number = 100): TaskExecution[] {
    let executions = Array.from(this.executions.values());

    if (taskId) {
      executions = executions.filter(e => e.taskId === taskId);
    }

    return executions
      .sort((a, b) => new Date(b.startTime).getTime() - new Date(a.startTime).getTime())
      .slice(0, limit);
  }

  getTaskEvents(taskId: string, limit: number = 50): TaskEvent[] {
    const events = this.taskEvents.filter(e => e.taskId === taskId);
    return events
      .sort((a, b) => new Date(b.timestamp).getTime() - new Date(a.timestamp).getTime())
      .slice(0, limit);
  }

  private addEvent(taskId: string, type: TaskEvent['type'], message: string, data?: Record<string, unknown>): void {
    const event: TaskEvent = {
      id: generateId('evt'),
      taskId,
      type,
      message,
      data,
      timestamp: getCurrentTimestamp()
    };

    this.taskEvents.push(event);

    if (this.taskEvents.length > this.config.maxHistorySize * 10) {
      this.taskEvents = this.taskEvents.slice(-this.config.maxHistorySize * 10);
    }
  }

  private startCleanup(): void {
    const cleanup = () => {
      if (!this.isRunning) return;

      const now = Date.now();
      const maxAge = 7 * 24 * 60 * 60 * 1000;

      for (const [id, execution] of this.executions) {
        if (execution.endTime && now - new Date(execution.endTime).getTime() > maxAge) {
          this.executions.delete(id);
        }
      }

      const timer = setTimeout(cleanup, this.config.cleanupIntervalMs);
      this.timers.set('cleanup', timer);
    };

    const timer = setTimeout(cleanup, this.config.cleanupIntervalMs);
    this.timers.set('cleanup', timer);
  }

  getStats() {
    const statusCounts = Array.from(this.tasks.values()).reduce((acc, t) => {
      acc[t.status] = (acc[t.status] || 0) + 1;
      return acc;
    }, {} as Record<string, number>);

    return {
      totalTasks: this.tasks.size,
      activeExecutions: this.activeExecutions.size,
      maxConcurrentTasks: this.config.maxConcurrentTasks,
      statusCounts,
      totalExecutions: this.executions.size,
      isRunning: this.isRunning
    };
  }

  destroy(): void {
    this.stop();
    this.tasks.clear();
    this.executions.clear();
    this.taskEvents = [];
    this.handlers.clear();
    this.activeExecutions.clear();
    this.removeAllListeners();
  }
}
