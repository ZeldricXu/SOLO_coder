export interface CoreEntity {
  id: string;
  type: string;
  status: string;
  attributes: Record<string, unknown>;
  created_at: string;
  updated_at: string;
}

export interface ConfigDefinition {
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
  phase: string;
  progress: number;
  started_at: string;
  completed_at: string | null;
  error_detail: string | null;
}

export interface StatsSnapshot {
  snapshot_id: string;
  timestamp: string;
  metrics: {
    throughput: number;
    latency_p99: number;
    error_rate: number;
  };
  dimensions: Record<string, string>;
}

export interface APIResponse<T = unknown> {
  code: number;
  data?: T;
  message?: string;
  pagination?: {
    page: number;
    page_size: number;
    total: number;
    total_pages: number;
  };
}

export interface LogEntry {
  timestamp: string;
  level: string;
  message: string;
  service: string;
  trace_id: string;
  span_id: string;
  attributes: Record<string, unknown>;
}

export interface TraceSpan {
  trace_id: string;
  span_id: string;
  parent_span_id: string | null;
  service_name: string;
  operation_name: string;
  start_time: string;
  end_time: string;
  status: string;
  attributes: Record<string, unknown>;
}

export interface UserPrincipal {
  user_id: string;
  username: string;
  roles: string[];
  permissions: string[];
  tenant_id: string;
}

export interface RateLimitConfig {
  max_requests: number;
  window_ms: number;
  key_prefix: string;
}

export interface Notification {
  id: string;
  type: string;
  recipient: string;
  content: Record<string, unknown>;
  status: 'pending' | 'sent' | 'failed' | 'retrying';
  retry_count: number;
  max_retries: number;
  created_at: string;
  sent_at: string | null;
  error_detail?: string | null;
}

export interface MetricPoint {
  timestamp: number;
  metric_name: string;
  value: number;
  tags: Record<string, string>;
}

export interface StoredObject {
  object_id: string;
  bucket: string;
  key: string;
  size: number;
  content_type: string;
  metadata: Record<string, string>;
  created_at: string;
}

export interface ServiceNode {
  service_name: string;
  type: string;
  instances: number;
  metadata: Record<string, unknown>;
}

export interface ServiceEdge {
  source: string;
  target: string;
  call_count: number;
  avg_latency_ms: number;
  error_rate: number;
}

export interface TopologyGraph {
  nodes: ServiceNode[];
  edges: ServiceEdge[];
  generated_at: string;
}

export interface ProfileSample {
  timestamp: number;
  type: 'cpu' | 'memory';
  stack_trace: string[];
  duration_ms: number;
}

export interface FlameGraphNode {
  name: string;
  value: number;
  children: FlameGraphNode[];
}

export interface ScheduledTask {
  id: string;
  name: string;
  cron_expression: string;
  payload: Record<string, unknown>;
  enabled: boolean;
  last_run: string | null;
  next_run: string;
  created_at: string;
}
