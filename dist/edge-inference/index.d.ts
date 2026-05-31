import { TaskStatus } from '../types';
import { Semaphore } from '../common/utils';
export interface InferenceTask {
    taskId: string;
    modelId: string;
    inputData: Record<string, unknown>;
    priority: number;
    status: TaskStatus;
    result?: Record<string, unknown>;
    error?: string;
    createdAt: string;
    startedAt?: string;
    completedAt?: string;
    callbackUrl?: string;
    batchId?: string;
    affinity?: string;
    retries?: number;
    maxRetries?: number;
}
export interface BatchInferenceRequest {
    modelId: string;
    inputs: Array<Record<string, unknown>>;
    priority?: number;
    callbackUrl?: string;
}
export interface BatchInferenceResult {
    batchId: string;
    modelId: string;
    results: Array<{
        taskId: string;
        success: boolean;
        result?: Record<string, unknown>;
        error?: string;
    }>;
    completedAt: string;
    totalTimeMs: number;
}
export interface ModelDeployment {
    modelId: string;
    version: string;
    edgeNodeId: string;
    status: 'deploying' | 'active' | 'failed' | 'undeploying';
    deployedAt: string;
    resources: {
        cpuCores: number;
        memoryMB: number;
        gpuEnabled: boolean;
        gpuUnits?: number;
    };
    maxBatchSize?: number;
    supportedBatch?: boolean;
}
export interface WorkerPoolConfig {
    minWorkers: number;
    maxWorkers: number;
    idleTimeoutMs: number;
    maxQueueSize: number;
}
export interface CacheConfig {
    l1Enabled: boolean;
    l1MaxSize: number;
    l1TtlMs: number;
    l2Enabled: boolean;
    l2MaxSize: number;
    l2TtlMs: number;
    warmupEnabled: boolean;
    warmupModels: string[];
    invalidationStrategy: 'lru' | 'lfu' | 'fifo';
}
export interface InferenceConfig {
    maxConcurrentTasks: number;
    taskTimeoutMs: number;
    retryCount: number;
    enableLocalCaching: boolean;
    enableBatching: boolean;
    maxBatchSize: number;
    batchTimeoutMs: number;
    priorityLevels: number;
    workerPool: WorkerPoolConfig;
    enableGpuScheduling: boolean;
    gpuConcurrency: number;
    cache: CacheConfig;
}
export interface TaskQueue {
    name: string;
    priority: number;
    tasks: InferenceTask[];
    concurrency: number;
    semaphore: Semaphore;
}
export interface Worker {
    workerId: string;
    status: 'idle' | 'busy' | 'stopped';
    currentTask?: InferenceTask;
    completedTasks: number;
    startedAt: string;
}
export interface CacheEntry {
    key: string;
    value: Record<string, unknown>;
    createdAt: number;
    lastAccessedAt: number;
    accessCount: number;
    ttlMs: number;
    size: number;
}
export interface CacheStats {
    l1: {
        hits: number;
        misses: number;
        size: number;
        maxSize: number;
        hitRate: number;
    };
    l2: {
        hits: number;
        misses: number;
        size: number;
        maxSize: number;
        hitRate: number;
    };
}
export interface DistributedCacheClient {
    get(key: string): Promise<Record<string, unknown> | undefined>;
    set(key: string, value: Record<string, unknown>, ttlMs: number): Promise<void>;
    delete(key: string): Promise<void>;
    clear(): Promise<void>;
    keys(pattern?: string): Promise<string[]>;
}
export declare class EdgeInferenceScheduler {
    private queues;
    private runningTasks;
    private completedTasks;
    private deployedModels;
    private workers;
    private batchCollectors;
    private config;
    private onTaskComplete?;
    private onBatchComplete?;
    private gpuSemaphore?;
    private isProcessing;
    private activeWorkers;
    private l1Cache;
    private l2Cache;
    private l1CacheStats;
    private l2CacheStats;
    private cacheCleanupTimer?;
    private warmupInProgress;
    constructor(config?: Partial<InferenceConfig>);
    private initializeQueues;
    private calculateConcurrencyForPriority;
    private initializeWorkerPool;
    private initializeCacheCleanup;
    private warmupCache;
    private generateCacheKey;
    private hashInput;
    private createWorker;
    private startWorkerLoop;
    private getNextTask;
    setTaskCompleteCallback(callback: (task: InferenceTask) => void): void;
    setBatchCompleteCallback(callback: (batch: BatchInferenceResult) => void): void;
    deployModel(modelId: string, version: string, edgeNodeId: string, resources: ModelDeployment['resources'], options?: {
        maxBatchSize?: number;
        supportedBatch?: boolean;
    }): Promise<ModelDeployment>;
    undeployModel(modelId: string): Promise<void>;
    submitInferenceTask(modelId: string, inputData: Record<string, unknown>, priority?: number, callbackUrl?: string, options?: {
        affinity?: string;
        batchId?: string;
        skipCache?: boolean;
    }): InferenceTask;
    submitInferenceTaskWithCache(modelId: string, inputData: Record<string, unknown>, priority?: number, callbackUrl?: string, options?: {
        affinity?: string;
        skipCache?: boolean;
    }): Promise<{
        task?: InferenceTask;
        cachedResult?: Record<string, unknown>;
        cacheSource?: 'l1' | 'l2';
    }>;
    submitBatchInference(request: BatchInferenceRequest): Promise<{
        batchId: string;
        tasks: InferenceTask[];
    }>;
    private shouldBatch;
    private addToBatchCollector;
    private flushBatch;
    private executeBatchInference;
    private ensureProcessing;
    private adjustWorkerPool;
    private executeTaskWithWorker;
    private runInference;
    private getWorkerForTask;
    private sendCallback;
    private simulateDeployment;
    getTaskStatus(taskId: string): InferenceTask | undefined;
    getDeployedModels(): ModelDeployment[];
    getCacheStats(): CacheStats;
    invalidateCache(pattern?: string): Promise<number>;
    warmupModelCache(modelId: string, samples: Array<Record<string, unknown>>): Promise<{
        warmed: number;
    }>;
    getQueueStats(): {
        pending: number;
        running: number;
        completed: number;
        workers: {
            total: number;
            active: number;
            idle: number;
        };
        queues: Array<{
            priority: number;
            size: number;
            concurrency: number;
        }>;
        cache?: CacheStats;
    };
    getWorkerStats(): Array<{
        workerId: string;
        status: Worker['status'];
        completedTasks: number;
        currentTaskId?: string;
    }>;
    cancelTask(taskId: string): boolean;
    stop(): Promise<void>;
}
export default EdgeInferenceScheduler;
//# sourceMappingURL=index.d.ts.map