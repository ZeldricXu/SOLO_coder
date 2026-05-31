import { z } from 'zod';
import { Entity, Config, RunInstance } from '../types/common';

export const ProcessingContextSchema = z.object({
  traceId: z.string(),
  namespace: z.string(),
  params: z.record(z.unknown()),
  config: z.record(z.unknown()).optional(),
  startTime: z.number(),
  timeoutMs: z.number().default(30000),
});

export type ProcessingContext = z.infer<typeof ProcessingContextSchema>;

export const ProcessingRequestSchema = z.object({
  traceId: z.string(),
  namespace: z.string(),
  params: z.record(z.unknown()),
  payload: z.unknown(),
});

export type ProcessingRequest = z.infer<typeof ProcessingRequestSchema>;

export const ProcessingResultSchema = z.object({
  success: z.boolean(),
  data: z.unknown(),
  error: z.string().optional(),
  processingTimeMs: z.number(),
});

export type ProcessingResult = z.infer<typeof ProcessingResultSchema>;

export interface PipelineStage {
  name: string;
  execute: (context: ProcessingContext, data: unknown) => Promise<unknown>;
}

export interface PipelineConfig {
  stages: PipelineStage[];
  timeoutMs?: number;
}

export interface Resource {
  id: string;
  type: string;
  status: 'available' | 'acquired' | 'released';
  acquiredAt?: number;
  metadata: Record<string, unknown>;
}

export interface ResourcePool {
  poolId: string;
  maxSize: number;
  resources: Resource[];
}
