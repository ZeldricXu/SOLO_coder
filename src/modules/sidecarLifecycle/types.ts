import { z } from 'zod';

export const SidecarTemplateSchema = z.object({
  name: z.string().min(1).max(100),
  image: z.string().min(1),
  args: z.array(z.string()).default([]),
  resources: z.object({
    requests: z.object({
      cpu: z.string().optional(),
      memory: z.string().optional(),
    }).optional(),
    limits: z.object({
      cpu: z.string().optional(),
      memory: z.string().optional(),
    }).optional(),
  }).default({}),
  volumeMounts: z.array(z.object({
    name: z.string(),
    mountPath: z.string(),
    readOnly: z.boolean().default(false),
  })).optional(),
  config: z.record(z.unknown()).optional(),
});

export const SidecarInjectionSchema = z.object({
  templateId: z.string().min(1),
  targetSelector: z.record(z.string()),
  injectionPolicy: z.enum(['always', 'if_not_present', 'never']).default('always'),
  enabled: z.boolean().default(true),
});

export const SidecarConfigSchema = z.object({
  key: z.string().min(1),
  value: z.unknown(),
});

export type CreateTemplateRequest = z.infer<typeof SidecarTemplateSchema>;
export type CreateInjectionRequest = z.infer<typeof SidecarInjectionSchema>;
export type UpdateConfigRequest = z.infer<typeof SidecarConfigSchema>;

export interface SidecarTemplate {
  templateId: string;
  name: string;
  image: string;
  args: string[];
  resources: Record<string, unknown>;
  volumeMounts?: Array<Record<string, unknown>>;
  config?: Record<string, unknown>;
  createdAt: Date;
  updatedAt: Date;
}

export interface SidecarInjection {
  injectionId: string;
  templateId: string;
  targetSelector: Record<string, string>;
  injectionPolicy: 'always' | 'if_not_present' | 'never';
  enabled: boolean;
  createdAt: Date;
  updatedAt: Date;
}

export interface SidecarInstance {
  instanceId: string;
  templateId: string;
  namespace: string;
  podName: string;
  nodeName: string;
  status: 'running' | 'pending' | 'failed' | 'terminated';
  config: Record<string, unknown>;
  startedAt: Date;
  lastHeartbeat: Date;
}

export interface ConfigUpdateResult {
  injectionId: string;
  updatedKeys: string[];
  affectedInstances: number;
  timestamp: Date;
}
