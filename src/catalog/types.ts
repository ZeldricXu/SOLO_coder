import { z } from 'zod';

export const ServiceMetadataSchema = z.object({
  serviceId: z.string(),
  name: z.string(),
  type: z.enum(['service', 'library', 'application', 'database', 'queue', 'cache']),
  description: z.string().optional(),
  version: z.string().default('1.0.0'),
  owner: z.string().optional(),
  team: z.string().optional(),
  tags: z.array(z.string()).default([]),
  status: z.enum(['active', 'deprecated', 'maintenance', 'retired']).default('active'),
  lifecycleStage: z.enum(['concept', 'development', 'staging', 'production', 'retired']).default('development'),
  endpoints: z.array(z.object({
    name: z.string(),
    protocol: z.enum(['http', 'grpc', 'graphql', 'websocket', 'tcp']),
    url: z.string(),
    environment: z.string().default('production'),
    healthCheck: z.string().optional(),
  })).default([]),
  repositories: z.array(z.object({
    type: z.enum(['git', 'svn', 'npm', 'pypi', 'maven']),
    url: z.string(),
    branch: z.string().optional(),
  })).default([]),
  contact: z.object({
    email: z.string().optional(),
    slack: z.string().optional(),
    phone: z.string().optional(),
  }).optional(),
  metadata: z.record(z.unknown()).default({}),
  createdAt: z.string().datetime(),
  updatedAt: z.string().datetime(),
});

export type ServiceMetadata = z.infer<typeof ServiceMetadataSchema>;

export const DependencySchema = z.object({
  dependencyId: z.string(),
  sourceServiceId: z.string(),
  targetServiceId: z.string(),
  relationship: z.enum(['depends_on', 'consumes', 'produces', 'calls', 'is_called_by']),
  protocol: z.string().optional(),
  description: z.string().optional(),
  isCritical: z.boolean().default(false),
  createdAt: z.string().datetime(),
});

export type Dependency = z.infer<typeof DependencySchema>;

export const DeploymentInfoSchema = z.object({
  deploymentId: z.string(),
  serviceId: z.string(),
  environment: z.string(),
  version: z.string(),
  status: z.enum(['deployed', 'deploying', 'failed', 'pending']),
  instances: z.number().default(1),
  region: z.string().optional(),
  cluster: z.string().optional(),
  lastDeployedAt: z.string().datetime(),
  deployedBy: z.string().optional(),
  metadata: z.record(z.unknown()).default({}),
});

export type DeploymentInfo = z.infer<typeof DeploymentInfoSchema>;

export const ServiceSearchQuerySchema = z.object({
  name: z.string().optional(),
  type: z.enum(['service', 'library', 'application', 'database', 'queue', 'cache']).optional(),
  team: z.string().optional(),
  owner: z.string().optional(),
  tags: z.array(z.string()).optional(),
  status: z.enum(['active', 'deprecated', 'maintenance', 'retired']).optional(),
  lifecycleStage: z.enum(['concept', 'development', 'staging', 'production', 'retired']).optional(),
  limit: z.number().optional(),
  offset: z.number().optional(),
});

export type ServiceSearchQuery = z.infer<typeof ServiceSearchQuerySchema>;

export interface DependencyGraph {
  nodes: Array<{
    id: string;
    name: string;
    type: string;
    status: string;
    version: string;
  }>;
  edges: Array<{
    source: string;
    target: string;
    relationship: string;
    isCritical: boolean;
  }>;
}

export interface ServiceHealth {
  serviceId: string;
  overall: 'healthy' | 'degraded' | 'unhealthy' | 'unknown';
  uptime: number;
  lastChecked: string;
  checks: Array<{
    name: string;
    status: 'pass' | 'warn' | 'fail';
    message?: string;
    lastChecked: string;
  }>;
}
