export interface Entity {
  id: string;
  type: string;
  status: 'pending' | 'active' | 'inactive' | 'error' | 'deleted';
  attributes: Record<string, unknown>;
  created_at: string;
  updated_at: string;
}

export interface Resource extends Entity {
  type: 'resource' | 'task' | 'service';
  config: Record<string, unknown>;
  labels: Record<string, string>;
}

export interface RunInstance {
  run_id: string;
  entity_id: string;
  phase: 'initializing' | 'processing' | 'finalizing' | 'completed' | 'failed';
  progress: number;
  started_at: string;
  completed_at: string | null;
  error_detail: ErrorDetail | null;
}

export interface ErrorDetail {
  code: string;
  message: string;
  stack?: string;
  metadata?: Record<string, unknown>;
}

export interface MetricsSnapshot {
  snapshot_id: string;
  timestamp: string;
  metrics: {
    throughput: number;
    latency_p99: number;
    error_rate: number;
    [key: string]: number;
  };
  dimensions: {
    host: string;
    region: string;
    [key: string]: string;
  };
}

export interface ApiResponse<T = unknown> {
  code: number;
  data?: T;
  message?: string;
  error?: string;
  details?: Record<string, unknown>;
}

export interface RequestContext {
  traceId: string;
  tenantId?: string;
  userId?: string;
  timestamp: number;
  metadata: Record<string, unknown>;
}
