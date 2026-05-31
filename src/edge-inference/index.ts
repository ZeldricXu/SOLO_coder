import { v4 as uuidv4 } from 'uuid';
import logger from '../common/logger';
import { TaskStatus, ProcessingContext } from '../types';
import { Semaphore, sleep } from '../common/utils';

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

class LRUCache<K, V> {
  private cache: Map<K, { value: V; createdAt: number; lastAccessedAt: number; accessCount: number; ttlMs: number; size: number }> = new Map();
  private maxSize: number;
  private strategy: 'lru' | 'lfu' | 'fifo';

  constructor(maxSize: number, strategy: 'lru' | 'lfu' | 'fifo' = 'lru') {
    this.maxSize = maxSize;
    this.strategy = strategy;
  }

  get(key: K): V | undefined {
    const entry = this.cache.get(key);
    if (!entry) return undefined;

    if (Date.now() - entry.createdAt > entry.ttlMs) {
      this.cache.delete(key);
      return undefined;
    }

    entry.lastAccessedAt = Date.now();
    entry.accessCount++;
    return entry.value;
  }

  set(key: K, value: V, ttlMs: number, size: number = 1): void {
    if (this.cache.size >= this.maxSize) {
      this.evict();
    }
    this.cache.set(key, {
      value,
      createdAt: Date.now(),
      lastAccessedAt: Date.now(),
      accessCount: 0,
      ttlMs,
      size
    });
  }

  private evict(): void {
    if (this.cache.size === 0) return;

    let keyToEvict: K | undefined;

    if (this.strategy === 'lru') {
      let oldestAccessTime = Infinity;
      for (const [key, entry] of this.cache) {
        if (entry.lastAccessedAt < oldestAccessTime) {
          oldestAccessTime = entry.lastAccessedAt;
          keyToEvict = key;
        }
      }
    } else if (this.strategy === 'lfu') {
      let minAccessCount = Infinity;
      for (const [key, entry] of this.cache) {
        if (entry.accessCount < minAccessCount) {
          minAccessCount = entry.accessCount;
          keyToEvict = key;
        }
      }
    } else {
      keyToEvict = this.cache.keys().next().value;
    }

    if (keyToEvict !== undefined) {
      this.cache.delete(keyToEvict);
    }
  }

  delete(key: K): boolean {
    return this.cache.delete(key);
  }

  has(key: K): boolean {
    const entry = this.cache.get(key);
    if (!entry) return false;
    if (Date.now() - entry.createdAt > entry.ttlMs) {
      this.cache.delete(key);
      return false;
    }
    return true;
  }

  size(): number {
    return this.cache.size;
  }

  clear(): void {
    this.cache.clear();
  }

  keys(): K[] {
    return Array.from(this.cache.keys());
  }

  cleanupExpired(): number {
    let removed = 0;
    for (const [key, entry] of this.cache) {
      if (Date.now() - entry.createdAt > entry.ttlMs) {
        this.cache.delete(key);
        removed++;
      }
    }
    return removed;
  }
}

class MockDistributedCacheClient implements DistributedCacheClient {
  private data: Map<string, { value: Record<string, unknown>; expiresAt: number }> = new Map();
  private maxSize: number;

  constructor(maxSize: number = 10000) {
    this.maxSize = maxSize;
  }

  async get(key: string): Promise<Record<string, unknown> | undefined> {
    const entry = this.data.get(key);
    if (!entry) return undefined;
    if (Date.now() > entry.expiresAt) {
      this.data.delete(key);
      return undefined;
    }
    return entry.value;
  }

  async set(key: string, value: Record<string, unknown>, ttlMs: number): Promise<void> {
    if (this.data.size >= this.maxSize) {
      const firstKey = this.data.keys().next().value;
      if (firstKey !== undefined) {
        this.data.delete(firstKey);
      }
    }
    this.data.set(key, {
      value,
      expiresAt: Date.now() + ttlMs
    });
  }

  async delete(key: string): Promise<void> {
    this.data.delete(key);
  }

  async clear(): Promise<void> {
    this.data.clear();
  }

  async keys(pattern?: string): Promise<string[]> {
    const allKeys = Array.from(this.data.keys());
    if (!pattern) return allKeys;
    const regex = new RegExp(pattern);
    return allKeys.filter(function(k) { return regex.test(k); });
  }

  size(): number {
    return this.data.size;
  }

  cleanupExpired(): number {
    let removed = 0;
    for (const [key, entry] of this.data) {
      if (Date.now() > entry.expiresAt) {
        this.data.delete(key);
        removed++;
      }
    }
    return removed;
  }
}

export class EdgeInferenceScheduler {
  private queues: Map<number, TaskQueue> = new Map();
  private runningTasks: Map<string, InferenceTask> = new Map();
  private completedTasks: Map<string, InferenceTask> = new Map();
  private deployedModels: Map<string, ModelDeployment> = new Map();
  private workers: Map<string, Worker> = new Map();
  private batchCollectors: Map<string, { tasks: InferenceTask[]; timeout: NodeJS.Timeout }> = new Map();
  private config: InferenceConfig;
  private onTaskComplete?: (task: InferenceTask) => void;
  private onBatchComplete?: (batch: BatchInferenceResult) => void;
  private gpuSemaphore?: Semaphore;
  private isProcessing: boolean = false;
  private activeWorkers: number = 0;

  private l1Cache: LRUCache<string, Record<string, unknown>>;
  private l2Cache: DistributedCacheClient;
  private l1CacheStats: { hits: number; misses: number } = { hits: 0, misses: 0 };
  private l2CacheStats: { hits: number; misses: number } = { hits: 0, misses: 0 };
  private cacheCleanupTimer?: NodeJS.Timeout;
  private warmupInProgress: Set<string> = new Set();

  constructor(config: Partial<InferenceConfig> = {}) {
    this.config = {
      maxConcurrentTasks: config.maxConcurrentTasks ?? 50,
      taskTimeoutMs: config.taskTimeoutMs ?? 300000,
      retryCount: config.retryCount ?? 3,
      enableLocalCaching: config.enableLocalCaching ?? true,
      enableBatching: config.enableBatching ?? true,
      maxBatchSize: config.maxBatchSize ?? 32,
      batchTimeoutMs: config.batchTimeoutMs ?? 100,
      priorityLevels: config.priorityLevels ?? 5,
      workerPool: {
        minWorkers: config.workerPool?.minWorkers ?? 2,
        maxWorkers: config.workerPool?.maxWorkers ?? 20,
        idleTimeoutMs: config.workerPool?.idleTimeoutMs ?? 60000,
        maxQueueSize: config.workerPool?.maxQueueSize ?? 1000
      },
      enableGpuScheduling: config.enableGpuScheduling ?? false,
      gpuConcurrency: config.gpuConcurrency ?? 4,
      cache: {
        l1Enabled: config.cache?.l1Enabled ?? true,
        l1MaxSize: config.cache?.l1MaxSize ?? 1000,
        l1TtlMs: config.cache?.l1TtlMs ?? 300000,
        l2Enabled: config.cache?.l2Enabled ?? true,
        l2MaxSize: config.cache?.l2MaxSize ?? 10000,
        l2TtlMs: config.cache?.l2TtlMs ?? 3600000,
        warmupEnabled: config.cache?.warmupEnabled ?? false,
        warmupModels: config.cache?.warmupModels ?? [],
        invalidationStrategy: config.cache?.invalidationStrategy ?? 'lru'
      }
    };

    this.l1Cache = new LRUCache<string, Record<string, unknown>>(
      this.config.cache.l1MaxSize,
      this.config.cache.invalidationStrategy
    );

    this.l2Cache = new MockDistributedCacheClient(this.config.cache.l2MaxSize);

    this.initializeQueues();
    this.initializeWorkerPool();
    this.initializeCacheCleanup();

    if (this.config.enableGpuScheduling) {
      this.gpuSemaphore = new Semaphore(this.config.gpuConcurrency);
    }

    if (this.config.cache.warmupEnabled) {
      this.warmupCache().catch(function(e) { logger.error({ e: e }, '缓存预热失败'); });
    }
  }

  private initializeQueues(): void {
    for (let i = 0; i < this.config.priorityLevels; i++) {
      const concurrency = this.calculateConcurrencyForPriority(i);
      this.queues.set(i, {
        name: 'priority-' + i,
        priority: i,
        tasks: [],
        concurrency,
        semaphore: new Semaphore(concurrency)
      });
    }
    logger.info({ priorityLevels: this.config.priorityLevels }, '多优先级队列已初始化');
  }

  private calculateConcurrencyForPriority(priority: number): number {
    const baseConcurrency = Math.ceil(this.config.maxConcurrentTasks / this.config.priorityLevels);
    const multiplier = 1 + (this.config.priorityLevels - 1 - priority) * 0.3;
    return Math.max(1, Math.floor(baseConcurrency * multiplier));
  }

  private initializeWorkerPool(): void {
    for (let i = 0; i < this.config.workerPool.minWorkers; i++) {
      this.createWorker();
    }
    logger.info({ workerCount: this.config.workerPool.minWorkers }, '工作池已初始化');
  }

  private initializeCacheCleanup(): void {
    const self = this;
    this.cacheCleanupTimer = setInterval(function() {
      const l1Expired = self.l1Cache.cleanupExpired();
      if (l1Expired > 0) {
        logger.debug({ count: l1Expired }, 'L1缓存过期清理完成');
      }
    }, 60000);
  }

  private async warmupCache(): Promise<void> {
    if (this.config.cache.warmupModels.length === 0) return;

    logger.info({ models: this.config.cache.warmupModels }, '开始缓存预热');

    for (let i = 0; i < this.config.cache.warmupModels.length; i++) {
      const modelId = this.config.cache.warmupModels[i];
      if (this.warmupInProgress.has(modelId)) continue;
      this.warmupInProgress.add(modelId);

      try {
        const warmupInput = { warmup: true, timestamp: Date.now() };
        const cacheKey = this.generateCacheKey(modelId, warmupInput);
        
        if (!this.l1Cache.has(cacheKey)) {
          const mockResult = {
            modelId: modelId,
            predictions: [],
            inferenceTimeMs: 0,
            timestamp: new Date().toISOString(),
            warmed: true
          };
          this.l1Cache.set(cacheKey, mockResult, this.config.cache.l1TtlMs);
        }
      } catch (e) {
        logger.error({ modelId: modelId, e: e }, '模型预热失败');
      } finally {
        this.warmupInProgress.delete(modelId);
      }
    }

    logger.info({ models: this.config.cache.warmupModels }, '缓存预热完成');
  }

  private generateCacheKey(modelId: string, inputData: Record<string, unknown>): string {
    const inputHash = this.hashInput(inputData);
    return 'inference:' + modelId + ':' + inputHash;
  }

  private hashInput(inputData: Record<string, unknown>): string {
    let hash = 0;
    const str = JSON.stringify(inputData);
    for (let i = 0; i < str.length; i++) {
      const char = str.charCodeAt(i);
      hash = ((hash << 5) - hash) + char;
      hash = hash & hash;
    }
    return Math.abs(hash).toString(36);
  }

  private createWorker(): Worker {
    const workerId = uuidv4();
    const worker: Worker = {
      workerId: workerId,
      status: 'idle',
      completedTasks: 0,
      startedAt: new Date().toISOString()
    };
    this.workers.set(workerId, worker);
    this.startWorkerLoop(worker);
    return worker;
  }

  private async startWorkerLoop(worker: Worker): Promise<void> {
    while (worker.status !== 'stopped') {
      try {
        const task = this.getNextTask();
        if (task) {
          worker.status = 'busy';
          worker.currentTask = task;
          this.activeWorkers++;
          await this.executeTaskWithWorker(task, worker);
          worker.completedTasks++;
          worker.currentTask = undefined;
          worker.status = 'idle';
          this.activeWorkers--;
        } else {
          await sleep(10);
        }
      } catch (error) {
        logger.error({ workerId: worker.workerId, error: error }, '工作线程异常');
        worker.status = 'idle';
        worker.currentTask = undefined;
        this.activeWorkers--;
        await sleep(100);
      }
    }
  }

  private getNextTask(): InferenceTask | null {
    const priorities = Array.from(this.queues.keys()).sort(function(a, b) { return b - a; });

    for (let i = 0; i < priorities.length; i++) {
      const priority = priorities[i];
      const queue = this.queues.get(priority)!;
      if (queue.tasks.length > 0 && queue.semaphore.availablePermits > 0) {
        return queue.tasks.shift() || null;
      }
    }

    return null;
  }

  setTaskCompleteCallback(callback: (task: InferenceTask) => void): void {
    this.onTaskComplete = callback;
  }

  setBatchCompleteCallback(callback: (batch: BatchInferenceResult) => void): void {
    this.onBatchComplete = callback;
  }

  async deployModel(
    modelId: string,
    version: string,
    edgeNodeId: string,
    resources: ModelDeployment['resources'],
    options?: { maxBatchSize?: number; supportedBatch?: boolean }
  ): Promise<ModelDeployment> {
    const deployment: ModelDeployment = {
      modelId: modelId,
      version: version,
      edgeNodeId: edgeNodeId,
      status: 'deploying',
      deployedAt: new Date().toISOString(),
      resources: resources,
      maxBatchSize: options?.maxBatchSize ?? 32,
      supportedBatch: options?.supportedBatch ?? true
    };

    logger.info({ modelId: modelId, version: version, edgeNodeId: edgeNodeId, resources: resources }, '开始部署AI模型');
    this.deployedModels.set(modelId, deployment);

    try {
      await this.simulateDeployment();
      deployment.status = 'active';
      logger.info({ modelId: modelId, gpuEnabled: resources.gpuEnabled }, '模型部署成功');
    } catch (error) {
      deployment.status = 'failed';
      logger.error({ modelId: modelId, error: error }, '模型部署失败');
      throw error;
    }

    return deployment;
  }

  async undeployModel(modelId: string): Promise<void> {
    const deployment = this.deployedModels.get(modelId);
    if (!deployment) {
      logger.warn({ modelId: modelId }, '模型未找到，无法卸载');
      return;
    }

    deployment.status = 'undeploying';
    logger.info({ modelId: modelId }, '开始卸载模型');
    await new Promise(function(resolve) { setTimeout(resolve, 1000); });
    this.deployedModels.delete(modelId);
    logger.info({ modelId: modelId }, '模型卸载完成');
  }

  submitInferenceTask(
    modelId: string,
    inputData: Record<string, unknown>,
    priority: number = 5,
    callbackUrl?: string,
    options?: { affinity?: string; batchId?: string; skipCache?: boolean }
  ): InferenceTask {
    const normalizedPriority = Math.max(0, Math.min(this.config.priorityLevels - 1, priority));
    const queue = this.queues.get(normalizedPriority);

    if (!queue) {
      throw new Error('无效的优先级: ' + priority);
    }

    if (queue.tasks.length >= this.config.workerPool.maxQueueSize) {
      throw new Error('任务队列已满，请稍后重试');
    }

    const task: InferenceTask = {
      taskId: uuidv4(),
      modelId: modelId,
      inputData: inputData,
      priority: normalizedPriority,
      status: TaskStatus.PENDING,
      createdAt: new Date().toISOString(),
      callbackUrl: callbackUrl,
      batchId: options?.batchId,
      affinity: options?.affinity,
      retries: 0,
      maxRetries: this.config.retryCount
    };

    queue.tasks.push(task);
    queue.tasks.sort(function(a, b) { return b.priority - a.priority; });

    logger.info({ taskId: task.taskId, modelId: modelId, priority: normalizedPriority, queueSize: queue.tasks.length }, '推理任务已提交');

    if (this.config.enableBatching && this.shouldBatch(modelId)) {
      this.addToBatchCollector(task);
    }

    this.ensureProcessing();
    this.adjustWorkerPool();

    return task;
  }

  async submitInferenceTaskWithCache(
    modelId: string,
    inputData: Record<string, unknown>,
    priority: number = 5,
    callbackUrl?: string,
    options?: { affinity?: string; skipCache?: boolean }
  ): Promise<{ task?: InferenceTask; cachedResult?: Record<string, unknown>; cacheSource?: 'l1' | 'l2' }> {
    if (!options?.skipCache && this.config.cache.l1Enabled) {
      const cacheKey = this.generateCacheKey(modelId, inputData);

      if (this.l1Cache.has(cacheKey)) {
        this.l1CacheStats.hits++;
        const cachedValue = this.l1Cache.get(cacheKey);
        if (cachedValue) {
          logger.debug({ taskId: 'cache-hit-l1', modelId: modelId, cacheKey: cacheKey }, 'L1缓存命中');
          return { cachedResult: cachedValue, cacheSource: 'l1' };
        }
      }

      this.l1CacheStats.misses++;

      if (this.config.cache.l2Enabled) {
        try {
          const l2Result = await this.l2Cache.get(cacheKey);
          if (l2Result) {
            this.l2CacheStats.hits++;
            this.l1Cache.set(cacheKey, l2Result, this.config.cache.l1TtlMs);
            logger.debug({ taskId: 'cache-hit-l2', modelId: modelId, cacheKey: cacheKey }, 'L2缓存命中');
            return { cachedResult: l2Result, cacheSource: 'l2' };
          }
          this.l2CacheStats.misses++;
        } catch (e) {
          logger.warn({ e: e }, 'L2缓存访问失败');
        }
      }
    }

    const task = this.submitInferenceTask(modelId, inputData, priority, callbackUrl, options);
    return { task: task };
  }

  async submitBatchInference(request: BatchInferenceRequest): Promise<{ batchId: string; tasks: InferenceTask[] }> {
    const batchId = uuidv4();
    const priority = request.priority ?? 5;
    const tasks: InferenceTask[] = [];

    for (let i = 0; i < request.inputs.length; i++) {
      const input = request.inputs[i];
      const task = this.submitInferenceTask(
        request.modelId,
        input,
        priority,
        request.callbackUrl,
        { batchId: batchId }
      );
      tasks.push(task);
    }

    logger.info({ batchId: batchId, modelId: request.modelId, taskCount: tasks.length }, '批量推理任务已提交');
    return { batchId: batchId, tasks: tasks };
  }

  private shouldBatch(modelId: string): boolean {
    const model = this.deployedModels.get(modelId);
    return model?.supportedBatch ?? false;
  }

  private addToBatchCollector(task: InferenceTask): void {
    const model = this.deployedModels.get(task.modelId);
    if (!model) return;

    const collectorKey = task.modelId;
    let collector = this.batchCollectors.get(collectorKey);

    if (!collector) {
      const self = this;
      collector = {
        tasks: [],
        timeout: setTimeout(function() { self.flushBatch(collectorKey); }, this.config.batchTimeoutMs)
      };
      this.batchCollectors.set(collectorKey, collector);
    }

    collector.tasks.push(task);

    const maxBatchSize = Math.min(model.maxBatchSize ?? this.config.maxBatchSize, this.config.maxBatchSize);
    if (collector.tasks.length >= maxBatchSize) {
      this.flushBatch(collectorKey);
    }
  }

  private async flushBatch(collectorKey: string): Promise<void> {
    const collector = this.batchCollectors.get(collectorKey);
    if (!collector || collector.tasks.length === 0) {
      this.batchCollectors.delete(collectorKey);
      return;
    }

    clearTimeout(collector.timeout);
    this.batchCollectors.delete(collectorKey);

    const tasks = collector.tasks;
    const batchId = tasks[0].batchId ?? uuidv4();
    const modelId = tasks[0].modelId;

    logger.debug({ batchId: batchId, modelId: modelId, taskCount: tasks.length }, '执行批量推理');

    try {
      const results = await this.executeBatchInference(modelId, tasks.map(function(t) { return t.inputData; }));

      const startTime = Date.now();
      const batchResult: BatchInferenceResult = {
        batchId: batchId,
        modelId: modelId,
        results: tasks.map(function(task, index) {
          return {
            taskId: task.taskId,
            success: true,
            result: results[index]
          };
        }),
        completedAt: new Date().toISOString(),
        totalTimeMs: Date.now() - startTime
      };

      for (let i = 0; i < tasks.length; i++) {
        const task = tasks[i];
        task.status = TaskStatus.COMPLETED;
        task.result = results[i];
        task.completedAt = new Date().toISOString();
        this.completedTasks.set(task.taskId, task);
        this.onTaskComplete?.(task);
      }

      this.onBatchComplete?.(batchResult);
    } catch (error) {
      const errorMessage = error instanceof Error ? error.message : String(error);
      for (let i = 0; i < tasks.length; i++) {
        const task = tasks[i];
        task.status = TaskStatus.FAILED;
        task.error = errorMessage;
        task.completedAt = new Date().toISOString();
        this.completedTasks.set(task.taskId, task);
        this.onTaskComplete?.(task);
      }
    }
  }

  private async executeBatchInference(
    modelId: string,
    inputs: Array<Record<string, unknown>>
  ): Promise<Array<Record<string, unknown>>> {
    const deployment = this.deployedModels.get(modelId);
    if (!deployment || deployment.status !== 'active') {
      throw new Error('模型 ' + modelId + ' 未部署或不可用');
    }

    if (deployment.resources.gpuEnabled && this.gpuSemaphore) {
      await this.gpuSemaphore.acquire();
      try {
        await new Promise(function(resolve) { setTimeout(resolve, 50 + Math.random() * 200); });
      } finally {
        this.gpuSemaphore.release();
      }
    } else {
      await new Promise(function(resolve) { setTimeout(resolve, 100 + Math.random() * 300); });
    }

    const self = this;
    const results: Array<Record<string, unknown>> = inputs.map(function(input, index) {
      return {
        taskId: 'batch-' + index,
        modelId: modelId,
        predictions: [
          { class: 'object_' + index, confidence: 0.85 + Math.random() * 0.15, boundingBox: [0.1, 0.2, 0.3, 0.4] }
        ],
        inferenceTimeMs: 15.5 + Math.random() * 10,
        timestamp: new Date().toISOString(),
        inputHash: JSON.stringify(input).length
      };
    });

    if (this.config.cache.l1Enabled) {
      for (let i = 0; i < inputs.length; i++) {
        const cacheKey = this.generateCacheKey(modelId, inputs[i]);
        this.l1Cache.set(cacheKey, results[i], this.config.cache.l1TtlMs);
      }
    }

    return results;
  }

  private ensureProcessing(): void {
    if (!this.isProcessing) {
      this.isProcessing = true;
    }
  }

  private adjustWorkerPool(): void {
    const totalPending = Array.from(this.queues.values()).reduce(function(sum, q) { return sum + q.tasks.length; }, 0);
    const desiredWorkers = Math.min(
      this.config.workerPool.maxWorkers,
      Math.max(
        this.config.workerPool.minWorkers,
        Math.ceil(totalPending / 10)
      )
    );

    while (this.workers.size < desiredWorkers) {
      this.createWorker();
    }
  }

  private async executeTaskWithWorker(task: InferenceTask, worker: Worker): Promise<void> {
    const queue = this.queues.get(task.priority);
    if (queue) {
      await queue.semaphore.acquire();
    }

    try {
      this.runningTasks.set(task.taskId, task);
      task.status = TaskStatus.RUNNING;
      task.startedAt = new Date().toISOString();

      logger.info({ taskId: task.taskId, modelId: task.modelId, workerId: worker.workerId }, '开始执行推理任务');

      const self = this;
      const timeoutPromise = new Promise<never>(function(_resolve: (value: never) => void, reject: (reason?: unknown) => void) {
        setTimeout(function() { reject(new Error('任务超时 (' + self.config.taskTimeoutMs + 'ms)')); }, self.config.taskTimeoutMs);
      });

      const result = await Promise.race([this.runInference(task), timeoutPromise]);
      task.status = TaskStatus.COMPLETED;
      task.result = result;
      task.completedAt = new Date().toISOString();

      logger.info({ taskId: task.taskId, workerId: worker.workerId }, '推理任务完成');

      if (task.callbackUrl) {
        this.sendCallback(task).catch(function(e) { logger.error({ taskId: task.taskId, e: e }, '回调发送失败'); });
      }

      this.completedTasks.set(task.taskId, task);
      this.onTaskComplete?.(task);
    } catch (error) {
      const errorMessage = error instanceof Error ? error.message : String(error);
      task.retries = (task.retries ?? 0) + 1;

      if (task.retries < (task.maxRetries ?? this.config.retryCount)) {
        logger.warn({ taskId: task.taskId, retries: task.retries, error: errorMessage }, '任务失败，准备重试');
        task.status = TaskStatus.PENDING;
        const queue = this.queues.get(task.priority);
        if (queue) {
          queue.tasks.push(task);
        }
      } else {
        task.status = TaskStatus.FAILED;
        task.error = errorMessage;
        task.completedAt = new Date().toISOString();
        logger.error({ taskId: task.taskId, retries: task.retries, error: errorMessage }, '推理任务失败，已达最大重试次数');
        this.completedTasks.set(task.taskId, task);
        this.onTaskComplete?.(task);
      }
    } finally {
      this.runningTasks.delete(task.taskId);
      if (queue) {
        queue.semaphore.release();
      }
    }
  }

  private async runInference(task: InferenceTask): Promise<Record<string, unknown>> {
    const deployment = this.deployedModels.get(task.modelId);
    if (!deployment || deployment.status !== 'active') {
      throw new Error('模型 ' + task.modelId + ' 未部署或不可用');
    }

    const cacheKey = this.generateCacheKey(task.modelId, task.inputData);

    if (this.config.cache.l1Enabled && this.l1Cache.has(cacheKey)) {
      this.l1CacheStats.hits++;
      const cached = this.l1Cache.get(cacheKey);
      if (cached) {
        logger.debug({ taskId: task.taskId, cacheKey: cacheKey }, 'L1缓存命中');
        return cached;
      }
    }

    this.l1CacheStats.misses++;

    if (this.config.cache.l2Enabled) {
      try {
        const l2Cached = await this.l2Cache.get(cacheKey);
        if (l2Cached) {
          this.l2CacheStats.hits++;
          this.l1Cache.set(cacheKey, l2Cached, this.config.cache.l1TtlMs);
          logger.debug({ taskId: task.taskId, cacheKey: cacheKey }, 'L2缓存命中');
          return l2Cached;
        }
        this.l2CacheStats.misses++;
      } catch (e) {
        logger.warn({ taskId: task.taskId, e: e }, 'L2缓存访问失败');
      }
    }

    if (deployment.resources.gpuEnabled && this.gpuSemaphore) {
      await this.gpuSemaphore.acquire();
      try {
        await new Promise(function(resolve) { setTimeout(resolve, 50 + Math.random() * 200); });
      } finally {
        this.gpuSemaphore.release();
      }
    } else {
      await new Promise(function(resolve) { setTimeout(resolve, 80 + Math.random() * 250); });
    }

    const result = {
      taskId: task.taskId,
      modelId: task.modelId,
      predictions: [
        { class: 'object_1', confidence: 0.95, boundingBox: [0.1, 0.2, 0.3, 0.4] },
        { class: 'object_2', confidence: 0.87, boundingBox: [0.5, 0.5, 0.7, 0.8] }
      ],
      inferenceTimeMs: 42.5,
      timestamp: new Date().toISOString(),
      workerId: this.getWorkerForTask(task.taskId)?.workerId
    };

    if (this.config.cache.l1Enabled) {
      this.l1Cache.set(cacheKey, result, this.config.cache.l1TtlMs);
    }

    if (this.config.cache.l2Enabled) {
      try {
        await this.l2Cache.set(cacheKey, result, this.config.cache.l2TtlMs);
      } catch (e) {
        logger.warn({ e: e }, 'L2缓存写入失败');
      }
    }

    return result;
  }

  private getWorkerForTask(taskId: string): Worker | undefined {
    for (const worker of this.workers.values()) {
      if (worker.currentTask?.taskId === taskId) {
        return worker;
      }
    }
    return undefined;
  }

  private async sendCallback(task: InferenceTask): Promise<void> {
    if (!task.callbackUrl) return;
    logger.info({ taskId: task.taskId, callbackUrl: task.callbackUrl }, '发送任务完成回调');
  }

  private async simulateDeployment(): Promise<void> {
    await new Promise(function(resolve) { setTimeout(resolve, 2000); });
  }

  getTaskStatus(taskId: string): InferenceTask | undefined {
    return this.runningTasks.get(taskId) ||
           this.completedTasks.get(taskId) ||
           Array.from(this.queues.values())
             .flatMap(function(q) { return q.tasks; })
             .find(function(t) { return t.taskId === taskId; });
  }

  getDeployedModels(): ModelDeployment[] {
    return Array.from(this.deployedModels.values());
  }

  getCacheStats(): CacheStats {
    const l1Total = this.l1CacheStats.hits + this.l1CacheStats.misses;
    const l2Total = this.l2CacheStats.hits + this.l2CacheStats.misses;
    return {
      l1: {
        hits: this.l1CacheStats.hits,
        misses: this.l1CacheStats.misses,
        size: this.l1Cache.size(),
        maxSize: this.config.cache.l1MaxSize,
        hitRate: l1Total > 0 ? this.l1CacheStats.hits / l1Total : 0
      },
      l2: {
        hits: this.l2CacheStats.hits,
        misses: this.l2CacheStats.misses,
        size: this.l2Cache instanceof MockDistributedCacheClient ? this.l2Cache.size() : 0,
        maxSize: this.config.cache.l2MaxSize,
        hitRate: l2Total > 0 ? this.l2CacheStats.hits / l2Total : 0
      }
    };
  }

  async invalidateCache(pattern?: string): Promise<number> {
    let invalidated = 0;

    if (pattern) {
      const regex = new RegExp(pattern);
      const l1Keys = this.l1Cache.keys();
      for (let i = 0; i < l1Keys.length; i++) {
        const key = l1Keys[i];
        if (regex.test(key)) {
          this.l1Cache.delete(key);
          invalidated++;
        }
      }
      if (this.config.cache.l2Enabled) {
        const l2Keys = await this.l2Cache.keys(pattern);
        for (let i = 0; i < l2Keys.length; i++) {
          const key = l2Keys[i];
          await this.l2Cache.delete(key);
          invalidated++;
        }
      }
    } else {
      invalidated += this.l1Cache.size();
      this.l1Cache.clear();
      if (this.config.cache.l2Enabled) {
        await this.l2Cache.clear();
      }
    }

    logger.info({ count: invalidated, pattern: pattern }, '缓存失效完成');
    return invalidated;
  }

  async warmupModelCache(modelId: string, samples: Array<Record<string, unknown>>): Promise<{ warmed: number }> {
    if (!this.config.cache.l1Enabled) return { warmed: 0 };

    logger.info({ modelId: modelId, sampleCount: samples.length }, '开始预热模型缓存');
    let warmed = 0;

    for (let i = 0; i < samples.length; i++) {
      const sample = samples[i];
      const cacheKey = this.generateCacheKey(modelId, sample);
      if (!this.l1Cache.has(cacheKey)) {
        try {
          const mockResult = {
            modelId: modelId,
            predictions: [],
            inferenceTimeMs: 0,
            timestamp: new Date().toISOString(),
            warmed: true
          };
          this.l1Cache.set(cacheKey, mockResult, this.config.cache.l1TtlMs);
          warmed++;
        } catch (e) {
          logger.warn({ modelId: modelId, e: e }, '样本预热失败');
        }
      }
    }

    logger.info({ modelId: modelId, warmed: warmed }, '模型缓存预热完成');
    return { warmed: warmed };
  }

  getQueueStats(): {
    pending: number;
    running: number;
    completed: number;
    workers: { total: number; active: number; idle: number };
    queues: Array<{ priority: number; size: number; concurrency: number }>;
    cache?: CacheStats;
  } {
    const pending = Array.from(this.queues.values()).reduce(function(sum, q) { return sum + q.tasks.length; }, 0);
    const queueStats = Array.from(this.queues.values()).map(function(q) {
      return {
        priority: q.priority,
        size: q.tasks.length,
        concurrency: q.concurrency
      };
    });

    const workers = Array.from(this.workers.values());
    const activeWorkers = workers.filter(function(w) { return w.status === 'busy'; }).length;
    const idleWorkers = workers.filter(function(w) { return w.status === 'idle'; }).length;

    return {
      pending: pending,
      running: this.runningTasks.size,
      completed: this.completedTasks.size,
      workers: {
        total: workers.length,
        active: activeWorkers,
        idle: idleWorkers
      },
      queues: queueStats,
      cache: this.getCacheStats()
    };
  }

  getWorkerStats(): Array<{
    workerId: string;
    status: Worker['status'];
    completedTasks: number;
    currentTaskId?: string;
  }> {
    return Array.from(this.workers.values()).map(function(w) {
      return {
        workerId: w.workerId,
        status: w.status,
        completedTasks: w.completedTasks,
        currentTaskId: w.currentTask?.taskId
      };
    });
  }

  cancelTask(taskId: string): boolean {
    for (const queue of this.queues.values()) {
      const index = queue.tasks.findIndex(function(t) { return t.taskId === taskId; });
      if (index > -1) {
        queue.tasks.splice(index, 1);
        const task = this.completedTasks.get(taskId) || queue.tasks[index];
        if (task) {
          task.status = TaskStatus.CANCELLED;
          this.completedTasks.set(taskId, task);
        }
        logger.info({ taskId: taskId }, '任务已取消');
        return true;
      }
    }
    return false;
  }

  async stop(): Promise<void> {
    for (const worker of this.workers.values()) {
      worker.status = 'stopped';
    }

    for (const collector of this.batchCollectors.values()) {
      clearTimeout(collector.timeout);
    }
    this.batchCollectors.clear();

    if (this.cacheCleanupTimer) {
      clearInterval(this.cacheCleanupTimer);
    }

    logger.info({ workerCount: this.workers.size }, '推理调度器已停止');
  }
}

export default EdgeInferenceScheduler;
