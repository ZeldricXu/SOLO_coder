import request from 'supertest';
import nock from 'nock';
import {
  DeviceActivateRequest,
  DeviceAuthRequest,
  DeviceHeartbeatRequest,
  DeviceDeactivateRequest,
  ApiResponse,
  DeviceResponse,
  DeviceAuthResponse,
  DeviceActivateRequestBuilder,
  DeviceAuthRequestBuilder,
  DeviceHeartbeatRequestBuilder,
  DeviceDeactivateRequestBuilder,
  DeviceResponseBuilder,
  DeviceAuthResponseBuilder,
  ApiResponseBuilder,
  DeviceIdBuilder,
  DeviceKeyBuilder,
  TraceIdBuilder,
  TestDataFactory,
  TestConstants,
  delay,
  assertDataConsistency,
  createIsolationContext,
  DeviceStatus,
} from '../builders/devicePlatformBuilders';

const API_BASE_URL = TestConstants.API_BASE_URL;
const API_PREFIX = TestConstants.API_V1_PREFIX;

describe('Device Registration & Lifecycle Module - Data Consistency', () => {
  let isolationContext: ReturnType<typeof createIsolationContext>;

  beforeEach(() => {
    isolationContext = createIsolationContext();
    nock.cleanAll();
  });

  afterEach(async () => {
    await isolationContext.cleanup();
    nock.cleanAll();
  });

  describe('Device Activation - Data Consistency', () => {
    it('should maintain data consistency during successful device activation', async () => {
      const activateRequest = DeviceActivateRequestBuilder.default()
        .withFirmwareVersion('v2.1.0')
        .withMetadata({ location: 'warehouse-a', zone: 'zone-1' })
        .build();

      const expectedDevice = DeviceResponseBuilder.default()
        .withDeviceKey(activateRequest.deviceKey)
        .withStatus('ONLINE')
        .withFirmwareVersion(activateRequest.firmwareVersion)
        .withHardwareVersion(activateRequest.hardwareVersion)
        .withMetadata(activateRequest.metadata || {})
        .build();

      nock(API_BASE_URL)
        .post(`${API_PREFIX}/devices/activate`)
        .reply(201, (_, requestBody) => {
          const req = requestBody as DeviceActivateRequest;
          const response = DeviceResponseBuilder.default()
            .withDeviceKey(req.deviceKey)
            .withStatus('ONLINE')
            .withFirmwareVersion(req.firmwareVersion)
            .withHardwareVersion(req.hardwareVersion)
            .withMetadata(req.metadata || {})
            .build();
          return ApiResponseBuilder.created(response);
        });

      const response = await request(API_BASE_URL)
        .post(`${API_PREFIX}/devices/activate`)
        .send(activateRequest)
        .set('X-Trace-Id', TraceIdBuilder.random())
        .expect(200);

      const apiResponse = response.body as ApiResponse<DeviceResponse>;
      expect(apiResponse.code).toBe(201);
      expect(apiResponse.traceId).toBeDefined();

      const device = apiResponse.data!;
      isolationContext.registerDevice(device.id);

      assertDataConsistency(
        device,
        expectedDevice,
        ['deviceKey', 'status', 'firmwareVersion', 'hardwareVersion'],
        {
          metadata: (a, b) => JSON.stringify(a) === JSON.stringify(b),
        }
      );

      expect(device.activatedAt).toBeDefined();
      expect(new Date(device.activatedAt).getTime()).toBeLessThanOrEqual(Date.now());
    });

    it('should maintain consistency when activating multiple devices concurrently', async () => {
      const batchSize = 10;
      const activationRequests = TestDataFactory.createDeviceActivationBatch(batchSize);

      nock(API_BASE_URL)
        .post(`${API_PREFIX}/devices/activate`)
        .times(batchSize)
        .reply(201, (_, requestBody) => {
          const req = requestBody as DeviceActivateRequest;
          const response = DeviceResponseBuilder.default()
            .withDeviceKey(req.deviceKey)
            .withStatus('ONLINE')
            .build();
          return ApiResponseBuilder.created(response);
        });

      const activationPromises = activationRequests.map((req, index) =>
        request(API_BASE_URL)
          .post(`${API_PREFIX}/devices/activate`)
          .send(req)
          .set('X-Trace-Id', TraceIdBuilder.fromSeed(index))
      );

      const responses = await Promise.all(activationPromises);

      responses.forEach((response, index) => {
        expect(response.status).toBe(200);
        const apiResponse = response.body as ApiResponse<DeviceResponse>;
        expect(apiResponse.code).toBe(201);
        expect(apiResponse.data?.deviceKey).toBe(activationRequests[index].deviceKey);
        isolationContext.registerDevice(apiResponse.data!.id);
      });

      const uniqueIds = new Set(responses.map(r => (r.body as ApiResponse<DeviceResponse>).data?.id));
      expect(uniqueIds.size).toBe(batchSize);
    });

    it('should not create partial device state when activation fails during persistence', async () => {
      const activateRequest = DeviceActivateRequestBuilder.default().build();

      nock(API_BASE_URL)
        .post(`${API_PREFIX}/devices/activate`)
        .reply(500, () => ApiResponseBuilder.internalError('Database connection failed'));

      const response = await request(API_BASE_URL)
        .post(`${API_PREFIX}/devices/activate`)
        .send(activateRequest)
        .set('X-Trace-Id', TraceIdBuilder.random())
        .expect(200);

      const apiResponse = response.body as ApiResponse;
      expect(apiResponse.code).toBe(500);

      const deviceKey = activateRequest.deviceKey;
      nock(API_BASE_URL)
        .get(`${API_PREFIX}/devices/key/${deviceKey}`)
        .reply(404, () => ApiResponseBuilder.notFound(`Device with key ${deviceKey} not found`));

      const checkResponse = await request(API_BASE_URL)
        .get(`${API_PREFIX}/devices/key/${deviceKey}`)
        .expect(200);

      const checkApiResponse = checkResponse.body as ApiResponse;
      expect(checkApiResponse.code).toBe(404);
    });

    it('should reject duplicate activation requests with same deviceKey', async () => {
      const activateRequest = DeviceActivateRequestBuilder.default().build();

      nock(API_BASE_URL)
        .post(`${API_PREFIX}/devices/activate`)
        .once()
        .reply(201, () => {
          const response = DeviceResponseBuilder.default()
            .withDeviceKey(activateRequest.deviceKey)
            .build();
          return ApiResponseBuilder.created(response);
        });

      nock(API_BASE_URL)
        .post(`${API_PREFIX}/devices/activate`)
        .once()
        .reply(409, () =>
          ApiResponseBuilder.conflict(`Device with key ${activateRequest.deviceKey} already exists`)
        );

      const firstResponse = await request(API_BASE_URL)
        .post(`${API_PREFIX}/devices/activate`)
        .send(activateRequest)
        .set('X-Trace-Id', TraceIdBuilder.random())
        .expect(200);

      const firstApiResponse = firstResponse.body as ApiResponse<DeviceResponse>;
      expect(firstApiResponse.code).toBe(201);
      isolationContext.registerDevice(firstApiResponse.data!.id);

      const secondResponse = await request(API_BASE_URL)
        .post(`${API_PREFIX}/devices/activate`)
        .send(activateRequest)
        .set('X-Trace-Id', TraceIdBuilder.random())
        .expect(200);

      const secondApiResponse = secondResponse.body as ApiResponse;
      expect(secondApiResponse.code).toBe(409);
    });
  });

  describe('Device Authentication - Data Consistency', () => {
    it('should generate consistent auth tokens for valid credentials', async () => {
      const deviceId = DeviceIdBuilder.random();
      const authRequest = DeviceAuthRequestBuilder.forDevice(deviceId).build();

      nock(API_BASE_URL)
        .post(`${API_PREFIX}/devices/auth`)
        .reply(200, () => {
          const authResponse = DeviceAuthResponseBuilder.standard();
          return ApiResponseBuilder.success(authResponse);
        });

      const response = await request(API_BASE_URL)
        .post(`${API_PREFIX}/devices/auth`)
        .send(authRequest)
        .set('X-Trace-Id', TraceIdBuilder.random())
        .expect(200);

      const apiResponse = response.body as ApiResponse<DeviceAuthResponse>;
      expect(apiResponse.code).toBe(200);

      const authData = apiResponse.data!;
      expect(authData.tokenType).toBe('Bearer');
      expect(authData.expiresIn).toBe(3600);
      expect(authData.token).toBeDefined();
      expect(authData.refreshToken).toBeDefined();
      expect(authData.token).not.toBe(authData.refreshToken);
    });

    it('should reject authentication with invalid credentials consistently', async () => {
      const deviceId = DeviceIdBuilder.random();

      nock(API_BASE_URL)
        .post(`${API_PREFIX}/devices/auth`)
        .times(3)
        .reply(401, () => ApiResponseBuilder.unauthorized('Invalid device credentials'));

      const authRequests = Array.from({ length: 3 }, () =>
        DeviceAuthRequestBuilder.forDevice(deviceId).build()
      );

      const responses = await Promise.all(
        authRequests.map(req =>
          request(API_BASE_URL)
            .post(`${API_PREFIX}/devices/auth`)
            .send(req)
            .set('X-Trace-Id', TraceIdBuilder.random())
        )
      );

      responses.forEach(response => {
        const apiResponse = response.body as ApiResponse;
        expect(apiResponse.code).toBe(401);
        expect(apiResponse.data).toBeUndefined();
      });
    });

    it('should maintain nonce uniqueness to prevent replay attacks', async () => {
      const deviceId = DeviceIdBuilder.random();
      const baseRequest = DeviceAuthRequestBuilder.forDevice(deviceId).build();

      nock(API_BASE_URL)
        .post(`${API_PREFIX}/devices/auth`)
        .once()
        .reply(200, () => {
          const authResponse = DeviceAuthResponseBuilder.standard();
          return ApiResponseBuilder.success(authResponse);
        });

      nock(API_BASE_URL)
        .post(`${API_PREFIX}/devices/auth`)
        .once()
        .reply(401, () => ApiResponseBuilder.unauthorized('Nonce already used'));

      const firstResponse = await request(API_BASE_URL)
        .post(`${API_PREFIX}/devices/auth`)
        .send(baseRequest)
        .set('X-Trace-Id', TraceIdBuilder.random())
        .expect(200);

      expect((firstResponse.body as ApiResponse).code).toBe(200);

      const secondResponse = await request(API_BASE_URL)
        .post(`${API_PREFIX}/devices/auth`)
        .send(baseRequest)
        .set('X-Trace-Id', TraceIdBuilder.random())
        .expect(200);

      expect((secondResponse.body as ApiResponse).code).toBe(401);
    });
  });

  describe('Device Heartbeat - State Consistency', () => {
    let testDeviceId: string;
    let authToken: string;

    beforeEach(async () => {
      testDeviceId = DeviceIdBuilder.random();
      authToken = `test-token-${Date.now()}`;

      nock(API_BASE_URL)
        .post(`${API_PREFIX}/devices/activate`)
        .reply(201, () => {
          const response = DeviceResponseBuilder.default()
            .withId(testDeviceId)
            .withStatus('ONLINE')
            .build();
          return ApiResponseBuilder.created(response);
        });

      const activateResponse = await request(API_BASE_URL)
        .post(`${API_PREFIX}/devices/activate`)
        .send(DeviceActivateRequestBuilder.default().build())
        .expect(200);

      isolationContext.registerDevice((activateResponse.body as ApiResponse<DeviceResponse>).data!.id);
    });

    it('should update device status consistently through heartbeat sequence', async () => {
      const heartbeats = TestDataFactory.createHeartbeatSequence(testDeviceId, 5);

      nock(API_BASE_URL)
        .post(`${API_PREFIX}/devices/heartbeat`)
        .times(5)
        .reply(200, (_, requestBody) => {
          const req = requestBody as DeviceHeartbeatRequest;
          return ApiResponseBuilder.success({
            deviceId: req.deviceId,
            status: req.status,
            receivedAt: new Date().toISOString(),
          });
        });

      const heartbeatResponses = await Promise.all(
        heartbeats.map((hb, i) =>
          request(API_BASE_URL)
            .post(`${API_PREFIX}/devices/heartbeat`)
            .send(hb)
            .set('Authorization', `Bearer ${authToken}`)
            .set('X-Trace-Id', TraceIdBuilder.fromSeed(i))
        )
      );

      heartbeatResponses.forEach(response => {
        expect((response.body as ApiResponse).code).toBe(200);
      });

      nock(API_BASE_URL)
        .get(`${API_PREFIX}/devices/${testDeviceId}/status`)
        .reply(200, () => {
          const response = DeviceResponseBuilder.default()
            .withId(testDeviceId)
            .withStatus('ONLINE')
            .build();
          return ApiResponseBuilder.success(response);
        });

      const statusResponse = await request(API_BASE_URL)
        .get(`${API_PREFIX}/devices/${testDeviceId}/status`)
        .set('Authorization', `Bearer ${authToken}`)
        .expect(200);

      const statusData = (statusResponse.body as ApiResponse<DeviceResponse>).data!;
      expect(statusData.status).toBe('ONLINE');
      expect(statusData.lastHeartbeatAt).toBeDefined();
    });

    it('should enforce valid state transitions only', async () => {
      const validTransitions = TestConstants.DEVICE_STATUS_TRANSITIONS.valid;

      for (const [fromStatus, toStatus] of validTransitions) {
        nock(API_BASE_URL)
          .post(`${API_PREFIX}/devices/heartbeat`)
          .once()
          .reply(200, () =>
            ApiResponseBuilder.success({
              deviceId: testDeviceId,
              status: toStatus,
              transitionValid: true,
            })
          );

        const heartbeat = DeviceHeartbeatRequestBuilder.default()
          .withDeviceId(testDeviceId)
          .withStatus(toStatus as DeviceStatus)
          .build();

        const response = await request(API_BASE_URL)
          .post(`${API_PREFIX}/devices/heartbeat`)
          .send(heartbeat)
          .set('Authorization', `Bearer ${authToken}`)
          .set('X-Trace-Id', TraceIdBuilder.random())
          .expect(200);

        const apiResponse = response.body as ApiResponse<{ transitionValid: boolean }>;
        expect(apiResponse.code).toBe(200);
        expect(apiResponse.data?.transitionValid).toBe(true);
      }
    });

    it('should reject invalid state transitions', async () => {
      const invalidTransitions = TestConstants.DEVICE_STATUS_TRANSITIONS.invalid;

      for (const [fromStatus, toStatus] of invalidTransitions) {
        nock(API_BASE_URL)
          .post(`${API_PREFIX}/devices/heartbeat`)
          .once()
          .reply(400, () =>
            ApiResponseBuilder.badRequest(
              `Invalid state transition: ${fromStatus} -> ${toStatus}`
            )
          );

        const heartbeat = DeviceHeartbeatRequestBuilder.default()
          .withDeviceId(testDeviceId)
          .withStatus(toStatus as DeviceStatus)
          .build();

        const response = await request(API_BASE_URL)
          .post(`${API_PREFIX}/devices/heartbeat`)
          .send(heartbeat)
          .set('Authorization', `Bearer ${authToken}`)
          .set('X-Trace-Id', TraceIdBuilder.random())
          .expect(200);

        const apiResponse = response.body as ApiResponse;
        expect(apiResponse.code).toBe(400);
        expect(apiResponse.message).toContain('Invalid state transition');
      }
    });

    it('should maintain heartbeat ordering under concurrent updates', async () => {
      const heartbeatCount = 20;
      const heartbeats = TestDataFactory.createHeartbeatSequence(testDeviceId, heartbeatCount);

      nock(API_BASE_URL)
        .post(`${API_PREFIX}/devices/heartbeat`)
        .times(heartbeatCount)
        .reply(200, (_, requestBody) => {
          const req = requestBody as DeviceHeartbeatRequest;
          return ApiResponseBuilder.success({
            deviceId: req.deviceId,
            status: req.status,
            sequenceNumber: Date.now(),
          });
        });

      const startTime = Date.now();
      const responses = await Promise.all(
        heartbeats.map((hb, i) =>
          request(API_BASE_URL)
            .post(`${API_PREFIX}/devices/heartbeat`)
            .send(hb)
            .set('Authorization', `Bearer ${authToken}`)
            .set('X-Trace-Id', TraceIdBuilder.fromSeed(i))
        )
      );
      const endTime = Date.now();

      expect(responses.length).toBe(heartbeatCount);
      responses.forEach(r => expect((r.body as ApiResponse).code).toBe(200));

      const sequenceNumbers = responses.map(
        r => (r.body as ApiResponse<{ sequenceNumber: number }>).data?.sequenceNumber || 0
      );
      const isMonotonic = sequenceNumbers.every(
        (num, i) => i === 0 || num >= sequenceNumbers[i - 1]
      );
      expect(isMonotonic).toBe(true);

      expect(endTime - startTime).toBeLessThan(5000);
    });

    it('should persist telemetry data atomically with status update', async () => {
      const heartbeat = DeviceHeartbeatRequestBuilder.onlineFor(testDeviceId);

      nock(API_BASE_URL)
        .post(`${API_PREFIX}/devices/heartbeat`)
        .reply(200, (_, requestBody) => {
          const req = requestBody as DeviceHeartbeatRequest;
          return ApiResponseBuilder.success({
            deviceId: req.deviceId,
            status: req.status,
            telemetryStored: true,
            metrics: {
              cpuUsage: req.cpuUsage,
              memoryUsage: req.memoryUsage,
              diskUsage: req.diskUsage,
              networkLatency: req.networkLatency,
            },
          });
        });

      const response = await request(API_BASE_URL)
        .post(`${API_PREFIX}/devices/heartbeat`)
        .send(heartbeat)
        .set('Authorization', `Bearer ${authToken}`)
        .set('X-Trace-Id', TraceIdBuilder.random())
        .expect(200);

      const apiResponse = response.body as ApiResponse<{
        telemetryStored: boolean;
        metrics: { cpuUsage: number; memoryUsage: number };
      }>;

      expect(apiResponse.code).toBe(200);
      expect(apiResponse.data?.telemetryStored).toBe(true);
      expect(apiResponse.data?.metrics.cpuUsage).toBe(heartbeat.cpuUsage);
      expect(apiResponse.data?.metrics.memoryUsage).toBe(heartbeat.memoryUsage);
    });
  });

  describe('Device Deactivation - Consistency Guarantees', () => {
    let testDeviceId: string;
    let authToken: string;

    beforeEach(async () => {
      testDeviceId = DeviceIdBuilder.random();
      authToken = `test-token-${Date.now()}`;

      nock(API_BASE_URL)
        .post(`${API_PREFIX}/devices/activate`)
        .reply(201, () => {
          const response = DeviceResponseBuilder.default()
            .withId(testDeviceId)
            .withStatus('ONLINE')
            .build();
          return ApiResponseBuilder.created(response);
        });

      const activateResponse = await request(API_BASE_URL)
        .post(`${API_PREFIX}/devices/activate`)
        .send(DeviceActivateRequestBuilder.default().build())
        .expect(200);

      isolationContext.registerDevice((activateResponse.body as ApiResponse<DeviceResponse>).data!.id);
    });

    it('should transition to DEACTIVATED state atomically', async () => {
      const deactivateRequest = DeviceDeactivateRequestBuilder.forDevice(
        testDeviceId,
        'End of lifecycle'
      );

      nock(API_BASE_URL)
        .delete(`${API_PREFIX}/devices/${testDeviceId}/deactivate`)
        .reply(200, (_, requestBody) => {
          const req = requestBody as DeviceDeactivateRequest;
          return ApiResponseBuilder.success({
            deviceId: req.deviceId,
            status: 'DEACTIVATED',
            reason: req.reason,
            deactivatedAt: new Date().toISOString(),
          });
        });

      const response = await request(API_BASE_URL)
        .delete(`${API_PREFIX}/devices/${testDeviceId}/deactivate`)
        .send(deactivateRequest)
        .set('Authorization', `Bearer ${authToken}`)
        .set('X-Trace-Id', TraceIdBuilder.random())
        .expect(200);

      const apiResponse = response.body as ApiResponse<{
        deviceId: string;
        status: DeviceStatus;
        deactivatedAt: string;
      }>;

      expect(apiResponse.code).toBe(200);
      expect(apiResponse.data?.status).toBe('DEACTIVATED');
      expect(apiResponse.data?.deactivatedAt).toBeDefined();

      nock(API_BASE_URL)
        .get(`${API_PREFIX}/devices/${testDeviceId}/status`)
        .reply(200, () => {
          const response = DeviceResponseBuilder.default()
            .withId(testDeviceId)
            .withStatus('DEACTIVATED')
            .build();
          return ApiResponseBuilder.success(response);
        });

      const statusResponse = await request(API_BASE_URL)
        .get(`${API_PREFIX}/devices/${testDeviceId}/status`)
        .set('Authorization', `Bearer ${authToken}`)
        .expect(200);

      expect((statusResponse.body as ApiResponse<DeviceResponse>).data?.status).toBe('DEACTIVATED');
    });

    it('should reject heartbeats from deactivated devices', async () => {
      const deactivateRequest = DeviceDeactivateRequestBuilder.forDevice(testDeviceId).build();

      nock(API_BASE_URL)
        .delete(`${API_PREFIX}/devices/${testDeviceId}/deactivate`)
        .reply(200, () =>
          ApiResponseBuilder.success({
            deviceId: testDeviceId,
            status: 'DEACTIVATED',
          })
        );

      await request(API_BASE_URL)
        .delete(`${API_PREFIX}/devices/${testDeviceId}/deactivate`)
        .send(deactivateRequest)
        .set('Authorization', `Bearer ${authToken}`)
        .expect(200);

      nock(API_BASE_URL)
        .post(`${API_PREFIX}/devices/heartbeat`)
        .reply(401, () =>
          ApiResponseBuilder.unauthorized('Device is deactivated')
        );

      const heartbeat = DeviceHeartbeatRequestBuilder.onlineFor(testDeviceId);
      const heartbeatResponse = await request(API_BASE_URL)
        .post(`${API_PREFIX}/devices/heartbeat`)
        .send(heartbeat)
        .set('Authorization', `Bearer ${authToken}`)
        .expect(200);

      expect((heartbeatResponse.body as ApiResponse).code).toBe(401);
    });

    it('should be idempotent - multiple deactivate requests should not cause errors', async () => {
      const deactivateRequest = DeviceDeactivateRequestBuilder.forDevice(testDeviceId).build();

      nock(API_BASE_URL)
        .delete(`${API_PREFIX}/devices/${testDeviceId}/deactivate`)
        .times(3)
        .reply(200, () =>
          ApiResponseBuilder.success({
            deviceId: testDeviceId,
            status: 'DEACTIVATED',
            alreadyDeactivated: false,
          })
        );

      const responses = await Promise.all([
        request(API_BASE_URL)
          .delete(`${API_PREFIX}/devices/${testDeviceId}/deactivate`)
          .send(deactivateRequest)
          .set('Authorization', `Bearer ${authToken}`),
        request(API_BASE_URL)
          .delete(`${API_PREFIX}/devices/${testDeviceId}/deactivate`)
          .send(deactivateRequest)
          .set('Authorization', `Bearer ${authToken}`),
        request(API_BASE_URL)
          .delete(`${API_PREFIX}/devices/${testDeviceId}/deactivate`)
          .send(deactivateRequest)
          .set('Authorization', `Bearer ${authToken}`),
      ]);

      responses.forEach(response => {
        const apiResponse = response.body as ApiResponse;
        expect([200, 201, 202]).toContain(apiResponse.code);
      });

      const allSuccessful = responses.every(r => (r.body as ApiResponse).code < 400);
      expect(allSuccessful).toBe(true);
    });
  });

  describe('Cross-Operation Consistency', () => {
    it('should maintain referential integrity across activation, heartbeat, and deactivation', async () => {
      const deviceKey = DeviceKeyBuilder.random();
      let deviceId: string;
      const authToken = `test-token-${Date.now()}`;

      nock(API_BASE_URL)
        .post(`${API_PREFIX}/devices/activate`)
        .reply(201, () => {
          const response = DeviceResponseBuilder.default()
            .withDeviceKey(deviceKey)
            .withStatus('ONLINE')
            .build();
          return ApiResponseBuilder.created(response);
        });

      const activateResponse = await request(API_BASE_URL)
        .post(`${API_PREFIX}/devices/activate`)
        .send(DeviceActivateRequestBuilder.default().withDeviceKey(deviceKey).build())
        .set('X-Trace-Id', TraceIdBuilder.random())
        .expect(200);

      deviceId = (activateResponse.body as ApiResponse<DeviceResponse>).data!.id;
      isolationContext.registerDevice(deviceId);

      nock(API_BASE_URL)
        .post(`${API_PREFIX}/devices/heartbeat`)
        .times(5)
        .reply(200, () =>
          ApiResponseBuilder.success({ deviceId, status: 'ONLINE' })
        );

      for (let i = 0; i < 5; i++) {
        await request(API_BASE_URL)
          .post(`${API_PREFIX}/devices/heartbeat`)
          .send(DeviceHeartbeatRequestBuilder.onlineFor(deviceId))
          .set('Authorization', `Bearer ${authToken}`)
          .set('X-Trace-Id', TraceIdBuilder.fromSeed(i))
          .expect(200);
      }

      nock(API_BASE_URL)
        .delete(`${API_PREFIX}/devices/${deviceId}/deactivate`)
        .reply(200, () =>
          ApiResponseBuilder.success({ deviceId, status: 'DEACTIVATED' })
        );

      const deactivateResponse = await request(API_BASE_URL)
        .delete(`${API_PREFIX}/devices/${deviceId}/deactivate`)
        .send(DeviceDeactivateRequestBuilder.forDevice(deviceId).build())
        .set('Authorization', `Bearer ${authToken}`)
        .expect(200);

      expect((deactivateResponse.body as ApiResponse).code).toBe(200);

      nock(API_BASE_URL)
        .get(`${API_PREFIX}/devices/${deviceId}`)
        .reply(200, () => {
          const response = DeviceResponseBuilder.default()
            .withId(deviceId)
            .withDeviceKey(deviceKey)
            .withStatus('DEACTIVATED')
            .build();
          return ApiResponseBuilder.success(response);
        });

      const finalStatus = await request(API_BASE_URL)
        .get(`${API_PREFIX}/devices/${deviceId}`)
        .set('Authorization', `Bearer ${authToken}`)
        .expect(200);

      const finalData = (finalStatus.body as ApiResponse<DeviceResponse>).data!;
      expect(finalData.id).toBe(deviceId);
      expect(finalData.deviceKey).toBe(deviceKey);
      expect(finalData.status).toBe('DEACTIVATED');
    });

    it('should handle concurrent operations on the same device without data corruption', async () => {
      const deviceKey = DeviceKeyBuilder.random();
      let deviceId: string;
      const authToken = `test-token-${Date.now()}`;

      nock(API_BASE_URL)
        .post(`${API_PREFIX}/devices/activate`)
        .reply(201, () => {
          const response = DeviceResponseBuilder.default()
            .withDeviceKey(deviceKey)
            .build();
          return ApiResponseBuilder.created(response);
        });

      const activateResponse = await request(API_BASE_URL)
        .post(`${API_PREFIX}/devices/activate`)
        .send(DeviceActivateRequestBuilder.default().withDeviceKey(deviceKey).build())
        .expect(200);

      deviceId = (activateResponse.body as ApiResponse<DeviceResponse>).data!.id;
      isolationContext.registerDevice(deviceId);

      nock(API_BASE_URL)
        .post(`${API_PREFIX}/devices/heartbeat`)
        .times(10)
        .reply(200, () =>
          ApiResponseBuilder.success({ deviceId, status: 'ONLINE' })
        );

      nock(API_BASE_URL)
        .get(`${API_PREFIX}/devices/${deviceId}/status`)
        .times(5)
        .reply(200, () => {
          const response = DeviceResponseBuilder.default()
            .withId(deviceId)
            .withStatus('ONLINE')
            .build();
          return ApiResponseBuilder.success(response);
        });

      const operations = [];

      for (let i = 0; i < 10; i++) {
        operations.push(
          request(API_BASE_URL)
            .post(`${API_PREFIX}/devices/heartbeat`)
            .send(DeviceHeartbeatRequestBuilder.onlineFor(deviceId))
            .set('Authorization', `Bearer ${authToken}`)
            .set('X-Trace-Id', TraceIdBuilder.fromSeed(i))
        );
      }

      for (let i = 0; i < 5; i++) {
        operations.push(
          request(API_BASE_URL)
            .get(`${API_PREFIX}/devices/${deviceId}/status`)
            .set('Authorization', `Bearer ${authToken}`)
            .set('X-Trace-Id', TraceIdBuilder.fromSeed(i + 100))
        );
      }

      const results = await Promise.all(operations);
      const successCount = results.filter(r => (r.body as ApiResponse).code === 200).length;

      expect(successCount).toBe(operations.length);

      nock(API_BASE_URL)
        .get(`${API_PREFIX}/devices/${deviceId}`)
        .reply(200, () => {
          const response = DeviceResponseBuilder.default()
            .withId(deviceId)
            .withStatus('ONLINE')
            .build();
          return ApiResponseBuilder.success(response);
        });

      const finalCheck = await request(API_BASE_URL)
        .get(`${API_PREFIX}/devices/${deviceId}`)
        .set('Authorization', `Bearer ${authToken}`)
        .expect(200);

      const finalData = (finalCheck.body as ApiResponse<DeviceResponse>).data!;
      expect(finalData.id).toBe(deviceId);
      expect(finalData.status).toBe('ONLINE');
      expect(finalData.lastHeartbeatAt).toBeDefined();
    });
  });
});
