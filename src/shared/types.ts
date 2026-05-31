export type ISO8601Date = string;
export type UUID = string;
export type Address = string;
export type Hash = string;
export type ChainId = number;
export type GasAmount = bigint;
export type WeiAmount = bigint;
export type GweiAmount = bigint;
export type HexString = `0x${string}`;

export interface Entity<T = unknown> {
  id: UUID;
  type: string;
  status: string;
  attributes: T;
  created_at: ISO8601Date;
  updated_at: ISO8601Date;
}

export interface Config<T = unknown> {
  config_id: UUID;
  namespace: string;
  version: number;
  parameters: T;
  enabled: boolean;
  applied_at: ISO8601Date;
}

export interface RunInstance {
  run_id: UUID;
  entity_id: UUID;
  phase: string;
  progress: number;
  started_at: ISO8601Date;
  completed_at: ISO8601Date | null;
  error_detail: string | null;
}

export interface MetricsSnapshot {
  snapshot_id: UUID;
  timestamp: ISO8601Date;
  metrics: {
    throughput: number;
    latency_p99: number;
    error_rate: number;
  };
  dimensions: Record<string, string>;
}

export interface PaginationParams {
  limit?: number;
  offset?: number;
}

export interface PaginatedResult<T> {
  items: T[];
  total: number;
  limit: number;
  offset: number;
}

export type AsyncResult<T, E = Error> = Promise<
  | { success: true; data: T }
  | { success: false; error: E }
>;
