import { z } from 'zod';

export const EntitySchema = z.object({
  id: z.string(),
  type: z.string(),
  status: z.enum(['active', 'inactive', 'pending', 'deleted']),
  attributes: z.record(z.unknown()),
  createdAt: z.string().datetime(),
  updatedAt: z.string().datetime(),
});

export const ConfigSchema = z.object({
  configId: z.string(),
  namespace: z.string(),
  version: z.number().int().min(1),
  parameters: z.record(z.unknown()),
  enabled: z.boolean(),
  appliedAt: z.string().datetime(),
});

export const RunInstanceSchema = z.object({
  runId: z.string(),
  entityId: z.string(),
  phase: z.enum(['pending', 'executing', 'completed', 'failed', 'cancelled']),
  progress: z.number().min(0).max(1),
  startedAt: z.string().datetime(),
  completedAt: z.string().datetime().nullable(),
  errorDetail: z.string().nullable(),
});

export const SnapshotSchema = z.object({
  snapshotId: z.string(),
  timestamp: z.string().datetime(),
  metrics: z.object({
    throughput: z.number(),
    latencyP99: z.number(),
    errorRate: z.number(),
  }),
  dimensions: z.record(z.string()),
});

export type Entity = z.infer<typeof EntitySchema>;
export type Config = z.infer<typeof ConfigSchema>;
export type RunInstance = z.infer<typeof RunInstanceSchema>;
export type Snapshot = z.infer<typeof SnapshotSchema>;

export interface ApiResponse<T = unknown> {
  code: number;
  data?: T;
  message?: string;
  error?: string;
}

export interface PaginationParams {
  page: number;
  pageSize: number;
}

export interface PaginatedResponse<T> {
  items: T[];
  total: number;
  page: number;
  pageSize: number;
  totalPages: number;
}
