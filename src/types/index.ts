export interface Entity {
  id: string;
  type: string;
  status: 'pending' | 'processing' | 'completed' | 'failed';
  attributes: Record<string, any>;
  created_at: string;
  updated_at: string;
}

export interface ConfigDefinition {
  config_id: string;
  namespace: string;
  version: number;
  parameters: Record<string, any>;
  enabled: boolean;
  applied_at: string;
}

export interface RunInstance {
  run_id: string;
  entity_id: string;
  phase: 'pending' | 'executing' | 'completed' | 'failed' | 'rolled_back';
  progress: number;
  started_at: string;
  completed_at: string | null;
  error_detail: string | null;
}

export interface SnapshotMetrics {
  throughput: number;
  latency_p99: number;
  error_rate: number;
}

export interface StatsSnapshot {
  snapshot_id: string;
  timestamp: string;
  metrics: SnapshotMetrics;
  dimensions: Record<string, string>;
}

export interface ApiResponse<T = any> {
  code: number;
  message?: string;
  data?: T;
}

export interface ResourceCreateRequest {
  type: string;
  config: Record<string, any>;
  labels: Record<string, string>;
}

export interface ResourceCreateResponse {
  id: string;
  status: string;
}

export interface ResourceStatusResponse {
  id: string;
  status: string;
  progress: number;
}

export interface BatchOperation {
  action: 'start' | 'stop' | 'restart' | 'delete';
  id: string;
}

export interface BatchRequest {
  operations: BatchOperation[];
}

export interface BatchResponse {
  batch_id: string;
  results: Array<{ id: string; status: string; error?: string }>;
}

export interface HandlerContext {
  traceId: string;
  startTime: number;
  metadata: Record<string, any>;
}

export interface ValidationError extends Error {
  details: Record<string, any>;
}

export interface ProcessingResult {
  success: boolean;
  data?: any;
  error?: string;
}
