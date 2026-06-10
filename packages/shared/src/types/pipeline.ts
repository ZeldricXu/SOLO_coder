import type { InferenceRequest, InferenceResponse } from './inference';

export type PipelineStepType = 'model' | 'transform' | 'condition' | 'aggregator';

export interface PipelineStep {
  id: string;
  name: string;
  type: PipelineStepType;
  description?: string;
  modelId?: string;
  version?: string;
  inputMapping: InputOutputMapping;
  outputMapping: InputOutputMapping;
  condition?: PipelineCondition;
  aggregatorConfig?: AggregatorConfig;
  transformConfig?: TransformConfig;
  dependsOn: string[];
  timeoutMs?: number;
  retryCount?: number;
  enabled: boolean;
}

export interface InputOutputMapping {
  type: 'direct' | 'prefix' | 'template' | 'custom';
  mappings: FieldMapping[];
  template?: string;
}

export interface FieldMapping {
  source: string;
  target: string;
  transform?: 'identity' | 'json_parse' | 'json_stringify' | 'flatten' | 'nest';
  defaultValue?: unknown;
  required?: boolean;
}

export interface PipelineCondition {
  type: 'field_equals' | 'field_greater' | 'field_less' | 'field_contains' | 'custom';
  field: string;
  value?: unknown;
  values?: unknown[];
  expression?: string;
  trueStepId?: string;
  falseStepId?: string;
}

export interface AggregatorConfig {
  type: 'mean' | 'sum' | 'max' | 'min' | 'weighted_sum' | 'concat';
  sourceSteps: string[];
  weights?: Record<string, number>;
  outputField: string;
}

export interface TransformConfig {
  type: 'scale' | 'normalize' | 'one_hot' | 'bucketize' | 'custom';
  scale?: { factor: number; offset?: number };
  normalize?: { method: 'min_max' | 'z_score'; min?: number; max?: number; mean?: number; std?: number };
  oneHot?: { categories: string[]; dropFirst?: boolean };
  bucketize?: { boundaries: number[]; labels?: string[] };
  customExpression?: string;
}

export interface ModelPipeline {
  id: string;
  name: string;
  description?: string;
  projectId: string;
  ownerId: string;
  team: string;
  status: 'draft' | 'active' | 'archived';
  steps: PipelineStep[];
  entryPoint: string;
  outputStep: string;
  tags: string[];
  metadata: Record<string, unknown>;
  createdAt: number;
  updatedAt: number;
  lastRunAt?: number;
  runCount: number;
  avgLatencyMs?: number;
  successRate?: number;
}

export interface PipelineCreateRequest {
  name: string;
  description?: string;
  projectId: string;
  ownerId: string;
  team: string;
  steps: PipelineStep[];
  entryPoint: string;
  outputStep: string;
  tags?: string[];
  metadata?: Record<string, unknown>;
}

export interface PipelineUpdateRequest {
  name?: string;
  description?: string;
  status?: 'draft' | 'active' | 'archived';
  steps?: PipelineStep[];
  entryPoint?: string;
  outputStep?: string;
  tags?: string[];
  metadata?: Record<string, unknown>;
}

export interface PipelineListRequest {
  name?: string;
  projectId?: string;
  ownerId?: string;
  team?: string;
  status?: 'draft' | 'active' | 'archived';
  tags?: string[];
  page?: number;
  pageSize?: number;
}

export interface PipelineInferenceRequest {
  pipelineId: string;
  inputs: Record<string, unknown>;
  requestId?: string;
  userId?: string;
  sessionId?: string;
  context?: Record<string, unknown>;
  bypassCache?: boolean;
  traceEnabled?: boolean;
}

export interface PipelineStepResult {
  stepId: string;
  stepName: string;
  inputs: Record<string, unknown>;
  outputs: Record<string, unknown>;
  latencyMs: number;
  success: boolean;
  error?: string;
  fromCache?: boolean;
  modelId?: string;
  modelVersion?: string;
}

export interface PipelineInferenceResponse {
  pipelineId: string;
  requestId: string;
  inferenceId: string;
  outputs: Record<string, unknown>;
  totalLatencyMs: number;
  stepResults: PipelineStepResult[];
  success: boolean;
  error?: string;
  cacheHit?: boolean;
  timestamp: number;
}

export interface PipelineExecutionTrace {
  traceId: string;
  pipelineId: string;
  startTime: number;
  endTime: number;
  steps: PipelineStepResult[];
  success: boolean;
  error?: string;
  userId?: string;
  sessionId?: string;
}

export interface PipelineValidationResult {
  valid: boolean;
  errors: string[];
  warnings: string[];
  topologicalOrder: string[];
  estimatedLatencyMs: number;
}

export type PaginatedResponse<T> = {
  data: T[];
  total: number;
  page: number;
  pageSize: number;
  totalPages: number;
};

export type PipelineListResponse = PaginatedResponse<ModelPipeline>;
