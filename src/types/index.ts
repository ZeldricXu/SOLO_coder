export interface CoreEntity {
  id: string;
  type: string;
  status: string;
  attributes: Record<string, unknown>;
  createdAt: Date;
  updatedAt: Date;
}

export interface ConfigDefinition {
  configId: string;
  namespace: string;
  version: number;
  parameters: Record<string, unknown>;
  enabled: boolean;
  appliedAt?: Date;
}

export interface RunInstance {
  runId: string;
  entityId: string;
  phase: string;
  progress: number;
  startedAt: Date;
  completedAt?: Date;
  errorDetail?: string;
}

export interface StatsSnapshot {
  snapshotId: string;
  timestamp: Date;
  metrics: Record<string, number>;
  dimensions: Record<string, string>;
}

export interface ApiResponse<T = unknown> {
  code: number;
  data?: T;
  message?: string;
  error?: string;
}

export interface PaginationParams {
  page: number;
  pageSize: number;
}

export interface PaginatedResult<T> {
  items: T[];
  total: number;
  page: number;
  pageSize: number;
  totalPages: number;
}

export type FaultType = 'network_delay' | 'packet_loss' | 'cpu_stress' | 'memory_stress' | 'disk_io' | 'service_kill' | 'dns_poison';

export type InjectionStatus = 'pending' | 'injecting' | 'active' | 'rolling_back' | 'completed' | 'failed';

export type TrafficPolicyType = 'canary' | 'blue_green' | 'mirror' | 'circuit_breaker';

export type CertificateStatus = 'active' | 'expiring' | 'expired' | 'revoked';

export type EventType = 'resource.created' | 'resource.updated' | 'resource.deleted' | 'run.started' | 'run.completed' | 'run.failed' | 'chaos.injected' | 'chaos.rolled_back';
