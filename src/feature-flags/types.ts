import { z } from 'zod';

export const UserSegmentSchema = z.object({
  id: z.string(),
  name: z.string(),
  description: z.string().optional(),
  conditions: z.array(z.object({
    field: z.string(),
    operator: z.enum(['eq', 'ne', 'gt', 'lt', 'gte', 'lte', 'in', 'not_in', 'contains', 'regex']),
    value: z.unknown(),
  })),
});

export type UserSegment = z.infer<typeof UserSegmentSchema>;

export const RolloutStrategySchema = z.object({
  type: z.enum(['gradual', 'immediate', 'scheduled']),
  percentage: z.number().min(0).max(100).default(100),
  startPercentage: z.number().min(0).max(100).default(0),
  targetPercentage: z.number().min(0).max(100).default(100),
  durationMs: z.number().default(3600000),
  startTime: z.string().datetime().optional(),
  endTime: z.string().datetime().optional(),
});

export type RolloutStrategy = z.infer<typeof RolloutStrategySchema>;

export const FeatureFlagSchema = z.object({
  id: z.string(),
  key: z.string(),
  name: z.string(),
  description: z.string().optional(),
  enabled: z.boolean().default(false),
  type: z.enum(['boolean', 'string', 'number', 'json']).default('boolean'),
  value: z.unknown(),
  defaultValue: z.unknown(),
  targetSegments: z.array(z.string()).default([]),
  rollout: RolloutStrategySchema.optional(),
  environment: z.string().default('production'),
  tags: z.array(z.string()).default([]),
  createdAt: z.string().datetime(),
  updatedAt: z.string().datetime(),
  createdBy: z.string().optional(),
});

export type FeatureFlag = z.infer<typeof FeatureFlagSchema>;

export const FlagEvaluationContextSchema = z.object({
  userId: z.string().optional(),
  sessionId: z.string().optional(),
  environment: z.string().default('production'),
  attributes: z.record(z.unknown()).default({}),
});

export type FlagEvaluationContext = z.infer<typeof FlagEvaluationContextSchema>;

export interface FlagEvaluationResult<T = unknown> {
  key: string;
  value: T;
  enabled: boolean;
  reason: 'enabled' | 'disabled' | 'segment_match' | 'rollout_percentage' | 'default_value';
  metadata?: Record<string, unknown>;
}

export interface FrequencyLimitConfig {
  maxRequests: number;
  windowMs: number;
  keyGenerator?: (context: FlagEvaluationContext) => string;
}
