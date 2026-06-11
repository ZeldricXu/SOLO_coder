import { z } from 'zod';

export const lineageQueryRequestSchema = z.object({
  runId: z.string().optional(),
  experimentId: z.string().optional(),
  depth: z.number().int().min(1).max(10).default(3),
  direction: z.enum(['up', 'down', 'both']).default('both'),
  includeMetrics: z.boolean().default(true),
  primaryMetric: z.string().optional(),
  improvementDirection: z.enum(['higher', 'lower']).default('higher'),
}).refine(data => data.runId || data.experimentId, {
  message: 'Either runId or experimentId must be provided',
});

export const lineageCompareRequestSchema = z.object({
  runIds: z.array(z.string()).min(2).max(20),
  metrics: z.array(z.string()).optional(),
  includeHyperParameters: z.boolean().default(false),
});

export const experimentForkRequestSchema = z.object({
  sourceRunId: z.string().min(1),
  name: z.string().min(1).max(100),
  description: z.string().max(1000).optional(),
  hyperParameterOverrides: z.record(z.string(), z.any()).optional(),
  tags: z.array(z.string()).max(20).optional(),
  notes: z.string().max(2000).optional(),
});

export const experimentCreateWithParentSchema = z.object({
  name: z.string().min(1).max(100),
  description: z.string().max(1000).optional(),
  projectId: z.string().min(1),
  ownerId: z.string().min(1),
  team: z.string().optional(),
  parentExperimentId: z.string().min(1),
  parentRunId: z.string().optional(),
  variantType: z.enum(['baseline', 'variant', 'finetune']).default('variant'),
  tags: z.array(z.string()).max(20).optional(),
  metadata: z.record(z.string(), z.any()).optional(),
});
