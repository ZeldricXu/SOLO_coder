export interface Metric {
  name: string;
  type: 'counter' | 'gauge' | 'histogram' | 'summary';
  value: number;
  labels: Record<string, string>;
  timestamp: number;
}

export interface MetricsAggregate {
  name: string;
  count: number;
  sum: number;
  min: number;
  max: number;
  avg: number;
  p50: number;
  p95: number;
  p99: number;
  labels: Record<string, string>;
  windowStart: number;
  windowEnd: number;
}

export interface Snapshot {
  snapshot_id: string;
  timestamp: string;
  metrics: Record<string, number>;
  dimensions: Record<string, string>;
}

export interface Alert {
  id: string;
  name: string;
  metric: string;
  condition: 'gt' | 'lt' | 'gte' | 'lte' | 'eq';
  threshold: number;
  duration: number;
  triggered: boolean;
  triggeredAt?: number;
  resolvedAt?: number;
}

export interface MonitoringConfig {
  collectionInterval: number;
  retentionPeriod: number;
  aggregationWindows: number[];
  enableAlerts: boolean;
  maxMetrics: number;
}
