import { z } from 'zod';

export const fieldMappingSchema = z.object({
  source: z.string().min(1),
  target: z.string().min(1),
  transform: z.string().optional(),
  defaultValue: z.any().optional(),
});

export const inputOutputMappingSchema = z.object({
  type: z.enum(['direct', 'mapped', 'custom']).default('direct'),
  mappings: z.array(fieldMappingSchema).default([]),
});

export const stepConditionSchema = z.object({
  type: z.enum(['field_equals', 'field_greater', 'field_less', 'field_contains', 'custom']),
  field: z.string().min(1),
  value: z.any().optional(),
  expression: z.string().optional(),
  trueStepId: z.string().optional(),
  falseStepId: z.string().optional(),
});

export const stepAggregatorConfigSchema = z.object({
  sourceSteps: z.array(z.string()).min(1),
  operation: z.enum(['concat', 'sum', 'average', 'max', 'min']).optional(),
  fields: z.array(z.string()).optional(),
  separator: z.string().optional(),
  target: z.string().optional(),
});

export const stepTransformConfigSchema = z.object({
  type: z.enum(['scale', 'normalize', 'one_hot', 'bucketize', 'custom']),
  scale: z.object({ factor: z.number(), offset: z.number() }).optional(),
  normalize: z.object({
    method: z.enum(['min_max', 'z_score']),
    min: z.number().optional(),
    max: z.number().optional(),
    mean: z.number().optional(),
    std: z.number().optional(),
  }).optional(),
  oneHot: z.object({
    categories: z.array(z.string()),
    dropFirst: z.boolean().default(false),
  }).optional(),
  bucketize: z.object({
    boundaries: z.array(z.number()),
    labels: z.array(z.string()).optional(),
  }).optional(),
  expression: z.string().optional(),
});

export const pipelineStepSchema = z.object({
  id: z.string().optional(),
  name: z.string().min(1).max(100),
  type: z.enum(['model', 'transform', 'condition', 'aggregator']).default('model'),
  description: z.string().max(500).optional(),
  modelId: z.string().optional(),
  version: z.string().optional(),
  inputMapping: inputOutputMappingSchema.default({ type: 'direct', mappings: [] }),
  outputMapping: inputOutputMappingSchema.default({ type: 'direct', mappings: [] }),
  condition: stepConditionSchema.optional(),
  aggregatorConfig: stepAggregatorConfigSchema.optional(),
  transformConfig: stepTransformConfigSchema.optional(),
  dependsOn: z.array(z.string()).default([]),
  timeoutMs: z.number().int().min(100).max(60000).default(30000),
  retryCount: z.number().int().min(0).max(5).default(0),
  enabled: z.boolean().default(true),
});

export const pipelineEdgeSchema = z.object({
  fromStepId: z.string().min(1),
  toStepId: z.string().min(1),
  condition: z.string().optional(),
});

export const pipelineCreateRequestSchema = z.object({
  name: z.string().min(1).max(100),
  description: z.string().max(1000).optional(),
  projectId: z.string().min(1),
  ownerId: z.string().min(1),
  team: z.string().optional(),
  entryPoint: z.string().min(1),
  outputStep: z.string().min(1),
  steps: z.array(pipelineStepSchema).min(1).max(20),
  edges: z.array(pipelineEdgeSchema).default([]),
  inputSchema: z.record(z.string(), z.any()).optional(),
  outputSchema: z.record(z.string(), z.any()).optional(),
  tags: z.array(z.string()).max(20).optional(),
  metadata: z.record(z.string(), z.any()).optional(),
});

export const pipelineInferenceRequestSchema = z.object({
  pipelineId: z.string().min(1),
  pipelineVersion: z.number().int().min(1).optional(),
  inputs: z.record(z.string(), z.any()),
  requestId: z.string().optional(),
  userId: z.string().optional(),
  sessionId: z.string().optional(),
  context: z.record(z.string(), z.any()).optional(),
  includeStepOutputs: z.boolean().default(false),
  bypassCache: z.boolean().default(false),
});

export const pipelineListRequestSchema = z.object({
  page: z.number().int().min(1).default(1),
  pageSize: z.number().int().min(1).max(100).default(20),
  name: z.string().optional(),
  projectId: z.string().optional(),
  ownerId: z.string().optional(),
  team: z.string().optional(),
  status: z.enum(['draft', 'active', 'archived']).optional(),
  tags: z.array(z.string()).optional(),
});
