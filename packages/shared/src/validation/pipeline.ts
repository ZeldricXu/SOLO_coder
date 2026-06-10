import { z } from 'zod';
import type {
  PipelineCreateRequest,
  PipelineUpdateRequest,
  PipelineInferenceRequest,
  PipelineListRequest,
} from '../types/pipeline';

export const fieldMappingSchema = z.object({
  source: z.string().min(1),
  target: z.string().min(1),
  transform: z.enum(['identity', 'json_parse', 'json_stringify', 'flatten', 'nest']).default('identity'),
  defaultValue: z.unknown().optional(),
  required: z.boolean().default(false),
});

export const inputOutputMappingSchema = z.object({
  type: z.enum(['direct', 'prefix', 'template', 'custom']),
  mappings: z.array(fieldMappingSchema).min(1),
  template: z.string().optional(),
});

export const pipelineConditionSchema = z.object({
  type: z.enum(['field_equals', 'field_greater', 'field_less', 'field_contains', 'custom']),
  field: z.string().min(1),
  value: z.unknown().optional(),
  values: z.array(z.unknown()).optional(),
  expression: z.string().optional(),
  trueStepId: z.string().optional(),
  falseStepId: z.string().optional(),
});

export const aggregatorConfigSchema = z.object({
  type: z.enum(['mean', 'sum', 'max', 'min', 'weighted_sum', 'concat']),
  sourceSteps: z.array(z.string()).min(1),
  weights: z.record(z.number()).optional(),
  outputField: z.string().min(1),
});

export const transformConfigSchema = z.object({
  type: z.enum(['scale', 'normalize', 'one_hot', 'bucketize', 'custom']),
  scale: z.object({
    factor: z.number(),
    offset: z.number().default(0),
  }).optional(),
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
    boundaries: z.array(z.number()).min(2),
    labels: z.array(z.string()).optional(),
  }).optional(),
  customExpression: z.string().optional(),
});

export const pipelineStepSchema = z.object({
  id: z.string().min(1),
  name: z.string().min(1),
  type: z.enum(['model', 'transform', 'condition', 'aggregator']),
  description: z.string().optional(),
  modelId: z.string().min(1).optional(),
  version: z.string().optional(),
  inputMapping: inputOutputMappingSchema,
  outputMapping: inputOutputMappingSchema,
  condition: pipelineConditionSchema.optional(),
  aggregatorConfig: aggregatorConfigSchema.optional(),
  transformConfig: transformConfigSchema.optional(),
  dependsOn: z.array(z.string()).default([]),
  timeoutMs: z.number().int().positive().default(30000),
  retryCount: z.number().int().min(0).max(5).default(0),
  enabled: z.boolean().default(true),
});

export const pipelineCreateRequestSchema = z.object({
  name: z.string().min(1).max(100),
  description: z.string().max(500).optional(),
  projectId: z.string().min(1),
  ownerId: z.string().min(1),
  team: z.string().min(1),
  steps: z.array(pipelineStepSchema).min(1),
  entryPoint: z.string().min(1),
  outputStep: z.string().min(1),
  tags: z.array(z.string()).default([]),
  metadata: z.record(z.unknown()).default({}),
});

export const pipelineUpdateRequestSchema = z.object({
  name: z.string().min(1).max(100).optional(),
  description: z.string().max(500).optional(),
  status: z.enum(['draft', 'active', 'archived']).optional(),
  steps: z.array(pipelineStepSchema).min(1).optional(),
  entryPoint: z.string().min(1).optional(),
  outputStep: z.string().min(1).optional(),
  tags: z.array(z.string()).optional(),
  metadata: z.record(z.unknown()).optional(),
});

export const pipelineInferenceRequestSchema = z.object({
  pipelineId: z.string().min(1),
  inputs: z.record(z.unknown()),
  requestId: z.string().optional(),
  userId: z.string().optional(),
  sessionId: z.string().optional(),
  context: z.record(z.unknown()).default({}),
  bypassCache: z.boolean().default(false),
  traceEnabled: z.boolean().default(false),
});

export const pipelineListRequestSchema = z.object({
  name: z.string().optional(),
  projectId: z.string().optional(),
  ownerId: z.string().optional(),
  team: z.string().optional(),
  status: z.enum(['draft', 'active', 'archived']).optional(),
  tags: z.array(z.string()).optional(),
  page: z.number().int().positive().default(1),
  pageSize: z.number().int().positive().max(100).default(20),
});

export type PipelineCreateRequestSchema = z.infer<typeof pipelineCreateRequestSchema>;
export type PipelineUpdateRequestSchema = z.infer<typeof pipelineUpdateRequestSchema>;
export type PipelineInferenceRequestSchema = z.infer<typeof pipelineInferenceRequestSchema>;
export type PipelineListRequestSchema = z.infer<typeof pipelineListRequestSchema>;
