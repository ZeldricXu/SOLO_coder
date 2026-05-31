export interface Entity {
  id: string;
  type: string;
  status: string;
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
  phase: string;
  progress: number;
  started_at: string;
  completed_at: string | null;
  error_detail: string | null;
}

export interface MetricSnapshot {
  snapshot_id: string;
  timestamp: string;
  metrics: Record<string, number>;
  dimensions: Record<string, string>;
}

export interface TraceSpan {
  traceId: string;
  spanId: string;
  parentSpanId?: string;
  name: string;
  serviceName: string;
  startTime: string;
  endTime?: string;
  duration?: number;
  status: 'OK' | 'ERROR' | 'UNSET';
  attributes: Record<string, unknown>;
  events?: SpanEvent[];
  links?: SpanLink[];
  sampled?: boolean;
}

export interface SpanEvent {
  name: string;
  timestamp: string;
  attributes: Record<string, unknown>;
}

export interface SpanLink {
  traceId: string;
  spanId: string;
  attributes: Record<string, unknown>;
}

export interface SamplingStrategy {
  id: string;
  name: string;
  type: 'head' | 'tail';
  rule: SamplingRule;
  priority: number;
  enabled: boolean;
}

export interface SamplingRule {
  serviceName?: string;
  spanName?: string;
  attributes?: Record<string, unknown>;
  minDuration?: number;
  errorOnly?: boolean;
  sampleRate: number;
}

export interface Notification {
  id: string;
  type: 'alert' | 'info' | 'warning' | 'critical';
  priority: 'low' | 'medium' | 'high' | 'critical';
  title: string;
  message: string;
  source: string;
  tags: string[];
  createdAt: string;
  suppressed?: boolean;
  suppressionReason?: string;
}

export interface NotificationChannel {
  id: string;
  type: 'email' | 'slack' | 'webhook' | 'sms';
  config: Record<string, unknown>;
  enabled: boolean;
  priorityThreshold: 'low' | 'medium' | 'high' | 'critical';
}

export interface SuppressionRule {
  id: string;
  name: string;
  matcher: {
    tags?: string[];
    source?: string;
    priority?: Notification['priority'];
  };
  duration: number;
  maxSuppressions: number;
  enabled: boolean;
}

export interface ProfileSample {
  timestamp: string;
  type: 'cpu' | 'memory' | 'wall';
  duration: number;
  stacks: ProfileStack[];
}

export interface ProfileStack {
  frames: string[];
  count: number;
  value: number;
}

export interface FlameGraphNode {
  name: string;
  value: number;
  children: FlameGraphNode[];
}

export interface MetricPoint {
  timestamp: number;
  value: number;
  tags: Record<string, string>;
  metric: string;
}

export interface AggregationRule {
  id: string;
  metric: string;
  function: 'sum' | 'avg' | 'count' | 'min' | 'max' | 'p50' | 'p95' | 'p99';
  groupBy: string[];
  interval: number;
}

export interface AlertRule {
  id: string;
  name: string;
  metric: string;
  condition: AlertCondition;
  threshold: number;
  duration: number;
  severity: 'warning' | 'critical';
  notificationChannels: string[];
  enabled: boolean;
  labels: Record<string, string>;
}

export interface AlertCondition {
  operator: 'gt' | 'lt' | 'gte' | 'lte' | 'eq' | 'neq';
  threshold: number;
}

export interface AlertState {
  ruleId: string;
  state: 'firing' | 'pending' | 'resolved';
  startedAt: string;
  value: number;
  labels: Record<string, string>;
  annotations: Record<string, string>;
}

export interface StorageObject {
  id: string;
  key: string;
  size: number;
  contentType: string;
  metadata: Record<string, string>;
  createdAt: string;
  updatedAt: string;
  lifecycleState: 'active' | 'archived' | 'deleted';
}

export interface LifecyclePolicy {
  id: string;
  name: string;
  prefix: string;
  transitions: {
    days: number;
    storageClass: 'standard' | 'infrequent' | 'archive';
  }[];
  expirationDays: number;
  enabled: boolean;
}

export interface AnomalyResult {
  timestamp: string;
  metric: string;
  tags: Record<string, string>;
  value: number;
  expected: number;
  deviation: number;
  severity: 'low' | 'medium' | 'high';
  algorithm: string;
}

export interface DetectionAlgorithm {
  name: string;
  detect(history: number[], current: number): AnomalyResult | null;
}

export interface SchemaMigration {
  version: number;
  name: string;
  up: string;
  down: string;
  appliedAt?: string;
}

export interface DataTransferResult {
  success: boolean;
  recordsTransferred: number;
  errors: string[];
}

export type ScenarioType = 'production' | 'staging' | 'development' | 'custom';

export interface ScenarioConfig {
  name: string;
  bufferTimeout: number;
  maxBufferSize: number;
  sampleRate: number;
  enableTailSampling: boolean;
  strategies: string[];
  customSettings?: Record<string, unknown>;
}

export interface TracingConfig {
  bufferTimeout: number;
  maxBufferSize: number;
  defaultSampleRate: number;
  scenarios: Record<ScenarioType, ScenarioConfig>;
  currentScenario: ScenarioType;
  version?: number;
}
