export interface InferenceRequest {
  modelId: string;
  version?: string;
  inputs: Record<string, unknown> | Record<string, unknown>[];
  requestId?: string;
  userId?: string;
  sessionId?: string;
  context?: Record<string, unknown>;
  bypassCache?: boolean;
}

export interface InferenceResponse {
  modelId: string;
  version: string;
  outputs: Record<string, unknown> | Record<string, unknown>[];
  requestId: string;
  inferenceId: string;
  latencyMs: number;
  batchSize: number;
  fromCache: boolean;
  timestamp: number;
}

export interface BatchInferenceRequest {
  modelId: string;
  version?: string;
  inputs: Record<string, unknown>[];
  batchSize?: number;
  maxConcurrency?: number;
  requestId?: string;
  context?: Record<string, unknown>;
}

export interface BatchInferenceResponse {
  modelId: string;
  version: string;
  outputs: Record<string, unknown>[];
  requestId: string;
  inferenceIds: string[];
  totalLatencyMs: number;
  batchCount: number;
  timestamp: number;
}

export interface InferenceMetrics {
  requestId: string;
  inferenceId: string;
  modelId: string;
  version: string;
  latencyMs: number;
  queueTimeMs: number;
  batchSize: number;
  inputSize: number;
  outputSize: number;
  fromCache: boolean;
  success: boolean;
  error?: string;
  timestamp: number;
  userId?: string;
  sessionId?: string;
  inputFeatures?: Record<string, unknown>;
  outputFeatures?: Record<string, unknown>;
}

export interface ModelDeployment {
  id: string;
  modelId: string;
  version: string;
  endpointName: string;
  status: 'pending' | 'deploying' | 'running' | 'failed' | 'stopped';
  protocol: 'rest' | 'grpc' | 'both';
  autoscaling: AutoscalingConfig;
  batchConfig: BatchConfig;
  environment: Record<string, string>;
  replicas: number;
  createdAt: number;
  updatedAt: number;
}

export interface AutoscalingConfig {
  minReplicas: number;
  maxReplicas: number;
  targetRPS: number;
  targetP99LatencyMs: number;
  scaleDownDelaySeconds: number;
}

export interface BatchConfig {
  maxBatchSize: number;
  batchTimeoutMs: number;
  dynamicBatching: boolean;
  maxQueueDepth: number;
}

export interface TokenBucketStats {
  currentTokens: number;
  maxBurstSize: number;
  refillRate: number;
  windowMs: number;
  maxBatchSize: number;
  adaptiveEnabled: boolean;
  adaptiveAdjustments: number;
}

export interface BatcherStats {
  modelId: string;
  version: string;
  currentQueueSize: number;
  totalRequests: number;
  totalBatches: number;
  avgBatchSize: number;
  avgQueueTimeMs: number;
  p50QueueTimeMs: number;
  p95QueueTimeMs: number;
  p99QueueTimeMs: number;
  tokenBucket?: TokenBucketStats;
}

export interface ModelLoadStatus {
  modelId: string;
  version: string;
  status: 'loading' | 'loaded' | 'unloading' | 'unloaded' | 'failed';
  loadProgress: number;
  loadError?: string;
  loadedAt?: number;
  memoryUsageBytes?: number;
}

export interface LoadedModel {
  modelId: string;
  version: string;
  handle: unknown;
  loaderType: string;
  loadedAt: number;
  lastUsedAt: number;
  usageCount: number;
  memoryUsageBytes: number;
}

export interface InferenceGatewayStatus {
  uptimeMs: number;
  totalRequests: number;
  successRate: number;
  avgLatencyMs: number;
  p50LatencyMs: number;
  p95LatencyMs: number;
  p99LatencyMs: number;
  loadModels: ModelLoadStatus[];
  batcherStats: BatcherStats[];
}
