export interface PipelineStep {
  id: string;
  name: string;
  type: 'model' | 'transform' | 'condition' | 'aggregator';
  description?: string;
  modelId?: string;
  version?: string;
  inputMapping: InputOutputMapping;
  outputMapping: InputOutputMapping;
  condition?: StepCondition;
  aggregatorConfig?: StepAggregatorConfig;
  transformConfig?: StepTransformConfig;
  dependsOn: string[];
  timeoutMs: number;
  retryCount: number;
  enabled: boolean;
}

export interface StepCondition {
  type: 'field_equals' | 'field_greater' | 'field_less' | 'field_contains' | 'custom';
  field: string;
  value?: unknown;
  expression?: string;
  trueStepId?: string;
  falseStepId?: string;
}

export interface StepAggregatorConfig {
  sourceSteps: string[];
  operation?: 'concat' | 'sum' | 'average' | 'max' | 'min';
  fields?: string[];
  separator?: string;
  target?: string;
}

export interface StepTransformConfig {
  type: 'scale' | 'normalize' | 'one_hot' | 'bucketize' | 'custom';
  scale?: { factor: number; offset: number };
  normalize?: { method: 'min_max' | 'z_score'; min?: number; max?: number; mean?: number; std?: number };
  oneHot?: { categories: string[]; dropFirst: boolean };
  bucketize?: { boundaries: number[]; labels?: string[] };
  expression?: string;
}

export interface InputOutputMapping {
  type: 'direct' | 'mapped' | 'custom';
  mappings: FieldMapping[];
}

export interface FieldMapping {
  source: string;
  target: string;
  transform?: string;
  defaultValue?: unknown;
}

export interface PipelineEdge {
  fromStepId: string;
  toStepId: string;
  condition?: string;
}

export interface ModelPipeline {
  id: string;
  name: string;
  description?: string;
  projectId: string;
  ownerId: string;
  team?: string;
  steps: PipelineStep[];
  edges: PipelineEdge[];
  entryPoint: string;
  outputStep: string;
  inputSchema?: Record<string, unknown>;
  outputSchema?: Record<string, unknown>;
  version: number;
  status: 'draft' | 'active' | 'archived';
  tags?: string[];
  metadata?: Record<string, unknown>;
  createdAt: number;
  updatedAt: number;
  lastRunAt?: number;
  runCount?: number;
  avgLatencyMs?: number;
  successRate?: number;
}

export interface PipelineCreateRequest {
  name: string;
  description?: string;
  projectId: string;
  ownerId: string;
  team?: string;
  entryPoint: string;
  outputStep: string;
  steps: Omit<PipelineStep, 'id'>[];
  edges?: PipelineEdge[];
  inputSchema?: Record<string, unknown>;
  outputSchema?: Record<string, unknown>;
  tags?: string[];
  metadata?: Record<string, unknown>;
}

export interface PipelineUpdateRequest {
  name?: string;
  description?: string;
  entryPoint?: string;
  outputStep?: string;
  steps?: Omit<PipelineStep, 'id'>[];
  edges?: PipelineEdge[];
  status?: 'draft' | 'active' | 'archived';
  tags?: string[];
  metadata?: Record<string, unknown>;
}

export interface PipelineInferenceRequest {
  pipelineId: string;
  pipelineVersion?: number;
  inputs: Record<string, unknown>;
  requestId?: string;
  userId?: string;
  sessionId?: string;
  context?: Record<string, unknown>;
  includeStepOutputs?: boolean;
  bypassCache?: boolean;
}

export interface PipelineStepResult {
  stepId: string;
  stepName: string;
  modelId?: string;
  modelVersion?: string;
  inputs: Record<string, unknown>;
  outputs: Record<string, unknown>;
  latencyMs: number;
  success: boolean;
  error?: string;
  fromCache?: boolean;
  inferenceId?: string;
}

export interface PipelineInferenceResponse {
  pipelineId: string;
  pipelineVersion?: number;
  outputs: Record<string, unknown>;
  stepResults: PipelineStepResult[];
  requestId: string;
  inferenceId?: string;
  totalLatencyMs: number;
  success: boolean;
  error?: string;
  timestamp: number;
}

export interface PipelineValidationResult {
  valid: boolean;
  errors: string[];
  warnings: string[];
  topologicalOrder: string[];
  estimatedLatencyMs: number;
}

export interface PipelineListRequest {
  page?: number;
  pageSize?: number;
  name?: string;
  projectId?: string;
  ownerId?: string;
  team?: string;
  status?: 'draft' | 'active' | 'archived';
  tags?: string[];
}

export interface PipelineListResponse {
  data: ModelPipeline[];
  total: number;
  page: number;
  pageSize: number;
}

export interface PipelineExecutionStatus {
  pipelineId: string;
  currentStepId: string;
  completedSteps: string[];
  failedSteps: string[];
  startTime: number;
  elapsedMs: number;
}
