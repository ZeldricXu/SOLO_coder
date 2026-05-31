import { EventEmitter } from 'events';
import { RunInstance, Task } from '../types';
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
declare class TaskQueue {
    private queue;
    private maxSize;
    enqueue(task: Task, priority?: number): boolean;
    dequeue(): Task | null;
    size(): number;
    clear(): void;
    peek(): Task | null;
}
declare class CoreProcessor extends EventEmitter {
    private handlers;
    private tasks;
    private runs;
    private scheduledTasks;
    private cronJobs;
    private queue;
    private taskStatus;
    private isRunning;
    private maxConcurrentTasks;
    private activeTaskCount;
    constructor();
    private registerDefaultHandlers;
    registerHandler(handler: TaskHandler): void;
    unregisterHandler(handlerName: string): boolean;
    createTask(type: string, config: Record<string, unknown>, labels?: Record<string, string>): Task;
    executeTask(taskId: string, payload: unknown, traceId?: string): Promise<ExecutionResult>;
    queueTask(taskId: string, payload: unknown, priority?: number, traceId?: string): boolean;
    private processQueue;
    scheduleTask(taskType: string, cronExpression: string, config: Record<string, unknown>, timezone?: string): ScheduledTask;
    cancelScheduledTask(taskId: string): boolean;
    getTask(taskId: string): Task | null;
    getRun(runId: string): RunInstance | null;
    getTaskRuns(taskId: string): RunInstance[];
    listTasks(status?: TaskStatus): Task[];
    listScheduledTasks(): ScheduledTask[];
    getTaskStatus(taskId: string): TaskStatus | null;
    cancelTask(taskId: string): boolean;
    setMaxConcurrentTasks(max: number): void;
    getQueueSize(): number;
    getActiveTaskCount(): number;
    getHandlerNames(): string[];
    shutdown(): void;
}
export declare const coreProcessor: CoreProcessor;
export { CoreProcessor, TaskHandler, TaskContext, ScheduledTask, ExecutionResult, TaskStatus, TaskQueue };
//# sourceMappingURL=index.d.ts.map