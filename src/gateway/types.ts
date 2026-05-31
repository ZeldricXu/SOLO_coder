import { z } from 'zod';

export const RouteSchema = z.object({
  id: z.string(),
  path: z.string(),
  method: z.enum(['GET', 'POST', 'PUT', 'DELETE', 'PATCH', 'HEAD', 'OPTIONS']),
  target: z.object({
    host: z.string(),
    port: z.number().int().min(1).max(65535),
    protocol: z.enum(['http', 'https']).default('http'),
    path: z.string().optional(),
  }),
  protocol: z.enum(['http', 'grpc', 'websocket', 'graphql']).default('http'),
  timeoutMs: z.number().default(30000),
  rateLimit: z.object({
    requestsPerMinute: z.number().default(60),
    burstSize: z.number().default(10),
  }).optional(),
  authentication: z.object({
    type: z.enum(['none', 'jwt', 'apikey', 'oauth2']).default('none'),
    credentials: z.record(z.string()).optional(),
  }).optional(),
  transformations: z.array(z.object({
    type: z.enum(['request', 'response']),
    action: z.enum(['add-header', 'remove-header', 'modify-body', 'rewrite-path']),
    config: z.record(z.unknown()),
  })).default([]),
  enabled: z.boolean().default(true),
});

export type Route = z.infer<typeof RouteSchema>;

export const GatewayConfigSchema = z.object({
  port: z.number().int().min(1).max(65535).default(8080),
  host: z.string().default('0.0.0.0'),
  routes: z.array(RouteSchema).default([]),
  globalTimeoutMs: z.number().default(60000),
  cors: z.object({
    enabled: z.boolean().default(false),
    origins: z.array(z.string()).default(['*']),
    methods: z.array(z.string()).default(['GET', 'POST', 'PUT', 'DELETE', 'OPTIONS']),
    headers: z.array(z.string()).default(['*']),
  }).optional(),
  logging: z.object({
    enabled: z.boolean().default(true),
    level: z.enum(['error', 'warn', 'info', 'debug']).default('info'),
  }).optional(),
});

export type GatewayConfig = z.infer<typeof GatewayConfigSchema>;

export interface ProxyRequest {
  method: string;
  path: string;
  headers: Record<string, string>;
  body?: unknown;
  query: Record<string, string>;
}

export interface ProxyResponse {
  status: number;
  headers: Record<string, string>;
  body: unknown;
  latencyMs: number;
}

export interface GatewayMetrics {
  totalRequests: number;
  activeConnections: number;
  errorRate: number;
  averageLatencyMs: number;
  p95LatencyMs: number;
  p99LatencyMs: number;
  requestsPerSecond: number;
  bytesTransferred: number;
}
