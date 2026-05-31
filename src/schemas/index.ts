import { z } from 'zod';

export const EntitySchema = z.object({
  id: z.string(),
  type: z.string(),
  status: z.enum(['pending', 'processing', 'completed', 'failed']),
  attributes: z.record(z.any()),
  created_at: z.string().datetime(),
  updated_at: z.string().datetime(),
});

export const ConfigDefinitionSchema = z.object({
  config_id: z.string(),
  namespace: z.string(),
  version: z.number().int().positive(),
  parameters: z.record(z.any()),
  enabled: z.boolean(),
  applied_at: z.string().datetime(),
});

export const RunInstanceSchema = z.object({
  run_id: z.string(),
  entity_id: z.string(),
  phase: z.enum(['pending', 'executing', 'completed', 'failed', 'rolled_back']),
  progress: z.number().min(0).max(1),
  started_at: z.string().datetime(),
  completed_at: z.string().datetime().nullable(),
  error_detail: z.string().nullable(),
});

export const ResourceCreateRequestSchema = z.object({
  type: z.string(),
  config: z.record(z.any()),
  labels: z.record(z.string()),
});

export const BatchOperationSchema = z.object({
  action: z.enum(['start', 'stop', 'restart', 'delete']),
  id: z.string(),
});

export const BatchRequestSchema = z.object({
  operations: z.array(BatchOperationSchema),
});

export function validate<T>(schema: z.ZodSchema<T>, data: unknown): T {
  const result = schema.safeParse(data);
  if (!result.success) {
    const error = new Error('Validation failed') as any;
    error.details = result.error.issues;
    throw error;
  }
  return result.data;
}
