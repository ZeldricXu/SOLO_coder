import { z } from 'zod';

export const abVariantSchema = z.object({
  name: z.string().min(1),
  description: z.string().optional(),
  isControl: z.boolean().default(false),
  trafficWeight: z.number().min(0).default(1),
  config: z.record(z.unknown()).default({}),
  modelId: z.string().optional(),
  modelVersion: z.string().optional(),
  status: z.enum(['active', 'inactive']).default('active'),
});

export const targetingRuleSchema = z.object({
  type: z.enum(['include', 'exclude']),
  attribute: z.string().min(1),
  operator: z.enum(['eq', 'ne', 'gt', 'gte', 'lt', 'lte', 'in', 'not_in', 'contains', 'regex']),
  value: z.unknown(),
});

export const abTestMetricSchema = z.object({
  name: z.string().min(1),
  description: z.string().optional(),
  type: z.enum(['primary', 'secondary', 'guardrail']),
  goal: z.enum(['increase', 'decrease', 'no_change']),
  significanceLevel: z.number().min(0).max(1).default(0.05),
  minimumDetectableEffect: z.number().min(0).default(0.01),
});

export const trafficAllocationConfigSchema = z.object({
  type: z.enum(['equal', 'weighted', 'custom']),
  totalTrafficPercentage: z.number().min(0).max(100).default(100),
  customWeights: z.record(z.number()).optional(),
});

export const abTestCreateRequestSchema = z.object({
  name: z.string().min(1).max(100),
  description: z.string().max(1000).optional(),
  projectId: z.string().min(1),
  ownerId: z.string().min(1),
  team: z.string().min(1),
  hypothesis: z.string().min(1),
  primaryMetric: z.string().min(1),
  bucketStrategy: z.enum(['random', 'user_id', 'session_id', 'device_id', 'custom']),
  bucketKey: z.string().optional(),
  variants: z.array(abVariantSchema).min(2),
  trafficAllocation: trafficAllocationConfigSchema,
  targetingRules: z.array(targetingRuleSchema).default([]),
  metrics: z.array(abTestMetricSchema).default([]),
  tags: z.array(z.string()).default([]),
  metadata: z.record(z.unknown()).default({}),
});

export const abTestUpdateRequestSchema = z.object({
  name: z.string().min(1).max(100).optional(),
  description: z.string().max(1000).optional(),
  hypothesis: z.string().min(1).optional(),
  primaryMetric: z.string().min(1).optional(),
  status: z.enum(['draft', 'running', 'paused', 'completed', 'archived']).optional(),
  startTime: z.number().optional(),
  endTime: z.number().optional(),
  variants: z.array(abVariantSchema).min(2).optional(),
  trafficAllocation: trafficAllocationConfigSchema.optional(),
  targetingRules: z.array(targetingRuleSchema).optional(),
  metrics: z.array(abTestMetricSchema).optional(),
  tags: z.array(z.string()).optional(),
  metadata: z.record(z.unknown()).optional(),
  expectedUpdatedAt: z.number().optional(),
});

export const assignmentRequestSchema = z.object({
  experimentId: z.string().min(1),
  userId: z.string().optional(),
  sessionId: z.string().optional(),
  deviceId: z.string().optional(),
  customKey: z.string().optional(),
  context: z.record(z.unknown()).default({}),
  previewVariantId: z.string().optional(),
});

export const trackEventRequestSchema = z.object({
  experimentId: z.string().min(1),
  variantId: z.string().min(1),
  userId: z.string().optional(),
  sessionId: z.string().optional(),
  eventName: z.string().min(1),
  properties: z.record(z.unknown()).default({}),
  timestamp: z.number().optional(),
});

export const abTestListRequestSchema = z.object({
  name: z.string().optional(),
  projectId: z.string().optional(),
  ownerId: z.string().optional(),
  team: z.string().optional(),
  status: z.enum(['draft', 'running', 'paused', 'completed', 'archived']).optional(),
  page: z.number().int().min(1).default(1),
  pageSize: z.number().int().min(1).max(100).default(20),
});

export type ABTestCreateRequestInput = z.infer<typeof abTestCreateRequestSchema>;
export type ABTestUpdateRequestInput = z.infer<typeof abTestUpdateRequestSchema>;
export type AssignmentRequestInput = z.infer<typeof assignmentRequestSchema>;
export type TrackEventRequestInput = z.infer<typeof trackEventRequestSchema>;
export type ABTestListRequestInput = z.infer<typeof abTestListRequestSchema>;
