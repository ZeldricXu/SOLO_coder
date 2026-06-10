import { describe, it, expect, beforeAll, afterAll, vi } from 'vitest';
import nock from 'nock';
import request from 'supertest';
import Fastify from 'fastify';
import { setupTestInfrastructure, seedTestData, clearTestData } from './utils/setup';
import type { TestInfrastructure } from './utils/setup';
import { registerModelRoutes } from '../../src/model/registry';
import { registerInferenceRoutes } from '../../src/inference/gateway';
import { registerFeatureStoreRoutes } from '../../src/feature-store/service';
import { registerABTestRoutes } from '../../src/abtest/engine';
import { createMockModelFile, generateUserId, delay } from '../fixtures';
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

vi.mock('../../src/storage', () => ({
  modelStorage: {
    putObject: vi.fn().mockResolvedValue('etag-123'),
    getObject: vi.fn().mockResolvedValue(Buffer.from('model-data')),
    getDownloadUrl: vi.fn().mockResolvedValue('/tmp/model.bin'),
    deleteObject: vi.fn().mockResolvedValue(undefined),
    getType: () => 'local',
  },
  featureStorage: {
    putObject: vi.fn().mockResolvedValue('etag-456'),
    getObject: vi.fn().mockResolvedValue(Buffer.from('feature-data')),
    getType: () => 'local',
  },
}));

describe('Full E2E Integration Test', () => {
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

  describe('Step 1: Model Registry Flow', () => {
    it('should upload model to registry and create version', async () => {
      const modelId = seededData.modelId;
      const fileBuffer = createMockModelFile('onnx');

      const response = await request(fastify.server)
        .post(`/api/v1/models/${modelId}/versions`)
        .field('version', '1.1.0')
        .field('semanticVersion', '1.1.0')
        .field('format', 'onnx')
        .field('metrics', JSON.stringify([{ name: 'accuracy', value: 0.96, timestamp: new Date().toISOString() }]))
        .field('hyperParameters', JSON.stringify({ learning_rate: 0.0005, epochs: 500 }))
        .attach('file', fileBuffer, 'model-v1.1.0.onnx');

      expect(response.status).toBe(201);
      expect(response.body.version).toBe('1.1.0');
      expect(response.body.format).toBe('onnx');
      expect(response.body.metrics).toBeDefined();
      expect(response.body.hyperParameters.learning_rate).toBe(0.0005);
    });

    it('should list models and versions correctly', async () => {
      const listResponse = await request(fastify.server)
        .get('/api/v1/models')
        .query({ page: 1, pageSize: 10 });

      expect(listResponse.status).toBe(200);
      expect(listResponse.body.total).toBeGreaterThanOrEqual(1);
      expect(listResponse.body.data[0].id).toBe(seededData.modelId);

      const versionsResponse = await request(fastify.server)
        .get(`/api/v1/models/${seededData.modelId}/versions`);

      expect(versionsResponse.status).toBe(200);
      expect(versionsResponse.body.data.length).toBeGreaterThanOrEqual(2);
    });

    it('should download model version file', async () => {
      const downloadResponse = await request(fastify.server)
        .get(`/api/v1/versions/${seededData.versionId}/download`);

      expect(downloadResponse.status).toBe(200);
      expect(downloadResponse.headers['content-disposition']).toContain('attachment');
      expect(Buffer.isBuffer(downloadResponse.body)).toBe(true);
    });
  });

  describe('Step 2: Inference Gateway Flow', () => {
    it('should load model into gateway', async () => {
      vi.spyOn(modelLoaderRegistry.get('onnx'), 'load').mockResolvedValue({
        type: 'onnx',
        loadedAt: Date.now(),
      });

      const loadResponse = await request(fastify.server)
        .post(`/api/v1/models/${seededData.modelId}/load`)
        .send({ versionId: seededData.versionId });

      expect(loadResponse.status).toBe(200);
      expect(loadResponse.body.modelId).toBe(seededData.modelId);
      expect(loadResponse.body.status).toBe('loaded');
    });

    it('should handle single inference request', async () => {
      vi.spyOn(modelLoaderRegistry.get('onnx'), 'batchPredict').mockResolvedValue([
        { prediction: 0.85, confidence: 0.95 },
      ]);

      const inferResponse = await request(fastify.server)
        .post('/api/v1/inference')
        .send({
          modelId: seededData.modelId,
          version: seededData.versionId,
          inputs: {
            features: [0.1, 0.2, 0.3, 0.4, 0.5],
          },
          userId: 'user-123',
        });

      expect(inferResponse.status).toBe(200);
      expect(inferResponse.body.modelId).toBe(seededData.modelId);
      expect(inferResponse.body.outputs.prediction).toBeDefined();
      expect(inferResponse.body.latencyMs).toBeGreaterThanOrEqual(0);
    });

    it('should handle batch inference request', async () => {
      const batchInputs = Array.from({ length: 20 }, (_, i) => ({
        features: [i * 0.05, Math.random(), Math.random(), Math.random(), Math.random()],
      }));

      vi.spyOn(modelLoaderRegistry.get('onnx'), 'batchPredict').mockImplementation(
        async (_, inputs) => {
          return inputs.map((__, idx) => ({
            prediction: 0.5 + idx * 0.02,
            confidence: 0.8 + idx * 0.01,
          }));
        }
      );

      const batchResponse = await request(fastify.server)
        .post('/api/v1/inference/batch')
        .send({
          modelId: seededData.modelId,
          version: seededData.versionId,
          inputs: batchInputs,
          batchSize: 8,
        });

      expect(batchResponse.status).toBe(200);
      expect(batchResponse.body.outputs).toHaveLength(20);
      expect(batchResponse.body.batchCount).toBe(3);
    });

    it('should return cached results for identical inputs', async () => {
      const inputs = { features: [0.1, 0.2, 0.3, 0.4, 0.5] };

      vi.spyOn(modelLoaderRegistry.get('onnx'), 'batchPredict').mockResolvedValue([
        { prediction: 0.75, confidence: 0.9 },
      ]);

      const firstResponse = await request(fastify.server)
        .post('/api/v1/inference')
        .send({
          modelId: seededData.modelId,
          version: seededData.versionId,
          inputs,
        });

      expect(firstResponse.body.fromCache).toBe(false);

      await delay(10);

      const secondResponse = await request(fastify.server)
        .post('/api/v1/inference')
        .send({
          modelId: seededData.modelId,
          version: seededData.versionId,
          inputs,
        });

      expect(secondResponse.status).toBe(200);
      expect(secondResponse.body.fromCache).toBe(true);
      expect(secondResponse.body.latencyMs).toBeLessThan(firstResponse.body.latencyMs);
    });

    it('should get gateway status with metrics', async () => {
      const statusResponse = await request(fastify.server)
        .get('/api/v1/inference/status');

      expect(statusResponse.status).toBe(200);
      expect(statusResponse.body.totalRequests).toBeGreaterThan(0);
      expect(statusResponse.body.successRate).toBeLessThanOrEqual(1);
      expect(statusResponse.body.loadModels.length).toBeGreaterThan(0);
      expect(statusResponse.body.p99LatencyMs).toBeGreaterThanOrEqual(0);
    });
  });

  describe('Step 3: Feature Store Flow', () => {
    it('should ingest feature data online', async () => {
      const featureData = Array.from({ length: 100 }, (_, i) => ({
        user_id: `user-${i}`,
        feature_1: Math.random() * 100,
        feature_2: Math.floor(Math.random() * 1000),
        feature_3: Math.random() > 0.5,
      }));

      const ingestResponse = await request(fastify.server)
        .post('/api/v1/features/ingest')
        .send({
          featureSetId: seededData.featureSetId,
          entityKeyField: 'user_id',
          data: featureData,
          mode: 'overwrite',
        });

      expect(ingestResponse.status).toBe(200);
      expect(ingestResponse.body.ingestedCount).toBe(100);
    });

    it('should query online features for multiple entities', async () => {
      const entityKeys = ['user-0', 'user-1', 'user-2', 'user-3', 'user-4'];

      const getResponse = await request(fastify.server)
        .post('/api/v1/features/get')
        .send({
          featureSetId: seededData.featureSetId,
          entityKeys,
          featureNames: ['feature_1', 'feature_2', 'feature_3'],
        });

      expect(getResponse.status).toBe(200);
      expect(Object.keys(getResponse.body.values)).toHaveLength(5);

      for (const entityKey of entityKeys) {
        expect(getResponse.body.values[entityKey]).toBeDefined();
        expect(getResponse.body.values[entityKey].feature_1).toBeDefined();
        expect(typeof getResponse.body.values[entityKey].feature_1).toBe('number');
      }
    });

    it('should return feature distribution statistics', async () => {
      const distResponse = await request(fastify.server)
        .get(`/api/v1/feature-sets/${seededData.featureSetId}/features/feature_1/distribution`);

      expect(distResponse.status).toBe(200);
      expect(distResponse.body.featureName).toBe('feature_1');
      expect(distResponse.body.statistics.mean).toBeGreaterThan(0);
      expect(distResponse.body.statistics.mean).toBeLessThan(100);
      expect(distResponse.body.distribution).toBeDefined();
      expect(distResponse.body.distribution.bins).toHaveLength(11);
    });
  });

  describe('Step 4: A/B Test Flow', () => {
    it('should assign users to variants consistently', async () => {
      const userId = 'user-abtest-123';

      const firstAssign = await request(fastify.server)
        .post('/api/v1/ab-tests/assign')
        .send({
          experimentId: seededData.experimentId,
          userId,
        });

      expect(firstAssign.status).toBe(200);
      expect(firstAssign.body.experimentId).toBe(seededData.experimentId);
      expect(firstAssign.body.variantId).toBeDefined();
      expect(firstAssign.body.cacheHit).toBe(false);

      const secondAssign = await request(fastify.server)
        .post('/api/v1/ab-tests/assign')
        .send({
          experimentId: seededData.experimentId,
          userId,
        });

      expect(secondAssign.body.variantId).toBe(firstAssign.body.variantId);
      expect(secondAssign.body.cacheHit).toBe(true);
    });

    it('should track conversion events', async () => {
      const variantId = seededData.variants[0].id;
      const userId = 'user-abtest-456';

      const trackImpression = await request(fastify.server)
        .post('/api/v1/ab-tests/track')
        .send({
          experimentId: seededData.experimentId,
          variantId,
          eventName: 'page_view',
          userId,
          properties: { page: '/checkout' },
        });

      expect(trackImpression.status).toBe(200);
      expect(trackImpression.body.tracked).toBe(true);

      const trackConversion = await request(fastify.server)
        .post('/api/v1/ab-tests/track')
        .send({
          experimentId: seededData.experimentId,
          variantId,
          eventName: 'purchase',
          userId,
          properties: { conversion: true, value: 99.99 },
        });

      expect(trackConversion.status).toBe(200);
    });

    it('should get real-time stats', async () => {
      for (let i = 0; i < 100; i++) {
        const userId = `user-stats-${i}`;
        const assign = await request(fastify.server)
          .post('/api/v1/ab-tests/assign')
          .send({
            experimentId: seededData.experimentId,
            userId,
          });

        await request(fastify.server)
          .post('/api/v1/ab-tests/track')
          .send({
            experimentId: seededData.experimentId,
            variantId: assign.body.variantId,
            eventName: 'impression',
            userId,
            properties: { value: 1 },
          });

        if (i % 4 === 0) {
          await request(fastify.server)
            .post('/api/v1/ab-tests/track')
            .send({
              experimentId: seededData.experimentId,
              variantId: assign.body.variantId,
              eventName: 'conversion',
              userId,
              properties: { conversion: true, value: 1 },
            });
        }
      }

      const statsResponse = await request(fastify.server)
        .get(`/api/v1/ab-tests/${seededData.experimentId}/stats`);

      expect(statsResponse.status).toBe(200);
      expect(statsResponse.body.variantStats).toBeDefined();

      let totalImpressions = 0;
      let totalConversions = 0;

      for (const variant of seededData.variants) {
        const stats = statsResponse.body.variantStats[variant.id];
        expect(stats).toBeDefined();
        expect(stats.impressions).toBeGreaterThan(0);
        totalImpressions += stats.impressions;
        totalConversions += stats.conversions.conversion_rate;
      }

      expect(totalImpressions).toBe(100);
      expect(totalConversions).toBe(25);
    });

    it('should calculate statistical results', async () => {
      const resultsResponse = await request(fastify.server)
        .post(`/api/v1/ab-tests/${seededData.experimentId}/results`)
        .send();

      expect(resultsResponse.status).toBe(200);
      expect(resultsResponse.body.status).toBe('ready');

      for (const variant of seededData.variants) {
        const variantResult = resultsResponse.body.variantResults[variant.id];
        expect(variantResult).toBeDefined();
        expect(variantResult.sampleSize).toBeGreaterThan(0);
        expect(variantResult.metricValues.conversion_rate).toBeDefined();
        expect(variantResult.metricValues.conversion_rate.mean).toBeGreaterThan(0);
        expect(variantResult.metricValues.conversion_rate.pValue).toBeDefined();
      }
    });
  });

  describe('Step 5: Monitoring & Alerting', () => {
    it('should track inference metrics correctly', async () => {
      vi.spyOn(modelLoaderRegistry.get('onnx'), 'batchPredict').mockResolvedValue([
        { prediction: 0.5, confidence: 0.8 },
      ]);

      const latencies: number[] = [];
      for (let i = 0; i < 50; i++) {
        const start = Date.now();
        const response = await request(fastify.server)
          .post('/api/v1/inference')
          .send({
            modelId: seededData.modelId,
            version: seededData.versionId,
            inputs: { features: [i * 0.02, Math.random(), Math.random()] },
            bypassCache: true,
          });
        latencies.push(response.body.latencyMs);
      }

      const p99 = calculatePercentile(latencies, 99);
      const statusResponse = await request(fastify.server)
        .get('/api/v1/inference/status');

      expect(statusResponse.body.p99LatencyMs).toBeGreaterThanOrEqual(0);
      expect(statusResponse.body.totalRequests).toBeGreaterThanOrEqual(50);
    });

    it('should compare experiment variants in dashboard', async () => {
      const experimentsResponse = await request(fastify.server)
        .get('/api/v1/ab-tests')
        .query({ status: 'running' });

      expect(experimentsResponse.status).toBe(200);
      expect(experimentsResponse.body.data.length).toBeGreaterThan(0);

      const experiment = experimentsResponse.body.data.find(
        (e: any) => e.id === seededData.experimentId
      );
      expect(experiment).toBeDefined();
      expect(experiment.variants).toHaveLength(3);

      const control = experiment.variants.find((v: any) => v.isControl);
      expect(control).toBeDefined();
      expect(control.trafficPercentage).toBe(50);
    });
  });
});

function calculatePercentile(values: number[], percentile: number): number {
  if (values.length === 0) return 0;
  const sorted = [...values].sort((a, b) => a - b);
  const idx = Math.ceil((percentile / 100) * sorted.length) - 1;
  return sorted[Math.max(0, Math.min(idx, sorted.length - 1))] || 0;
}
