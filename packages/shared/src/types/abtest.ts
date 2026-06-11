import type { PaginatedResponse } from './common';

export type BucketStrategy = 'random' | 'user_id' | 'session_id' | 'device_id' | 'custom';
export type TrafficAllocation = 'equal' | 'weighted' | 'custom';

export interface ABTest {
  id: string;
  name: string;
  description?: string;
  projectId: string;
  ownerId: string;
  team: string;
  hypothesis: string;
  primaryMetric: string;
  status: 'draft' | 'running' | 'paused' | 'completed' | 'archived';
  startTime?: number;
  endTime?: number;
  statusUpdatedAt: number;
  tags: string[];
  bucketStrategy: BucketStrategy;
  bucketKey?: string;
  variants: ABVariant[];
  trafficAllocation: TrafficAllocationConfig;
  targetingRules: ABTargetingRule[];
  metrics: ABTestMetric[];
  results?: ABTestResults;
  createdAt: number;
  updatedAt: number;
  metadata: Record<string, unknown>;
}

export interface ABVariant {
  id: string;
  name: string;
  description?: string;
  isControl: boolean;
  trafficWeight: number;
  trafficPercentage: number;
  config: Record<string, unknown>;
  modelId?: string;
  modelVersion?: string;
  status: 'active' | 'inactive';
}

export interface TrafficAllocationConfig {
  type: TrafficAllocation;
  totalTrafficPercentage: number;
  customWeights?: Record<string, number>;
  weights: Record<string, number>;
}

export interface ABTargetingRule {
  id: string;
  type: 'include' | 'exclude';
  attribute: string;
  operator: 'eq' | 'ne' | 'gt' | 'gte' | 'lt' | 'lte' | 'in' | 'not_in' | 'contains' | 'regex';
  value: unknown;
}

export interface ABTestMetric {
  name: string;
  description?: string;
  type: 'primary' | 'secondary' | 'guardrail';
  goal: 'increase' | 'decrease' | 'no_change';
  significanceLevel: number;
  minimumDetectableEffect: number;
}

export interface ABTestResults {
  status: 'calculating' | 'ready' | 'error';
  lastCalculatedAt?: number;
  variantResults: Record<string, ABVariantResult>;
}

export interface ABVariantResult {
  variantId: string;
  variantName: string;
  sampleSize: number;
  metricValues: Record<string, ABMetricResult>;
}

export interface ABMetricResult {
  metricName: string;
  mean: number;
  std: number;
  confidenceInterval: [number, number];
  pValue: number;
  isSignificant: boolean;
  effectSize: number;
  relativeChange: number;
  relativeChangeCI: [number, number];
}

export interface AssignmentRequest {
  experimentId: string;
  userId?: string;
  sessionId?: string;
  deviceId?: string;
  customKey?: string;
  context?: Record<string, unknown>;
  previewVariantId?: string;
}

export interface AssignmentResponse {
  experimentId: string;
  variantId: string;
  variantName: string;
  isControl: boolean;
  config: Record<string, unknown>;
  modelId?: string;
  modelVersion?: string;
  assignedAt: number;
  cacheHit: boolean;
}

export interface ABTestListRequest {
  name?: string;
  projectId?: string;
  ownerId?: string;
  team?: string;
  status?: ABTest['status'];
  page?: number;
  pageSize?: number;
}

export type ABTestListResponse = PaginatedResponse<ABTest>;

export interface ABTestCreateRequest {
  name: string;
  description?: string;
  projectId: string;
  ownerId: string;
  team: string;
  hypothesis: string;
  primaryMetric: string;
  bucketStrategy: BucketStrategy;
  bucketKey?: string;
  variants: Omit<ABVariant, 'id'>[];
  trafficAllocation: Omit<TrafficAllocationConfig, 'weights'> & { weights?: Record<string, number> };
  targetingRules?: Omit<ABTargetingRule, 'id'>[];
  metrics?: Omit<ABTestMetric, 'id'>[];
  tags?: string[];
  metadata?: Record<string, unknown>;
}

export interface ABTestUpdateRequest {
  name?: string;
  description?: string;
  hypothesis?: string;
  primaryMetric?: string;
  status?: ABTest['status'];
  startTime?: number;
  endTime?: number;
  variants?: Omit<ABVariant, 'id'>[];
  trafficAllocation?: TrafficAllocationConfig;
  targetingRules?: Omit<ABTargetingRule, 'id'>[];
  metrics?: Omit<ABTestMetric, 'id'>[];
  tags?: string[];
  metadata?: Record<string, unknown>;
}

export interface TrackEventRequest {
  experimentId: string;
  variantId: string;
  userId?: string;
  sessionId?: string;
  eventName: string;
  properties?: Record<string, unknown>;
  timestamp?: number;
}

export interface RealTimeStats {
  experimentId: string;
  variantStats: Record<string, {
    variantId: string;
    variantName: string;
    impressions: number;
    conversions: Record<string, number>;
    conversionRates: Record<string, number>;
    lastUpdated: number;
  }>;
}
