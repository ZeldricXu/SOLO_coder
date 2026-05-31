import { User } from '../types';

export interface AuthContext {
  user: User;
  token: string;
  authenticated: boolean;
}

export interface RateLimitInfo {
  remaining: number;
  resetTime: number;
  limit: number;
}

export interface GatewayConfig {
  jwtSecret: string;
  jwtExpiresIn: number;
  rateLimit: {
    windowMs: number;
    maxRequests: number;
    keyPrefix: string;
  };
  redisUrl?: string;
  enableCircuitBreaker: boolean;
  circuitBreakerThreshold: number;
  circuitBreakerTimeout: number;
}

export interface Route {
  path: string;
  method: 'GET' | 'POST' | 'PUT' | 'DELETE' | 'PATCH';
  handler: (req: GatewayRequest) => Promise<GatewayResponse>;
  authRequired: boolean;
  roles?: string[];
  permissions?: string[];
  rateLimitOverride?: Partial<RateLimitInfo>;
  params?: Record<string, string>;
}

export interface GatewayRequest {
  id: string;
  method: string;
  path: string;
  headers: Record<string, string>;
  query: Record<string, string>;
  params: Record<string, string>;
  body?: unknown;
  auth?: AuthContext;
  timestamp: number;
  traceId: string;
}

export interface GatewayResponse {
  statusCode: number;
  headers: Record<string, string>;
  body: unknown;
}

export interface AuthenticationResult {
  success: boolean;
  auth?: AuthContext;
  error?: string;
  errorCode?: string;
}

export interface RateLimitResult {
  allowed: boolean;
  info: RateLimitInfo;
}
