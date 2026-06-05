import type { PaginatedResponse } from './common';

export type AlertSeverity = 'info' | 'warning' | 'critical';
export type AlertStatus = 'active' | 'acknowledged' | 'resolved';
export type DriftType = 'data_drift' | 'concept_drift' | 'prediction_drift';
export type StatisticalTest = 'ks' | 'chi_square' | 't_test' | 'mann_whitney' | 'adversarial';

export interface Alert {
  id: string;
  name: string;
  description?: string;
  type: 'inference_latency' | 'error_rate' | 'model_drift' | 'feature_drift' | 'throughput' | 'memory' | 'custom';
  severity: AlertSeverity;
  status: AlertStatus;
  modelId?: string;
  version?: string;
  featureSetId?: string;
  featureName?: string;
  threshold: AlertThreshold;
  condition: AlertCondition;
  notificationChannels: NotificationChannel[];
  lastTriggeredAt?: number;
  lastResolvedAt?: number;
  triggerCount: number;
  acknowledgedBy?: string;
  acknowledgedAt?: number;
  resolvedBy?: string;
  resolvedReason?: string;
  createdAt: number;
  updatedAt: number;
}

export interface AlertThreshold {
  metric: string;
  operator: 'gt' | 'gte' | 'lt' | 'lte' | 'eq' | 'ne';
  value: number;
  durationMinutes: number;
  comparison?: 'absolute' | 'relative' | 'percentile';
  percentile?: number;
}

export interface AlertCondition {
  type: 'single' | 'and' | 'or';
  conditions?: AlertCondition[];
}

export interface NotificationChannel {
  type: 'email' | 'slack' | 'webhook' | 'pagerduty';
  config: Record<string, string>;
  enabled: boolean;
}

export interface AlertEvent {
  id: string;
  alertId: string;
  severity: AlertSeverity;
  message: string;
  metricValue: number;
  threshold: number;
  timestamp: number;
  context?: Record<string, unknown>;
}

export interface DriftDetectionConfig {
  id: string;
  modelId: string;
  version?: string;
  name: string;
  driftType: DriftType;
  statisticalTest: StatisticalTest;
  featureNames?: string[];
  thresholdPValue: number;
  windowSizeMinutes: number;
  baselineWindowSizeMinutes: number;
  sampleSize: number;
  alertOnDetection: boolean;
  alertSeverity: AlertSeverity;
  enabled: boolean;
  createdAt: number;
  updatedAt: number;
}

export interface DriftDetectionResult {
  id: string;
  configId: string;
  modelId: string;
  version?: string;
  driftType: DriftType;
  featureName?: string;
  statisticalTest: StatisticalTest;
  statistic: number;
  pValue: number;
  thresholdPValue: number;
  isDriftDetected: boolean;
  effectSize: number;
  baselineStats: DistributionStats;
  currentStats: DistributionStats;
  timestamp: number;
  windowStart: number;
  windowEnd: number;
  alertId?: string;
}

export interface DistributionStats {
  mean: number;
  std: number;
  min: number;
  max: number;
  median: number;
  p25: number;
  p75: number;
  p95: number;
  p99: number;
  sampleCount: number;
  histogram?: { bins: number[]; counts: number[] };
}

export interface InferenceLatencyMetrics {
  modelId: string;
  version: string;
  timestamp: number;
  windowSeconds: number;
  count: number;
  avg: number;
  min: number;
  max: number;
  p50: number;
  p75: number;
  p90: number;
  p95: number;
  p99: number;
  p999: number;
}

export interface ErrorRateMetrics {
  modelId: string;
  version: string;
  timestamp: number;
  windowSeconds: number;
  totalRequests: number;
  errorCount: number;
  errorRate: number;
  errors: { type: string; count: number; message?: string }[];
}

export interface ThroughputMetrics {
  modelId: string;
  version: string;
  timestamp: number;
  windowSeconds: number;
  requestsPerSecond: number;
  totalRequests: number;
  successCount: number;
  cacheHitRate: number;
}

export interface FeatureDistributionMetrics {
  featureSetId: string;
  featureName: string;
  timestamp: number;
  stats: DistributionStats;
  nullRate: number;
  uniqueCount: number;
}

export interface AlertListRequest {
  name?: string;
  type?: Alert['type'];
  severity?: AlertSeverity;
  status?: AlertStatus;
  modelId?: string;
  featureSetId?: string;
  page?: number;
  pageSize?: number;
}

export type AlertListResponse = PaginatedResponse<Alert>;

export interface AlertCreateRequest {
  name: string;
  description?: string;
  type: Alert['type'];
  severity: AlertSeverity;
  modelId?: string;
  version?: string;
  featureSetId?: string;
  featureName?: string;
  threshold: AlertThreshold;
  condition: AlertCondition;
  notificationChannels: NotificationChannel[];
}

export interface MonitoringDashboardData {
  latencyMetrics: InferenceLatencyMetrics[];
  errorRateMetrics: ErrorRateMetrics[];
  throughputMetrics: ThroughputMetrics[];
  activeAlerts: Alert[];
  recentDriftDetections: DriftDetectionResult[];
  driftDetections: DriftDetectionResult[];
}

export interface MetricQueryRequest {
  metricType: 'latency' | 'error_rate' | 'throughput' | 'feature_distribution';
  modelId?: string;
  version?: string;
  featureSetId?: string;
  featureName?: string;
  startTime: number;
  endTime: number;
  aggregationSeconds?: number;
  percentile?: number;
}
