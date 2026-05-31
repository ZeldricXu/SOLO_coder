import cron from 'node-cron';
import { EventEmitter } from 'events';
import { IScheduler } from '@ports/index';
import { ScheduledTask } from '@apptypes/index';
import { rootLogger } from '@modules/logging';
import { generateId, nowISO } from '@utils/index';

interface ActiveTask {
  task: ScheduledTask;
  cronJob: cron.ScheduledTask;
}

type TaskHandler = (payload: Record<string, unknown>) => Promise<void>;

export class Scheduler implements IScheduler {
  private logger = rootLogger.child({ module: 'Scheduler' });
  private tasks: Map<string, ActiveTask> = new Map();
  private handlers: Map<string, TaskHandler> = new Map();
  private eventEmitter: EventEmitter = new EventEmitter();

  private calculateNextRun(cronExpression: string): string {
    try {
      const now = new Date();
      const task = cron.schedule(cronExpression, () => {});
      const nextDates = (task as unknown as { nextDates: (count: number) => Date[] }).nextDates(1);
      const next = nextDates[0];
      task.stop();
      return next ? next.toISOString() : nowISO();
    } catch {
      return nowISO();
    }
  }

  registerHandler(name: string, handler: TaskHandler): void {
    this.handlers.set(name, handler);
    this.logger.info('Task handler registered', { handler_name: name });
  }

  private createCronTask(task: ScheduledTask): cron.ScheduledTask {
    const cronJob = cron.schedule(
      task.cron_expression,
      async () => {
        try {
          const now = nowISO();
          const activeTask = this.tasks.get(task.id);
          if (activeTask) {
            activeTask.task.last_run = now;
            activeTask.task.next_run = this.calculateNextRun(task.cron_expression);
          }

          this.eventEmitter.emit('task.started', { task_id: task.id, timestamp: now });
          this.logger.info('Task execution started', { task_id: task.id, task_name: task.name });

          const handler = this.handlers.get(task.name);
          if (handler) {
            await handler(task.payload);
            this.eventEmitter.emit('task.completed', { task_id: task.id, timestamp: nowISO() });
            this.logger.info('Task execution completed', { task_id: task.id, task_name: task.name });
          } else {
            this.logger.warn('No handler found for task', { task_id: task.id, task_name: task.name });
            this.eventEmitter.emit('task.failed', {
              task_id: task.id,
              error: 'No handler registered',
              timestamp: nowISO(),
            });
          }
        } catch (error) {
          this.logger.error('Task execution failed', {
            task_id: task.id,
            task_name: task.name,
            error: (error as Error).message,
          });
          this.eventEmitter.emit('task.failed', {
            task_id: task.id,
            error: (error as Error).message,
            timestamp: nowISO(),
          });
        }
      },
      {
        scheduled: task.enabled,
        timezone: 'UTC',
      }
    );

    return cronJob;
  }

  async schedule(
    taskData: Omit<ScheduledTask, 'id' | 'created_at' | 'last_run' | 'next_run'>
  ): Promise<ScheduledTask> {
    const taskId = generateId('task_');
    const task: ScheduledTask = {
      id: taskId,
      ...taskData,
      created_at: nowISO(),
      last_run: null,
      next_run: this.calculateNextRun(taskData.cron_expression),
    };

    if (!cron.validate(task.cron_expression)) {
      throw new Error(`Invalid cron expression: ${task.cron_expression}`);
    }

    const cronJob = this.createCronTask(task);
    this.tasks.set(taskId, { task, cronJob });

    this.logger.info('Task scheduled', {
      task_id: taskId,
      task_name: task.name,
      cron_expression: task.cron_expression,
      enabled: task.enabled,
    });

    this.eventEmitter.emit('task.scheduled', task);
    return task;
  }

  async unschedule(taskId: string): Promise<boolean> {
    const activeTask = this.tasks.get(taskId);
    if (!activeTask) {
      return false;
    }

    activeTask.cronJob.stop();
    this.tasks.delete(taskId);

    this.logger.info('Task unscheduled', { task_id: taskId, task_name: activeTask.task.name });
    this.eventEmitter.emit('task.unscheduled', { task_id: taskId });

    return true;
  }

  async list(): Promise<ScheduledTask[]> {
    return Array.from(this.tasks.values()).map((active) => active.task);
  }

  async get(taskId: string): Promise<ScheduledTask | null> {
    const activeTask = this.tasks.get(taskId);
    return activeTask ? activeTask.task : null;
  }

  async update(
    taskId: string,
    updates: Partial<Omit<ScheduledTask, 'id' | 'created_at'>>
  ): Promise<ScheduledTask | null> {
    const activeTask = this.tasks.get(taskId);
    if (!activeTask) {
      return null;
    }

    if (updates.cron_expression && !cron.validate(updates.cron_expression)) {
      throw new Error(`Invalid cron expression: ${updates.cron_expression}`);
    }

    activeTask.cronJob.stop();

    Object.assign(activeTask.task, updates);

    if (updates.cron_expression) {
      activeTask.task.next_run = this.calculateNextRun(updates.cron_expression);
    }

    activeTask.cronJob = this.createCronTask(activeTask.task);

    this.logger.info('Task updated', {
      task_id: taskId,
      updates: Object.keys(updates),
    });

    this.eventEmitter.emit('task.updated', activeTask.task);
    return activeTask.task;
  }

  async trigger(taskId: string): Promise<void> {
    const activeTask = this.tasks.get(taskId);
    if (!activeTask) {
      throw new Error(`Task not found: ${taskId}`);
    }

    this.logger.info('Manually triggering task', {
      task_id: taskId,
      task_name: activeTask.task.name,
    });

    activeTask.cronJob.now();
  }

  pause(taskId: string): boolean {
    const activeTask = this.tasks.get(taskId);
    if (!activeTask) {
      return false;
    }

    activeTask.cronJob.stop();
    activeTask.task.enabled = false;
    this.logger.info('Task paused', { task_id: taskId, task_name: activeTask.task.name });
    return true;
  }

  resume(taskId: string): boolean {
    const activeTask = this.tasks.get(taskId);
    if (!activeTask) {
      return false;
    }

    activeTask.cronJob.start();
    activeTask.task.enabled = true;
    this.logger.info('Task resumed', { task_id: taskId, task_name: activeTask.task.name });
    return true;
  }

  on(event: string, handler: (data: Record<string, unknown>) => void): void {
    this.eventEmitter.on(event, handler);
  }

  off(event: string, handler: (data: Record<string, unknown>) => void): void {
    this.eventEmitter.off(event, handler);
  }

  stopAll(): void {
    for (const [taskId, activeTask] of this.tasks) {
      activeTask.cronJob.stop();
      this.logger.info('Task stopped', { task_id: taskId, task_name: activeTask.task.name });
    }
    this.logger.info('All tasks stopped');
  }

  startAll(): void {
    for (const [taskId, activeTask] of this.tasks) {
      if (activeTask.task.enabled) {
        activeTask.cronJob.start();
        this.logger.info('Task started', { task_id: taskId, task_name: activeTask.task.name });
      }
    }
    this.logger.info('All enabled tasks started');
  }

  getTaskStatus(taskId: string): {
    id: string;
    name: string;
    enabled: boolean;
    last_run: string | null;
    next_run: string;
    is_running: boolean;
  } | null {
    const activeTask = this.tasks.get(taskId);
    if (!activeTask) {
      return null;
    }

    return {
      id: activeTask.task.id,
      name: activeTask.task.name,
      enabled: activeTask.task.enabled,
      last_run: activeTask.task.last_run,
      next_run: activeTask.task.next_run,
      is_running: (activeTask.cronJob as unknown as { getStatus: () => string }).getStatus() === 'scheduled',
    };
  }
}

export const scheduler = new Scheduler();
