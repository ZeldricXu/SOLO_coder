export type DeviceStatus = 'INACTIVE' | 'ONLINE' | 'OFFLINE' | 'FAULT' | 'DEACTIVATED';
export type EntityStatus = 'PENDING' | 'PROCESSING' | 'COMPLETED' | 'FAILED' | 'CANCELLED';
export type RunPhase = 'VALIDATE' | 'LOAD_CONFIG' | 'ACQUIRE_RESOURCE' | 'PROCESS_CORE' | 'PERSIST' | 'EMIT_EVENT';
export type OTAStatus = 'PENDING' | 'DOWNLOADING' | 'UPGRADING' | 'SUCCESS' | 'FAILED' | 'ROLLED_BACK';

export interface DeviceActivateRequest {
  deviceKey: string;
  deviceSecret: string;
  firmwareVersion: string;
  hardwareVersion: string;
  metadata?: Record<string, unknown>;
}

export interface DeviceAuthRequest {
  deviceId: string;
  deviceSecret: string;
  timestamp: number;
  signature: string;
  nonce: string;
}

export interface DeviceHeartbeatRequest {
  deviceId: string;
  status: DeviceStatus;
  cpuUsage?: number;
  memoryUsage?: number;
  diskUsage?: number;
  networkLatency?: number;
  firmwareVersion?: string;
}

export interface DeviceDeactivateRequest {
  deviceId: string;
  reason?: string;
}

export interface ApiResponse<T = unknown> {
  code: number;
  message?: string;
  data?: T;
  traceId: string;
  timestamp: string;
}

export interface DeviceResponse {
  id: string;
  deviceKey: string;
  status: DeviceStatus;
  firmwareVersion: string;
  hardwareVersion: string;
  activatedAt: string;
  lastHeartbeatAt?: string;
  metadata?: Record<string, unknown>;
}

export interface DeviceAuthResponse {
  token: string;
  refreshToken: string;
  expiresIn: number;
  tokenType: string;
}

export interface ProcessRequest {
  traceId?: string;
  namespace: string;
  params: Record<string, unknown>;
  payload: Record<string, unknown>;
}

export interface ProcessResponse {
  runId: string;
  entityId: string;
  status: EntityStatus;
  phase: RunPhase;
  progress: number;
  result?: Record<string, unknown>;
  startedAt: string;
  completedAt?: string;
  errorDetail?: string;
}

export interface ResourceCreateRequest {
  type: string;
  config: Record<string, unknown>;
  labels?: Record<string, string>;
}

export interface ResourceResponse {
  id: string;
  status: string;
  type: string;
  config: Record<string, unknown>;
  labels?: Record<string, string>;
  createdAt: string;
}

export interface MetricsSnapshot {
  snapshotId: string;
  timestamp: string;
  metrics: {
    throughput: number;
    latencyP50: number;
    latencyP95: number;
    latencyP99: number;
    errorRate: number;
    activeDevices: number;
  };
  dimensions: Record<string, string>;
}

export class DeviceIdBuilder {
  static random(): string {
    return `dev_${Date.now()}_${Math.random().toString(36).slice(2, 10)}`;
  }

  static fromSeed(seed: number): string {
    return `dev_${seed.toString().padStart(12, '0')}`;
  }
}

export class DeviceKeyBuilder {
  static random(): string {
    return `KEY_${Math.random().toString(36).slice(2, 16).toUpperCase()}`;
  }

  static fromSeed(seed: number): string {
    return `KEY_${seed.toString().padStart(12, '0')}`;
  }
}

export class DeviceSecretBuilder {
  static random(): string {
    return Array.from({ length: 32 }, () =>
      Math.floor(Math.random() * 16).toString(16)
    ).join('');
  }
}

export class TraceIdBuilder {
  static random(): string {
    return `trace_${Date.now()}_${Math.random().toString(36).slice(2, 10)}`;
  }

  static fromSeed(seed: number): string {
    return `trace_${seed.toString().padStart(20, '0')}`;
  }
}

export class SignatureBuilder {
  static random(): string {
    return `SIG_${Array.from({ length: 64 }, () =>
      Math.floor(Math.random() * 16).toString(16)
    ).join('')}`;
  }
}

export class NonceBuilder {
  static random(): string {
    return Math.random().toString(36).slice(2, 18);
  }
}

export class DeviceActivateRequestBuilder {
  private deviceKey: string = DeviceKeyBuilder.random();
  private deviceSecret: string = DeviceSecretBuilder.random();
  private firmwareVersion: string = 'v1.0.0';
  private hardwareVersion: string = 'HW-A1';
  private metadata: Record<string, unknown> = {};

  withDeviceKey(key: string): this {
    this.deviceKey = key;
    return this;
  }

  withDeviceSecret(secret: string): this {
    this.deviceSecret = secret;
    return this;
  }

  withFirmwareVersion(version: string): this {
    this.firmwareVersion = version;
    return this;
  }

  withHardwareVersion(version: string): this {
    this.hardwareVersion = version;
    return this;
  }

  withMetadata(metadata: Record<string, unknown>): this {
    this.metadata = metadata;
    return this;
  }

  build(): DeviceActivateRequest {
    return {
      deviceKey: this.deviceKey,
      deviceSecret: this.deviceSecret,
      firmwareVersion: this.firmwareVersion,
      hardwareVersion: this.hardwareVersion,
      metadata: this.metadata,
    };
  }

  static default(): DeviceActivateRequestBuilder {
    return new DeviceActivateRequestBuilder();
  }

  static withFirmware(version: string): DeviceActivateRequest {
    return new DeviceActivateRequestBuilder()
      .withFirmwareVersion(version)
      .build();
  }

  static withMetadata(data: Record<string, unknown>): DeviceActivateRequest {
    return new DeviceActivateRequestBuilder()
      .withMetadata(data)
      .build();
  }
}

export class DeviceAuthRequestBuilder {
  private deviceId: string = DeviceIdBuilder.random();
  private deviceSecret: string = DeviceSecretBuilder.random();
  private timestamp: number = Date.now();
  private signature: string = SignatureBuilder.random();
  private nonce: string = NonceBuilder.random();

  withDeviceId(id: string): this {
    this.deviceId = id;
    return this;
  }

  withDeviceSecret(secret: string): this {
    this.deviceSecret = secret;
    return this;
  }

  withTimestamp(ts: number): this {
    this.timestamp = ts;
    return this;
  }

  withSignature(sig: string): this {
    this.signature = sig;
    return this;
  }

  withNonce(nonce: string): this {
    this.nonce = nonce;
    return this;
  }

  build(): DeviceAuthRequest {
    return {
      deviceId: this.deviceId,
      deviceSecret: this.deviceSecret,
      timestamp: this.timestamp,
      signature: this.signature,
      nonce: this.nonce,
    };
  }

  static default(): DeviceAuthRequestBuilder {
    return new DeviceAuthRequestBuilder();
  }

  static forDevice(deviceId: string): DeviceAuthRequest {
    return new DeviceAuthRequestBuilder()
      .withDeviceId(deviceId)
      .build();
  }
}

export class DeviceHeartbeatRequestBuilder {
  private deviceId: string = DeviceIdBuilder.random();
  private status: DeviceStatus = 'ONLINE';
  private cpuUsage: number = Math.random() * 100;
  private memoryUsage: number = Math.random() * 100;
  private diskUsage: number = Math.random() * 100;
  private networkLatency: number = Math.random() * 100;
  private firmwareVersion: string = 'v1.0.0';

  withDeviceId(id: string): this {
    this.deviceId = id;
    return this;
  }

  withStatus(status: DeviceStatus): this {
    this.status = status;
    return this;
  }

  withCpuUsage(usage: number): this {
    this.cpuUsage = usage;
    return this;
  }

  withMemoryUsage(usage: number): this {
    this.memoryUsage = usage;
    return this;
  }

  withDiskUsage(usage: number): this {
    this.diskUsage = usage;
    return this;
  }

  withNetworkLatency(latency: number): this {
    this.networkLatency = latency;
    return this;
  }

  withFirmwareVersion(version: string): this {
    this.firmwareVersion = version;
    return this;
  }

  build(): DeviceHeartbeatRequest {
    return {
      deviceId: this.deviceId,
      status: this.status,
      cpuUsage: this.cpuUsage,
      memoryUsage: this.memoryUsage,
      diskUsage: this.diskUsage,
      networkLatency: this.networkLatency,
      firmwareVersion: this.firmwareVersion,
    };
  }

  static default(): DeviceHeartbeatRequestBuilder {
    return new DeviceHeartbeatRequestBuilder();
  }

  static onlineFor(deviceId: string): DeviceHeartbeatRequest {
    return new DeviceHeartbeatRequestBuilder()
      .withDeviceId(deviceId)
      .withStatus('ONLINE')
      .withCpuUsage(45.5)
      .withMemoryUsage(62.3)
      .withDiskUsage(38.1)
      .withNetworkLatency(25)
      .build();
  }

  static offlineFor(deviceId: string): DeviceHeartbeatRequest {
    return new DeviceHeartbeatRequestBuilder()
      .withDeviceId(deviceId)
      .withStatus('OFFLINE')
      .build();
  }

  static faultFor(deviceId: string): DeviceHeartbeatRequest {
    return new DeviceHeartbeatRequestBuilder()
      .withDeviceId(deviceId)
      .withStatus('FAULT')
      .withCpuUsage(95.0)
      .withMemoryUsage(98.0)
      .build();
  }
}

export class DeviceDeactivateRequestBuilder {
  private deviceId: string = DeviceIdBuilder.random();
  private reason: string = 'User requested deactivation';

  withDeviceId(id: string): this {
    this.deviceId = id;
    return this;
  }

  withReason(reason: string): this {
    this.reason = reason;
    return this;
  }

  build(): DeviceDeactivateRequest {
    return {
      deviceId: this.deviceId,
      reason: this.reason,
    };
  }

  static default(): DeviceDeactivateRequestBuilder {
    return new DeviceDeactivateRequestBuilder();
  }

  static forDevice(deviceId: string, reason?: string): DeviceDeactivateRequest {
    const builder = new DeviceDeactivateRequestBuilder().withDeviceId(deviceId);
    if (reason) {
      builder.withReason(reason);
    }
    return builder.build();
  }
}

export class ProcessRequestBuilder {
  private traceId: string = TraceIdBuilder.random();
  private namespace: string = 'production';
  private params: Record<string, unknown> = {};
  private payload: Record<string, unknown> = {};

  withTraceId(traceId: string): this {
    this.traceId = traceId;
    return this;
  }

  withNamespace(namespace: string): this {
    this.namespace = namespace;
    return this;
  }

  withParams(params: Record<string, unknown>): this {
    this.params = params;
    return this;
  }

  withPayload(payload: Record<string, unknown>): this {
    this.payload = payload;
    return this;
  }

  build(): ProcessRequest {
    return {
      traceId: this.traceId,
      namespace: this.namespace,
      params: this.params,
      payload: this.payload,
    };
  }

  static default(): ProcessRequestBuilder {
    return new ProcessRequestBuilder();
  }

  static simple(namespace: string = 'default'): ProcessRequest {
    return new ProcessRequestBuilder()
      .withNamespace(namespace)
      .withParams({ timeout: 30, retries: 3 })
      .withPayload({ type: 'data_process', value: 42 })
      .build();
  }

  static withLargePayload(): ProcessRequest {
    const largeData = Array.from({ length: 1000 }, (_, i) => ({
      id: i,
      value: Math.random(),
      timestamp: Date.now() - i * 1000,
    }));
    return new ProcessRequestBuilder()
      .withParams({ batchSize: 1000, mode: 'bulk' })
      .withPayload({ records: largeData })
      .build();
  }
}

export class ResourceResponseBuilder {
  private id: string = `rsc_${Date.now()}_${Math.random().toString(36).slice(2, 8)}`;
  private status: string = 'provisioning';
  private type: string = 'task';
  private config: Record<string, unknown> = {};
  private labels: Record<string, string> = {};
  private createdAt: string = new Date().toISOString();

  withId(id: string): this {
    this.id = id;
    return this;
  }

  withStatus(status: string): this {
    this.status = status;
    return this;
  }

  withType(type: string): this {
    this.type = type;
    return this;
  }

  withConfig(config: Record<string, unknown>): this {
    this.config = config;
    return this;
  }

  withLabels(labels: Record<string, string>): this {
    this.labels = labels;
    return this;
  }

  withCreatedAt(date: string): this {
    this.createdAt = date;
    return this;
  }

  build(): ResourceResponse {
    return {
      id: this.id,
      status: this.status,
      type: this.type,
      config: this.config,
      labels: this.labels,
      createdAt: this.createdAt,
    };
  }

  static default(): ResourceResponseBuilder {
    return new ResourceResponseBuilder();
  }

  static provisioning(): ResourceResponse {
    return new ResourceResponseBuilder()
      .withStatus('provisioning')
      .build();
  }

  static running(): ResourceResponse {
    return new ResourceResponseBuilder()
      .withStatus('running')
      .build();
  }

  static failed(): ResourceResponse {
    return new ResourceResponseBuilder()
      .withStatus('failed')
      .build();
  }
}

export class ResourceCreateRequestBuilder {
  private type: string = 'task';
  private config: Record<string, unknown> = {};
  private labels: Record<string, string> = {};

  withType(type: string): this {
    this.type = type;
    return this;
  }

  withConfig(config: Record<string, unknown>): this {
    this.config = config;
    return this;
  }

  withLabels(labels: Record<string, string>): this {
    this.labels = labels;
    return this;
  }

  build(): ResourceCreateRequest {
    return {
      type: this.type,
      config: this.config,
      labels: this.labels,
    };
  }

  static default(): ResourceCreateRequestBuilder {
    return new ResourceCreateRequestBuilder();
  }

  static taskResource(): ResourceCreateRequest {
    return new ResourceCreateRequestBuilder()
      .withType('task')
      .withConfig({
        priority: 'high',
        timeout: 60,
        retries: 3,
      })
      .withLabels({
        environment: 'test',
        team: 'device-platform',
      })
      .build();
  }

  static computeResource(): ResourceCreateRequest {
    return new ResourceCreateRequestBuilder()
      .withType('compute')
      .withConfig({
        cpu: '4',
        memory: '8GB',
        gpu: false,
      })
      .withLabels({
        region: 'cn-east',
        instance: 'edge-01',
      })
      .build();
  }
}

export class DeviceResponseBuilder {
  private id: string = DeviceIdBuilder.random();
  private deviceKey: string = DeviceKeyBuilder.random();
  private status: DeviceStatus = 'ONLINE';
  private firmwareVersion: string = 'v1.0.0';
  private hardwareVersion: string = 'HW-A1';
  private activatedAt: string = new Date().toISOString();
  private lastHeartbeatAt: string = new Date().toISOString();
  private metadata: Record<string, unknown> = {};

  withId(id: string): this {
    this.id = id;
    return this;
  }

  withDeviceKey(key: string): this {
    this.deviceKey = key;
    return this;
  }

  withStatus(status: DeviceStatus): this {
    this.status = status;
    return this;
  }

  withFirmwareVersion(version: string): this {
    this.firmwareVersion = version;
    return this;
  }

  withHardwareVersion(version: string): this {
    this.hardwareVersion = version;
    return this;
  }

  withActivatedAt(date: string): this {
    this.activatedAt = date;
    return this;
  }

  withLastHeartbeatAt(date: string): this {
    this.lastHeartbeatAt = date;
    return this;
  }

  withMetadata(metadata: Record<string, unknown>): this {
    this.metadata = metadata;
    return this;
  }

  build(): DeviceResponse {
    return {
      id: this.id,
      deviceKey: this.deviceKey,
      status: this.status,
      firmwareVersion: this.firmwareVersion,
      hardwareVersion: this.hardwareVersion,
      activatedAt: this.activatedAt,
      lastHeartbeatAt: this.lastHeartbeatAt,
      metadata: this.metadata,
    };
  }

  static default(): DeviceResponseBuilder {
    return new DeviceResponseBuilder();
  }

  static activated(): DeviceResponse {
    return new DeviceResponseBuilder()
      .withStatus('ONLINE')
      .withMetadata({ location: 'factory-floor-1', ip: '192.168.1.100' })
      .build();
  }
}

export class DeviceAuthResponseBuilder {
  private token: string = `eyJhbGciOiJIUzI1NiJ9.${Math.random().toString(36)}`;
  private refreshToken: string = `refresh_${Math.random().toString(36)}`;
  private expiresIn: number = 3600;
  private tokenType: string = 'Bearer';

  withToken(token: string): this {
    this.token = token;
    return this;
  }

  withRefreshToken(token: string): this {
    this.refreshToken = token;
    return this;
  }

  withExpiresIn(seconds: number): this {
    this.expiresIn = seconds;
    return this;
  }

  withTokenType(type: string): this {
    this.tokenType = type;
    return this;
  }

  build(): DeviceAuthResponse {
    return {
      token: this.token,
      refreshToken: this.refreshToken,
      expiresIn: this.expiresIn,
      tokenType: this.tokenType,
    };
  }

  static default(): DeviceAuthResponseBuilder {
    return new DeviceAuthResponseBuilder();
  }

  static standard(): DeviceAuthResponse {
    return new DeviceAuthResponseBuilder()
      .withExpiresIn(3600)
      .withTokenType('Bearer')
      .build();
  }
}

export class ProcessResponseBuilder {
  private runId: string = `run_${Date.now()}_${Math.random().toString(36).slice(2, 8)}`;
  private entityId: string = `ent_${Math.random().toString(36).slice(2, 12)}`;
  private status: EntityStatus = 'COMPLETED';
  private phase: RunPhase = 'EMIT_EVENT';
  private progress: number = 1.0;
  private result: Record<string, unknown> = {};
  private startedAt: string = new Date(Date.now() - 5000).toISOString();
  private completedAt: string = new Date().toISOString();
  private errorDetail?: string;

  withRunId(id: string): this {
    this.runId = id;
    return this;
  }

  withEntityId(id: string): this {
    this.entityId = id;
    return this;
  }

  withStatus(status: EntityStatus): this {
    this.status = status;
    return this;
  }

  withPhase(phase: RunPhase): this {
    this.phase = phase;
    return this;
  }

  withProgress(progress: number): this {
    this.progress = progress;
    return this;
  }

  withResult(result: Record<string, unknown>): this {
    this.result = result;
    return this;
  }

  withStartedAt(date: string): this {
    this.startedAt = date;
    return this;
  }

  withCompletedAt(date: string): this {
    this.completedAt = date;
    return this;
  }

  withErrorDetail(detail: string): this {
    this.errorDetail = detail;
    return this;
  }

  build(): ProcessResponse {
    return {
      runId: this.runId,
      entityId: this.entityId,
      status: this.status,
      phase: this.phase,
      progress: this.progress,
      result: this.result,
      startedAt: this.startedAt,
      completedAt: this.completedAt,
      errorDetail: this.errorDetail,
    };
  }

  static default(): ProcessResponseBuilder {
    return new ProcessResponseBuilder();
  }

  static success(): ProcessResponse {
    return new ProcessResponseBuilder()
      .withStatus('COMPLETED')
      .withPhase('EMIT_EVENT')
      .withProgress(1.0)
      .withResult({ output: 'processed', count: 42 })
      .build();
  }

  static processing(): ProcessResponse {
    return new ProcessResponseBuilder()
      .withStatus('PROCESSING')
      .withPhase('PROCESS_CORE')
      .withProgress(0.75)
      .withCompletedAt(undefined as unknown as string)
      .withErrorDetail(undefined as unknown as string)
      .build();
  }

  static failed(error: string): ProcessResponse {
    return new ProcessResponseBuilder()
      .withStatus('FAILED')
      .withPhase('PROCESS_CORE')
      .withProgress(0.5)
      .withErrorDetail(error)
      .build();
  }
}

export class ApiResponseBuilder {
  private code: number = 200;
  private message: string = 'Success';
  private data: unknown = {};
  private traceId: string = TraceIdBuilder.random();
  private timestamp: string = new Date().toISOString();

  withCode(code: number): this {
    this.code = code;
    return this;
  }

  withMessage(message: string): this {
    this.message = message;
    return this;
  }

  withData(data: unknown): this {
    this.data = data;
    return this;
  }

  withTraceId(traceId: string): this {
    this.traceId = traceId;
    return this;
  }

  build<T>(): ApiResponse<T> {
    return {
      code: this.code,
      message: this.message,
      data: this.data as T,
      traceId: this.traceId,
      timestamp: this.timestamp,
    };
  }

  static default(): ApiResponseBuilder {
    return new ApiResponseBuilder();
  }

  static success<T>(data: T): ApiResponse<T> {
    return new ApiResponseBuilder()
      .withCode(200)
      .withMessage('Success')
      .withData(data)
      .build<T>();
  }

  static created<T>(data: T): ApiResponse<T> {
    return new ApiResponseBuilder()
      .withCode(201)
      .withMessage('Created')
      .withData(data)
      .build<T>();
  }

  static accepted<T>(data: T): ApiResponse<T> {
    return new ApiResponseBuilder()
      .withCode(202)
      .withMessage('Accepted')
      .withData(data)
      .build<T>();
  }

  static badRequest(message: string): ApiResponse {
    return new ApiResponseBuilder()
      .withCode(400)
      .withMessage(message)
      .build();
  }

  static unauthorized(message: string = 'Unauthorized'): ApiResponse {
    return new ApiResponseBuilder()
      .withCode(401)
      .withMessage(message)
      .build();
  }

  static notFound(message: string = 'Not Found'): ApiResponse {
    return new ApiResponseBuilder()
      .withCode(404)
      .withMessage(message)
      .build();
  }

  static conflict(message: string): ApiResponse {
    return new ApiResponseBuilder()
      .withCode(409)
      .withMessage(message)
      .build();
  }

  static tooManyRequests(message: string = 'Rate limit exceeded'): ApiResponse {
    return new ApiResponseBuilder()
      .withCode(429)
      .withMessage(message)
      .build();
  }

  static gatewayTimeout(message: string = 'Gateway Timeout'): ApiResponse {
    return new ApiResponseBuilder()
      .withCode(504)
      .withMessage(message)
      .build();
  }

  static internalError(message: string = 'Internal Server Error'): ApiResponse {
    return new ApiResponseBuilder()
      .withCode(500)
      .withMessage(message)
      .build();
  }
}

export class MetricsSnapshotBuilder {
  private snapshotId: string = `snap_${Date.now()}_${Math.random().toString(36).slice(2, 8)}`;
  private timestamp: string = new Date().toISOString();
  private throughput: number = 1500;
  private latencyP50: number = 50;
  private latencyP95: number = 150;
  private latencyP99: number = 250;
  private errorRate: number = 0.001;
  private activeDevices: number = 1000;
  private dimensions: Record<string, string> = {
    host: 'node-1',
    region: 'cn-east',
  };

  withThroughput(tps: number): this {
    this.throughput = tps;
    return this;
  }

  withLatencyP50(ms: number): this {
    this.latencyP50 = ms;
    return this;
  }

  withLatencyP95(ms: number): this {
    this.latencyP95 = ms;
    return this;
  }

  withLatencyP99(ms: number): this {
    this.latencyP99 = ms;
    return this;
  }

  withErrorRate(rate: number): this {
    this.errorRate = rate;
    return this;
  }

  withActiveDevices(count: number): this {
    this.activeDevices = count;
    return this;
  }

  withDimensions(dimensions: Record<string, string>): this {
    this.dimensions = dimensions;
    return this;
  }

  build(): MetricsSnapshot {
    return {
      snapshotId: this.snapshotId,
      timestamp: this.timestamp,
      metrics: {
        throughput: this.throughput,
        latencyP50: this.latencyP50,
        latencyP95: this.latencyP95,
        latencyP99: this.latencyP99,
        errorRate: this.errorRate,
        activeDevices: this.activeDevices,
      },
      dimensions: this.dimensions,
    };
  }

  static default(): MetricsSnapshotBuilder {
    return new MetricsSnapshotBuilder();
  }

  static healthy(): MetricsSnapshot {
    return new MetricsSnapshotBuilder()
      .withThroughput(2000)
      .withLatencyP50(30)
      .withLatencyP95(80)
      .withLatencyP99(150)
      .withErrorRate(0.0001)
      .withActiveDevices(5000)
      .build();
  }

  static underLoad(): MetricsSnapshot {
    return new MetricsSnapshotBuilder()
      .withThroughput(800)
      .withLatencyP50(100)
      .withLatencyP95(300)
      .withLatencyP99(500)
      .withErrorRate(0.01)
      .withActiveDevices(10000)
      .build();
  }

  static degraded(): MetricsSnapshot {
    return new MetricsSnapshotBuilder()
      .withThroughput(300)
      .withLatencyP50(500)
      .withLatencyP95(1500)
      .withLatencyP99(3000)
      .withErrorRate(0.1)
      .withActiveDevices(2000)
      .build();
  }
}

export class TestHttpClient {
  private baseUrl: string;
  private defaultHeaders: Record<string, string>;

  constructor(baseUrl: string = 'http://localhost:8080') {
    this.baseUrl = baseUrl;
    this.defaultHeaders = {
      'Content-Type': 'application/json',
      'Accept': 'application/json',
      'X-Request-Id': TraceIdBuilder.random(),
    };
  }

  withHeader(key: string, value: string): this {
    this.defaultHeaders[key] = value;
    return this;
  }

  withTraceId(traceId: string): this {
    this.defaultHeaders['X-Trace-Id'] = traceId;
    return this;
  }

  withAuthToken(token: string): this {
    this.defaultHeaders['Authorization'] = `Bearer ${token}`;
    return this;
  }

  getBaseUrl(): string {
    return this.baseUrl;
  }

  getHeaders(): Record<string, string> {
    return { ...this.defaultHeaders };
  }

  static create(baseUrl?: string): TestHttpClient {
    return new TestHttpClient(baseUrl);
  }

  static withAuth(baseUrl: string, token: string): TestHttpClient {
    return new TestHttpClient(baseUrl).withAuthToken(token);
  }
}

export class TestDataFactory {
  static createDeviceActivationBatch(count: number): DeviceActivateRequest[] {
    return Array.from({ length: count }, (_, i) =>
      DeviceActivateRequestBuilder.default()
        .withDeviceKey(DeviceKeyBuilder.fromSeed(i))
        .withMetadata({ batchIndex: i, group: `group-${Math.floor(i / 10)}` })
        .build()
    );
  }

  static createHeartbeatSequence(deviceId: string, count: number): DeviceHeartbeatRequest[] {
    return Array.from({ length: count }, (_, i) =>
      DeviceHeartbeatRequestBuilder.default()
        .withDeviceId(deviceId)
        .withStatus(i % 5 === 0 ? 'FAULT' : 'ONLINE')
        .withCpuUsage(30 + Math.random() * 40)
        .withMemoryUsage(40 + Math.random() * 30)
        .build()
    );
  }

  static createConcurrentRequests(count: number): ProcessRequest[] {
    return Array.from({ length: count }, (_, i) =>
      ProcessRequestBuilder.default()
        .withTraceId(TraceIdBuilder.fromSeed(i))
        .withNamespace(`namespace-${i % 5}`)
        .withParams({ taskId: i, priority: i % 3 === 0 ? 'high' : 'normal' })
        .withPayload({ data: `payload-${i}`, value: i * 10 })
        .build()
    );
  }

  static createResourceBatch(count: number): ResourceCreateRequest[] {
    return Array.from({ length: count }, (_, i) =>
      ResourceCreateRequestBuilder.default()
        .withType(i % 2 === 0 ? 'task' : 'compute')
        .withConfig({ id: i, size: 1024 * (i + 1) })
        .withLabels({ batch: `batch-${Math.floor(i / 20)}`, index: String(i) })
        .build()
    );
  }
}

export const TestConstants = {
  API_BASE_URL: 'http://localhost:8080',
  API_V1_PREFIX: '/api/v1',
  DEFAULT_TIMEOUT: 5000,
  LONG_TIMEOUT: 30000,
  RATE_LIMIT_REQUESTS: 100,
  RATE_LIMIT_WINDOW: 1000,
  CONCURRENT_REQUESTS: 50,
  HEARTBEAT_INTERVAL: 30000,
  TOKEN_EXPIRY: 3600,
  DEVICE_STATUS_TRANSITIONS: {
    valid: [
      ['INACTIVE', 'ONLINE'],
      ['ONLINE', 'OFFLINE'],
      ['ONLINE', 'FAULT'],
      ['OFFLINE', 'ONLINE'],
      ['FAULT', 'ONLINE'],
      ['ONLINE', 'DEACTIVATED'],
    ],
    invalid: [
      ['DEACTIVATED', 'ONLINE'],
      ['DEACTIVATED', 'OFFLINE'],
      ['INACTIVE', 'DEACTIVATED'],
    ],
  } as const,
} as const;

export function delay(ms: number): Promise<void> {
  return new Promise(resolve => setTimeout(resolve, ms));
}

export function generateSequentialIds(count: number): string[] {
  return Array.from({ length: count }, (_, i) => DeviceIdBuilder.fromSeed(i));
}

export function measureExecutionTime<T>(fn: () => Promise<T>): Promise<{ result: T; duration: number }> {
  const start = Date.now();
  return fn().then(result => ({
    result,
    duration: Date.now() - start,
  }));
}

type ComparatorFn<T> = (a: T, b: T) => boolean;

export function assertDataConsistency<T>(
  actual: T,
  expected: T,
  fieldsToCheck: (keyof T)[],
  customComparators?: Partial<Record<keyof T, ComparatorFn<T[keyof T]>>>
): void {
  for (const field of fieldsToCheck) {
    const comparator = customComparators?.[field];
    if (comparator) {
      expect(comparator(actual[field], expected[field])).toBe(true);
    } else {
      expect(actual[field]).toEqual(expected[field]);
    }
  }
}

export function createIsolationContext(): {
  contextId: string;
  createdDevices: string[];
  createdResources: string[];
  registerDevice: (id: string) => void;
  registerResource: (id: string) => void;
  cleanup: () => Promise<void>;
} {
  const contextId = `ctx_${Date.now()}_${Math.random().toString(36).slice(2, 8)}`;
  const createdDevices: string[] = [];
  const createdResources: string[] = [];

  return {
    contextId,
    createdDevices,
    createdResources,
    registerDevice: (id: string) => createdDevices.push(id),
    registerResource: (id: string) => createdResources.push(id),
    cleanup: async () => {
      createdDevices.length = 0;
      createdResources.length = 0;
    },
  };
}
