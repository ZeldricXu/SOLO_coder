import { z } from 'zod';

export const inferenceRequestSchema = z.object({
  modelId: z.string().min(1),
  version: z.string().optional(),
  inputs: z.union([
    z.record(z.unknown()),
    z.array(z.record(z.unknown())).min(1),
  ]),
  requestId: z.string().optional(),
  userId: z.string().optional(),
  sessionId: z.string().optional(),
  context: z.record(z.unknown()).default({}),
  bypassCache: z.boolean().default(false),
});

export const batchInferenceRequestSchema = z.object({
  modelId: z.string().min(1),
  version: z.string().optional(),
  inputs: z.array(z.record(z.unknown())).min(1),
  batchSize: z.number().int().min(1).max(1024).optional(),
  maxConcurrency: z.number().int().min(1).max(64).optional(),
  requestId: z.string().optional(),
  context: z.record(z.unknown()).default({}),
});

export const batchConfigSchema = z.object({
  maxBatchSize: z.number().int().min(1).max(1024).default(32),
  batchTimeoutMs: z.number().int().min(1).max(60000).default(10),
  dynamicBatching: z.boolean().default(true),
  maxQueueDepth: z.number().int().min(1).default(1000),
});

export const autoscalingConfigSchema = z.object({
  minReplicas: z.number().int().min(0).default(1),
  maxReplicas: z.number().int().min(1).default(10),
  targetRPS: z.number().int().min(1).default(100),
  targetP99LatencyMs: z.number().int().min(1).default(500),
  scaleDownDelaySeconds: z.number().int().min(0).default(300),
});

export type InferenceRequestInput = z.infer<typeof inferenceRequestSchema>;
export type BatchInferenceRequestInput = z.infer<typeof batchInferenceRequestSchema>;
export type BatchConfigInput = z.infer<typeof batchConfigSchema>;
export type AutoscalingConfigInput = z.infer<typeof autoscalingConfigSchema>;
