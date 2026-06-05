import { z } from 'zod';
import type { ModelFormat, Status } from '../types/common';

export const modelFormatSchema = z.enum(['pkl', 'onnx', 'pt', 'joblib', 'h5', 'pb', 'custom']) as z.ZodType<ModelFormat>;

export const statusSchema = z.enum(['active', 'inactive', 'archived', 'deleted']) as z.ZodType<Status>;

export const modelInputOutputSchema = z.object({
  name: z.string().min(1),
  type: z.enum(['float32', 'float64', 'int32', 'int64', 'string', 'bool']),
  shape: z.array(z.union([z.number(), z.null()])).min(1),
  description: z.string().optional(),
});

export const modelDataSchemaSchema = z.object({
  inputs: z.array(modelInputOutputSchema).min(1),
  outputs: z.array(modelInputOutputSchema).min(1),
});

export const modelCreateRequestSchema = z.object({
  name: z.string().min(1).max(100),
  description: z.string().max(1000).optional(),
  ownerId: z.string().min(1),
  team: z.string().min(1),
  tags: z.array(z.string()).default([]),
  metadata: z.record(z.unknown()).default({}),
});

export const modelVersionCreateRequestSchema = z.object({
  modelId: z.string().min(1),
  version: z.string().min(1).regex(/^[a-zA-Z0-9.-]+$/),
  semanticVersion: z.string().min(1).regex(/^\d+\.\d+\.\d+$/),
  format: modelFormatSchema,
  dataSchema: modelDataSchemaSchema,
  metrics: z.array(z.object({
    name: z.string(),
    value: z.number(),
    timestamp: z.number(),
    step: z.number().optional(),
    context: z.record(z.unknown()).optional(),
  })).default([]),
  hyperParameters: z.record(z.union([z.string(), z.number(), z.boolean(), z.null()])).default({}),
  loaderConfig: z.record(z.unknown()).default({}),
  experimentId: z.string().optional(),
  tags: z.array(z.string()).default([]),
});

export const modelListRequestSchema = z.object({
  name: z.string().optional(),
  ownerId: z.string().optional(),
  team: z.string().optional(),
  tags: z.array(z.string()).optional(),
  status: statusSchema.optional(),
  page: z.number().int().min(1).default(1),
  pageSize: z.number().int().min(1).max(100).default(20),
});

export const modelVersionListRequestSchema = z.object({
  modelId: z.string().min(1),
  status: z.enum(['pending', 'ready', 'failed', 'archived']).optional(),
  page: z.number().int().min(1).default(1),
  pageSize: z.number().int().min(1).max(100).default(20),
});

export type ModelCreateRequestInput = z.infer<typeof modelCreateRequestSchema>;
export type ModelVersionCreateRequestInput = z.infer<typeof modelVersionCreateRequestSchema>;
export type ModelListRequestInput = z.infer<typeof modelListRequestSchema>;
export type ModelVersionListRequestInput = z.infer<typeof modelVersionListRequestSchema>;
