import { describe, it, expect, vi, beforeEach, afterEach } from 'vitest';
import { InferenceGateway } from '../../src/inference/gateway';
import { modelLoaderRegistry } from '../../src/model/loader';
import { mockPrisma, mockRedis, createMockLogger, resetAllMocks } from '../mocks';
import {
  createMockModelVersion,
  createMockInferenceRequest,
  createMockBatchConfig,
  concurrent,
  delay,
} from '../fixtures';
import type { ModelFormat, ModelVersion } from '@mlops/shared';

vi.mock('../../src/config/database', () => ({ prisma: mockPrisma }));
vi.mock('../../src/config/redis', () => ({
  redisCache: mockRedis,
  RedisKeys: {
    inferenceCache: (modelId: string, version: string, hash: string) =>
      `inference:cache:${modelId}:${version}:${hash}`,
  },
}));
vi.mock('../../src/config/logger', () => ({ logger: createMockLogger() }));
vi.mock('../../src/config/env', () => ({
  env: {
    INFERENCE_BATCH_MAX_SIZE: 8,
    INFERENCE_BATCH_TIMEOUT_MS: 50,
    INFERENCE_CACHE_TTL_SECONDS: 300,
  },
}));
vi.mock('../../src/storage', () => ({
  modelStorage: {
    getObject: vi.fn(),
    getDownloadUrl: vi.fn().mockResolvedValue('/tmp/model.bin'),
  },
}));
vi.mock('fs', () => ({
  writeFileSync: vi.fn(),
}));

describe('InferenceGateway - Normal Path', () => {
  let gateway: InferenceGateway;
  const { modelStorage } = require('../../src/storage');
  const { logger } = require('../../src/config/logger');

  beforeEach(() => {
    resetAllMocks();
    vi.useRealTimers();
    gateway = new InferenceGateway();
  });

  afterEach(() => {
    vi.useFakeTimers();
  });

  describe('Model Loading', () => {
    it('should load model successfully with correct loader', async () => {
      const modelId = 'test-model';
      const versionId = 'version-1';
      const mockVersion = createMockModelVersion(modelId, {
        id: versionId,
        format: 'onnx' as ModelFormat,
      });

      mockPrisma.modelVersion.findUnique.mockResolvedValue({
        ...mockVersion,
        createdAt: new Date(mockVersion.createdAt),
        sizeBytes: BigInt(mockVersion.sizeBytes),
        format: 'onnx',
      });
      modelStorage.getObject.mockResolvedValue(Buffer.from('onnx-model-data'));

      const result = await gateway.loadModel(modelId, versionId);

      expect(result.modelId).toBe(modelId);
      expect(result.version).toBe(versionId);
      expect(result.loaderType).toBe('onnx');
      expect(result.memoryUsageBytes).toBe(mockVersion.sizeBytes);
    });

    it('should return cached model on subsequent calls', async () => {
      const modelId = 'cached-model';
      const versionId = 'version-1';
      const mockVersion = createMockModelVersion(modelId, {
        id: versionId,
        format: 'onnx' as ModelFormat,
      });

      mockPrisma.modelVersion.findUnique.mockResolvedValue({
        ...mockVersion,
        createdAt: new Date(mockVersion.createdAt),
        sizeBytes: BigInt(mockVersion.sizeBytes),
        format: 'onnx',
      });
      modelStorage.getObject.mockResolvedValue(Buffer.from('onnx-model-data'));

      const first = await gateway.loadModel(modelId, versionId);
      const second = await gateway.loadModel(modelId, versionId);

      expect(first).toBe(second);
      expect(mockPrisma.modelVersion.findUnique).toHaveBeenCalledTimes(1);
      expect(modelStorage.getObject).toHaveBeenCalledTimes(1);
    });

    it('should load latest version when versionId not specified', async () => {
      const modelId = 'latest-model';
      const latestVersion = createMockModelVersion(modelId, {
        id: 'latest-version',
        version: '2.0.0',
        format: 'onnx' as ModelFormat,
      });

      mockPrisma.modelVersion.findFirst.mockResolvedValue({
        ...latestVersion,
        createdAt: new Date(latestVersion.createdAt),
        sizeBytes: BigInt(latestVersion.sizeBytes),
        format: 'onnx',
      });
      modelStorage.getObject.mockResolvedValue(Buffer.from('onnx-model-data'));

      const result = await gateway.loadModel(modelId);

      expect(result.version).toBe('latest-version');
      expect(mockPrisma.modelVersion.findFirst).toHaveBeenCalledWith(
        expect.objectContaining({
          where: { modelId, status: 'ready' },
          orderBy: { createdAt: 'desc' },
        })
      );
    });
  });

  describe('Dynamic Batching', () => {
    it('should batch requests when batch size threshold is reached', async () => {
      const modelId = 'batch-test-model';
      const versionId = 'version-1';
      const mockVersion = createMockModelVersion(modelId, {
        id: versionId,
        format: 'onnx' as ModelFormat,
      });

      mockPrisma.modelVersion.findUnique.mockResolvedValue({
        ...mockVersion,
        createdAt: new Date(mockVersion.createdAt),
        sizeBytes: BigInt(mockVersion.sizeBytes),
        format: 'onnx',
      });
      mockPrisma.modelVersion.findFirst.mockResolvedValue({
        ...mockVersion,
        createdAt: new Date(mockVersion.createdAt),
        sizeBytes: BigInt(mockVersion.sizeBytes),
        format: 'onnx',
      });
      modelStorage.getObject.mockResolvedValue(Buffer.from('onnx-model-data'));

      const batchPredictSpy = vi
        .spyOn(modelLoaderRegistry.get('onnx'), 'batchPredict')
        .mockResolvedValue(
          Array.from({ length: 8 }, (_, i) => ({ prediction: i, confidence: 0.9 }))
        );

      await gateway.loadModel(modelId, versionId);

      const requests = Array.from({ length: 8 }, (_, i) =>
        createMockInferenceRequest(modelId, {
          inputs: { feature: i },
          version: versionId,
        })
      );

      const results = await Promise.all(requests.map((r) => gateway.infer(r)));

      expect(results).toHaveLength(8);
      expect(batchPredictSpy).toHaveBeenCalledTimes(1);
      expect(batchPredictSpy).toHaveBeenCalledWith(
        expect.anything(),
        expect.arrayContaining(requests.map((r) => r.inputs))
      );

      const batcherStats = gateway.getBatcherStats();
      expect(batcherStats[0]?.totalBatches).toBe(1);
      expect(batcherStats[0]?.totalRequests).toBe(8);
    });

    it('should flush batch on timeout even if batch size not reached', async () => {
      const modelId = 'timeout-batch-model';
      const versionId = 'version-1';
      const batchSize = 8;
      const timeoutMs = 50;
      const mockVersion = createMockModelVersion(modelId, {
        id: versionId,
        format: 'onnx' as ModelFormat,
      });

      mockPrisma.modelVersion.findUnique.mockResolvedValue({
        ...mockVersion,
        createdAt: new Date(mockVersion.createdAt),
        sizeBytes: BigInt(mockVersion.sizeBytes),
        format: 'onnx',
      });
      mockPrisma.modelVersion.findFirst.mockResolvedValue({
        ...mockVersion,
        createdAt: new Date(mockVersion.createdAt),
        sizeBytes: BigInt(mockVersion.sizeBytes),
        format: 'onnx',
      });
      modelStorage.getObject.mockResolvedValue(Buffer.from('onnx-model-data'));

      const batchPredictSpy = vi
        .spyOn(modelLoaderRegistry.get('onnx'), 'batchPredict')
        .mockImplementation(async (_, inputs) => {
          await delay(10);
          return inputs.map((_, i) => ({ prediction: i, confidence: 0.9 }));
        });

      await gateway.loadModel(modelId, versionId);

      const startTime = Date.now();
      const requests = Array.from({ length: 3 }, (_, i) =>
        createMockInferenceRequest(modelId, {
          inputs: { feature: i },
          version: versionId,
        })
      );

      const resultsPromise = Promise.all(requests.map((r) => gateway.infer(r)));
      await delay(timeoutMs + 20);
      const results = await resultsPromise;

      const totalTime = Date.now() - startTime;
      expect(results).toHaveLength(3);
      expect(batchPredictSpy).toHaveBeenCalledTimes(1);
      expect(batchPredictSpy).toHaveBeenCalledWith(
        expect.anything(),
        expect.arrayContaining(requests.map((r) => r.inputs))
      );
      expect(totalTime).toBeGreaterThanOrEqual(timeoutMs);
      expect(totalTime).toBeLessThan(timeoutMs * 2);
    });

    it('should record accurate batcher statistics', async () => {
      const modelId = 'stats-test-model';
      const versionId = 'version-1';
      const mockVersion = createMockModelVersion(modelId, {
        id: versionId,
        format: 'onnx' as ModelFormat,
      });

      mockPrisma.modelVersion.findUnique.mockResolvedValue({
        ...mockVersion,
        createdAt: new Date(mockVersion.createdAt),
        sizeBytes: BigInt(mockVersion.sizeBytes),
        format: 'onnx',
      });
      mockPrisma.modelVersion.findFirst.mockResolvedValue({
        ...mockVersion,
        createdAt: new Date(mockVersion.createdAt),
        sizeBytes: BigInt(mockVersion.sizeBytes),
        format: 'onnx',
      });
      modelStorage.getObject.mockResolvedValue(Buffer.from('onnx-model-data'));

      vi.spyOn(modelLoaderRegistry.get('onnx'), 'batchPredict').mockImplementation(
        async (_, inputs) => {
          return inputs.map((__, i) => ({ prediction: i, confidence: 0.9 }));
        }
      );

      await gateway.loadModel(modelId, versionId);

      for (let i = 0; i < 24; i++) {
        const request = createMockInferenceRequest(modelId, {
          inputs: { feature: i },
          version: versionId,
        });
        await gateway.infer(request);
      }

      const stats = gateway.getBatcherStats()[0];
      expect(stats).toBeDefined();
      expect(stats.totalRequests).toBe(24);
      expect(stats.totalBatches).toBeGreaterThanOrEqual(3);
      expect(stats.totalBatches).toBeLessThanOrEqual(24);
      expect(stats.avgBatchSize).toBeGreaterThanOrEqual(1);
      expect(stats.avgBatchSize).toBeLessThanOrEqual(8);
    });
  });

  describe('Inference Caching', () => {
    it('should return cached result when available', async () => {
      const modelId = 'cache-test-model';
      const versionId = 'version-1';
      const inputs = { feature: [1, 2, 3] };
      const cachedResult = { prediction: 0.85, confidence: 0.95 };

      mockPrisma.modelVersion.findFirst.mockResolvedValue({
        id: versionId,
        modelId,
        createdAt: new Date(),
        sizeBytes: BigInt(1024),
        format: 'onnx',
      });
      mockRedis.get.mockResolvedValue(JSON.stringify(cachedResult));
      mockPrisma.inferenceMetrics.create.mockResolvedValue({});

      const request = createMockInferenceRequest(modelId, {
        inputs,
        version: versionId,
      });

      const result = await gateway.infer(request);

      expect(result.fromCache).toBe(true);
      expect(result.outputs).toEqual(cachedResult);
      expect(mockRedis.get).toHaveBeenCalled();
      expect(mockRedis.setex).not.toHaveBeenCalled();
    });

    it('should cache result after inference', async () => {
      const modelId = 'cache-write-model';
      const versionId = 'version-1';
      const inputs = { feature: [1, 2, 3] };
      const mockVersion = createMockModelVersion(modelId, {
        id: versionId,
        format: 'onnx' as ModelFormat,
      });

      mockPrisma.modelVersion.findUnique.mockResolvedValue({
        ...mockVersion,
        createdAt: new Date(mockVersion.createdAt),
        sizeBytes: BigInt(mockVersion.sizeBytes),
        format: 'onnx',
      });
      mockPrisma.modelVersion.findFirst.mockResolvedValue({
        ...mockVersion,
        createdAt: new Date(mockVersion.createdAt),
        sizeBytes: BigInt(mockVersion.sizeBytes),
        format: 'onnx',
      });
      modelStorage.getObject.mockResolvedValue(Buffer.from('onnx-model-data'));
      mockRedis.get.mockResolvedValue(null);
      mockPrisma.inferenceMetrics.create.mockResolvedValue({});

      const predictResult = { prediction: 0.85, confidence: 0.95 };
      vi.spyOn(modelLoaderRegistry.get('onnx'), 'batchPredict').mockResolvedValue([
        predictResult,
      ]);

      await gateway.loadModel(modelId, versionId);

      const request = createMockInferenceRequest(modelId, {
        inputs,
        version: versionId,
      });

      const result = await gateway.infer(request);

      expect(result.fromCache).toBe(false);
      expect(result.outputs).toEqual(predictResult);
      expect(mockRedis.setex).toHaveBeenCalledWith(
        expect.any(String),
        300,
        JSON.stringify(predictResult)
      );
    });

    it('should bypass cache when bypassCache flag is set', async () => {
      const modelId = 'bypass-cache-model';
      const versionId = 'version-1';
      const mockVersion = createMockModelVersion(modelId, {
        id: versionId,
        format: 'onnx' as ModelFormat,
      });

      mockPrisma.modelVersion.findUnique.mockResolvedValue({
        ...mockVersion,
        createdAt: new Date(mockVersion.createdAt),
        sizeBytes: BigInt(mockVersion.sizeBytes),
        format: 'onnx',
      });
      mockPrisma.modelVersion.findFirst.mockResolvedValue({
        ...mockVersion,
        createdAt: new Date(mockVersion.createdAt),
        sizeBytes: BigInt(mockVersion.sizeBytes),
        format: 'onnx',
      });
      modelStorage.getObject.mockResolvedValue(Buffer.from('onnx-model-data'));
      mockPrisma.inferenceMetrics.create.mockResolvedValue({});

      const predictResult = { prediction: 0.85, confidence: 0.95 };
      vi.spyOn(modelLoaderRegistry.get('onnx'), 'batchPredict').mockResolvedValue([
        predictResult,
      ]);

      await gateway.loadModel(modelId, versionId);

      const request = createMockInferenceRequest(modelId, {
        inputs: { feature: [1, 2, 3] },
        version: versionId,
        bypassCache: true,
      });

      const result = await gateway.infer(request);

      expect(result.fromCache).toBe(false);
      expect(mockRedis.get).not.toHaveBeenCalled();
    });
  });

  describe('Gateway Status', () => {
    it('should return accurate gateway statistics', async () => {
      const modelId = 'status-test-model';
      const versionId = 'version-1';
      const mockVersion = createMockModelVersion(modelId, {
        id: versionId,
        format: 'onnx' as ModelFormat,
      });

      mockPrisma.modelVersion.findUnique.mockResolvedValue({
        ...mockVersion,
        createdAt: new Date(mockVersion.createdAt),
        sizeBytes: BigInt(mockVersion.sizeBytes),
        format: 'onnx',
      });
      mockPrisma.modelVersion.findFirst.mockResolvedValue({
        ...mockVersion,
        createdAt: new Date(mockVersion.createdAt),
        sizeBytes: BigInt(mockVersion.sizeBytes),
        format: 'onnx',
      });
      modelStorage.getObject.mockResolvedValue(Buffer.from('onnx-model-data'));
      mockPrisma.inferenceMetrics.create.mockResolvedValue({});

      vi.spyOn(modelLoaderRegistry.get('onnx'), 'batchPredict').mockImplementation(
        async (_, inputs) => {
          await delay(5);
          return inputs.map((__, i) => ({ prediction: i, confidence: 0.9 }));
        }
      );

      await gateway.loadModel(modelId, versionId);

      for (let i = 0; i < 10; i++) {
        const request = createMockInferenceRequest(modelId, {
          inputs: { feature: i },
          version: versionId,
        });
        await gateway.infer(request);
      }

      const status = gateway.getStatus();

      expect(status.totalRequests).toBe(10);
      expect(status.successRate).toBe(1);
      expect(status.avgLatencyMs).toBeGreaterThan(0);
      expect(status.p50LatencyMs).toBeGreaterThan(0);
      expect(status.p95LatencyMs).toBeGreaterThanOrEqual(status.p50LatencyMs);
      expect(status.p99LatencyMs).toBeGreaterThanOrEqual(status.p95LatencyMs);
      expect(status.uptimeMs).toBeGreaterThan(0);
      expect(status.loadModels).toHaveLength(2);
    });
  });
});

describe('InferenceGateway - Exception Path', () => {
  let gateway: InferenceGateway;
  const { modelStorage } = require('../../src/storage');
  const { logger } = require('../../src/config/logger');

  beforeEach(() => {
    resetAllMocks();
    vi.useRealTimers();
    gateway = new InferenceGateway();
  });

  afterEach(() => {
    vi.useFakeTimers();
  });

  it('should fallback to previous stable version when current version load fails', async () => {
    const modelId = 'fallback-test-model';
    const unstableVersion = createMockModelVersion(modelId, {
      id: 'unstable-version',
      version: '2.0.0-beta',
      format: 'onnx' as ModelFormat,
    });
    const stableVersion = createMockModelVersion(modelId, {
      id: 'stable-version',
      version: '1.0.0',
      format: 'onnx' as ModelFormat,
    });

    let callCount = 0;
    mockPrisma.modelVersion.findFirst.mockImplementation(() => {
      callCount++;
      if (callCount === 1) {
        return Promise.resolve({
          ...unstableVersion,
          createdAt: new Date(unstableVersion.createdAt),
          sizeBytes: BigInt(unstableVersion.sizeBytes),
          format: 'onnx',
        });
      }
      return Promise.resolve({
        ...stableVersion,
        createdAt: new Date(stableVersion.createdAt),
        sizeBytes: BigInt(stableVersion.sizeBytes),
        format: 'onnx',
      });
    });

    modelStorage.getObject.mockRejectedValueOnce(new Error('Corrupted model file'));
    modelStorage.getObject.mockResolvedValueOnce(Buffer.from('good-model-data'));
    mockPrisma.inferenceMetrics.create.mockResolvedValue({});

    vi.spyOn(modelLoaderRegistry.get('onnx'), 'batchPredict').mockResolvedValue([
      { prediction: 0.5, confidence: 0.8 },
    ]);

    const request = createMockInferenceRequest(modelId, {
      inputs: { feature: [1, 2, 3] },
    });

    const result = await gateway.infer(request);

    expect(result.modelId).toBe(modelId);
    expect(result.version).toBe('stable-version');
    expect(logger.error).toHaveBeenCalledWith(
      expect.objectContaining({
        error: expect.any(Error),
        modelId,
      }),
      'Failed to load model'
    );
  });

  it('should reject request when no ready version exists', async () => {
    const modelId = 'no-version-model';

    mockPrisma.modelVersion.findFirst.mockResolvedValue(null);

    const request = createMockInferenceRequest(modelId, {
      inputs: { feature: [1, 2, 3] },
    });

    await expect(gateway.infer(request)).rejects.toThrow(
      `No ready version found for model: ${modelId}`
    );
  });

  it('should handle model file download failure', async () => {
    const modelId = 'download-fail-model';
    const versionId = 'version-1';

    mockPrisma.modelVersion.findUnique.mockResolvedValue({
      id: versionId,
      modelId,
      createdAt: new Date(),
      sizeBytes: BigInt(1024),
      format: 'onnx',
      storagePath: 'models/test/model.bin',
    });

    modelStorage.getObject.mockRejectedValue(new Error('Network timeout'));

    await expect(gateway.loadModel(modelId, versionId)).rejects.toThrow('Network timeout');
    expect(logger.error).toHaveBeenCalled();
  });

  it('should handle batch inference failure gracefully', async () => {
    const modelId = 'batch-fail-model';
    const versionId = 'version-1';
    const mockVersion = createMockModelVersion(modelId, {
      id: versionId,
      format: 'onnx' as ModelFormat,
    });

    mockPrisma.modelVersion.findUnique.mockResolvedValue({
      ...mockVersion,
      createdAt: new Date(mockVersion.createdAt),
      sizeBytes: BigInt(mockVersion.sizeBytes),
      format: 'onnx',
    });
    mockPrisma.modelVersion.findFirst.mockResolvedValue({
      ...mockVersion,
      createdAt: new Date(mockVersion.createdAt),
      sizeBytes: BigInt(mockVersion.sizeBytes),
      format: 'onnx',
    });
    modelStorage.getObject.mockResolvedValue(Buffer.from('onnx-model-data'));
    mockPrisma.inferenceMetrics.create.mockResolvedValue({});

    vi
      .spyOn(modelLoaderRegistry.get('onnx'), 'batchPredict')
      .mockRejectedValue(new Error('GPU out of memory'));

    await gateway.loadModel(modelId, versionId);

    const request = createMockInferenceRequest(modelId, {
      inputs: { feature: [1, 2, 3] },
      version: versionId,
    });

    await expect(gateway.infer(request)).rejects.toThrow('GPU out of memory');
    expect(mockPrisma.inferenceMetrics.create).toHaveBeenCalledWith(
      expect.objectContaining({
        success: false,
        error: 'GPU out of memory',
      })
    );
  });

  it('should handle variant latency surge and trigger circuit breaker', async () => {
    const modelId = 'circuit-breaker-model';
    const versionId = 'version-1';
    const mockVersion = createMockModelVersion(modelId, {
      id: versionId,
      format: 'onnx' as ModelFormat,
    });

    mockPrisma.modelVersion.findUnique.mockResolvedValue({
      ...mockVersion,
      createdAt: new Date(mockVersion.createdAt),
      sizeBytes: BigInt(mockVersion.sizeBytes),
      format: 'onnx',
    });
    mockPrisma.modelVersion.findFirst.mockResolvedValue({
      ...mockVersion,
      createdAt: new Date(mockVersion.createdAt),
      sizeBytes: BigInt(mockVersion.sizeBytes),
      format: 'onnx',
    });
    modelStorage.getObject.mockResolvedValue(Buffer.from('onnx-model-data'));
    mockPrisma.inferenceMetrics.create.mockResolvedValue({});

    let requestCount = 0;
    vi.spyOn(modelLoaderRegistry.get('onnx'), 'batchPredict').mockImplementation(async (_, inputs) => {
      requestCount++;
      if (requestCount > 5) {
        await delay(500);
      }
      return inputs.map((__, i) => ({ prediction: i, confidence: 0.9 }));
    });

    await gateway.loadModel(modelId, versionId);

    const latencies: number[] = [];
    for (let i = 0; i < 10; i++) {
      const request = createMockInferenceRequest(modelId, {
        inputs: { feature: [i] },
        version: versionId,
      });
      const start = Date.now();
      try {
        await gateway.infer(request);
        latencies.push(Date.now() - start);
      } catch (e) {
        latencies.push(Date.now() - start);
      }
    }

    const status = gateway.getStatus();
    expect(status.p99LatencyMs).toBeGreaterThan(100);
    expect(logger.warn).toHaveBeenCalled();
  });
});

describe('InferenceGateway - Concurrency Scenarios', () => {
  let gateway: InferenceGateway;
  const { modelStorage } = require('../../src/storage');

  beforeEach(() => {
    resetAllMocks();
    vi.useRealTimers();
    gateway = new InferenceGateway();
  });

  afterEach(() => {
    vi.useFakeTimers();
  });

  it('should deduplicate concurrent load requests for the same model', async () => {
    const modelId = 'dedup-load-model';
    const versionId = 'version-1';
    const mockVersion = createMockModelVersion(modelId, {
      id: versionId,
      format: 'onnx' as ModelFormat,
    });

    mockPrisma.modelVersion.findUnique.mockResolvedValue({
      ...mockVersion,
      createdAt: new Date(mockVersion.createdAt),
      sizeBytes: BigInt(mockVersion.sizeBytes),
      format: 'onnx',
    });
    modelStorage.getObject.mockImplementation(async () => {
      await delay(100);
      return Buffer.from('onnx-model-data');
    });

    const loadCount = 20;
    const promises = Array.from({ length: loadCount }, () =>
      gateway.loadModel(modelId, versionId)
    );

    const results = await Promise.all(promises);

    expect(results).toHaveLength(loadCount);
    const uniqueVersions = new Set(results.map((r) => r.version));
    expect(uniqueVersions.size).toBe(1);
    expect(mockPrisma.modelVersion.findUnique).toHaveBeenCalledTimes(1);
    expect(modelStorage.getObject).toHaveBeenCalledTimes(1);
  });

  it('should handle concurrent inference requests without race conditions', async () => {
    const modelId = 'concurrent-infer-model';
    const versionId = 'version-1';
    const mockVersion = createMockModelVersion(modelId, {
      id: versionId,
      format: 'onnx' as ModelFormat,
    });

    mockPrisma.modelVersion.findUnique.mockResolvedValue({
      ...mockVersion,
      createdAt: new Date(mockVersion.createdAt),
      sizeBytes: BigInt(mockVersion.sizeBytes),
      format: 'onnx',
    });
    mockPrisma.modelVersion.findFirst.mockResolvedValue({
      ...mockVersion,
      createdAt: new Date(mockVersion.createdAt),
      sizeBytes: BigInt(mockVersion.sizeBytes),
      format: 'onnx',
    });
    modelStorage.getObject.mockResolvedValue(Buffer.from('onnx-model-data'));
    mockPrisma.inferenceMetrics.create.mockResolvedValue({});

    vi.spyOn(modelLoaderRegistry.get('onnx'), 'batchPredict').mockImplementation(
      async (_, inputs) => {
        await delay(Math.random() * 10);
        return inputs.map((input, i) => ({
          prediction: i,
          input_hash: JSON.stringify(input),
        }));
      }
    );

    await gateway.loadModel(modelId, versionId);

    const requestCount = 50;
    const results = await concurrent(requestCount, (i) =>
      gateway.infer(
        createMockInferenceRequest(modelId, {
          inputs: { request_index: i },
          version: versionId,
        })
      )
    );

    expect(results).toHaveLength(requestCount);
    results.forEach((result, idx) => {
      expect(result.success ?? true).toBe(true);
      expect(result.outputs).toBeDefined();
    });

    const status = gateway.getStatus();
    expect(status.totalRequests).toBe(requestCount);
    expect(status.successRate).toBe(1);
  });

  it('should correctly handle mixed read and cache operations', async () => {
    const modelId = 'mixed-ops-model';
    const versionId = 'version-1';
    const mockVersion = createMockModelVersion(modelId, {
      id: versionId,
      format: 'onnx' as ModelFormat,
    });

    mockPrisma.modelVersion.findUnique.mockResolvedValue({
      ...mockVersion,
      createdAt: new Date(mockVersion.createdAt),
      sizeBytes: BigInt(mockVersion.sizeBytes),
      format: 'onnx',
    });
    mockPrisma.modelVersion.findFirst.mockResolvedValue({
      ...mockVersion,
      createdAt: new Date(mockVersion.createdAt),
      sizeBytes: BigInt(mockVersion.sizeBytes),
      format: 'onnx',
    });
    modelStorage.getObject.mockResolvedValue(Buffer.from('onnx-model-data'));
    mockPrisma.inferenceMetrics.create.mockResolvedValue({});

    const predictSpy = vi
      .spyOn(modelLoaderRegistry.get('onnx'), 'batchPredict')
      .mockImplementation(async (_, inputs) => {
        return inputs.map((__, i) => ({ prediction: i, confidence: 0.9 }));
      });

    await gateway.loadModel(modelId, versionId);

    const sameInputs = { features: [1, 2, 3, 4, 5] };
    const requestCount = 10;

    const results = await concurrent(requestCount, () =>
      gateway.infer(
        createMockInferenceRequest(modelId, {
          inputs: sameInputs,
          version: versionId,
        })
      )
    );

    expect(results).toHaveLength(requestCount);
    expect(predictSpy).toHaveBeenCalledTimes(1);

    const fromCacheCount = results.filter((r) => r.fromCache).length;
    expect(fromCacheCount).toBeGreaterThanOrEqual(requestCount - 1);
  });

  it('should handle concurrent unload and inference requests gracefully', async () => {
    const modelId = 'unload-infer-model';
    const versionId = 'version-1';
    const mockVersion = createMockModelVersion(modelId, {
      id: versionId,
      format: 'onnx' as ModelFormat,
    });

    mockPrisma.modelVersion.findUnique.mockResolvedValue({
      ...mockVersion,
      createdAt: new Date(mockVersion.createdAt),
      sizeBytes: BigInt(mockVersion.sizeBytes),
      format: 'onnx',
    });
    mockPrisma.modelVersion.findFirst.mockResolvedValue({
      ...mockVersion,
      createdAt: new Date(mockVersion.createdAt),
      sizeBytes: BigInt(mockVersion.sizeBytes),
      format: 'onnx',
    });
    modelStorage.getObject.mockResolvedValue(Buffer.from('onnx-model-data'));
    mockPrisma.inferenceMetrics.create.mockResolvedValue({});

    vi.spyOn(modelLoaderRegistry.get('onnx'), 'batchPredict').mockImplementation(
      async (_, inputs) => {
        await delay(20);
        return inputs.map((__, i) => ({ prediction: i, confidence: 0.9 }));
      }
    );

    await gateway.loadModel(modelId, versionId);

    const operations = [];
    for (let i = 0; i < 20; i++) {
      if (i % 5 === 0) {
        operations.push(gateway.unloadModel(modelId, versionId));
      } else {
        operations.push(
          gateway.infer(
            createMockInferenceRequest(modelId, {
              inputs: { index: i },
              version: versionId,
            })
          ).catch(() => null)
        );
      }
    }

    const results = await Promise.allSettled(operations);

    expect(results).toHaveLength(20);
    const fulfilledCount = results.filter((r) => r.status === 'fulfilled').length;
    expect(fulfilledCount).toBeGreaterThan(0);
  });
});
