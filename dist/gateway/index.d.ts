import { Request, Response, NextFunction } from 'express';
import { EventEmitter } from 'events';
import { AuthContext, RateLimitConfig } from '../types';
interface User {
    user_id: string;
    username: string;
    password_hash: string;
    roles: string[];
    permissions: string[];
    tenant_id: string;
    created_at: string;
    is_active: boolean;
}
interface ApiKey {
    key_id: string;
    key_hash: string;
    user_id: string;
    name: string;
    scopes: string[];
    created_at: string;
    expires_at: string | null;
    is_active: boolean;
}
interface RateLimitState {
    count: number;
    reset_time: number;
    burst_remaining: number;
}
declare class ApiGateway extends EventEmitter {
    private users;
    private apiKeys;
    private rateLimits;
    private tokenCache;
    private defaultRateLimit;
    private customRateLimits;
    private jwtSecret;
    private tokenExpiry;
    constructor();
    private initializeDefaultUser;
    authenticateUser(username: string, password: string): Promise<{
        token: string;
        user: Omit<User, 'password_hash'>;
    } | null>;
    createApiKey(userId: string, name: string, scopes: string[], expiresAt?: Date): ApiKey;
    validateToken(token: string): AuthContext | null;
    checkRateLimit(identifier: string, customConfig?: Partial<RateLimitConfig>): {
        allowed: boolean;
        remaining: number;
        resetMs: number;
    };
    setCustomRateLimit(identifier: string, config: Partial<RateLimitConfig>): void;
    checkPermission(auth: AuthContext, permission: string): boolean;
    checkScope(auth: AuthContext, requiredScope: string): boolean;
    private scopeMatches;
    authMiddleware(requiredPermission?: string): (req: Request, res: Response, next: NextFunction) => void;
    rateLimitMiddleware(config?: Partial<RateLimitConfig>): (req: Request, res: Response, next: NextFunction) => void;
    private getClientIdentifier;
    createUser(username: string, password: string, roles: string[], permissions: string[], tenantId?: string): Omit<User, 'password_hash'> | null;
    revokeApiKey(keyId: string): boolean;
    invalidateToken(token: string): void;
    setJwtSecret(secret: string): void;
    setTokenExpiry(expiry: string): void;
    cleanupExpired(): void;
}
export declare const apiGateway: ApiGateway;
export { ApiGateway, AuthContext, User, ApiKey, RateLimitState, RateLimitConfig };
//# sourceMappingURL=index.d.ts.map