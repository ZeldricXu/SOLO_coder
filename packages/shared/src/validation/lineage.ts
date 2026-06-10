import { z } from 'zod';
import type {
  LineageQueryRequest,
  LineageCompareRequest,
  ExperimentPromoteRequest,
  ExperimentForkRequest,
} from '../types/lineage';

export const lineageQueryRequestSchema = z.object({
  experimentId: z.string().optional(),
  runId: z.string().optional(),
  depth: z.number().int().positive().max(10).default(3),
  direction: z.enum(['up', 'down', 'both']).default('both'),
  includeMetrics: z.array(z.string()).optional(),
  primaryMetric: z.string().optional(),
  improvementDirection: z.enum(['higher', 'lower']).default('higher'),
}).refine(data => data.experimentId || data.runId, {
  message: 'Either experimentId or runId must be provided',
  path: ['experimentId'],
});

export const lineageCompareRequestSchema = z.object({
  runIds: z.array(z.string()).min(2).max(20),
  primaryMetric: z.string().optional(),
  improvementDirection: z.enum(['higher', 'lower']).default('higher'),
  includeAllMetrics: z.boolean().default(true),
});

export const experimentPromoteRequestSchema = z.object({
  sourceRunId: z.string().min(1),
  targetExperimentId: z.string().min(1),
  newRunName: z.string().min(1).optional(),
  inheritMetrics: z.boolean().default(true),
  inheritHyperParameters: z.boolean().default(true),
  note: z.string().max(500).optional(),
});

export const experimentForkRequestSchema = z.object({
  sourceRunId: z.string().min(1),
  newExperimentName: z.string().min(1).max(100),
  newRunName: z.string().min(1).optional(),
  description: z.string().max(500).optional(),
  hyperParameterOverrides: z.record(z.unknown()).default({}),
  projectId: z.string().optional(),
  ownerId: z.string().optional(),
  team: z.string().optional(),
});

export type LineageQueryRequestSchema = z.infer<typeof lineageQueryRequestSchema>;
export type LineageCompareRequestSchema = z.infer<typeof lineageCompareRequestSchema>;
export type ExperimentPromoteRequestSchema = z.infer<typeof experimentPromoteRequestSchema>;
export type ExperimentForkRequestSchema = z.infer<typeof experimentForkRequestSchema>;
