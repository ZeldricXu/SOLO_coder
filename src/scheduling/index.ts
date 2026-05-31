import { v4 as uuidv4 } from 'uuid';
import logger from '../common/logger';
import { TaskStatus } from '../types';
import { Semaphore, sleep } from '../common/utils';

export interface Task {
  taskId: string;
  name: string;
  type: string;
  dependencies: string[];
  status: TaskStatus;
  priority: number;
  payload: Record<string, unknown>;
  result?: Record<string, unknown>;
  error?: string;
  createdAt: string;
  startedAt?: string;
  completedAt?: string;
  retries: number;
  maxRetries: number;
  timeoutMs: number;
  tags: Record<string, string>;
}

export interface TaskDefinition {
  name: string;
  type: string;
  dependencies?: string[];
  priority?: number;
  payload: Record<string, unknown>;
  maxRetries?: number;
  timeoutMs?: number;
  tags?: Record<string, string>;
}

export interface Job {
  jobId: string;
  name: string;
  tasks: Task[];
  status: TaskStatus;
  createdAt: string;
  startedAt?: string;
  completedAt?: string;
  context: Record<string, unknown>;
}

export interface TaskExecutor {
  (task: Task, jobContext: Record<string, unknown>): Promise<Record<string, unknown>>;
}

export interface SchedulerConfig {
  maxConcurrentTasks: number;
  defaultTimeoutMs: number;
  defaultMaxRetries: number;
  retryDelayMs: number;
}

export class TaskScheduler {
  private taskQueue: Task[] = [];
  private runningTasks: Map<string, Task> = new Map();
  private completedTasks: Map<string, Task> = new Map();
  private jobs: Map<string, Job> = new Map();
  private executors: Map<string, TaskExecutor> = new Map();
  private config: SchedulerConfig;
  private semaphore: Semaphore;
  private isRunning: boolean = false;
  private onTaskComplete?: (task: Task) => void;
  private onJobComplete?: (job: Job) => void;

  constructor(config: Partial<SchedulerConfig> = {}) {
    this.config = {
      maxConcurrentTasks: config.maxConcurrentTasks ?? 10,
      defaultTimeoutMs: config.defaultTimeoutMs ?? 300000,
      defaultMaxRetries: config.defaultMaxRetries ?? 3,
      retryDelayMs: config.retryDelayMs ?? 1000
    };
    this.semaphore = new Semaphore(this.config.maxConcurrentTasks);
  }

  setTaskCompleteCallback(callback: (task: Task) => void): void {
    this.onTaskComplete = callback;
  }

  setJobCompleteCallback(callback: (job: Job) => void): void {
    this.onJobComplete = callback;
  }

  registerExecutor(taskType: string, executor: TaskExecutor): void {
    this.executors.set(taskType, executor);
    logger.info({ taskType }, '注册任务执行器');
  }

  unregisterExecutor(taskType: string): void {
    this.executors.delete(taskType);
  }

  createJob(name: string, taskDefinitions: TaskDefinition[], context: Record<string, unknown> = {}): Job {
    const jobId = uuidv4();
    const tasks: Task[] = taskDefinitions.map((def, index) => ({
      taskId: uuidv4(),
      name: def.name,
      type: def.type,
      dependencies: def.dependencies || [],
      status: TaskStatus.PENDING,
      priority: def.priority ?? 5,
      payload: def.payload,
      createdAt: new Date().toISOString(),
      retries: 0,
      maxRetries: def.maxRetries ?? this.config.defaultMaxRetries,
      timeoutMs: def.timeoutMs ?? this.config.defaultTimeoutMs,
      tags: def.tags || {}
    }));

    const job: Job = {
      jobId,
      name,
      tasks,
      status: TaskStatus.PENDING,
      createdAt: new Date().toISOString(),
      context
    };

    this.jobs.set(jobId, job);
    logger.info({ jobId, name, taskCount: tasks.length }, '创建任务编排');
    return job;
  }

  submitJob(jobId: string): void {
    const job = this.jobs.get(jobId);
    if (!job) {
      throw new Error(`任务编排不存在: ${jobId}`);
    }

    job.status = TaskStatus.RUNNING;
    job.startedAt = new Date().toISOString();

    for (const task of job.tasks) {
      this.taskQueue.push(task);
    }

    this.taskQueue.sort((a, b) => b.priority - a.priority);
    logger.info({ jobId, taskCount: job.tasks.length }, '提交任务编排');
    this.startProcessing();
  }

  submitSingleTask(definition: TaskDefinition): Task {
    const task: Task = {
      taskId: uuidv4(),
      name: definition.name,
      type: definition.type,
      dependencies: definition.dependencies || [],
      status: TaskStatus.PENDING,
      priority: definition.priority ?? 5,
      payload: definition.payload,
      createdAt: new Date().toISOString(),
      retries: 0,
      maxRetries: definition.maxRetries ?? this.config.defaultMaxRetries,
      timeoutMs: definition.timeoutMs ?? this.config.defaultTimeoutMs,
      tags: definition.tags || {}
    };

    this.taskQueue.push(task);
    this.taskQueue.sort((a, b) => b.priority - a.priority);
    logger.info({ taskId: task.taskId, name: task.name }, '提交独立任务');
    this.startProcessing();
    return task;
  }

  private startProcessing(): void {
    if (this.isRunning) return;
    this.isRunning = true;
    this.processLoop().catch(error => {
      logger.error({ error }, '调度处理循环异常');
      this.isRunning = false;
    });
  }

  private async processLoop(): Promise<void> {
    while (this.taskQueue.length > 0 || this.runningTasks.size > 0) {
      const readyTasks = this.getReadyTasks();

      for (const task of readyTasks) {
        if (!this.runningTasks.has(task.taskId)) {
          this.executeTask(task).catch(error => {
            logger.error({ taskId: task.taskId, error }, '任务执行异常');
          });
        }
      }

      if (this.taskQueue.length === 0 && this.runningTasks.size === 0) {
        break;
      }

      await sleep(100);
    }

    this.isRunning = false;
    this.checkAndCompleteJobs();
  }

  private getReadyTasks(): Task[] {
    const ready: Task[] = [];
    const completedIds = new Set([
      ...this.completedTasks.keys(),
      ...Array.from(this.runningTasks.values()).filter(t => t.status === TaskStatus.COMPLETED).map(t => t.taskId)
    ]);

    for (let i = 0; i < this.taskQueue.length; i++) {
      const task = this.taskQueue[i];
      if (task.status !== TaskStatus.PENDING) continue;

      const dependenciesMet = task.dependencies.every(dep => completedIds.has(dep));
      if (dependenciesMet) {
        ready.push(task);
      }
    }

    return ready.sort((a, b) => b.priority - a.priority);
  }

  private async executeTask(task: Task): Promise<void> {
    await this.semaphore.acquire();

    const executor = this.executors.get(task.type);
    if (!executor) {
      task.status = TaskStatus.FAILED;
      task.error = `未找到执行器: ${task.type}`;
      this.completeTask(task);
      this.semaphore.release();
      return;
    }

    this.runningTasks.set(task.taskId, task);
    task.status = TaskStatus.RUNNING;
    task.startedAt = new Date().toISOString();

    const job = this.findJobForTask(task.taskId);
    logger.info({ taskId: task.taskId, type: task.type, jobId: job?.jobId }, '开始执行任务');

    const timeoutPromise = new Promise<never>((_, reject) => {
      setTimeout(() => reject(new Error(`任务超时 (${task.timeoutMs}ms)`)), task.timeoutMs);
    });

    try {
      const result = await Promise.race([
        executor(task, job?.context || {}),
        timeoutPromise
      ]);

      task.status = TaskStatus.COMPLETED;
      task.result = result;
      task.completedAt = new Date().toISOString();
      logger.info({ taskId: task.taskId, duration: Date.now() - new Date(task.startedAt).getTime() }, '任务执行成功');
    } catch (error) {
      const errorMessage = error instanceof Error ? error.message : String(error);

      if (task.retries < task.maxRetries) {
        task.retries++;
        task.status = TaskStatus.PENDING;
        logger.warn({ taskId: task.taskId, retries: task.retries, error: errorMessage }, '任务执行失败，准备重试');
        this.runningTasks.delete(task.taskId);
        this.semaphore.release();
        await sleep(this.config.retryDelayMs * Math.pow(2, task.retries - 1));
        return;
      }

      task.status = TaskStatus.FAILED;
      task.error = errorMessage;
      task.completedAt = new Date().toISOString();
      logger.error({ taskId: task.taskId, retries: task.retries, error: errorMessage }, '任务执行失败，已达最大重试次数');
    }

    this.completeTask(task);
    this.semaphore.release();
  }

  private completeTask(task: Task): void {
    this.runningTasks.delete(task.taskId);
    this.completedTasks.set(task.taskId, task);

    const index = this.taskQueue.findIndex(t => t.taskId === task.taskId);
    if (index > -1) {
      this.taskQueue.splice(index, 1);
    }

    this.onTaskComplete?.(task);
    this.checkAndCompleteJobs();
  }

  private findJobForTask(taskId: string): Job | undefined {
    for (const job of this.jobs.values()) {
      if (job.tasks.some(t => t.taskId === taskId)) {
        return job;
      }
    }
    return undefined;
  }

  private checkAndCompleteJobs(): void {
    for (const job of this.jobs.values()) {
      if (job.status !== TaskStatus.RUNNING) continue;

      const allCompleted = job.tasks.every(t =>
        t.status === TaskStatus.COMPLETED || t.status === TaskStatus.FAILED
      );

      if (allCompleted) {
        const hasFailed = job.tasks.some(t => t.status === TaskStatus.FAILED);
        job.status = hasFailed ? TaskStatus.FAILED : TaskStatus.COMPLETED;
        job.completedAt = new Date().toISOString();
        logger.info({ jobId: job.jobId, status: job.status }, '任务编排完成');
        this.onJobComplete?.(job);
      }
    }
  }

  getTaskStatus(taskId: string): Task | undefined {
    return this.runningTasks.get(taskId) ||
           this.completedTasks.get(taskId) ||
           this.taskQueue.find(t => t.taskId === taskId);
  }

  getJobStatus(jobId: string): Job | undefined {
    return this.jobs.get(jobId);
  }

  cancelTask(taskId: string): boolean {
    const task = this.getTaskStatus(taskId);
    if (!task || task.status === TaskStatus.COMPLETED || task.status === TaskStatus.FAILED) {
      return false;
    }

    task.status = TaskStatus.CANCELLED;
    const index = this.taskQueue.findIndex(t => t.taskId === taskId);
    if (index > -1) {
      this.taskQueue.splice(index, 1);
    }
    this.completedTasks.set(taskId, task);
    logger.info({ taskId }, '任务已取消');
    return true;
  }

  getStats(): {
    pending: number;
    running: number;
    completed: number;
    failed: number;
    jobs: number;
  } {
    return {
      pending: this.taskQueue.filter(t => t.status === TaskStatus.PENDING).length,
      running: this.runningTasks.size,
      completed: Array.from(this.completedTasks.values()).filter(t => t.status === TaskStatus.COMPLETED).length,
      failed: Array.from(this.completedTasks.values()).filter(t => t.status === TaskStatus.FAILED).length,
      jobs: this.jobs.size
    };
  }

  listJobs(): Job[] {
    return Array.from(this.jobs.values());
  }

  stop(): void {
    this.isRunning = false;
    this.taskQueue = [];
  }

  reset(): void {
    this.stop();
    this.taskQueue = [];
    this.runningTasks.clear();
    this.completedTasks.clear();
    this.jobs.clear();
  }
}
