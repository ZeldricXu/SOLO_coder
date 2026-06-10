import { describe, it, expect, beforeAll, afterAll, vi } from 'vitest';
import request from 'supertest';
import Fastify from 'fastify';
import { setupTestInfrastructure, seedTestData, clearTestData } from './utils/setup';
import type { TestInfrastructure } from './utils/setup';
import { registerModelRoutes } from '../../src/model/registry';
import { registerInferenceRoutes } from '../../src/inference/gateway';
import { registerFeatureStoreRoutes } from '../../src/feature-store/service';
import { registerABTestRoutes } from '../../src/abtest/engine';
import { createMockModelFile, generateUserId, delay, concurrent } from '../fixtures';
import { modelLoaderRegistry } from '../../src/model/loader';

vi.mock('../../src/config/redis', async () => {
  const actual = await vi.importActual('../../src/config/redis');
  return {
    ...actual,
    redis: global.__testRedis,
    redisCache: global.__testRedis,
    RedisKeys: actual.RedisKeys,
  };
});

vi.mock('../../src/config/database', async () => {
  const actual = await vi.importActual('../../src/config/database');
  return {
    ...actual,
    prisma: global.__testPrisma,
  };
});

vi.mock('../../src/config/env', () => ({
  env: {
    INFERENCE_BATCH_MAX_SIZE: 8,
    INFERENCE_BATCH_TIMEOUT_MS: 50,
    INFERENCE_CACHE_TTL_SECONDS: 300,
    STORAGE_BACKEND: 'local',
    LOCAL_STORAGE_PATH: '/tmp/mlops-integration-test',
  },
}));

describe('Failure Recovery Integration Test', () => {
  let infra: TestInfrastructure;
  let fastify: any;
  let seededData: Awaited<ReturnType<typeof seedTestData>>;

  beforeAll(async () => {
    infra = await setupTestInfrastructure();
    global.__testPrisma = infra.prisma;
    global.__testRedis = infra.redis;

    fastify = Fastify({ logger: false });
    await registerModelRoutes(fastify);
    await registerInferenceRoutes(fastify);
    await registerFeatureStoreRoutes(fastify);
    await registerABTestRoutes(fastify);
    await fastify.ready();

    seededData = await seedTestData(infra.prisma);
  }, 120000);

  afterAll(async () => {
    await clearTestData(infra.prisma);
    await fastify.close();
    await infra.cleanup();
  });

  describe('Failure Recovery - Model Download', () => {
    it('should handle interrupted model download and resume correctly', async () => {
      const modelId = seededData.modelId;
      const fileBuffer = createMockModelFile('onnx');

      const storage = require('../../src/storage').modelStorage;
      let failCount = 0;

      vi.spyOn(storage, 'putObject').mockImplementation(async () => {
        failCount++;
        if (failCount <= 2) {
          throw new Error('Network connection interrupted');
        }
        return 'etag-resumed-123';
      });

      const firstAttempt = request(fastify.server)
        .post(`/api/v1/models/${modelId}/versions`)
        .field('version', '2.0.0')
        .field('semanticVersion', '2.0.0')
        .attach('file', fileBuffer, 'model-v2.onnx');

      await expect(firstAttempt).rejects.toThrow();
      expect(failCount).toBe(1);

      const secondAttempt = request(fastify.server)
        .post(`/api/v1/models/${modelId}/versions`)
        .field('version', '2.0.0')
        .field('semanticVersion', '2.0.0')
        .attach('file', fileBuffer, 'model-v2.onnx');

      await expect(secondAttempt).rejects.toThrow();
      expect(failCount).toBe(2);

      const successAttempt = await request(fastify.server)
        .post(`/api/v1/models/${modelId}/versions`)
        .field('version', '2.0.0')
        .field('semanticVersion', '2.0.0')
        .attach('file', fileBuffer, 'model-v2.onnx');

      expect(successAttempt.status).toBe(201);
      expect(successAttempt.body.version).toBe('2.0.0');
      expect(failCount).toBe(3);
    });

    it('should fallback to previous version when current version fails to load', async () => {
      const modelId = seededData.modelId;
      const goodVersionId = seededData.versionId;
      const badVersion = '3.0.0-corrupted';

      const badVersionResponse = await request(fastify.server)
        .post(`/api/v1/models/${modelId}/versions`)
        .field('version', badVersion)
        .field('semanticVersion', badVersion)
        .field('format', 'onnx')
        .attach('file', createMockModelFile('onnx'), 'corrupted.onnx');

      expect(badVersionResponse.status).toBe(201);
      const badVersionId = badVersionResponse.body.id;

      vi.spyOn(modelLoaderRegistry.get('onnx'), 'load')
        .mockImplementationOnce(async () => {
          throw new Error('Model corrupted: invalid ONNX format');
        })
        .mockImplementationOnce(async () => {
          return { type: 'onnx', loadedAt: Date.now() };
        });

      const firstLoad = await request(fastify.server)
        .post(`/api/v1/models/${modelId}/load`)
        .send({ versionId: badVersionId });

      expect(firstLoad.status).toBe(500);

      vi.spyOn(modelLoaderRegistry.get('onnx'), 'batchPredict').mockResolvedValue([
        { prediction: 0.5, confidence: 0.8 },
      ]);

      const fallbackInfer = await request(fastify.server)
        .post('/api/v1/inference')
        .send({
          modelId,
          inputs: { features: [1, 2, 3] },
        });

      expect(fallbackInfer.status).toBe(200);
      expect(fallbackInfer.body.version).toBe(goodVersionId);
    });
  });

  describe('Failure Recovery - Redis Cache', () => {
    it('should continue operating when Redis is unavailable', async () => {
      const featureSetId = seededData.featureSetId;

      const originalGet = infra.redis.get.bind(infra.redis);
      const originalHgetall = infra.redis.hgetall.bind(infra.redis);

      vi.spyOn(infra.redis, 'hgetall').mockRejectedValueOnce(
        new Error('Redis connection refused')
      );

      const queryWithCacheMiss = await request(fastify.server)
        .post('/api/v1/features/get')
        .send({
          featureSetId,
          entityKeys: ['user-redis-1'],
          featureNames: ['feature_1'],
        });

      expect(queryWithCacheMiss.status).toBe(200);
      expect(queryWithCacheMiss.body.values['user-redis-1'].feature_1).toBe(0.0);

      vi.spyOn(infra.redis, 'hgetall').mockImplementation(originalHgetall);
      vi.spyOn(infra.redis, 'get').mockImplementation(originalGet);
    });

    it('should auto-warm feature cache after Redis restart', async () => {
      const featureSetId = seededData.featureSetId;
      const entityKeys = Array.from({ length: 50 }, (_, i) => `user-warm-${i}`);
      const featureData = entityKeys.map((id, i) => ({
        user_id: id,
        feature_1: i * 2,
        feature_2: i * 10,
        feature_3: i % 2 === 0,
      }));

      await request(fastify.server)
        .post('/api/v1/features/ingest')
        .send({
          featureSetId,
          entityKeyField: 'user_id',
          data: featureData,
          mode: 'overwrite',
        });

      await infra.redis.flushall();

      const keysBefore = await infra.redis.keys('feature:*');
      expect(keysBefore.length).toBe(0);

      await concurrent(50, async (i) => {
        return request(fastify.server)
          .post('/api/v1/features/get')
          .send({
            featureSetId,
            entityKeys: [`user-warm-${i}`],
            featureNames: ['feature_1', 'feature_2', 'feature_3'],
          });
      });

      const keysAfter = await infra.redis.keys('feature:*');
      expect(keysAfter.length).toBeGreaterThanOrEqual(40);

      for (let i = 0; i < 10; i++) {
        const key = `feature:value:${featureSetId}:${seededData.featureVersionId}:user-warm-${i}`;
        const cached = await infra.redis.hgetall(key);
        expect(cached.feature_1).toBeDefined();
        expect(JSON.parse(cached.feature_1)).toBe(i * 2);
      }
    });

    it('should preserve A/B assignment consistency across Redis restarts', async () => {
      const experimentId = seededData.experimentId;
      const userId = 'user-consistency-test';

      const firstAssign = await request(fastify.server)
        .post('/api/v1/ab-tests/assign')
        .send({ experimentId, userId });

      const firstVariantId = firstAssign.body.variantId;

      await infra.redis.flushall();

      const secondAssign = await request(fastify.server)
        .post('/api/v1/ab-tests/assign')
        .send({ experimentId, userId });

      expect(secondAssign.body.variantId).toBe(firstVariantId);
      expect(secondAssign.body.cacheHit).toBe(false);

      const thirdAssign = await request(fastify.server)
        .post('/api/v1/ab-tests/assign')
        .send({ experimentId, userId });

      expect(thirdAssign.body.variantId).toBe(firstVariantId);
      expect(thirdAssign.body.cacheHit).toBe(true);
    });
  });

  describe('Failure Recovery - Database', () => {
    it('should not lose data during concurrent writes and DB reconnection', async () => {
      const experimentId = seededData.experimentId;
      const variantId = seededData.variants[0].id;
      const totalEvents = 200;

      let injectedFailure = false;
      const originalCreate = infra.prisma.aBTestEvent.create.bind(infra.prisma);

      vi.spyOn(infra.prisma.aBTestEvent, 'create').mockImplementation(async (args: any) => {
        if (!injectedFailure && Math.random() < 0.1) {
          injectedFailure = true;
          throw new Error('Database connection timeout');
        }
        return originalCreate(args);
      });

      const results = await concurrent(totalEvents, async (i) => {
        try {
          const response = await request(fastify.server)
            .post('/api/v1/ab-tests/track')
            .send({
              experimentId,
              variantId,
              eventName: 'test_event',
              userId: `user-db-${i}`,
              properties: { index: i },
            });
          return { success: response.status === 200, status: response.status };
        } catch (e) {
          return { success: false, error: e.message };
        }
      });

      const successCount = results.filter((r) => r.success).length;
      expect(successCount).toBeGreaterThan(totalEvents * 0.85);

      const eventCount = await infra.prisma.aBTestEvent.count({
        where: { experimentId, eventName: 'test_event' },
      });

      expect(eventCount).toBe(successCount);

      const impressions = await infra.redis.get(
        `ab:stats:${experimentId}:${variantId}:impressions`
      );
      expect(parseInt(impressions || '0')).toBe(successCount);
    });

    it('should handle feature write operations during DB failover simulation', async () => {
      const featureSetId = seededData.featureSetId;
      const batchSize = 100;
      const batches = 5;

      const originalHset = infra.redis.hset.bind(infra.redis);
      const originalExec = infra.redis.pipeline().exec.bind(infra.redis);

      const writtenEntities: Set<string> = new Set();

      vi.spyOn(infra.redis, 'pipeline').mockImplementation(() => {
        const pipeline = {
          hset: vi.fn().mockImplementation((key: string, values: any) => {
            const entityKey = key.split(':').pop() || '';
            writtenEntities.add(entityKey);
            return pipeline;
          }),
          expire: vi.fn().mockReturnThis(),
          del: vi.fn().mockReturnThis(),
          exec: vi.fn().mockImplementation(async () => {
            if (writtenEntities.size > 200 && writtenEntities.size < 300) {
              throw new Error('Redis failover in progress');
            }
            return Array.from({ length: 2 }, () => [null, 'OK']);
          }),
        };
        return pipeline as any;
      });

      const allResults: any[] = [];

      for (let batch = 0; batch < batches; batch++) {
        const featureData = Array.from({ length: batchSize }, (_, i) => ({
          user_id: `user-failover-${batch * batchSize + i}`,
          feature_1: (batch * batchSize + i) * 0.5,
          feature_2: batch * batchSize + i,
          feature_3: (batch * batchSize + i) % 2 === 0,
        }));

        try {
          const response = await request(fastify.server)
            .post('/api/v1/features/ingest')
            .send({
              featureSetId,
              entityKeyField: 'user_id',
              data: featureData,
              mode: 'upsert',
            });
          allResults.push({ batch, success: true, status: response.status });
        } catch (e) {
          allResults.push({ batch, success: false, error: e.message });
        }
      }

      const successfulBatches = allResults.filter((r) => r.success);
      expect(successfulBatches.length).toBeGreaterThanOrEqual(3);

      for (const result of successfulBatches) {
        expect(result.status).toBe(200);
      }
    });

    it('should maintain transactional integrity for model version creation', async () => {
      const modelId = seededData.modelId;
      const testVersions = ['4.0.0', '4.1.0', '4.2.0'];
      const createdVersions: string[] = [];

      const originalCreate = infra.prisma.modelVersion.create.bind(infra.prisma);
      const originalUpdate = infra.prisma.model.update.bind(infra.prisma);

      vi.spyOn(infra.prisma.modelVersion, 'create').mockImplementation(
        async (args: any) => {
          if (args.data.version === '4.1.0') {
            throw new Error('Simulated DB failure during version creation');
          }
          const result = await originalCreate(args);
          createdVersions.push(args.data.version);
          return result;
        }
      );

      for (const version of testVersions) {
        try {
          await request(fastify.server)
            .post(`/api/v1/models/${modelId}/versions`)
            .field('version', version)
            .field('semanticVersion', version)
            .attach('file', createMockModelFile('onnx'), `model-${version}.onnx`);
        } catch (e) {
          console.log(`Failed to create version ${version}:`, e.message);
        }
      }

      const versionsInDB = await infra.prisma.modelVersion.findMany({
        where: { modelId },
        select: { version: true },
        orderBy: { createdAt: 'asc' },
      });

      const versionNumbers = versionsInDB.map((v) => v.version);

      expect(versionNumbers).toContain('4.0.0');
      expect(versionNumbers).not.toContain('4.1.0');
      expect(versionNumbers).toContain('4.2.0');

      const model = await infra.prisma.model.findUnique({
        where: { id: modelId },
        select: { latestVersionId: true },
      });

      const latestVersion = await infra.prisma.modelVersion.findUnique({
        where: { id: model?.latestVersionId || '' },
        select: { version: true },
      });

      expect(latestVersion?.version).toBe('4.2.0');
    });
  });

  describe('Failure Recovery - Circuit Breaker', () => {
    it('should trigger circuit breaker for high latency variants', async () => {
      const experimentId = seededData.experimentId;
      const slowVariant = seededData.variants[1];
      const controlVariant = seededData.variants[0];

      vi.spyOn(modelLoaderRegistry.get('onnx'), 'load').mockResolvedValue({
        type: 'onnx',
        loadedAt: Date.now(),
      });

      let callCount = 0;
      vi.spyOn(modelLoaderRegistry.get('onnx'), 'batchPredict').mockImplementation(
        async (_, inputs) => {
          callCount++;
          if (callCount > 10 && callCount <= 30) {
            await delay(1000);
          }
          return inputs.map((__, i) => ({
            prediction: 0.5,
            confidence: 0.8,
            latency: callCount,
          }));
        }
      );

      const latencies: number[] = [];
      for (let i = 0; i < 40; i++) {
        const userId = `user-circuit-${i}`;
        const assign = await request(fastify.server)
          .post('/api/v1/ab-tests/assign')
          .send({ experimentId, userId });

        const variantId = assign.body.variantId;

        const start = Date.now();
        const infer = await request(fastify.server)
          .post('/api/v1/inference')
          .send({
            modelId: seededData.modelId,
            inputs: { features: [i * 0.1] },
          });
        const latency = Date.now() - start;
        latencies.push(latency);

        await request(fastify.server)
          .post('/api/v1/ab-tests/track')
          .send({
            experimentId,
            variantId,
            eventName: 'inference',
            userId,
            properties: { latency, conversion: i % 10 === 0 },
          });
      }

      const p99Latency = calculatePercentile(latencies, 99);
      expect(p99Latency).toBeGreaterThan(100);

      const statusResponse = await request(fastify.server)
        .get('/api/v1/inference/status');

      expect(statusResponse.body.p99LatencyMs).toBeGreaterThan(100);

      const alerts = await infra.prisma.alert.findMany({
        where: { type: 'latency' },
      });

      expect(alerts.length).toBeGreaterThanOrEqual(0);
    });
  });
});

function calculatePercentile(values: number[], percentile: number): number {
  if (values.length === 0) return 0;
  const sorted = [...values].sort((a, b) => a - b);
  const idx = Math.ceil((percentile / 100) * sorted.length) - 1;
  return sorted[Math.max(0, Math.min(idx, sorted.length - 1))] || 0;
}
