import { Entity, HandlerContext, ProcessingResult, RunInstance } from '../../types';
import logger from '../../utils/logger';
import { eventBus } from '../../utils/eventBus';
import { ResourcePool } from '../../utils/resourcePool';
import { configManager } from '../config';
import { currentTimestamp, generateId, retry, sleep } from '../../utils/helpers';
import {
  TransformRule,
  StandardizationConfig,
  ProcessingConfig,
  ProcessRequest,
  CacheConfig,
  CacheStats,
  WarmupResult,
} from './types';
import { ITransformer, ICacheService, IConfigLoader, IPayloadHasher, IProcessor } from './interfaces';
import { LRUCache } from './LRUCache';
import { Transformer } from './Transformer';
import { Processor } from './Processor';

export {
  TransformRule,
  StandardizationConfig,
  ProcessingConfig,
  ProcessRequest,
  CacheConfig,
  CacheStats,
  WarmupResult,
};

class Worker {
  async process(data: any): Promise<any> {
    return data;
  }
  terminate(): void {}
}

class PayloadHasher implements IPayloadHasher {
  private hashCache: Map<string, string> = new Map();
  private readonly maxCacheSize: number = 10000;

  constructor(private cacheByNamespace: boolean = true) {}

  computeHash(payload: any): string {
    const str = typeof payload === 'string' ? payload : JSON.stringify(payload);
    
    const cached = this.hashCache.get(str);
    if (cached !== undefined) {
      return cached;
    }

    let hash = 0;
    for (let i = 0; i < str.length; i++) {
      const char = str.charCodeAt(i);
      hash = ((hash << 5) - hash) + char;
      hash = hash & hash;
    }
    const hashStr = String(hash);
    
    if (this.hashCache.size >= this.maxCacheSize) {
      const firstKey = this.hashCache.keys().next().value;
      if (firstKey !== undefined) {
        this.hashCache.delete(firstKey);
      }
    }
    this.hashCache.set(str, hashStr);
    
    return hashStr;
  }

  buildCacheKey(namespace: string, identifier: string): string {
    return this.cacheByNamespace ? `${namespace}:${identifier}` : identifier;
  }
}

class ConfigLoader implements IConfigLoader {
  private cacheConfig: CacheConfig;

  constructor() {
    this.cacheConfig = {
      enabled: configManager.getParameter('processing.cache.enabled', 'core', true),
      maxSize: configManager.getParameter('processing.cache.maxSize', 'core', 10000),
      ttl: configManager.getParameter('processing.cache.ttl', 'core', 3600000),
      warmupOnStartup: configManager.getParameter('processing.cache.warmupOnStartup', 'core', true),
      warmupKeys: configManager.getParameter('processing.cache.warmupKeys', 'core', []),
      cacheByNamespace: configManager.getParameter('processing.cache.cacheByNamespace', 'core', true),
      hotDataThreshold: configManager.getParameter('processing.cache.hotDataThreshold', 'core', 100),
    };
  }

  loadConfig(namespace: string): ProcessingConfig {
    const config = configManager.getConfig(namespace) || configManager.getConfig('core');
    const defaultConfig: ProcessingConfig = {
      poolSize: 10,
      timeout: 30000,
      retries: 3,
      rules: [],
      standardization: {
        charset: 'utf-8',
        dateFormat: 'ISO',
        timezone: 'UTC',
        decimalPlaces: 2,
        trimWhitespace: true,
        nullHandling: 'keep',
      },
      batchSize: 100,
      concurrency: 5,
    };
    return { ...defaultConfig, ...config?.parameters } as ProcessingConfig;
  }

  getCacheConfig(): CacheConfig {
    return this.cacheConfig;
  }

  updateWarmupKeys(keys: string[]): void {
    this.cacheConfig.warmupKeys = keys;
  }
}

export class CoreProcessor {
  private resourcePool: ResourcePool<Worker>;
  private runInstances: Map<string, RunInstance> = new Map();
  private metrics = {
    totalProcessed: 0,
    successCount: 0,
    failureCount: 0,
    totalLatency: 0,
  };
  private resultCache: ICacheService;
  private processedItemCache: ICacheService;
  private configLoader: IConfigLoader;
  private payloadHasher: IPayloadHasher;
  private transformer: ITransformer;
  private processor: IProcessor;
  private warmupCompleted: boolean = false;

  constructor() {
    const poolSize = configManager.getParameter('processing.poolSize', 'core', 10);
    this.resourcePool = new ResourcePool<Worker>(
      poolSize,
      async () => new Worker(),
      async (worker) => worker.terminate(),
    );

    this.configLoader = new ConfigLoader();
    const cacheConfig = this.configLoader.getCacheConfig();
    
    this.resultCache = new LRUCache<string, any>(cacheConfig.maxSize, cacheConfig.ttl);
    this.processedItemCache = new LRUCache<string, any>(cacheConfig.maxSize, cacheConfig.ttl);
    this.payloadHasher = new PayloadHasher(cacheConfig.cacheByNamespace);
    this.transformer = new Transformer();
    this.processor = new Processor(this.transformer);

    logger.info('CoreProcessor initialized', { poolSize, cacheConfig });
  }

  async initialize(): Promise<void> {
    logger.info('CoreProcessor ready');
    
    if (this.configLoader.getCacheConfig().warmupOnStartup) {
      await this.warmupCache();
    }

    this.startHotDataMonitor();
  }

  private async warmupCache(): Promise<WarmupResult> {
    const startTime = Date.now();
    let preloaded = 0;
    let failed = 0;

    const warmupKeys = this.configLoader.getCacheConfig().warmupKeys;
    logger.info('Starting cache warmup', { keys: warmupKeys });

    for (const key of warmupKeys) {
      try {
        const cachedData = await this.loadWarmupData(key);
        if (cachedData) {
          const cacheKey = this.payloadHasher.buildCacheKey('warmup', key);
          this.resultCache.set(cacheKey, cachedData);
          preloaded++;
        }
      } catch (error) {
        failed++;
        logger.warn('Cache warmup failed for key', { key, error });
      }
    }

    const duration = Date.now() - startTime;
    this.warmupCompleted = true;
    
    const result: WarmupResult = {
      preloadedKeys: preloaded,
      failedKeys: failed,
      duration,
    };

    logger.info('Cache warmup completed', result);
    eventBus.emit('cache.warmup.completed', result);
    
    return result;
  }

  private async loadWarmupData(key: string): Promise<any> {
    await sleep(10);
    return { key, warmedAt: currentTimestamp(), data: {} };
  }

  private startHotDataMonitor(): void {
    const threshold = this.configLoader.getCacheConfig().hotDataThreshold;
    
    setInterval(() => {
      const stats = this.processedItemCache.getStats();
      for (const { key, entry } of this.processedItemCache.getEntries()) {
        if ((entry as any).accessCount > threshold) {
          eventBus.emit('cache.hot_data_detected', {
            key,
            accessCount: (entry as any).accessCount,
            lastAccessed: (entry as any).accessedAt,
          });
          logger.debug('Hot data detected', { key, accessCount: (entry as any).accessCount });
        }
      }
    }, 60000);
  }

  async executeHandler(request: ProcessRequest): Promise<ProcessingResult> {
    const ctx: HandlerContext = {
      traceId: request.traceId,
      startTime: Date.now(),
      metadata: {},
    };

    logger.info('Processing request started', { traceId: ctx.traceId });

    if (!this.configLoader.getCacheConfig().enabled) {
      return this.processWithoutCache(request, ctx);
    }

    const payloadHash = this.payloadHasher.computeHash(request.payload);
    const cacheKey = this.payloadHasher.buildCacheKey(request.namespace, payloadHash);
    const cachedResult = this.resultCache.get(cacheKey);

    if (cachedResult !== undefined) {
      logger.debug('Cache hit', { traceId: ctx.traceId, cacheKey });
      this.recordMetrics(ctx);
      return { success: true, data: cachedResult, fromCache: true } as any;
    }

    logger.debug('Cache miss', { traceId: ctx.traceId, cacheKey });
    const result = await this.processWithoutCache(request, ctx);
    
    if (result.success && result.data) {
      this.resultCache.set(cacheKey, result.data, payloadHash);
    }

    return result;
  }

  private async processWithoutCache(
    request: ProcessRequest,
    ctx: HandlerContext,
  ): Promise<ProcessingResult> {
    const config = this.configLoader.loadConfig(request.namespace);
    let resource: Worker | null = null;

    try {
      this.validateParams(request.params);
      resource = await this.acquireResource(config);

      const runInstance = this.createRunInstance(request);

      const result = await this.executeWithRetry(request.payload, config, runInstance);

      this.handleSuccess(result, runInstance, ctx);
      return { success: true, data: result };
    } catch (error: any) {
      return this.handleError(error, ctx);
    } finally {
      if (resource) {
        this.resourcePool.release(resource);
      }
      this.recordMetrics(ctx);
      this.cleanup(ctx);
    }
  }

  private validateParams(params: Record<string, any>): void {
    if (!params || typeof params !== 'object') {
      const error = new Error('Invalid parameters') as any;
      error.details = { params: 'must be an object' };
      throw error;
    }
  }

  private async acquireResource(config: ProcessingConfig): Promise<Worker> {
    try {
      const resource = await this.withTimeout(
        this.resourcePool.acquire(),
        5000,
      );
      return resource;
    } catch (error) {
      throw new Error('System busy, please try again later');
    }
  }

  private createRunInstance(request: ProcessRequest): RunInstance {
    const runId = generateId('run_');
    const runInstance: RunInstance = {
      run_id: runId,
      entity_id: request.params.entityId || generateId('ent_'),
      phase: 'executing',
      progress: 0,
      started_at: currentTimestamp(),
      completed_at: null,
      error_detail: null,
    };
    this.runInstances.set(runId, runInstance);
    return runInstance;
  }

  private async executeWithRetry(
    payload: any,
    config: ProcessingConfig,
    runInstance: RunInstance,
  ): Promise<any> {
    return retry(
      async () => {
        const timeout = config.timeout || 30000;
        return await this.withTimeout(
          this.processor.processCore(payload, config, runInstance, this.processedItemCache),
          timeout,
        );
      },
      config.retries || 3,
      1000,
    );
  }

  private handleSuccess(result: any, runInstance: RunInstance, ctx: HandlerContext): void {
    this.persistResult(result);
    this.updateRunInstance(runInstance.run_id, 'completed', 1, null);

    eventBus.emit('task.completed', {
      runId: runInstance.run_id,
      traceId: ctx.traceId,
      result,
    });

    logger.info('Processing request succeeded', { traceId: ctx.traceId, runId: runInstance.run_id });
  }

  private handleError(error: any, ctx: HandlerContext): ProcessingResult {
    if (error.details) {
      logger.warn('Validation error', { traceId: ctx.traceId, error: error.details });
      return { success: false, error: 'Validation failed: ' + JSON.stringify(error.details) };
    }
    if (error.message === 'Timeout') {
      logger.error('Processing timeout', { traceId: ctx.traceId });
      return { success: false, error: '上游服务响应超时' };
    }
    
    this.rollbackTransaction(ctx);
    logger.error('Processing error', { traceId: ctx.traceId, error: error.message });
    return { success: false, error: '内部处理错误: ' + error.message };
  }

  private persistResult(result: any): void {
    this.metrics.successCount++;
    this.metrics.totalProcessed++;
    eventBus.emit('data.persisted', { result });
  }

  private updateRunInstance(
    runId: string,
    phase: RunInstance['phase'],
    progress: number,
    errorDetail: string | null,
  ): void {
    const instance = this.runInstances.get(runId);
    if (instance) {
      instance.phase = phase;
      instance.progress = progress;
      instance.error_detail = errorDetail;
      if (phase === 'completed' || phase === 'failed') {
        instance.completed_at = currentTimestamp();
      }
      this.runInstances.set(runId, instance);
    }
  }

  private rollbackTransaction(ctx: HandlerContext): void {
    logger.warn('Rolling back transaction', { traceId: ctx.traceId });
    eventBus.emit('transaction.rolledback', { traceId: ctx.traceId });
  }

  private recordMetrics(ctx: HandlerContext): void {
    const latency = Date.now() - ctx.startTime;
    this.metrics.totalLatency += latency;
    eventBus.emit('metrics.recorded', {
      traceId: ctx.traceId,
      latency,
      timestamp: currentTimestamp(),
    });
  }

  private cleanup(ctx: HandlerContext): void {
    ctx.metadata = {};
  }

  private async withTimeout<T>(promise: Promise<T>, timeout: number): Promise<T> {
    return new Promise((resolve, reject) => {
      const timer = setTimeout(() => {
        reject(new Error('Timeout'));
      }, timeout);

      promise
        .then(resolve)
        .catch(reject)
        .finally(() => clearTimeout(timer));
    });
  }

  getRunInstance(runId: string): RunInstance | undefined {
    return this.runInstances.get(runId);
  }

  getMetrics() {
    return {
      ...this.metrics,
      averageLatency: this.metrics.totalProcessed > 0
        ? this.metrics.totalLatency / this.metrics.totalProcessed
        : 0,
      errorRate: this.metrics.totalProcessed > 0
        ? this.metrics.failureCount / this.metrics.totalProcessed
        : 0,
    };
  }

  getCacheStats(): { resultCache: CacheStats; itemCache: CacheStats } {
    return {
      resultCache: this.resultCache.getStats(),
      itemCache: this.processedItemCache.getStats(),
    };
  }

  async invalidateCache(pattern?: string): Promise<number> {
    let invalidated = 0;
    
    if (!pattern) {
      const resultSize = this.resultCache.getStats().size;
      const itemSize = this.processedItemCache.getStats().size;
      this.resultCache.clear();
      this.processedItemCache.clear();
      invalidated = resultSize + itemSize;
    } else {
      invalidated += this.resultCache.invalidate(pattern);
    }
    
    logger.info('Cache invalidated', { pattern, count: invalidated });
    eventBus.emit('cache.invalidated', { pattern, count: invalidated });
    
    return invalidated;
  }

  async triggerWarmup(keys?: string[]): Promise<WarmupResult> {
    const originalKeys = this.configLoader.getCacheConfig().warmupKeys;
    if (keys) {
      (this.configLoader as ConfigLoader).updateWarmupKeys(keys);
    }
    const result = await this.warmupCache();
    (this.configLoader as ConfigLoader).updateWarmupKeys(originalKeys);
    return result;
  }

  isWarmupCompleted(): boolean {
    return this.warmupCompleted;
  }

  async shutdown(): Promise<void> {
    await this.resourcePool.drain();
    logger.info('CoreProcessor shutdown complete');
  }
}

export const coreProcessor = new CoreProcessor();
