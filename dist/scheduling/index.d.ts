import { TaskStatus } from '../types';
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
export declare class TaskScheduler {
    private taskQueue;
    private runningTasks;
    private completedTasks;
    private jobs;
    private executors;
    private config;
    private semaphore;
    private isRunning;
    private onTaskComplete?;
    private onJobComplete?;
    constructor(config?: Partial<SchedulerConfig>);
    setTaskCompleteCallback(callback: (task: Task) => void): void;
    setJobCompleteCallback(callback: (job: Job) => void): void;
    registerExecutor(taskType: string, executor: TaskExecutor): void;
    unregisterExecutor(taskType: string): void;
    createJob(name: string, taskDefinitions: TaskDefinition[], context?: Record<string, unknown>): Job;
    submitJob(jobId: string): void;
    submitSingleTask(definition: TaskDefinition): Task;
    private startProcessing;
    private processLoop;
    private getReadyTasks;
    private executeTask;
    private completeTask;
    private findJobForTask;
    private checkAndCompleteJobs;
    getTaskStatus(taskId: string): Task | undefined;
    getJobStatus(jobId: string): Job | undefined;
    cancelTask(taskId: string): boolean;
    getStats(): {
        pending: number;
        running: number;
        completed: number;
        failed: number;
        jobs: number;
    };
    listJobs(): Job[];
    stop(): void;
    reset(): void;
}
//# sourceMappingURL=index.d.ts.map