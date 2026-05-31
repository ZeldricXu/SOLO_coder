import request from 'supertest';
import nock from 'nock';
import {
  ProcessRequest,
  ProcessResponse,
  ResourceCreateRequest,
  ResourceResponse,
  ApiResponse,
  ProcessRequestBuilder,
  ProcessResponseBuilder,
  ResourceCreateRequestBuilder,
  ResourceResponseBuilder,
  ApiResponseBuilder,
  TraceIdBuilder,
  TestDataFactory,
  TestConstants,
  createIsolationContext,
  measureExecutionTime,
  delay,
  EntityStatus,
  RunPhase,
} from '../builders/devicePlatformBuilders';

const API_BASE_URL = TestConstants.API_BASE_URL;
const API_PREFIX = TestConstants.API_V1_PREFIX;

describe('Core Processing Module - Concurrency Isolation', () => {
  let isolationContext: ReturnType<typeof createIsolationContext>;

  beforeEach(() => {
    isolationContext = createIsolationContext();
    nock.cleanAll();
    jest.clearAllTimers();
  });

  afterEach(async () => {
    await isolationContext.cleanup();
    nock.cleanAll();
  });

  describe('Request Isolation', () => {
    it('should isolate concurrent requests with unique trace IDs', async () => {
      const requestCount = 20;
      const requests = TestDataFactory.createConcurrentRequests(requestCount);
      const traceIds = requests.map(r => r.traceId!);

      nock(API_BASE_URL)
        .post(`${API_PREFIX}/process`)
        .times(requestCount)
        .reply(200, (_, requestBody) => {
          const req = requestBody as ProcessRequest;
          return ApiResponseBuilder.success(
            ProcessResponseBuilder.success()
              .withResult({
                traceId: req.traceId,
                namespace: req.namespace,
                processed: true,
              })
              .build()
          );
        });

      const responses = await Promise.all(
        requests.map((req, i) =>
          request(API_BASE_URL)
            .post(`${API_PREFIX}/process`)
            .send(req)
            .set('X-Trace-Id', req.traceId!)
        )
      );

      expect(responses.length).toBe(requestCount);

      const responseTraceIds = responses.map(
        r => (r.body as ApiResponse<ProcessResponse>).data?.result?.traceId as string
      );

      const uniqueTraceIds = new Set(responseTraceIds);
      expect(uniqueTraceIds.size).toBe(requestCount);

      traceIds.forEach(traceId => {
        expect(uniqueTraceIds.has(traceId)).toBe(true);
      });
    });

    it('should maintain namespace isolation for concurrent requests', async () => {
      const namespaces = ['namespace-a', 'namespace-b', 'namespace-c', 'namespace-d', 'namespace-e'];
      const requestCount = 25;
      const requests = TestDataFactory.createConcurrentRequests(requestCount);

      const namespaceConfig = {
        'namespace-a': { timeout: 30, priority: 'high' },
        'namespace-b': { timeout: 60, priority: 'normal' },
        'namespace-c': { timeout: 15, priority: 'low' },
        'namespace-d': { timeout: 45, priority: 'high' },
        'namespace-e': { timeout: 90, priority: 'bulk' },
      };

      nock(API_BASE_URL)
        .post(`${API_PREFIX}/process`)
        .times(requestCount)
        .reply(200, (_, requestBody) => {
          const req = requestBody as ProcessRequest;
          const config = namespaceConfig[req.namespace as keyof typeof namespaceConfig];
          return ApiResponseBuilder.success(
            ProcessResponseBuilder.success()
              .withResult({
                namespace: req.namespace,
                config,
                processed: true,
              })
              .build()
          );
        });

      const responses = await Promise.all(
        requests.map(req =>
          request(API_BASE_URL)
            .post(`${API_PREFIX}/process`)
            .send(req)
            .set('X-Trace-Id', req.traceId!)
        )
      );

      responses.forEach((response, index) => {
        const apiResponse = response.body as ApiResponse<ProcessResponse>;
        expect(apiResponse.code).toBe(200);

        const result = apiResponse.data?.result as { namespace: string; config: typeof namespaceConfig[string] };
        const expectedNamespace = requests[index].namespace;
        const expectedConfig = namespaceConfig[expectedNamespace as keyof typeof namespaceConfig];

        expect(result.namespace).toBe(expectedNamespace);
        expect(result.config).toEqual(expectedConfig);
      });
    });

    it('should not leak data between concurrent request contexts', async () => {
      const requestCount = 15;
      const sensitiveData = Array.from({ length: requestCount }, (_, i) => ({
        userId: `user_${i}`,
        secret: `secret_${i}_${Date.now()}`,
        token: `token_${i}_${Math.random().toString(36)}`,
      }));

      const requests = sensitiveData.map((data, i) =>
        ProcessRequestBuilder.default()
          .withTraceId(TraceIdBuilder.fromSeed(i))
          .withParams({ userId: data.userId })
          .withPayload({ secret: data.secret, token: data.token })
          .build()
      );

      nock(API_BASE_URL)
        .post(`${API_PREFIX}/process`)
        .times(requestCount)
        .reply(200, (_, requestBody) => {
          const req = requestBody as ProcessRequest;
          return ApiResponseBuilder.success(
            ProcessResponseBuilder.success()
              .withResult({
                echo: req.payload,
                requestTraceId: req.traceId,
              })
              .build()
          );
        });

      const responses = await Promise.all(
        requests.map(req =>
          request(API_BASE_URL)
            .post(`${API_PREFIX}/process`)
            .send(req)
            .set('X-Trace-Id', req.traceId!)
        )
      );

      responses.forEach((response, index) => {
        const result = (response.body as ApiResponse<ProcessResponse>).data?.result as { echo: typeof sensitiveData[number] };
        expect(result.echo.secret).toBe(sensitiveData[index].secret);
        expect(result.echo.token).toBe(sensitiveData[index].token);

        for (let j = 0; j < requestCount; j++) {
          if (j !== index) {
            expect(result.echo.secret).not.toBe(sensitiveData[j].secret);
            expect(result.echo.token).not.toBe(sensitiveData[j].token);
          }
        }
      });
    });
  });

  describe('Transaction Isolation', () => {
    it('should rollback transactions on failure without affecting concurrent successful requests', async () => {
      const totalRequests = 20;
      const failEveryNth = 4;

      const requests = Array.from({ length: totalRequests }, (_, i) =>
        ProcessRequestBuilder.default()
          .withTraceId(TraceIdBuilder.fromSeed(i))
          .withParams({ shouldFail: i % failEveryNth === failEveryNth - 1 })
          .withPayload({ requestIndex: i })
          .build()
      );

      nock(API_BASE_URL)
        .post(`${API_PREFIX}/process`)
        .times(totalRequests)
        .reply(200, (_, requestBody) => {
          const req = requestBody as ProcessRequest;
          if (req.params.shouldFail) {
            return ApiResponseBuilder.internalError('Simulated processing failure');
          }
          return ApiResponseBuilder.success(
            ProcessResponseBuilder.success()
              .withResult({
                requestIndex: req.payload.requestIndex,
                committed: true,
              })
              .build()
          );
        });

      const responses = await Promise.all(
        requests.map(req =>
          request(API_BASE_URL)
            .post(`${API_PREFIX}/process`)
            .send(req)
            .set('X-Trace-Id', req.traceId!)
        )
      );

      let successCount = 0;
      let failureCount = 0;

      responses.forEach((response, index) => {
        const apiResponse = response.body as ApiResponse<ProcessResponse>;
        const shouldFail = index % failEveryNth === failEveryNth - 1;

        if (shouldFail) {
          expect(apiResponse.code).toBe(500);
          failureCount++;
        } else {
          expect(apiResponse.code).toBe(200);
          expect(apiResponse.data?.result?.committed).toBe(true);
          successCount++;
        }
      });

      expect(successCount).toBe(totalRequests - Math.floor(totalRequests / failEveryNth));
      expect(failureCount).toBe(Math.floor(totalRequests / failEveryNth));
    });

    it('should handle concurrent resource creation without race conditions', async () => {
      const resourceCount = 30;
      const resources = TestDataFactory.createResourceBatch(resourceCount);

      const createdIds = new Set<string>();
      let duplicateDetected = false;

      nock(API_BASE_URL)
        .post(`${API_PREFIX}/resources`)
        .times(resourceCount)
        .reply(201, () => {
          const id = `rsc_${Date.now()}_${Math.random().toString(36).slice(2, 8)}`;
          if (createdIds.has(id)) {
            duplicateDetected = true;
          }
          createdIds.add(id);

          return ApiResponseBuilder.created({
            id,
            status: 'provisioning',
            createdAt: new Date().toISOString(),
          });
        });

      const responses = await Promise.all(
        resources.map((resource, i) =>
          request(API_BASE_URL)
            .post(`${API_PREFIX}/resources`)
            .send(resource)
            .set('X-Trace-Id', TraceIdBuilder.fromSeed(i))
        )
      );

      expect(duplicateDetected).toBe(false);
      expect(createdIds.size).toBe(resourceCount);

      responses.forEach(response => {
        const apiResponse = response.body as ApiResponse<ResourceResponse>;
        expect(apiResponse.code).toBe(201);
        expect(createdIds.has(apiResponse.data?.id!)).toBe(true);
        isolationContext.registerResource(apiResponse.data!.id);
      });
    });

    it('should maintain read consistency during concurrent writes', async () => {
      const resourceId = `rsc_${Date.now()}_test`;
      const updateCount = 15;
      const readCount = 10;

      isolationContext.registerResource(resourceId);

      let currentState = { status: 'initial', version: 0 };

      nock(API_BASE_URL)
        .post(`${API_PREFIX}/resources/${resourceId}/update`)
        .times(updateCount)
        .reply(200, () => {
          currentState = {
            status: `state_${currentState.version + 1}`,
            version: currentState.version + 1,
          };
          return ApiResponseBuilder.success({ ...currentState });
        });

      nock(API_BASE_URL)
        .get(`${API_PREFIX}/resources/${resourceId}/status`)
        .times(readCount)
        .reply(200, () => {
          return ApiResponseBuilder.success({
            id: resourceId,
            ...currentState,
            readConsistent: true,
          });
        });

      const operations: Promise<unknown>[] = [];

      for (let i = 0; i < updateCount; i++) {
        operations.push(
          request(API_BASE_URL)
            .post(`${API_PREFIX}/resources/${resourceId}/update`)
            .send({ status: `state_${i + 1}` })
            .set('X-Trace-Id', TraceIdBuilder.fromSeed(i))
        );
      }

      for (let i = 0; i < readCount; i++) {
        operations.push(
          delay(i * 50).then(() =>
            request(API_BASE_URL)
              .get(`${API_PREFIX}/resources/${resourceId}/status`)
              .set('X-Trace-Id', TraceIdBuilder.fromSeed(i + 1000))
          )
        );
      }

      const results = await Promise.all(operations);

      const readResponses = results.slice(updateCount) as request.Response[];
      readResponses.forEach(response => {
        const data = (response.body as ApiResponse<{ version: number; readConsistent: boolean }>).data!;
        expect(data.readConsistent).toBe(true);
        expect(data.version).toBeGreaterThanOrEqual(0);
        expect(data.version).toBeLessThanOrEqual(updateCount);
      });

      expect(currentState.version).toBe(updateCount);
    });
  });

  describe('Resource Pool Isolation', () => {
    it('should properly acquire and release resources under concurrent load', async () => {
      const poolSize = 10;
      const concurrentRequests = 50;

      let activeResources = 0;
      let maxActiveResources = 0;
      let resourceLeakDetected = false;

      nock(API_BASE_URL)
        .post(`${API_PREFIX}/process`)
        .times(concurrentRequests)
        .reply(200, async () => {
          activeResources++;
          maxActiveResources = Math.max(maxActiveResources, activeResources);

          if (activeResources > poolSize) {
            resourceLeakDetected = true;
          }

          await delay(Math.random() * 100);

          activeResources--;

          return ApiResponseBuilder.success(
            ProcessResponseBuilder.success()
              .withResult({
                poolUtilization: maxActiveResources,
                withinPoolLimit: maxActiveResources <= poolSize,
              })
              .build()
          );
        });

      const requests = Array.from({ length: concurrentRequests }, (_, i) =>
        ProcessRequestBuilder.simple(`pool-test-${i}`)
      );

      const startTime = Date.now();
      const responses = await Promise.all(
        requests.map((req, i) =>
          request(API_BASE_URL)
            .post(`${API_PREFIX}/process`)
            .send(req)
            .set('X-Trace-Id', TraceIdBuilder.fromSeed(i))
        )
      );
      const duration = Date.now() - startTime;

      expect(resourceLeakDetected).toBe(false);
      expect(maxActiveResources).toBeLessThanOrEqual(poolSize);
      expect(activeResources).toBe(0);

      responses.forEach(response => {
        const result = (response.body as ApiResponse<ProcessResponse>).data?.result as { withinPoolLimit: boolean };
        expect(result.withinPoolLimit).toBe(true);
      });

      expect(duration).toBeLessThan(10000);
    });

    it('should isolate resource pools for different namespaces', async () => {
      const namespaces = ['namespace-high', 'namespace-normal', 'namespace-low'];
      const poolSizes = {
        'namespace-high': 20,
        'namespace-normal': 10,
        'namespace-low': 5,
      };

      const activeByNamespace: Record<string, number> = {
        'namespace-high': 0,
        'namespace-normal': 0,
        'namespace-low': 0,
      };

      const maxByNamespace: Record<string, number> = {
        'namespace-high': 0,
        'namespace-normal': 0,
        'namespace-low': 0,
      };

      const totalRequests = 60;
      const requestsPerNamespace = totalRequests / namespaces.length;

      nock(API_BASE_URL)
        .post(`${API_PREFIX}/process`)
        .times(totalRequests)
        .reply(200, async (_, requestBody) => {
          const req = requestBody as ProcessRequest;
          const ns = req.namespace;

          activeByNamespace[ns]++;
          maxByNamespace[ns] = Math.max(maxByNamespace[ns], activeByNamespace[ns]);

          const poolSize = poolSizes[ns as keyof typeof poolSizes];
          if (activeByNamespace[ns] > poolSize) {
            return ApiResponseBuilder.internalError(`Namespace ${ns} exceeded pool limit`);
          }

          await delay(Math.random() * 50);
          activeByNamespace[ns]--;

          return ApiResponseBuilder.success(
            ProcessResponseBuilder.success()
              .withResult({
                namespace: ns,
                maxActive: maxByNamespace[ns],
                withinLimit: maxByNamespace[ns] <= poolSize,
              })
              .build()
          );
        });

      const allRequests: ProcessRequest[] = [];
      namespaces.forEach(ns => {
        for (let i = 0; i < requestsPerNamespace; i++) {
          allRequests.push(
            ProcessRequestBuilder.default()
              .withNamespace(ns)
              .withTraceId(TraceIdBuilder.random())
              .build()
          );
        }
      });

      const shuffledRequests = allRequests.sort(() => Math.random() - 0.5);

      const responses = await Promise.all(
        shuffledRequests.map(req =>
          request(API_BASE_URL)
            .post(`${API_PREFIX}/process`)
            .send(req)
            .set('X-Trace-Id', req.traceId!)
        )
      );

      responses.forEach(response => {
        const apiResponse = response.body as ApiResponse<ProcessResponse>;
        expect(apiResponse.code).toBe(200);

        const result = apiResponse.data?.result as { namespace: string; withinLimit: boolean };
        expect(result.withinLimit).toBe(true);
      });

      expect(maxByNamespace['namespace-high']).toBeLessThanOrEqual(poolSizes['namespace-high']);
      expect(maxByNamespace['namespace-normal']).toBeLessThanOrEqual(poolSizes['namespace-normal']);
      expect(maxByNamespace['namespace-low']).toBeLessThanOrEqual(poolSizes['namespace-low']);

      Object.values(activeByNamespace).forEach(active => {
        expect(active).toBe(0);
      });
    });
  });

  describe('Concurrency Control', () => {
    it('should handle request prioritization correctly under load', async () => {
      const highPriorityCount = 20;
      const normalPriorityCount = 30;
      const lowPriorityCount = 20;
      const totalRequests = highPriorityCount + normalPriorityCount + lowPriorityCount;

      const completionOrder: string[] = [];

      nock(API_BASE_URL)
        .post(`${API_PREFIX}/process`)
        .times(totalRequests)
        .reply(200, async (_, requestBody) => {
          const req = requestBody as ProcessRequest;
          const priority = req.params.priority as string;

          const baseDelay = priority === 'high' ? 10 : priority === 'normal' ? 30 : 60;
          await delay(baseDelay + Math.random() * 20);

          completionOrder.push(priority);

          return ApiResponseBuilder.success(
            ProcessResponseBuilder.success()
              .withResult({ priority, completed: true })
              .build()
          );
        });

      const allRequests: ProcessRequest[] = [];

      for (let i = 0; i < highPriorityCount; i++) {
        allRequests.push(
          ProcessRequestBuilder.default()
            .withTraceId(TraceIdBuilder.fromSeed(i))
            .withParams({ priority: 'high', taskId: i })
            .build()
        );
      }

      for (let i = 0; i < normalPriorityCount; i++) {
        allRequests.push(
          ProcessRequestBuilder.default()
            .withTraceId(TraceIdBuilder.fromSeed(i + 100))
            .withParams({ priority: 'normal', taskId: i })
            .build()
        );
      }

      for (let i = 0; i < lowPriorityCount; i++) {
        allRequests.push(
          ProcessRequestBuilder.default()
            .withTraceId(TraceIdBuilder.fromSeed(i + 200))
            .withParams({ priority: 'low', taskId: i })
            .build()
        );
      }

      const shuffled = allRequests.sort(() => Math.random() - 0.5);

      await Promise.all(
        shuffled.map(req =>
          request(API_BASE_URL)
            .post(`${API_PREFIX}/process`)
            .send(req)
            .set('X-Trace-Id', req.traceId!)
        )
      );

      const firstTen = completionOrder.slice(0, 10);
      const highPriorityInFirstTen = firstTen.filter(p => p === 'high').length;

      expect(highPriorityInFirstTen).toBeGreaterThanOrEqual(5);
    });

    it('should maintain correct execution order for sequential dependencies', async () => {
      const stepCount = 10;
      const executionOrder: number[] = [];

      nock(API_BASE_URL)
        .post(`${API_PREFIX}/process`)
        .times(stepCount)
        .reply(200, (_, requestBody) => {
          const req = requestBody as ProcessRequest;
          const step = req.params.step as number;
          executionOrder.push(step);

          return ApiResponseBuilder.success(
            ProcessResponseBuilder.success()
              .withResult({ step, executed: true })
              .build()
          );
        });

      for (let i = 0; i < stepCount; i++) {
        const request = ProcessRequestBuilder.default()
          .withTraceId(TraceIdBuilder.fromSeed(i))
          .withParams({ step: i })
          .build();

        await request(API_BASE_URL)
          .post(`${API_PREFIX}/process`)
          .send(request)
          .set('X-Trace-Id', request.traceId!)
          .expect(200);
      }

      expect(executionOrder).toEqual(Array.from({ length: stepCount }, (_, i) => i));
    });

    it('should detect and prevent duplicate processing', async () => {
      const idempotencyKey = `idem_${Date.now()}_test`;
      const requestCount = 5;

      let processCount = 0;

      nock(API_BASE_URL)
        .post(`${API_PREFIX}/process`)
        .times(requestCount)
        .reply(200, () => {
          processCount++;
          return ApiResponseBuilder.success(
            ProcessResponseBuilder.success()
              .withResult({
                idempotencyKey,
                processCount,
                isDuplicate: processCount > 1,
              })
              .build()
          );
        });

      const request = ProcessRequestBuilder.default()
        .withTraceId(TraceIdBuilder.fromSeed(1))
        .withParams({ idempotencyKey })
        .build();

      const responses = await Promise.all(
        Array.from({ length: requestCount }, (_, i) =>
          request(API_BASE_URL)
            .post(`${API_PREFIX}/process`)
            .send(request)
            .set('X-Idempotency-Key', idempotencyKey)
            .set('X-Trace-Id', TraceIdBuilder.fromSeed(i))
        )
      );

      expect(processCount).toBe(1);

      responses.forEach(response => {
        const result = (response.body as ApiResponse<ProcessResponse>).data?.result as { isDuplicate: boolean };
        expect(result.isDuplicate).toBe(true);
      });
    });
  });

  describe('Performance Under Load', () => {
    it('should maintain consistent latency under concurrent load', async () => {
      const requestCount = 50;
      const maxAllowedLatency = 2000;

      const requests = TestDataFactory.createConcurrentRequests(requestCount);

      nock(API_BASE_URL)
        .post(`${API_PREFIX}/process`)
        .times(requestCount)
        .reply(200, () => {
          return ApiResponseBuilder.success(ProcessResponseBuilder.success().build());
        });

      const { result: responses, duration: totalDuration } = await measureExecutionTime(
        () => Promise.all(
          requests.map((req, i) =>
            request(API_BASE_URL)
              .post(`${API_PREFIX}/process`)
              .send(req)
              .set('X-Trace-Id', TraceIdBuilder.fromSeed(i))
          )
        )
      );

      const avgLatency = totalDuration / requestCount;

      expect(responses.length).toBe(requestCount);
      responses.forEach(r => expect((r.body as ApiResponse).code).toBe(200));

      expect(avgLatency).toBeLessThan(maxAllowedLatency);
      expect(totalDuration).toBeLessThan(maxAllowedLatency * Math.ceil(requestCount / 10));
    });

    it('should handle large payloads without memory leaks', async () => {
      const requestCount = 10;
      const requests = Array.from({ length: requestCount }, () =>
        ProcessRequestBuilder.withLargePayload()
      );

      nock(API_BASE_URL)
        .post(`${API_PREFIX}/process`)
        .times(requestCount)
        .reply(200, (_, requestBody) => {
          const req = requestBody as ProcessRequest;
          const payloadSize = JSON.stringify(req.payload).length;
          return ApiResponseBuilder.success(
            ProcessResponseBuilder.success()
              .withResult({
                processed: true,
                payloadSize,
                recordCount: (req.payload.records as unknown[]).length,
              })
              .build()
          );
        });

      const responses = await Promise.all(
        requests.map((req, i) =>
          request(API_BASE_URL)
            .post(`${API_PREFIX}/process`)
            .send(req)
            .set('X-Trace-Id', TraceIdBuilder.fromSeed(i))
        )
      );

      responses.forEach(response => {
        const result = (response.body as ApiResponse<ProcessResponse>).data?.result as {
          processed: boolean;
          recordCount: number;
        };
        expect(result.processed).toBe(true);
        expect(result.recordCount).toBe(1000);
      });
    });
  });
});
