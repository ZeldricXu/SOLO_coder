export interface BaseEntity {
  id: string;
  type: string;
  status: string;
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
    [key: string]: number;
  };
  dimensions: Record<string, string>;
}

export interface SLIConfig {
  sli_id: string;
  name: string;
  description?: string;
  slo_target: number;
  time_window: string;
  sli_type: 'availability' | 'latency' | 'throughput' | 'quality' | 'custom';
  parameters: {
    metric_name: string;
    threshold?: number;
    operator?: 'lt' | 'lte' | 'gt' | 'gte' | 'eq' | 'neq';
    [key: string]: any;
  };
}

export interface SLOConfig {
  slo_id: string;
  name: string;
  description?: string;
  sli_ids: string[];
  target: number;
  time_window_days: number;
  alerting_thresholds: {
    burn_rate_severe: number;
    burn_rate_warning: number;
    error_budget_remaining: number;
  };
}

export interface SLIMetric {
  sli_id: string;
  timestamp: string;
  value: number;
  good_events: number;
  total_events: number;
  dimensions?: Record<string, string>;
}

export interface ErrorBudgetState {
  slo_id: string;
  total_budget: number;
  remaining_budget: number;
  consumed_budget: number;
  burn_rate: number;
  window_start: string;
  window_end: string;
  last_updated: string;
}

export interface BurnRateAlert {
  alert_id: string;
  slo_id: string;
  severity: 'critical' | 'warning' | 'info';
  burn_rate: number;
  error_budget_remaining: number;
  timestamp: string;
  message: string;
}

export interface TimeSeriesPoint {
  timestamp: number;
  value: number;
  dimensions?: Record<string, string>;
}

export interface MetricAggregationConfig {
  metric_name: string;
  aggregations: Array<'sum' | 'avg' | 'min' | 'max' | 'count' | 'p50' | 'p95' | 'p99'>;
  granularities: Array<'1m' | '5m' | '15m' | '1h' | '6h' | '1d'>;
  retention_days: number;
}

export interface CacheConfig {
  default_ttl: number;
  max_size: number;
  eviction_policy: 'lru' | 'lfu' | 'fifo';
  namespace?: string;
}

export interface CacheEntry<T> {
  key: string;
  value: T;
  created_at: number;
  expires_at: number;
  access_count: number;
  last_accessed: number;
}

export interface AlertRule {
  rule_id: string;
  name: string;
  description?: string;
  enabled: boolean;
  condition: AlertCondition;
  notification_channels: string[];
  evaluation_interval: string;
  labels?: Record<string, string>;
}

export interface AlertCondition {
  type: 'threshold' | 'burn_rate' | 'anomaly' | 'expression';
  metric?: string;
  threshold?: number;
  operator?: 'lt' | 'lte' | 'gt' | 'gte' | 'eq' | 'neq';
  duration?: string;
  burn_rate_threshold?: number;
  slo_id?: string;
  expression?: string;
  anomaly_config?: AnomalyDetectionConfig;
}

export interface AnomalyDetectionConfig {
  algorithm: 'static_threshold' | 'moving_average' | 'exponential_smoothing' | 'z_score' | 'isolation_forest';
  lookback_period: string;
  sensitivity: number;
  baseline_params?: Record<string, any>;
}

export interface AnomalyResult {
  is_anomaly: boolean;
  score: number;
  expected_value?: number;
  actual_value: number;
  algorithm: string;
  timestamp: string;
  details?: Record<string, any>;
}

export interface AlertNotification {
  notification_id: string;
  rule_id: string;
  severity: 'critical' | 'warning' | 'info';
  title: string;
  message: string;
  timestamp: string;
  labels?: Record<string, string>;
  status: 'pending' | 'sent' | 'failed';
  channel: string;
}

export interface TraceSpan {
  trace_id: string;
  span_id: string;
  parent_span_id?: string;
  name: string;
  service_name: string;
  start_time: number;
  end_time: number;
  duration_ms: number;
  status: 'ok' | 'error' | 'unknown';
  attributes?: Record<string, any>;
  events?: Array<{ name: string; timestamp: number; attributes?: Record<string, any> }>;
}

export interface SamplingConfig {
  default_sampling_rate: number;
  rules: Array<{
    service_name?: string;
    operation_name?: string;
    min_duration_ms?: number;
    error_only?: boolean;
    sampling_rate: number;
  }>;
  tail_sampling_enabled: boolean;
  tail_sampling_wait_time: string;
}

export interface APIResponse<T = any> {
  code: number;
  data?: T;
  message?: string;
  error?: string;
  timestamp: string;
}

export interface ResourceCreateRequest {
  type: string;
  config: Record<string, any>;
  labels?: Record<string, string>;
}

export interface ResourceResponse {
  id: string;
  status: string;
  type: string;
  created_at: string;
}

export interface BatchOperationRequest {
  operations: Array<{
    action: string;
    id: string;
    params?: Record<string, any>;
  }>;
}

export interface BatchOperationResponse {
  batch_id: string;
  results: Array<{
    id: string;
    action: string;
    success: boolean;
    error?: string;
  }>;
}
