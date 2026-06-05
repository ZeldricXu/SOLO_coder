import { v4 as uuidv4 } from 'uuid';
import crypto from 'node:crypto';
import type { FastifyRequest, FastifyReply } from 'fastify';
import { prisma } from '../config/database';
import { redisCache, RedisKeys } from '../config/redis';
import { logger } from '../config/logger';
import { env } from '../config/env';
import { modelStorage } from '../storage';
import { modelLoaderRegistry } from './loader';
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

interface ModelBatcher {
  modelId: string;
  version: string;
  queue: QueuedRequest[];
  maxBatchSize: number;
  batchTimeoutMs: number;
  timer: NodeJS.Timeout | null;
  stats: {
    totalRequests: number;
    totalBatches: number;
    batchSizes: number[];
    queueTimes: number[];
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
      this.batchers.set(key, {
        modelId,
        version,
        queue: [],
        maxBatchSize: env.INFERENCE_BATCH_MAX_SIZE,
        batchTimeoutMs: env.INFERENCE_BATCH_TIMEOUT_MS,
        timer: null,
        stats: {
          totalRequests: 0,
          totalBatches: 0,
          batchSizes: [],
          queueTimes: [],
        },
      });
    }
    return this.batchers.get(key)!;
  }

  private async processBatch(batcher: ModelBatcher): Promise<void> {
    if (batcher.queue.length === 0) return;

    const batch = batcher.queue.splice(0, Math.min(batcher.queue.length, batcher.maxBatchSize));
    batcher.stats.totalBatches++;
    batcher.stats.batchSizes.push(batch.length);
    if (batcher.stats.batchSizes.length > 1000) {
      batcher.stats.batchSizes = batcher.stats.batchSizes.slice(-1000);
    }

    try {
      const model = await this.loadModel(batcher.modelId, batcher.version);
      const loader = modelLoaderRegistry.get(model.loaderType as any);

      const inputs = batch.map((req) => req.inputs);
      const startTime = Date.now();

      const outputs = await loader.batchPredict(model.handle, inputs);

      const latency = Date.now() - startTime;
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
          latencyMs: latency,
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
    }
  }

  private scheduleBatch(batcher: ModelBatcher): void {
    if (batcher.timer) return;

    batcher.timer = setTimeout(() => {
      batcher.timer = null;
      this.processBatch(batcher);
    }, batcher.batchTimeoutMs);
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

        if (batcher.queue.length >= batcher.maxBatchSize) {
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

    return {
      modelId: validated.modelId,
      version: versionId,
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

export async function registerInferenceRoutes(fastify: any): Promise<void> {
  const gateway = inferenceGateway;

  fastify.post('/api/v1/inference', async (request: FastifyRequest, reply: FastifyReply) => {
    try {
      const result = await gateway.infer(request.body as InferenceRequest);
      return result;
    } catch (error) {
      logger.error({ error }, 'Inference failed');
      return reply.status(500).send({
        error: error instanceof Error ? error.message : 'Inference failed',
      });
    }
  });

  fastify.post('/api/v1/inference/batch', async (request: FastifyRequest, reply: FastifyReply) => {
    try {
      const result = await gateway.batchInfer(request.body as BatchInferenceRequest);
      return result;
    } catch (error) {
      logger.error({ error }, 'Batch inference failed');
      return reply.status(500).send({
        error: error instanceof Error ? error.message : 'Batch inference failed',
      });
    }
  });

  fastify.get('/api/v1/inference/status', async () => {
    return gateway.getStatus();
  });

  fastify.post('/api/v1/models/:id/load', async (request: FastifyRequest<{ Params: { id: string }; Body: { versionId?: string } }>, reply: FastifyReply) => {
    try {
      const result = await gateway.loadModel(request.params.id, request.body.versionId);
      return reply.status(200).send({
        modelId: result.modelId,
        version: result.version,
        status: 'loaded',
        memoryUsageBytes: result.memoryUsageBytes,
      });
    } catch (error) {
      return reply.status(500).send({ error: error instanceof Error ? error.message : 'Failed to load model' });
    }
  });

  fastify.post('/api/v1/models/:id/unload', async (request: FastifyRequest<{ Params: { id: string }; Body: { versionId: string } }>, reply: FastifyReply) => {
    try {
      await gateway.unloadModel(request.params.id, request.body.versionId);
      return reply.status(200).send({ status: 'unloaded' });
    } catch (error) {
      return reply.status(500).send({ error: error instanceof Error ? error.message : 'Failed to unload model' });
    }
  });
}
