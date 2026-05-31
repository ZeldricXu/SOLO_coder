export interface Entity {
  id: string;
  type: string;
  status: 'pending' | 'processing' | 'completed' | 'failed';
  attributes: Record<string, unknown>;
  created_at: string;
  updated_at: string;
}

export interface Config {
  config_id: string;
  namespace: string;
  version: number;
  parameters: Record<string, unknown>;
  enabled: boolean;
  applied_at: string;
}

export interface RunInstance {
  run_id: string;
  entity_id: string;
  phase: 'pending' | 'executing' | 'completed' | 'failed';
  progress: number;
  started_at: string;
  completed_at: string | null;
  error_detail: string | null;
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
  dimensions: Record<string, string>;
}

export interface ApiResponse<T = unknown> {
  code: number;
  data?: T;
  message?: string;
}

export interface RequestContext {
  traceId: string;
  startTime: number;
  namespace: string;
  userId?: string;
}
