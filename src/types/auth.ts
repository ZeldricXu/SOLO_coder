export interface User {
  id: string;
  username: string;
  email: string;
  passwordHash: string;
  tenantId: string;
  roles: string[];
  permissions: string[];
  status: 'active' | 'inactive' | 'suspended';
  createdAt: string;
  updatedAt: string;
  lastLoginAt?: string;
}

export interface Tenant {
  id: string;
  name: string;
  plan: 'free' | 'basic' | 'pro' | 'enterprise';
  status: 'active' | 'suspended' | 'cancelled';
  settings: TenantSettings;
  createdAt: string;
  updatedAt: string;
}

export interface TenantSettings {
  rateLimit: {
    windowMs: number;
    maxRequests: number;
  };
  maxUsers: number;
  maxStorageGb: number;
  features: string[];
}

export interface AuthToken {
  accessToken: string;
  refreshToken: string;
  tokenType: 'Bearer';
  expiresIn: number;
  issuedAt: string;
}

export interface JwtPayload {
  sub: string;
  username: string;
  tenantId: string;
  roles: string[];
  permissions: string[];
  iat: number;
  exp: number;
  jti: string;
}

export interface AuthCredentials {
  username: string;
  password: string;
}

export interface RateLimitInfo {
  key: string;
  limit: number;
  remaining: number;
  resetTime: number;
}

export interface AuthMiddlewareOptions {
  excludePaths?: string[];
  requireAllRoles?: boolean;
  refreshTokenEndpoint?: string;
}

export type PermissionAction = 'create' | 'read' | 'update' | 'delete' | 'execute';
export type ResourceType = 'resource' | 'flow' | 'config' | 'user' | 'tenant' | 'billing';

export interface PermissionCheck {
  action: PermissionAction;
  resource: ResourceType;
  resourceId?: string;
}
