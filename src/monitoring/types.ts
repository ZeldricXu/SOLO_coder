import { z } from 'zod';

export const MetricSchema = z.object({
  metricId: z.string(),
  name: z.string(),
  type: z.enum(['counter', 'gauge', 'histogram', 'summary']),
  value: z.number(),
  labels: z.record(z.string()).default({}),
  timestamp: z.string().datetime(),
  unit: z.string().optional(),
  description: z.string().optional(),
});

export type Metric = z.infer<typeof MetricSchema>;

export const AlertRuleSchema = z.object({
  ruleId: z.string(),
  name: z.string(),
  description: z.string().optional(),
  metricName: z.string(),
  condition: z.object({
    operator: z.enum(['>', '<', '>=', '<=', '==', '!=']),
    threshold: z.number(),
    durationMs: z.number().default(0),
  }),
  labels: z.record(z.string()).default({}),
  severity: z.enum(['INFO', 'WARNING', 'ERROR', 'CRITICAL']).default('WARNING'),
  notificationChannels: z.array(z.string()).default([]),
  enabled: z.boolean().default(true),
  lastFiredAt: z.string().datetime().nullable().default(null),
  cooldownMs: z.number().default(300000),
});

export type AlertRule = z.infer<typeof AlertRuleSchema>;

export const AlertSchema = z.object({
  alertId: z.string(),
  ruleId: z.string(),
  name: z.string(),
  severity: z.enum(['INFO', 'WARNING', 'ERROR', 'CRITICAL']),
  message: z.string(),
  metricName: z.string(),
  currentValue: z.number(),
  threshold: z.number(),
  labels: z.record(z.string()).default({}),
  firedAt: z.string().datetime(),
  resolved: z.boolean().default(false),
  resolvedAt: z.string().datetime().nullable().default(null),
  notifications: z.array(z.object({
    channelId: z.string(),
    status: z.enum(['pending', 'sent', 'failed']),
    sentAt: z.string().datetime().nullable().default(null),
    error: z.string().optional(),
  })).default([]),
});

export type Alert = z.infer<typeof AlertSchema>;

export const NotificationChannelSchema = z.object({
  channelId: z.string(),
  type: z.enum(['email', 'slack', 'webhook', 'pagerduty', 'sms']),
  name: z.string(),
  config: z.record(z.unknown()),
  enabled: z.boolean().default(true),
});

export type NotificationChannel = z.infer<typeof NotificationChannelSchema>;

export const SnapshotSchema = z.object({
  snapshotId: z.string(),
  timestamp: z.string().datetime(),
  metrics: z.object({
    throughput: z.number(),
    latencyP99: z.number(),
    errorRate: z.number(),
  }),
  dimensions: z.record(z.string()).default({}),
});

export type Snapshot = z.infer<typeof SnapshotSchema>;

export interface MetricsQuery {
  name?: string;
  labels?: Record<string, string>;
  startTime?: string;
  endTime?: string;
  limit?: number;
}

export interface MetricsAggregator {
  min: number;
  max: number;
  avg: number;
  sum: number;
  count: number;
  p50: number;
  p95: number;
  p99: number;
}

export interface NotificationPayload {
  alert: Alert;
  rule: AlertRule;
}
