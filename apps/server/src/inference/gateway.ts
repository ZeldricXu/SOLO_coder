import { v4 as uuidv4 } from 'uuid';
import crypto from 'node:crypto';
import { prisma } from '../config/database';
import { redisCache, RedisKeys } from '../config/redis';
import { logger } from '../config/logger';
import { env } from '../config/env';
import { modelStorage } from '../storage/index';
import { modelLoaderRegistry } from '../model/loader';
import type {
  InferenceRequest,
  InferenceResponse,
  BatchInferenceRequest,
  BatchInferenceResponse,
  LoadedModel,
  BatcherStats,
  ModelLoadStatus,
  InferenceGatewayStatus,
  InferenceMetrics,
} from '@mlops/shared';
import { inferenceRequestSchema, batchInferenceRequestSchema } from '@mlops/shared';

interface QueuedRequest {
  requestId: string;
  inputs: Record<string, unknown>;
  resolve: (result: Record<string, unknown>) => void;
  reject: (error: Error) => void;
  timestamp: number;
  context?: Record<string, unknown>;
  userId?: string;
  sessionId?: string;
}

interface TokenBucketConfig {
  maxBatchSize: number;
  windowMs: number;
  maxBurstSize: number;
  refillRate: number;
  minBatchSize: number;
  adaptiveEnabled: boolean;
  targetLatencyMs: number;
  latencyToleranceRatio: number;
}

const DEFAULT_TOKEN_BUCKET_CONFIG: TokenBucketConfig = {
  maxBatchSize: 32,
  windowMs: 10,
  maxBurstSize: 64,
  refillRate: 100,
  minBatchSize: 1,
  adaptiveEnabled: true,
  targetLatencyMs: 50,
  latencyToleranceRatio: 0.3,
};

interface ModelBatcher {
  modelId: string;
  version: string;
  queue: QueuedRequest[];
  config: TokenBucketConfig;
  tokens: number;
  lastRefillTime: number;
  windowStart: number;
  timer: NodeJS.Timeout | null;
  processing: boolean;
  stats: {
    totalRequests: number;
    totalBatches: number;
    batchSizes: number[];
    queueTimes: number[];
    processingTimes: number[];
    tokensConsumed: number;
    tokensRefilled: number;
    windowFlushes: number;
    sizeFlushes: number;
    adaptiveAdjustments: number;
  };
}

export class InferenceGateway {
  private loadedModels: Map<string, LoadedModel> = new Map();
  private batchers: Map<string, ModelBatcher> = new Map();
  private loadPromises: Map<string, Promise<LoadedModel>> = new Map();
  private startTime: number = Date.now();
  private requestCount: number = 0;
  private successCount: number = 0;
  private latencies: number[] = [];

  private generateCacheKey(inputs: Record<string, unknown>): string {
    return crypto.createHash('md5').update(JSON.stringify(inputs)).digest('hex');
  }

  private async getCachedResult(
    modelId: string,
    version: string,
    inputs: Record<string, unknown>
  ): Promise<Record<string, unknown> | null> {
    const hash = this.generateCacheKey(inputs);
    const key = RedisKeys.inferenceCache(modelId, version, hash);
    const cached = await redisCache.get(key);
    if (cached) {
      try {
        return JSON.parse(cached);
      } catch {
        return null;
      }
    }
    return null;
  }

  private async cacheResult(
    modelId: string,
    version: string,
    inputs: Record<string, unknown>,
    outputs: Record<string, unknown>
  ): Promise<void> {
    const hash = this.generateCacheKey(inputs);
    const key = RedisKeys.inferenceCache(modelId, version, hash);
    await redisCache.setex(key, env.INFERENCE_CACHE_TTL_SECONDS, JSON.stringify(outputs));
  }

  async loadModel(modelId: string, versionId?: string): Promise<LoadedModel> {
    const cacheKey = versionId ? `${modelId}:${versionId}` : `${modelId}:latest`;

    if (this.loadedModels.has(cacheKey)) {
      const model = this.loadedModels.get(cacheKey)!;
      model.lastUsedAt = Date.now();
      model.usageCount++;
      return model;
    }

    if (this.loadPromises.has(cacheKey)) {
      return this.loadPromises.get(cacheKey)!;
    }

    const loadPromise = (async (): Promise<LoadedModel> => {
      logger.info({ modelId, versionId, cacheKey }, 'Loading model');

      let version;
      if (versionId) {
        version = await prisma.modelVersion.findUnique({ where: { id: versionId } });
      } else {
        version = await prisma.modelVersion.findFirst({
          where: { modelId, status: 'ready' },
          orderBy: { createdAt: 'desc' },
        });
      }

      if (!version) {
        throw new Error(`Model version not found for model: ${modelId}`);
      }

      const actualCacheKey = `${modelId}:${version.id}`;
      if (this.loadedModels.has(actualCacheKey)) {
        const model = this.loadedModels.get(actualCacheKey)!;
        model.lastUsedAt = Date.now();
        model.usageCount++;
        return model;
      }

      try {
        const modelPath = await modelStorage.getDownloadUrl(version.storagePath);
        const loader = modelLoaderRegistry.get(version.format as any);
        const tempPath = `/tmp/${uuidv4()}.${version.format}`;
        const buffer = await modelStorage.getObject(version.storagePath);
        const fs = require('fs');
        fs.writeFileSync(tempPath, buffer);

        const handle = await loader.load(tempPath, version.loaderConfig as Record<string, unknown>);

        const loadedModel: LoadedModel = {
          modelId,
          version: version.id,
          handle,
          loaderType: loader.format,
          loadedAt: Date.now(),
          lastUsedAt: Date.now(),
          usageCount: 0,
          memoryUsageBytes: Number(version.sizeBytes),
        };

        this.loadedModels.set(cacheKey, loadedModel);
        this.loadedModels.set(actualCacheKey, loadedModel);

        logger.info(
          { modelId, versionId: version.id, format: version.format, duration: Date.now() - loadedModel.loadedAt },
          'Model loaded successfully'
        );

        return loadedModel;
      } catch (err) {
        logger.error({ error: err, modelId, versionId }, 'Failed to load model');

        if (!versionId) {
          const fallbackVersion = await prisma.modelVersion.findFirst({
            where: { modelId, status: 'ready', id: { not: version.id } },
            orderBy: { createdAt: 'desc' },
          });

          if (fallbackVersion) {
            logger.info({ modelId, fallbackVersionId: fallbackVersion.id }, 'Attempting fallback to previous version');
            return this.loadModel(modelId, fallbackVersion.id);
          }
        }

        throw err;
      }
    })();

    this.loadPromises.set(cacheKey, loadPromise);
    loadPromise
      .catch((err) => {
        logger.error({ error: err, modelId, versionId }, 'Failed to load model');
      })
      .finally(() => {
        this.loadPromises.delete(cacheKey);
      });

    return loadPromise;
  }

  async unloadModel(modelId: string, versionId: string): Promise<void> {
    const cacheKey = `${modelId}:${versionId}`;
    const model = this.loadedModels.get(cacheKey);
    if (model) {
      const loader = modelLoaderRegistry.get(model.loaderType as any);
      await loader.unload(model.handle).catch(() => {});
      this.loadedModels.delete(cacheKey);
      this.loadedModels.delete(`${modelId}:latest`);
      this.batchers.delete(cacheKey);
      logger.info({ modelId, versionId }, 'Model unloaded');
    }
  }

  private getOrCreateBatcher(modelId: string, version: string): ModelBatcher {
    const key = `${modelId}:${version}`;
    if (!this.batchers.has(key)) {
      const config: TokenBucketConfig = {
        ...DEFAULT_TOKEN_BUCKET_CONFIG,
        maxBatchSize: env.INFERENCE_BATCH_MAX_SIZE,
        windowMs: env.INFERENCE_BATCH_TIMEOUT_MS,
      };
      const now = Date.now();
      this.batchers.set(key, {
        modelId,
        version,
        queue: [],
        config,
        tokens: config.maxBurstSize,
        lastRefillTime: now,
        windowStart: now,
        timer: null,
        processing: false,
        stats: {
          totalRequests: 0,
          totalBatches: 0,
          batchSizes: [],
          queueTimes: [],
          processingTimes: [],
          tokensConsumed: 0,
          tokensRefilled: 0,
          windowFlushes: 0,
          sizeFlushes: 0,
          adaptiveAdjustments: 0,
        },
      });
    }
    return this.batchers.get(key)!;
  }

  private refillTokens(batcher: ModelBatcher): void {
    const now = Date.now();
    const elapsed = now - batcher.lastRefillTime;
    const tokensToAdd = Math.floor((elapsed / 1000) * batcher.config.refillRate);
    if (tokensToAdd > 0) {
      batcher.tokens = Math.min(
        batcher.config.maxBurstSize,
        batcher.tokens + tokensToAdd
      );
      batcher.lastRefillTime = now;
      batcher.stats.tokensRefilled += tokensToAdd;
    }
  }

  private tryConsumeTokens(batcher: ModelBatcher, count: number): boolean {
    this.refillTokens(batcher);
    if (batcher.tokens >= count) {
      batcher.tokens -= count;
      batcher.stats.tokensConsumed += count;
      return true;
    }
    return false;
  }

  private shouldFlushBatch(batcher: ModelBatcher): boolean {
    if (batcher.queue.length === 0) return false;
    if (batcher.processing) return false;

    const now = Date.now();
    const windowElapsed = now - batcher.windowStart;

    if (batcher.queue.length >= batcher.config.maxBatchSize) {
      batcher.stats.sizeFlushes++;
      return true;
    }

    if (windowElapsed >= batcher.config.windowMs) {
      if (batcher.queue.length >= batcher.config.minBatchSize) {
        if (this.tryConsumeTokens(batcher, batcher.queue.length)) {
          batcher.stats.windowFlushes++;
          return true;
        }
      }
    }

    if (windowElapsed >= batcher.config.windowMs * 2 && batcher.queue.length > 0) {
      batcher.stats.windowFlushes++;
      return true;
    }

    return false;
  }

  private adaptBatchConfig(batcher: ModelBatcher): void {
    if (!batcher.config.adaptiveEnabled) return;
    if (batcher.stats.processingTimes.length < 5) return;

    const recentProcessingTimes = batcher.stats.processingTimes.slice(-20);
    const avgProcessingTime = recentProcessingTimes.reduce((a, b) => a + b, 0) / recentProcessingTimes.length;
    const recentQueueTimes = batcher.stats.queueTimes.slice(-20);
    const avgQueueTime = recentQueueTimes.reduce((a, b) => a + b, 0) / recentQueueTimes.length;

    const totalLatency = avgProcessingTime + avgQueueTime;
    const targetLatency = batcher.config.targetLatencyMs;
    const tolerance = targetLatency * batcher.config.latencyToleranceRatio;

    if (totalLatency > targetLatency + tolerance) {
      const newMaxBatch = Math.max(4, Math.floor(batcher.config.maxBatchSize * 0.8));
      if (newMaxBatch !== batcher.config.maxBatchSize) {
        batcher.config.maxBatchSize = newMaxBatch;
        batcher.config.windowMs = Math.max(5, Math.floor(batcher.config.windowMs * 0.8));
        batcher.stats.adaptiveAdjustments++;
        logger.warn(
          { modelId: batcher.modelId, avgLatency: totalLatency, targetLatency, newMaxBatch, newWindowMs: batcher.config.windowMs },
          'High latency detected, reducing batch size'
        );
      }
    } else if (totalLatency < targetLatency - tolerance && avgQueueTime < targetLatency * 0.5) {
      const newMaxBatch = Math.min(128, Math.floor(batcher.config.maxBatchSize * 1.2));
      if (newMaxBatch !== batcher.config.maxBatchSize) {
        batcher.config.maxBatchSize = newMaxBatch;
        batcher.config.windowMs = Math.min(100, Math.floor(batcher.config.windowMs * 1.1));
        batcher.stats.adaptiveAdjustments++;
      }
    }
  }

  private scheduleBatch(batcher: ModelBatcher): void {
    if (batcher.timer) return;

    batcher.timer = setTimeout(() => {
      batcher.timer = null;
      if (this.shouldFlushBatch(batcher)) {
        this.processBatch(batcher);
      } else if (batcher.queue.length > 0) {
        this.scheduleBatch(batcher);
      }
    }, Math.max(1, batcher.config.windowMs));
  }

  private async processBatch(batcher: ModelBatcher): Promise<void> {
    if (batcher.queue.length === 0 || batcher.processing) return;

    batcher.processing = true;
    const batch = batcher.queue.splice(0, Math.min(batcher.queue.length, batcher.config.maxBatchSize));
    batcher.windowStart = Date.now();
    batcher.stats.totalBatches++;
    batcher.stats.batchSizes.push(batch.length);
    if (batcher.stats.batchSizes.length > 1000) {
      batcher.stats.batchSizes = batcher.stats.batchSizes.slice(-1000);
    }

    try {
      let model: LoadedModel;
      try {
        model = await this.loadModel(batcher.modelId, batcher.version);
      } catch (loadErr) {
        const fallbackVersion = await prisma.modelVersion.findFirst({
          where: { modelId: batcher.modelId, status: 'ready', id: { not: batcher.version } },
          orderBy: { createdAt: 'desc' },
        });
        if (fallbackVersion) {
          logger.info({ modelId: batcher.modelId, fallbackVersionId: fallbackVersion.id }, 'Attempting fallback to previous version in batch processing');
          batcher.version = fallbackVersion.id;
          model = await this.loadModel(batcher.modelId, fallbackVersion.id);
        } else {
          throw loadErr;
        }
      }
      const loader = modelLoaderRegistry.get(model.loaderType as any);

      const inputs = batch.map((req) => req.inputs);
      const startTime = Date.now();

      const outputs = await loader.batchPredict(model.handle, inputs);

      const processingTime = Date.now() - startTime;
      batcher.stats.processingTimes.push(processingTime);
      if (batcher.stats.processingTimes.length > 1000) {
        batcher.stats.processingTimes = batcher.stats.processingTimes.slice(-1000);
      }
      model.usageCount += batch.length;

      batch.forEach((req, idx) => {
        const queueTime = Date.now() - req.timestamp;
        batcher.stats.queueTimes.push(queueTime);
        if (batcher.stats.queueTimes.length > 1000) {
          batcher.stats.queueTimes = batcher.stats.queueTimes.slice(-1000);
        }

        const output = outputs[idx] as Record<string, unknown>;
        this.cacheResult(batcher.modelId, batcher.version, req.inputs, output).catch(() => {});
        this.recordMetrics({
          requestId: req.requestId,
          inferenceId: uuidv4(),
          modelId: batcher.modelId,
          version: batcher.version,
          latencyMs: processingTime,
          queueTimeMs: queueTime,
          batchSize: batch.length,
          inputSize: JSON.stringify(req.inputs).length,
          outputSize: JSON.stringify(output).length,
          fromCache: false,
          success: true,
          timestamp: Date.now(),
          userId: req.userId,
          sessionId: req.sessionId,
          inputFeatures: req.inputs,
          outputFeatures: output,
        }).catch(() => {});

        req.resolve(output);
      });

      this.adaptBatchConfig(batcher);
    } catch (error) {
      batch.forEach((req) => {
        this.recordMetrics({
          requestId: req.requestId,
          inferenceId: uuidv4(),
          modelId: batcher.modelId,
          version: batcher.version,
          latencyMs: 0,
          queueTimeMs: Date.now() - req.timestamp,
          batchSize: batch.length,
          inputSize: JSON.stringify(req.inputs).length,
          outputSize: 0,
          fromCache: false,
          success: false,
          error: error instanceof Error ? error.message : 'Unknown error',
          timestamp: Date.now(),
          userId: req.userId,
          sessionId: req.sessionId,
          inputFeatures: req.inputs,
        }).catch(() => {});

        req.reject(error instanceof Error ? error : new Error('Inference failed'));
      });
    } finally {
      batcher.processing = false;
      if (batcher.queue.length > 0) {
        if (this.shouldFlushBatch(batcher)) {
          setImmediate(() => this.processBatch(batcher));
        } else {
          this.scheduleBatch(batcher);
        }
      }
    }
  }

  async infer(request: InferenceRequest): Promise<InferenceResponse> {
    const validated = inferenceRequestSchema.parse(request);
    const requestId = validated.requestId || uuidv4();
    const startTime = Date.now();
    this.requestCount++;

    let versionId = validated.version;
    if (!versionId) {
      const latest = await prisma.modelVersion.findFirst({
        where: { modelId: validated.modelId, status: 'ready' },
        orderBy: { createdAt: 'desc' },
      });
      if (!latest) {
        throw new Error(`No ready version found for model: ${validated.modelId}`);
      }
      versionId = latest.id;
    }

    let fromCache = false;
    let outputs: Record<string, unknown> | Record<string, unknown>[];
    let batchSize = 1;

    if (!validated.bypassCache) {
      const inputIsSingle = !Array.isArray(validated.inputs);
      const singleInput = inputIsSingle
        ? validated.inputs as Record<string, unknown>
        : (validated.inputs as Record<string, unknown>[])[0];

      if (singleInput) {
        const cached = await this.getCachedResult(validated.modelId, versionId, singleInput);
        if (cached) {
          outputs = inputIsSingle ? cached : [cached];
          fromCache = true;
          this.successCount++;

          const latency = Date.now() - startTime;
          this.latencies.push(latency);
          if (this.latencies.length > 10000) {
            this.latencies = this.latencies.slice(-10000);
          }

          this.recordMetrics({
            requestId,
            inferenceId: uuidv4(),
            modelId: validated.modelId,
            version: versionId,
            latencyMs: latency,
            queueTimeMs: 0,
            batchSize: 1,
            inputSize: JSON.stringify(validated.inputs).length,
            outputSize: JSON.stringify(outputs).length,
            fromCache: true,
            success: true,
            timestamp: Date.now(),
            userId: validated.userId,
            sessionId: validated.sessionId,
            inputFeatures: singleInput,
            outputFeatures: inputIsSingle ? (outputs as Record<string, unknown>) : (outputs as Record<string, unknown>[])[0],
          }).catch(() => {});

          return {
            modelId: validated.modelId,
            version: versionId,
            outputs,
            requestId,
            inferenceId: uuidv4(),
            latencyMs: latency,
            batchSize: 1,
            fromCache: true,
            timestamp: Date.now(),
          };
        }
      }
    }

    const inputArray = Array.isArray(validated.inputs) ? validated.inputs : [validated.inputs];

    const results: Record<string, unknown>[] = [];
    for (const input of inputArray) {
      const batcher = this.getOrCreateBatcher(validated.modelId, versionId);
      batcher.stats.totalRequests++;

      const result = await new Promise<Record<string, unknown>>((resolve, reject) => {
        batcher.queue.push({
          requestId,
          inputs: input,
          resolve,
          reject,
          timestamp: Date.now(),
          context: validated.context,
          userId: validated.userId,
          sessionId: validated.sessionId,
        });

        if (this.shouldFlushBatch(batcher)) {
          if (batcher.timer) {
            clearTimeout(batcher.timer);
            batcher.timer = null;
          }
          setImmediate(() => this.processBatch(batcher));
        } else {
          this.scheduleBatch(batcher);
        }
      });

      results.push(result);
    }

    this.successCount++;
    const latency = Date.now() - startTime;
    this.latencies.push(latency);
    if (this.latencies.length > 10000) {
      this.latencies = this.latencies.slice(-10000);
    }

    outputs = Array.isArray(validated.inputs) ? results : results[0]!;
    batchSize = inputArray.length;

    const actualVersion = this.batchers.has(`${validated.modelId}:${versionId}`)
      ? this.batchers.get(`${validated.modelId}:${versionId}`)!.version
      : versionId;

    return {
      modelId: validated.modelId,
      version: actualVersion,
      outputs,
      requestId,
      inferenceId: uuidv4(),
      latencyMs: latency,
      batchSize,
      fromCache,
      timestamp: Date.now(),
    };
  }

  async batchInfer(request: BatchInferenceRequest): Promise<BatchInferenceResponse> {
    const validated = batchInferenceRequestSchema.parse(request);
    const requestId = validated.requestId || uuidv4();
    const startTime = Date.now();

    const batchSize = validated.batchSize || 32;
    const inferenceIds: string[] = [];
    const outputs: Record<string, unknown>[] = [];

    for (let i = 0; i < validated.inputs.length; i += batchSize) {
      const batch = validated.inputs.slice(i, i + batchSize);
      const result = await this.infer({
        ...request,
        inputs: batch,
        requestId: `${requestId}_${i}`,
      });
      inferenceIds.push(result.inferenceId);
      outputs.push(...(Array.isArray(result.outputs) ? result.outputs : [result.outputs as Record<string, unknown>]));
    }

    return {
      modelId: validated.modelId,
      version: inferenceIds[0] ? inferenceIds[0] : '',
      outputs,
      requestId,
      inferenceIds,
      totalLatencyMs: Date.now() - startTime,
      batchCount: Math.ceil(validated.inputs.length / batchSize),
      timestamp: Date.now(),
    };
  }

  private async recordMetrics(metrics: InferenceMetrics): Promise<void> {
    await prisma.inferenceMetrics.create({
      data: {
        id: metrics.inferenceId,
        requestId: metrics.requestId,
        inferenceId: metrics.inferenceId,
        modelId: metrics.modelId,
        version: metrics.version,
        latencyMs: metrics.latencyMs,
        queueTimeMs: metrics.queueTimeMs,
        batchSize: metrics.batchSize,
        inputSize: metrics.inputSize,
        outputSize: metrics.outputSize,
        fromCache: metrics.fromCache,
        success: metrics.success,
        error: metrics.error,
        timestamp: new Date(metrics.timestamp),
        userId: metrics.userId,
        sessionId: metrics.sessionId,
        inputFeatures: metrics.inputFeatures,
        outputFeatures: metrics.outputFeatures,
      },
    });
  }

  getLoadStatuses(): ModelLoadStatus[] {
    return Array.from(this.loadedModels.entries()).map(([key, model]) => ({
      modelId: model.modelId,
      version: model.version,
      status: 'loaded',
      loadProgress: 100,
      loadedAt: model.loadedAt,
      memoryUsageBytes: model.memoryUsageBytes,
    }));
  }

  getBatcherStats(): BatcherStats[] {
    return Array.from(this.batchers.values()).map((batcher) => {
      const sortedLatencies = [...batcher.stats.queueTimes].sort((a, b) => a - b);
      const percentile = (p: number) => {
        if (sortedLatencies.length === 0) return 0;
        const idx = Math.ceil((p / 100) * sortedLatencies.length) - 1;
        return sortedLatencies[Math.max(0, Math.min(idx, sortedLatencies.length - 1))] || 0;
      };

      return {
        modelId: batcher.modelId,
        version: batcher.version,
        currentQueueSize: batcher.queue.length,
        totalRequests: batcher.stats.totalRequests,
        totalBatches: batcher.stats.totalBatches,
        avgBatchSize:
          batcher.stats.batchSizes.length > 0
            ? batcher.stats.batchSizes.reduce((a, b) => a + b, 0) / batcher.stats.batchSizes.length
            : 0,
        avgQueueTimeMs:
          sortedLatencies.length > 0 ? sortedLatencies.reduce((a, b) => a + b, 0) / sortedLatencies.length : 0,
        p50QueueTimeMs: percentile(50),
        p95QueueTimeMs: percentile(95),
        p99QueueTimeMs: percentile(99),
        tokenBucket: {
          currentTokens: batcher.tokens,
          maxBurstSize: batcher.config.maxBurstSize,
          refillRate: batcher.config.refillRate,
          windowMs: batcher.config.windowMs,
          maxBatchSize: batcher.config.maxBatchSize,
          adaptiveEnabled: batcher.config.adaptiveEnabled,
          adaptiveAdjustments: batcher.stats.adaptiveAdjustments,
        },
      };
    });
  }

  getStatus(): InferenceGatewayStatus {
    const sorted = [...this.latencies].sort((a, b) => a - b);
    const percentile = (p: number) => {
      if (sorted.length === 0) return 0;
      const idx = Math.ceil((p / 100) * sorted.length) - 1;
      return sorted[Math.max(0, Math.min(idx, sorted.length - 1))] || 0;
    };

    return {
      uptimeMs: Date.now() - this.startTime,
      totalRequests: this.requestCount,
      successRate: this.requestCount > 0 ? this.successCount / this.requestCount : 1,
      avgLatencyMs: sorted.length > 0 ? sorted.reduce((a, b) => a + b, 0) / sorted.length : 0,
      p50LatencyMs: percentile(50),
      p95LatencyMs: percentile(95),
      p99LatencyMs: percentile(99),
      loadModels: this.getLoadStatuses(),
      batcherStats: this.getBatcherStats(),
    };
  }
}

export const inferenceGateway = new InferenceGateway();
