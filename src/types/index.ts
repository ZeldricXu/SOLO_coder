export interface CoreEntity {
  id: string;
  type: string;
  status: 'pending' | 'processing' | 'completed' | 'failed';
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
  phase: 'initializing' | 'running' | 'paused' | 'completed' | 'failed';
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

export interface User {
  id: string;
  username: string;
  email: string;
  roles: string[];
  permissions: string[];
}

export interface AuthToken {
  accessToken: string;
  refreshToken: string;
  expiresIn: number;
}

export interface RateLimitConfig {
  windowMs: number;
  maxRequests: number;
  keyPrefix: string;
}

export interface ApiResponse<T = unknown> {
  code: number;
  message: string;
  data?: T;
  error?: string;
}

export interface CacheEntry<T = unknown> {
  key: string;
  value: T;
  timestamp: number;
  ttl: number;
  synced: boolean;
}

export interface DeviceShadow {
  deviceId: string;
  desired: Record<string, unknown>;
  reported: Record<string, unknown>;
  delta: Record<string, unknown>;
  version: number;
  timestamp: string;
}

export interface Notification {
  id: string;
  type: 'email' | 'sms' | 'push' | 'webhook';
  recipient: string;
  template: string;
  data: Record<string, unknown>;
  status: 'pending' | 'sent' | 'failed';
  created_at: string;
  sent_at: string | null;
}

export interface BackupJob {
  id: string;
  type: 'full' | 'incremental';
  source: string;
  destination: string;
  status: 'pending' | 'running' | 'completed' | 'failed';
  progress: number;
  created_at: string;
  completed_at: string | null;
}

export interface RuleCondition {
  field: string;
  operator: 'eq' | 'ne' | 'gt' | 'lt' | 'gte' | 'lte' | 'contains' | 'matches';
  value: unknown;
}

export interface EdgeRule {
  id: string;
  name: string;
  conditions: RuleCondition[];
  action: {
    type: string;
    params: Record<string, unknown>;
  };
  enabled: boolean;
}

export interface FirmwareVersion {
  version: string;
  checksum: string;
  size: number;
  releaseNotes: string;
  url: string;
}

export interface OTABatch {
  id: string;
  firmwareVersion: string;
  deviceIds: string[];
  phase: number;
  status: 'pending' | 'in_progress' | 'completed' | 'rolled_back';
  rolloutPercentage: number;
}

export interface LogEntry {
  timestamp: string;
  level: 'debug' | 'info' | 'warn' | 'error' | 'fatal';
  message: string;
  context: Record<string, unknown>;
  traceId?: string;
}
