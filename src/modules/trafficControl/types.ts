import { z } from 'zod';
import type { TrafficPolicyType } from '../../types';

export const TrafficPolicySchema = z.object({
  name: z.string().min(1).max(100),
  policyType: z.enum(['canary', 'blue_green', 'mirror', 'circuit_breaker']),
  namespace: z.string().min(1),
  rules: z.record(z.unknown()),
  targets: z.record(z.unknown()),
  enabled: z.boolean().default(true),
});

export const CanaryRulesSchema = z.object({
  weight: z.number().int().min(0).max(100),
  stableVersion: z.string(),
  canaryVersion: z.string(),
  headerMatch: z.array(z.object({
    name: z.string(),
    value: z.string(),
    type: z.enum(['exact', 'regex', 'prefix']).default('exact'),
  })).optional(),
  cookieMatch: z.array(z.object({
    name: z.string(),
    value: z.string(),
  })).optional(),
  rolloutDuration: z.number().int().positive().optional(),
});

export const BlueGreenRulesSchema = z.object({
  blueVersion: z.string(),
  greenVersion: z.string(),
  activeColor: z.enum(['blue', 'green']),
  promotionStrategy: z.enum(['all_at_once', 'gradual']).default('all_at_once'),
});

export const MirrorRulesSchema = z.object({
  source: z.string(),
  target: z.string(),
  percentage: z.number().int().min(1).max(100).default(100),
  timeoutMs: z.number().int().positive().default(1000),
});

export const CircuitBreakerRulesSchema = z.object({
  failureThreshold: z.number().int().min(1).default(5),
  successThreshold: z.number().int().min(1).default(3),
  timeoutMs: z.number().int().positive().default(30000),
  halfOpenMaxCalls: z.number().int().positive().default(1),
});

export type CreateTrafficPolicyRequest = z.infer<typeof TrafficPolicySchema>;
export type CanaryRules = z.infer<typeof CanaryRulesSchema>;
export type BlueGreenRules = z.infer<typeof BlueGreenRulesSchema>;
export type MirrorRules = z.infer<typeof MirrorRulesSchema>;
export type CircuitBreakerRules = z.infer<typeof CircuitBreakerRulesSchema>;

export interface TrafficPolicy {
  policyId: string;
  name: string;
  policyType: TrafficPolicyType;
  namespace: string;
  rules: Record<string, unknown>;
  targets: Record<string, unknown>;
  enabled: boolean;
  createdAt: Date;
  updatedAt: Date;
}
