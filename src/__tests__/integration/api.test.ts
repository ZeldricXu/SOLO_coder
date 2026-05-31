import request from 'supertest';
import express from 'express';
import { createResourceRoutes, ApiDeps } from '../../api/routes/resources';
import { createContext } from '../../common';
import {
  GpuTaskScheduler,
  DefaultDataProcessingService,
  DefaultNotificationService,
  PromptExperimentService,
  TaskExecutionTracker,
  DefaultFeatureStoreService,
  MonitoringService,
  AdversarialSampleGeneratorService,
  defaultMonitoring
} from '../../modules';
import { DefaultCacheManager } from '../../infrastructure/cache';

describe('API Integration Tests', () => {
  let app: express.Express;
  let deps: ApiDeps;

  beforeEach(() => {
    app = express();
    app.use(express.json());

    deps = {
      gpuScheduler: new GpuTaskScheduler({
        maxConcurrentTasks: 5,
        pollIntervalMs: 50,
        nodeConfigs: [
          {
            nodeId: 'test-node',
            gpus: [{ id: 0, totalMemoryMb: 8192 }]
          }
        ]
      }),
      dataProcessingService: new DefaultDataProcessingService(),
      notificationService: new DefaultNotificationService(),
      promptExperimentService: new PromptExperimentService(),
      taskTracker: new TaskExecutionTracker(),
      featureStore: new DefaultFeatureStoreService(),
      monitoringService: defaultMonitoring,
      adversarialService: new AdversarialSampleGeneratorService(),
      cacheManager: new DefaultCacheManager()
    };

    app.use(createResourceRoutes(deps));
  });

  afterEach(async () => {
    await deps.gpuScheduler.stop();
  });

  describe('POST /api/v1/resources', () => {
    it('should create a new resource', async () => {
      const response = await request(app)
        .post('/api/v1/resources')
        .send({
          type: 'workflow',
          config: { steps: 3 },
          labels: { environment: 'test' }
        })
        .set('x-namespace', 'test-ns');

      expect(response.status).toBe(201);
      expect(response.body.code).toBe(201);
      expect(response.body.data.id).toBeDefined();
      expect(response.body.data.id.startsWith('rsc_')).toBe(true);
      expect(response.body.data.status).toBe('provisioning');
    });

    it('should handle request without namespace header', async () => {
      const response = await request(app)
        .post('/api/v1/resources')
        .send({
          type: 'task',
          config: {}
        });

      expect(response.status).toBe(201);
      expect(response.body.data.id).toBeDefined();
    });

    it('should create resource with empty config', async () => {
      const response = await request(app)
        .post('/api/v1/resources')
        .send({
          type: 'simple',
          config: {}
        });

      expect(response.status).toBe(201);
    });
  });

  describe('GET /api/v1/resources/:id/status', () => {
    it('should return status for existing task', async () => {
      const createResponse = await request(app)
        .post('/api/v1/resources')
        .send({ type: 'test', config: {} });

      const resourceId = createResponse.body.data.id;

      const statusResponse = await request(app)
        .get(`/api/v1/resources/${resourceId}/status`);

      expect(statusResponse.status).toBe(200);
      expect(statusResponse.body.code).toBe(200);
      expect(statusResponse.body.data.id).toBe(resourceId);
      expect(statusResponse.body.data.status).toBeDefined();
      expect(typeof statusResponse.body.data.progress).toBe('number');
    });

    it('should return unknown status for non-existent resource', async () => {
      const response = await request(app)
        .get('/api/v1/resources/non-existent-id/status');

      expect(response.status).toBe(200);
      expect(response.body.data.status).toBe('unknown');
      expect(response.body.data.progress).toBe(0);
    });
  });

  describe('POST /api/v1/resources/batch', () => {
    it('should process batch operations', async () => {
      const response = await request(app)
        .post('/api/v1/resources/batch')
        .send({
          operations: [
            { action: 'restart', id: 'task_1' },
            { action: 'restart', id: 'task_2' }
          ]
        });

      expect(response.status).toBe(200);
      expect(response.body.code).toBe(200);
      expect(response.body.data.batchId).toBeDefined();
      expect(response.body.data.batchId.startsWith('batch_')).toBe(true);
      expect(response.body.data.results.length).toBe(2);
    });

    it('should handle empty operations array', async () => {
      const response = await request(app)
        .post('/api/v1/resources/batch')
        .send({ operations: [] });

      expect(response.status).toBe(200);
      expect(response.body.data.results.length).toBe(0);
    });

    it('should handle unknown actions', async () => {
      const response = await request(app)
        .post('/api/v1/resources/batch')
        .send({
          operations: [
            { action: 'unknown_action', id: 'task_1' }
          ]
        });

      expect(response.status).toBe(200);
      expect(response.body.data.results[0].success).toBe(false);
      expect(response.body.data.results[0].error).toBeDefined();
    });
  });

  describe('GET /api/v1/metrics', () => {
    it('should return metrics snapshot', async () => {
      const response = await request(app).get('/api/v1/metrics');

      expect(response.status).toBe(200);
      expect(response.body.code).toBe(200);
      expect(response.body.data).toBeDefined();
    });
  });

  describe('GET /api/v1/metrics/prometheus', () => {
    it('should return Prometheus format metrics', async () => {
      const response = await request(app).get('/api/v1/metrics/prometheus');

      expect(response.status).toBe(200);
      expect(response.headers['content-type']).toContain('text/plain');
      expect(typeof response.text).toBe('string');
    });
  });

  describe('GET /health', () => {
    it('should return health status', async () => {
      const response = await request(app).get('/health');

      expect(response.status).toBe(200);
      expect(response.body.status).toBe('healthy');
      expect(response.body.timestamp).toBeDefined();
      expect(response.body.gpu).toBeDefined();
      expect(response.body.tasks).toBeDefined();
    });

    it('should have correct GPU stats structure', async () => {
      const response = await request(app).get('/health');

      expect(response.body.gpu.totalGpuMemoryMb).toBe(8192);
      expect(response.body.gpu.availableGpuMemoryMb).toBe(8192);
      expect(response.body.gpu.totalGpus).toBe(1);
      expect(response.body.gpu.availableGpus).toBe(1);
    });
  });

  describe('Concurrent API Requests', () => {
    it('should handle concurrent resource creation', async () => {
      const CONCURRENT_REQUESTS = 10;

      const requests = Array.from({ length: CONCURRENT_REQUESTS }, () =>
        request(app)
          .post('/api/v1/resources')
          .send({ type: 'concurrent', config: {} })
      );

      const responses = await Promise.all(requests);

      expect(responses.length).toBe(CONCURRENT_REQUESTS);
      responses.forEach(response => {
        expect(response.status).toBe(201);
        expect(response.body.data.id).toBeDefined();
      });

      const ids = responses.map(r => r.body.data.id);
      expect(new Set(ids).size).toBe(CONCURRENT_REQUESTS);
    });

    it('should handle concurrent status checks', async () => {
      const createResponse = await request(app)
        .post('/api/v1/resources')
        .send({ type: 'test', config: {} });

      const resourceId = createResponse.body.data.id;

      const statusRequests = Array.from({ length: 20 }, () =>
        request(app).get(`/api/v1/resources/${resourceId}/status`)
      );

      const responses = await Promise.all(statusRequests);

      responses.forEach(response => {
        expect(response.status).toBe(200);
        expect(response.body.data.id).toBe(resourceId);
      });
    });
  });

  describe('Request Validation', () => {
    it('should handle malformed JSON', async () => {
      const response = await request(app)
        .post('/api/v1/resources')
        .send('not-json')
        .set('Content-Type', 'application/json');

      expect(response.status).toBe(400);
    });

    it('should handle very large payloads', async () => {
      const largeData = {
        type: 'large',
        config: {
          data: 'x'.repeat(10000)
        }
      };

      const response = await request(app)
        .post('/api/v1/resources')
        .send(largeData);

      expect(response.status).toBe(201);
    });
  });
});
