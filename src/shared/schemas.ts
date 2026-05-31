import { z } from 'zod';

export const CoreEntitySchema = z.object({
  id: z.string(),
  type: z.string(),
  status: z.enum(['active', 'inactive', 'archived']),
  attributes: z.record(z.unknown()),
  created_at: z.string().datetime(),
  updated_at: z.string().datetime(),
});

export const ConfigDefinitionSchema = z.object({
  config_id: z.string(),
  namespace: z.string(),
  version: z.number().int().min(1),
  parameters: z.record(z.unknown()),
  enabled: z.boolean(),
  applied_at: z.string().datetime(),
  rollback_from: z.number().int().min(1).optional(),
});

export const RunInstanceSchema = z.object({
  run_id: z.string(),
  entity_id: z.string(),
  phase: z.enum(['pending', 'executing', 'completed', 'failed', 'rollback']),
  progress: z.number().min(0).max(1),
  started_at: z.string().datetime(),
  completed_at: z.string().datetime().nullable(),
  error_detail: z.string().nullable(),
  metadata: z.record(z.unknown()),
});

export const StatsSnapshotSchema = z.object({
  snapshot_id: z.string(),
  timestamp: z.string().datetime(),
  metrics: z.object({
    throughput: z.number(),
    latency_p99: z.number(),
    error_rate: z.number(),
  }).catchall(z.number()),
  dimensions: z.object({
    host: z.string(),
    region: z.string(),
  }).catchall(z.string()),
});

export const TaskCreateSchema = z.object({
  type: z.string(),
  config: z.record(z.unknown()),
  labels: z.record(z.string()),
});

export const BatchOperationSchema = z.object({
  operations: z.array(
    z.object({
      action: z.enum(['start', 'stop', 'restart', 'delete']),
      id: z.string(),
    })
  ),
});

export const AuthTokenSchema = z.object({
  user_id: z.string(),
  roles: z.array(z.string()),
  permissions: z.array(z.string()),
  tenant_id: z.string(),
  exp: z.number(),
});

export const LogLevelSchema = z.enum(['debug', 'info', 'warn', 'error', 'fatal']);

export type CoreEntity = z.infer<typeof CoreEntitySchema>;
export type ConfigDefinition = z.infer<typeof ConfigDefinitionSchema>;
export type RunInstance = z.infer<typeof RunInstanceSchema>;
export type StatsSnapshot = z.infer<typeof StatsSnapshotSchema>;
export type TaskCreate = z.infer<typeof TaskCreateSchema>;
export type BatchOperation = z.infer<typeof BatchOperationSchema>;
export type AuthToken = z.infer<typeof AuthTokenSchema>;
export type LogLevel = z.infer<typeof LogLevelSchema>;
