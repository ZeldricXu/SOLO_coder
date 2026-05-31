import request from 'supertest';
import nock from 'nock';
import {
  ApiResponse,
  DeviceActivateRequest,
  ProcessRequest,
  DeviceActivateRequestBuilder,
  ProcessRequestBuilder,
  ApiResponseBuilder,
  TraceIdBuilder,
  TestDataFactory,
  TestConstants,
  createIsolationContext,
  delay,
  measureExecutionTime,
  MetricsSnapshotBuilder,
  MetricsSnapshot,
} from '../builders/devicePlatformBuilders';

const API_BASE_URL = TestConstants.API_BASE_URL;
const API_PREFIX = TestConstants.API_V1_PREFIX;
const DEFAULT_TIMEOUT = TestConstants.DEFAULT_TIMEOUT;
const LONG_TIMEOUT = TestConstants.LONG_TIMEOUT;

describe('API Gateway Module - Timeout & Degradation', () => {
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

  describe('Request Timeout Handling', () => {
    it('should return 504 Gateway Timeout when service exceeds timeout threshold', async () => {
      const requestBody = ProcessRequestBuilder.simple('timeout-test');
      const traceId = TraceIdBuilder.random();

      nock(API_BASE_URL)
        .post(`${API_PREFIX}/process`)
        .delay(DEFAULT_TIMEOUT + 1000)
        .reply(200, () =>
          ApiResponseBuilder.success({ message: 'This should not be returned' })
        );

      const { duration } = await measureExecutionTime(
        () => request(API_BASE_URL)
          .post(`${API_PREFIX}/process`)
          .send(requestBody)
          .set('X-Trace-Id', traceId)
          .timeout(DEFAULT_TIMEOUT)
          .expect(504)
      );

      expect(duration).toBeGreaterThanOrEqual(DEFAULT_TIMEOUT - 100);
      expect(duration).toBeLessThan(DEFAULT_TIMEOUT + 500);
    });

    it('should complete successfully within timeout threshold', async () => {
      const requestBody = ProcessRequestBuilder.simple('success-test');
      const traceId = TraceIdBuilder.random();

      nock(API_BASE_URL)
        .post(`${API_PREFIX}/process`)
        .delay(100)
        .reply(200, () =>
          ApiResponseBuilder.success({ processed: true })
        );

      const { result: response, duration } = await measureExecutionTime(
        () => request(API_BASE_URL)
          .post(`${API_PREFIX}/process`)
          .send(requestBody)
          .set('X-Trace-Id', traceId)
          .timeout(DEFAULT_TIMEOUT)
          .expect(200)
      );

      expect(duration).toBeLessThan(DEFAULT_TIMEOUT);
      expect((response.body as ApiResponse).code).toBe(200);
    });

    it('should handle different timeout configurations per endpoint', async () => {
      const shortTimeout = 1000;
      const longTimeout = 5000;

      const activationRequest = DeviceActivateRequestBuilder.default().build();
      const processRequest = ProcessRequestBuilder.simple('long-running');

      nock(API_BASE_URL)
        .post(`${API_PREFIX}/devices/activate`)
        .delay(shortTimeout + 500)
        .reply(200, () => ApiResponseBuilder.created({ id: 'test' }));

      nock(API_BASE_URL)
        .post(`${API_PREFIX}/process`)
        .delay(longTimeout - 500)
        .reply(200, () => ApiResponseBuilder.success({ completed: true }));

      const activationPromise = request(API_BASE_URL)
        .post(`${API_PREFIX}/devices/activate`)
        .send(activationRequest)
        .timeout(shortTimeout);

      const processPromise = request(API_BASE_URL)
        .post(`${API_PREFIX}/process`)
        .send(processRequest)
        .timeout(longTimeout);

      const [activationResponse, processResponse] = await Promise.all([
        activationPromise.catch(err => err),
        processPromise,
      ]);

      expect(activationResponse.timeout || activationResponse.code === 'ECONNABORTED').toBe(true);
      expect(processResponse.status).toBe(200);
      expect((processResponse.body as ApiResponse).code).toBe(200);
    });

    it('should include trace ID in timeout error responses', async () => {
      const traceId = TraceIdBuilder.random();
      const requestBody = ProcessRequestBuilder.simple('trace-test');

      nock(API_BASE_URL)
        .post(`${API_PREFIX}/process`)
        .delay(DEFAULT_TIMEOUT + 1000)
        .reply(504, () =>
          ApiResponseBuilder.gatewayTimeout('Service processing timed out')
        );

      try {
        await request(API_BASE_URL)
          .post(`${API_PREFIX}/process`)
          .send(requestBody)
          .set('X-Trace-Id', traceId)
          .timeout(DEFAULT_TIMEOUT)
          .expect(504);
      } catch (error) {
        if ((error as { response: { body: ApiResponse } }).response) {
          const response = (error as { response: { body: ApiResponse } }).response;
          expect(response.body.traceId).toBe(traceId);
        }
      }
    });
  });

  describe('Circuit Breaker Pattern', () => {
    it('should open circuit after consecutive failures', async () => {
      const failureThreshold = 5;
      const requestBody = ProcessRequestBuilder.simple('circuit-test');

      nock(API_BASE_URL)
        .post(`${API_PREFIX}/process`)
        .times(failureThreshold)
        .reply(500, () => ApiResponseBuilder.internalError('Service unavailable'));

      const responses: request.Response[] = [];

      for (let i = 0; i < failureThreshold; i++) {
        const response = await request(API_BASE_URL)
          .post(`${API_PREFIX}/process`)
          .send(requestBody)
          .set('X-Trace-Id', TraceIdBuilder.fromSeed(i));

        responses.push(response);
      }

      responses.forEach(response => {
        expect([500, 503]).toContain((response.body as ApiResponse).code);
      });

      const failureCount = responses.filter(
        r => (r.body as ApiResponse).code >= 500
      ).length;
      expect(failureCount).toBe(failureThreshold);

      nock(API_BASE_URL)
        .post(`${API_PREFIX}/process`)
        .reply(503, () =>
          ApiResponseBuilder.internalError('Circuit breaker is open')
        );

      const circuitOpenResponse = await request(API_BASE_URL)
        .post(`${API_PREFIX}/process`)
        .send(requestBody)
        .set('X-Trace-Id', TraceIdBuilder.random())
        .expect(200);

      expect((circuitOpenResponse.body as ApiResponse).message).toContain('Circuit');
    });

    it('should allow requests after circuit half-open timeout', async () => {
      const failureThreshold = 3;
      const resetTimeout = 5000;
      const requestBody = ProcessRequestBuilder.simple('half-open-test');

      nock(API_BASE_URL)
        .post(`${API_PREFIX}/process`)
        .times(failureThreshold)
        .reply(500, () => ApiResponseBuilder.internalError('Database error'));

      for (let i = 0; i < failureThreshold; i++) {
        await request(API_BASE_URL)
          .post(`${API_PREFIX}/process`)
          .send(requestBody)
          .set('X-Trace-Id', TraceIdBuilder.fromSeed(i));
      }

      nock(API_BASE_URL)
        .post(`${API_PREFIX}/process`)
        .reply(200, () =>
          ApiResponseBuilder.success({ processed: true, recovered: true })
        );

      const probeResponse = await request(API_BASE_URL)
        .post(`${API_PREFIX}/process`)
        .send(requestBody)
        .set('X-Trace-Id', TraceIdBuilder.random())
        .expect(200);

      expect((probeResponse.body as ApiResponse).code).toBe(200);
    });

    it('should close circuit after consecutive successes in half-open state', async () => {
      const successThreshold = 3;
      const requestBody = ProcessRequestBuilder.simple('recovery-test');

      nock(API_BASE_URL)
        .post(`${API_PREFIX}/process`)
        .times(successThreshold)
        .reply(200, () =>
          ApiResponseBuilder.success({ processed: true, circuitStatus: 'half-open' })
        );

      const responses: request.Response[] = [];
      for (let i = 0; i < successThreshold; i++) {
        const response = await request(API_BASE_URL)
          .post(`${API_PREFIX}/process`)
          .send(requestBody)
          .set('X-Trace-Id', TraceIdBuilder.fromSeed(i));
        responses.push(response);
      }

      responses.forEach(response => {
        expect((response.body as ApiResponse).code).toBe(200);
      });

      nock(API_BASE_URL)
        .post(`${API_PREFIX}/process`)
        .reply(200, () =>
          ApiResponseBuilder.success({ processed: true, circuitStatus: 'closed' })
        );

      const finalResponse = await request(API_BASE_URL)
        .post(`${API_PREFIX}/process`)
        .send(requestBody)
        .set('X-Trace-Id', TraceIdBuilder.random())
        .expect(200);

      const result = (finalResponse.body as ApiResponse<{ circuitStatus: string }>).data!;
      expect(result.circuitStatus).toBe('closed');
    });
  });

  describe('Rate Limiting & Degradation', () => {
    it('should enforce rate limits and return 429 when exceeded', async () => {
      const rateLimit = TestConstants.RATE_LIMIT_REQUESTS;
      const requestBody = ProcessRequestBuilder.simple('rate-limit-test');

      nock(API_BASE_URL)
        .post(`${API_PREFIX}/process`)
        .times(rateLimit)
        .reply(200, () => ApiResponseBuilder.success({ processed: true }));

      nock(API_BASE_URL)
        .post(`${API_PREFIX}/process`)
        .reply(429, () => ApiResponseBuilder.tooManyRequests('Rate limit exceeded'));

      const requests = Array.from({ length: rateLimit + 5 }, (_, i) =>
        request(API_BASE_URL)
          .post(`${API_PREFIX}/process`)
          .send(requestBody)
          .set('X-Trace-Id', TraceIdBuilder.fromSeed(i))
      );

      const responses = await Promise.all(requests);

      const successCount = responses.filter(
        r => (r.body as ApiResponse).code === 200
      ).length;
      const rateLimitedCount = responses.filter(
        r => (r.body as ApiResponse).code === 429
      ).length;

      expect(successCount).toBe(rateLimit);
      expect(rateLimitedCount).toBeGreaterThanOrEqual(1);

      const rateLimitedResponse = responses.find(
        r => (r.body as ApiResponse).code === 429
      );
      if (rateLimitedResponse) {
        expect(rateLimitedResponse.headers['retry-after']).toBeDefined();
      }
    });

    it('should apply rate limits per client IP', async () => {
      const clients = ['192.168.1.1', '192.168.1.2', '192.168.1.3'];
      const requestsPerClient = 5;
      const requestBody = ProcessRequestBuilder.simple('per-ip-test');

      nock(API_BASE_URL)
        .post(`${API_PREFIX}/process`)
        .times(clients.length * requestsPerClient)
        .reply(200, () => ApiResponseBuilder.success({ processed: true }));

      const allRequests: Promise<request.Response>[] = [];

      clients.forEach((ip, clientIndex) => {
        for (let i = 0; i < requestsPerClient; i++) {
          allRequests.push(
            request(API_BASE_URL)
              .post(`${API_PREFIX}/process`)
              .send(requestBody)
              .set('X-Forwarded-For', ip)
              .set('X-Trace-Id', TraceIdBuilder.fromSeed(clientIndex * 100 + i))
          );
        }
      });

      const responses = await Promise.all(allRequests);

      responses.forEach(response => {
        expect((response.body as ApiResponse).code).toBe(200);
      });

      const groupedByIp = clients.map(ip =>
        responses.filter(
          (_, idx) => Math.floor(idx / requestsPerClient) === clients.indexOf(ip)
        )
      );

      groupedByIp.forEach(group => {
        expect(group.length).toBe(requestsPerClient);
      });
    });

    it('should provide degraded response when rate limited', async () => {
      const requestBody = ProcessRequestBuilder.simple('degraded-test');

      nock(API_BASE_URL)
        .post(`${API_PREFIX}/process`)
        .reply(429, () => ({
          ...ApiResponseBuilder.tooManyRequests('Rate limit exceeded'),
          data: {
            degraded: true,
            cachedResult: { value: 'stale-data' },
            retryAfter: 60,
          },
        }));

      const response = await request(API_BASE_URL)
        .post(`${API_PREFIX}/process`)
        .send(requestBody)
        .set('X-Trace-Id', TraceIdBuilder.random())
        .expect(200);

      const apiResponse = response.body as ApiResponse<{
        degraded: boolean;
        cachedResult: { value: string };
        retryAfter: number;
      }>;

      expect(apiResponse.code).toBe(429);
      expect(apiResponse.data?.degraded).toBe(true);
      expect(apiResponse.data?.cachedResult).toBeDefined();
      expect(apiResponse.data?.retryAfter).toBe(60);
    });
  });

  describe('Retry Mechanism', () => {
    it('should retry transient failures automatically', async () => {
      const maxRetries = 3;
      const requestBody = ProcessRequestBuilder.simple('retry-test');
      let attemptCount = 0;

      nock(API_BASE_URL)
        .post(`${API_PREFIX}/process`)
        .times(maxRetries + 1)
        .reply(200, () => {
          attemptCount++;
          if (attemptCount <= maxRetries) {
            return ApiResponseBuilder.internalError('Transient network error');
          }
          return ApiResponseBuilder.success({
            processed: true,
            retryCount: maxRetries,
            succeeded: true,
          });
        });

      const response = await request(API_BASE_URL)
        .post(`${API_PREFIX}/process`)
        .send(requestBody)
        .set('X-Trace-Id', TraceIdBuilder.random())
        .set('X-Retry-Count', String(maxRetries))
        .expect(200);

      const apiResponse = response.body as ApiResponse<{
        retryCount: number;
        succeeded: boolean;
      }>;

      expect(apiResponse.data?.succeeded).toBe(true);
      expect(apiResponse.data?.retryCount).toBe(maxRetries);
      expect(attemptCount).toBe(maxRetries + 1);
    });

    it('should respect retry backoff delays', async () => {
      const maxRetries = 3;
      const baseDelay = 100;
      const requestBody = ProcessRequestBuilder.simple('backoff-test');

      let attemptTimes: number[] = [];

      nock(API_BASE_URL)
        .post(`${API_PREFIX}/process`)
        .times(maxRetries + 1)
        .reply(200, () => {
          attemptTimes.push(Date.now());
          if (attemptTimes.length <= maxRetries) {
            return ApiResponseBuilder.internalError('Temporary failure');
          }
          return ApiResponseBuilder.success({ completed: true });
        });

      const { duration } = await measureExecutionTime(
        () => request(API_BASE_URL)
          .post(`${API_PREFIX}/process`)
          .send(requestBody)
          .set('X-Trace-Id', TraceIdBuilder.random())
          .set('X-Retry-Count', String(maxRetries))
          .set('X-Retry-Backoff', 'exponential')
          .expect(200)
      );

      const minExpectedDuration = baseDelay * (1 + 2 + 4);
      expect(duration).toBeGreaterThanOrEqual(minExpectedDuration * 0.8);
      expect(attemptTimes.length).toBe(maxRetries + 1);

      for (let i = 1; i < attemptTimes.length; i++) {
        const gap = attemptTimes[i] - attemptTimes[i - 1];
        const expectedMinGap = baseDelay * Math.pow(2, i - 1);
        expect(gap).toBeGreaterThanOrEqual(expectedMinGap * 0.5);
      }
    });

    it('should not retry non-transient errors', async () => {
      const requestBody = ProcessRequestBuilder.simple('no-retry-test');
      let attemptCount = 0;

      nock(API_BASE_URL)
        .post(`${API_PREFIX}/process`)
        .reply(400, () => {
          attemptCount++;
          return ApiResponseBuilder.badRequest('Invalid request parameters');
        });

      const response = await request(API_BASE_URL)
        .post(`${API_PREFIX}/process`)
        .send(requestBody)
        .set('X-Trace-Id', TraceIdBuilder.random())
        .set('X-Retry-Count', '3')
        .expect(200);

      expect((response.body as ApiResponse).code).toBe(400);
      expect(attemptCount).toBe(1);
    });

    it('should return error after exhausting all retries', async () => {
      const maxRetries = 3;
      const requestBody = ProcessRequestBuilder.simple('exhausted-test');
      let attemptCount = 0;

      nock(API_BASE_URL)
        .post(`${API_PREFIX}/process`)
        .times(maxRetries + 1)
        .reply(503, () => {
          attemptCount++;
          return ApiResponseBuilder.internalError('Service permanently unavailable');
        });

      const response = await request(API_BASE_URL)
        .post(`${API_PREFIX}/process`)
        .send(requestBody)
        .set('X-Trace-Id', TraceIdBuilder.random())
        .set('X-Retry-Count', String(maxRetries))
        .expect(200);

      const apiResponse = response.body as ApiResponse;
      expect(apiResponse.code).toBe(503);
      expect(attemptCount).toBe(maxRetries + 1);
      expect(apiResponse.message).toContain('exhausted');
    });
  });

  describe('Trace Context Propagation', () => {
    it('should propagate trace ID through the entire request chain', async () => {
      const traceId = TraceIdBuilder.random();
      const requestBody = ProcessRequestBuilder.simple('trace-chain-test');

      const capturedHeaders: Record<string, string>[] = [];

      nock(API_BASE_URL)
        .post(`${API_PREFIX}/process`)
        .reply(200, function () {
          capturedHeaders.push({
            traceId: this.req.headers['x-trace-id'] as string,
            requestId: this.req.headers['x-request-id'] as string,
          });

          nock(API_BASE_URL)
            .post(`${API_PREFIX}/internal/validate`)
            .reply(200, function () {
              capturedHeaders.push({
                traceId: this.req.headers['x-trace-id'] as string,
                requestId: this.req.headers['x-request-id'] as string,
              });
              return { valid: true };
            });

          return ApiResponseBuilder.success({
            traceId,
            validated: true,
          });
        });

      const response = await request(API_BASE_URL)
        .post(`${API_PREFIX}/process`)
        .send(requestBody)
        .set('X-Trace-Id', traceId)
        .set('X-Request-Id', `req_${Date.now()}`)
        .expect(200);

      expect(capturedHeaders.length).toBeGreaterThanOrEqual(1);
      capturedHeaders.forEach(headers => {
        expect(headers.traceId).toBe(traceId);
      });

      const apiResponse = response.body as ApiResponse<{ traceId: string }>;
      expect(apiResponse.data?.traceId).toBe(traceId);
      expect(apiResponse.traceId).toBe(traceId);
    });

    it('should generate trace ID when not provided', async () => {
      const requestBody = ProcessRequestBuilder.simple('auto-trace-test');

      nock(API_BASE_URL)
        .post(`${API_PREFIX}/process`)
        .reply(200, function () {
          const generatedTraceId = this.req.headers['x-trace-id'] as string;
          return ApiResponseBuilder.success({
            generatedTraceId,
            processed: true,
          });
        });

      const response = await request(API_BASE_URL)
        .post(`${API_PREFIX}/process`)
        .send(requestBody)
        .expect(200);

      const apiResponse = response.body as ApiResponse<{ generatedTraceId: string }>;

      expect(apiResponse.traceId).toBeDefined();
      expect(apiResponse.traceId).not.toBe('');
      expect(apiResponse.data?.generatedTraceId).toBe(apiResponse.traceId);
    });

    it('should maintain trace context across async operations', async () => {
      const traceId = TraceIdBuilder.random();
      const requestBody = ProcessRequestBuilder.simple('async-trace-test');
      let capturedTraceId: string | undefined;

      nock(API_BASE_URL)
        .post(`${API_PREFIX}/process`)
        .delay(100)
        .reply(200, function () {
          capturedTraceId = this.req.headers['x-trace-id'] as string;
          return ApiResponseBuilder.success({ processed: true });
        });

      const concurrentOperations = [
        request(API_BASE_URL)
          .post(`${API_PREFIX}/process`)
          .send(requestBody)
          .set('X-Trace-Id', traceId)
          .expect(200),
        delay(50).then(() =>
          request(API_BASE_URL)
            .get(`${API_PREFIX}/metrics`)
            .set('X-Trace-Id', TraceIdBuilder.random())
        ),
      ];

      await Promise.all(concurrentOperations);

      expect(capturedTraceId).toBe(traceId);
    });
  });

  describe('Request Logging', () => {
    it('should log requests with appropriate metadata', async () => {
      const traceId = TraceIdBuilder.random();
      const requestBody = DeviceActivateRequestBuilder.default()
        .withFirmwareVersion('v2.0.0')
        .build();

      nock(API_BASE_URL)
        .post(`${API_PREFIX}/devices/activate`)
        .reply(201, function () {
          return ApiResponseBuilder.created({
            id: 'dev_test',
            logged: true,
            headers: {
              traceId: this.req.headers['x-trace-id'],
              userAgent: this.req.headers['user-agent'],
              contentType: this.req.headers['content-type'],
            },
          });
        });

      const response = await request(API_BASE_URL)
        .post(`${API_PREFIX}/devices/activate`)
        .send(requestBody)
        .set('X-Trace-Id', traceId)
        .set('User-Agent', 'Test-Client/1.0')
        .expect(200);

      const apiResponse = response.body as ApiResponse<{
        logged: boolean;
        headers: { traceId: string; contentType: string };
      }>;

      expect(apiResponse.data?.logged).toBe(true);
      expect(apiResponse.data?.headers.traceId).toBe(traceId);
      expect(apiResponse.data?.headers.contentType).toContain('application/json');
    });

    it('should log response times and status codes', async () => {
      const requestCount = 10;
      const requests = TestDataFactory.createConcurrentRequests(requestCount);

      nock(API_BASE_URL)
        .post(`${API_PREFIX}/process`)
        .times(requestCount)
        .reply(200, (_, body) => {
          const delayMs = Math.random() * 100;
          return ApiResponseBuilder.success({
            processed: true,
            delay: delayMs,
          });
        });

      const { result: responses, duration: totalDuration } = await measureExecutionTime(
        () => Promise.all(
          requests.map((req, i) =>
            request(API_BASE_URL)
              .post(`${API_PREFIX}/process`)
              .send(req)
              .set('X-Trace-Id', req.traceId!)
          )
        )
      );

      responses.forEach(response => {
        expect(response.headers['x-response-time']).toBeDefined();
        const apiResponse = response.body as ApiResponse;
        expect(apiResponse.code).toBe(200);
      });

      expect(totalDuration).toBeLessThan(5000);
    });
  });

  describe('Graceful Degradation', () => {
    it('should return cached data when backend is unavailable', async () => {
      const requestBody = ProcessRequestBuilder.simple('cache-fallback-test');

      nock(API_BASE_URL)
        .post(`${API_PREFIX}/process`)
        .reply(503, () => ({
          ...ApiResponseBuilder.internalError('Backend unavailable'),
          data: {
            degraded: true,
            source: 'cache',
            cachedAt: new Date(Date.now() - 30000).toISOString(),
            stale: true,
            result: { value: 'cached-result' },
          },
        }));

      const response = await request(API_BASE_URL)
        .post(`${API_PREFIX}/process`)
        .send(requestBody)
        .set('X-Trace-Id', TraceIdBuilder.random())
        .expect(200);

      const apiResponse = response.body as ApiResponse<{
        degraded: boolean;
        source: string;
        stale: boolean;
        result: { value: string };
      }>;

      expect(apiResponse.code).toBe(503);
      expect(apiResponse.data?.degraded).toBe(true);
      expect(apiResponse.data?.source).toBe('cache');
      expect(apiResponse.data?.result.value).toBe('cached-result');
      expect(response.headers['x-degraded']).toBe('true');
    });

    it('should provide simplified response during high load', async () => {
      const requestBody = ProcessRequestBuilder.simple('simplified-test');

      nock(API_BASE_URL)
        .post(`${API_PREFIX}/process`)
        .reply(200, () => ({
          ...ApiResponseBuilder.success({
            simplified: true,
            essentialData: { id: '123', status: 'ok' },
            omittedFields: ['details', 'metadata', 'history'],
          }),
          headers: {
            'X-Load-Level': 'high',
            'X-Degraded': 'partial',
          },
        }));

      const response = await request(API_BASE_URL)
        .post(`${API_PREFIX}/process`)
        .send(requestBody)
        .set('X-Trace-Id', TraceIdBuilder.random())
        .expect(200);

      expect(response.headers['x-load-level']).toBe('high');

      const apiResponse = response.body as ApiResponse<{
        simplified: boolean;
        essentialData: { id: string; status: string };
      }>;

      expect(apiResponse.data?.simplified).toBe(true);
      expect(apiResponse.data?.essentialData).toBeDefined();
      expect(apiResponse.data?.essentialData.id).toBe('123');
    });

    it('should reject non-critical requests during system overload', async () => {
      const nonCriticalRequest = ProcessRequestBuilder.default()
        .withParams({ priority: 'low', critical: false })
        .build();

      const criticalRequest = ProcessRequestBuilder.default()
        .withParams({ priority: 'high', critical: true })
        .build();

      nock(API_BASE_URL)
        .post(`${API_PREFIX}/process`)
        .reply(503, () =>
          ApiResponseBuilder.internalError('System overload - non-critical requests rejected')
        )
        .post(`${API_PREFIX}/process`)
        .reply(200, () =>
          ApiResponseBuilder.success({ processed: true, critical: true })
        );

      const nonCriticalResponse = await request(API_BASE_URL)
        .post(`${API_PREFIX}/process`)
        .send(nonCriticalRequest)
        .set('X-Trace-Id', TraceIdBuilder.random())
        .expect(200);

      expect((nonCriticalResponse.body as ApiResponse).code).toBe(503);

      const criticalResponse = await request(API_BASE_URL)
        .post(`${API_PREFIX}/process`)
        .send(criticalRequest)
        .set('X-Trace-Id', TraceIdBuilder.random())
        .expect(200);

      const criticalResult = (criticalResponse.body as ApiResponse<{ critical: boolean }>).data!;
      expect(criticalResult.critical).toBe(true);
    });

    it('should expose degradation metrics', async () => {
      nock(API_BASE_URL)
        .get(`${API_PREFIX}/metrics`)
        .reply(200, () =>
          ApiResponseBuilder.success(MetricsSnapshotBuilder.degraded())
        );

      const response = await request(API_BASE_URL)
        .get(`${API_PREFIX}/metrics`)
        .set('X-Trace-Id', TraceIdBuilder.random())
        .expect(200);

      const apiResponse = response.body as ApiResponse<MetricsSnapshot>;
      const metrics = apiResponse.data!;

      expect(metrics.metrics.errorRate).toBeGreaterThan(0.05);
      expect(metrics.metrics.latencyP99).toBeGreaterThan(1000);
    });
  });

  describe('Failover & Redundancy', () => {
    it('should failover to secondary endpoint when primary fails', async () => {
      const requestBody = ProcessRequestBuilder.simple('failover-test');
      let primaryAttempted = false;

      nock(API_BASE_URL)
        .post(`${API_PREFIX}/process`)
        .reply(500, () => {
          primaryAttempted = true;
          return ApiResponseBuilder.internalError('Primary endpoint failed');
        });

      const response = await request(API_BASE_URL)
        .post(`${API_PREFIX}/process`)
        .send(requestBody)
        .set('X-Trace-Id', TraceIdBuilder.random())
        .set('X-Failover', 'enabled')
        .expect(200);

      expect(primaryAttempted).toBe(true);

      const apiResponse = response.body as ApiResponse<{
        failover: boolean;
        endpoint: string;
      }>;

      expect(apiResponse.data?.failover).toBe(true);
    });

    it('should maintain service availability during rolling updates', async () => {
      const requestCount = 20;
      const requests = TestDataFactory.createConcurrentRequests(requestCount);
      let failureCount = 0;

      nock(API_BASE_URL)
        .post(`${API_PREFIX}/process`)
        .times(requestCount)
        .reply(200, () => {
          if (Math.random() < 0.1) {
            failureCount++;
            return ApiResponseBuilder.internalError('Temporary node unavailable');
          }
          return ApiResponseBuilder.success({ processed: true });
        });

      const responses = await Promise.all(
        requests.map((req, i) =>
          request(API_BASE_URL)
            .post(`${API_PREFIX}/process`)
            .send(req)
            .set('X-Trace-Id', req.traceId!)
        )
      );

      const successCount = responses.filter(
        r => (r.body as ApiResponse).code === 200
      ).length;

      const availability = successCount / requestCount;
      expect(availability).toBeGreaterThan(0.9);
      expect(failureCount).toBeLessThan(requestCount * 0.2);
    });
  });
});
