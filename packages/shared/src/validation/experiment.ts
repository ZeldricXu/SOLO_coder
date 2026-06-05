import { z } from 'zod';

export const experimentSourceSchema = z.object({
  type: z.enum(['git', 'notebook', 'script', 'manual']),
  uri: z.string().optional(),
  commitHash: z.string().optional(),
  entryPoint: z.string().optional(),
});

export const hyperParameterSchema = z.object({
  name: z.string().min(1),
  value: z.union([z.string(), z.number(), z.boolean(), z.null()]),
  type: z.enum(['string', 'number', 'boolean', 'json']),
});

export const metricValueSchema = z.object({
  name: z.string().min(1),
  value: z.number(),
  timestamp: z.number(),
  step: z.number().int().optional(),
  context: z.record(z.unknown()).optional(),
});

export const experimentCreateRequestSchema = z.object({
  name: z.string().min(1).max(100),
  description: z.string().max(1000).optional(),
  projectId: z.string().min(1),
  ownerId: z.string().min(1),
  team: z.string().min(1),
  tags: z.array(z.string()).default([]),
  metadata: z.record(z.unknown()).default({}),
});

export const runCreateRequestSchema = z.object({
  experimentId: z.string().min(1),
  name: z.string().min(1).max(100),
  hyperParameters: z.array(hyperParameterSchema).default([]),
  tags: z.array(z.string()).default([]),
  notes: z.string().optional(),
  source: experimentSourceSchema.optional(),
  datasetVersion: z.string().optional(),
  parentRunId: z.string().optional(),
});

export const runUpdateRequestSchema = z.object({
  status: z.enum(['running', 'completed', 'failed', 'killed']).optional(),
  endTime: z.number().optional(),
  metrics: z.array(metricValueSchema).default([]),
  artifactPaths: z.array(z.string()).default([]),
  modelVersionId: z.string().optional(),
  notes: z.string().optional(),
  tags: z.array(z.string()).optional(),
});

export const experimentListRequestSchema = z.object({
  name: z.string().optional(),
  projectId: z.string().optional(),
  ownerId: z.string().optional(),
  team: z.string().optional(),
  tags: z.array(z.string()).optional(),
  status: z.enum(['active', 'inactive', 'archived', 'deleted']).optional(),
  page: z.number().int().min(1).default(1),
  pageSize: z.number().int().min(1).max(100).default(20),
});

export const runListRequestSchema = z.object({
  experimentId: z.string().optional(),
  status: z.enum(['running', 'completed', 'failed', 'killed']).optional(),
  parentRunId: z.string().optional(),
  tags: z.array(z.string()).optional(),
  page: z.number().int().min(1).default(1),
  pageSize: z.number().int().min(1).max(100).default(20),
});

export type ExperimentCreateRequestInput = z.infer<typeof experimentCreateRequestSchema>;
export type RunCreateRequestInput = z.infer<typeof runCreateRequestSchema>;
export type RunUpdateRequestInput = z.infer<typeof runUpdateRequestSchema>;
export type ExperimentListRequestInput = z.infer<typeof experimentListRequestSchema>;
export type RunListRequestInput = z.infer<typeof runListRequestSchema>;
