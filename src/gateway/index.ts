import { Request, Response, NextFunction } from 'express';
import jwt from 'jsonwebtoken';
import bcrypt from 'bcryptjs';
import NodeCache from 'node-cache';
import { EventEmitter } from 'events';
import { AuthContext, RateLimitConfig } from '../types';
import { generateId, nowISO } from '../shared/utils';
import { logger } from '../logging';

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

class ApiGateway extends EventEmitter {
  private users: Map<string, User> = new Map();
  private apiKeys: Map<string, ApiKey> = new Map();
  private rateLimits: Map<string, RateLimitState> = new Map();
  private tokenCache: NodeCache;
  private defaultRateLimit: RateLimitConfig = {
    requests_per_minute: 100,
    burst: 50,
    window_ms: 60000,
  };
  private customRateLimits: Map<string, RateLimitConfig> = new Map();
  private jwtSecret: string = process.env.JWT_SECRET || 'default-secret-change-in-production';
  private tokenExpiry: string = '24h';

  constructor() {
    super();
    this.tokenCache = new NodeCache({ stdTTL: 300, checkperiod: 60 });
    this.initializeDefaultUser();
  }

  private initializeDefaultUser(): void {
    const defaultPassword = 'admin123';
    const passwordHash = bcrypt.hashSync(defaultPassword, 10);
    const user: User = {
      user_id: generateId('usr'),
      username: 'admin',
      password_hash: passwordHash,
      roles: ['admin'],
      permissions: ['*'],
      tenant_id: 'default',
      created_at: nowISO(),
      is_active: true,
    };
    this.users.set(user.username, user);
    logger.info('Default user created', { username: 'admin', user_id: user.user_id });
  }

  async authenticateUser(username: string, password: string): Promise<{ token: string; user: Omit<User, 'password_hash'> } | null> {
    const user = this.users.get(username);
    if (!user || !user.is_active) {
      logger.warn('Authentication failed: user not found or inactive', { username });
      return null;
    }

    const isValid = await bcrypt.compare(password, user.password_hash);
    if (!isValid) {
      logger.warn('Authentication failed: invalid password', { username, user_id: user.user_id });
      return null;
    }

    const tokenPayload = {
      user_id: user.user_id,
      username: user.username,
      roles: user.roles,
      permissions: user.permissions,
      tenant_id: user.tenant_id,
    };

    const token = jwt.sign(tokenPayload, this.jwtSecret, { expiresIn: this.tokenExpiry as any });
    const { password_hash, ...userWithoutPassword } = user;

    logger.info('User authenticated', { username, user_id: user.user_id });
    this.emit('user.authenticated', user.user_id);

    return { token, user: userWithoutPassword };
  }

  createApiKey(userId: string, name: string, scopes: string[], expiresAt?: Date): ApiKey {
    const apiKey = generateId('sk');
    const keyHash = bcrypt.hashSync(apiKey, 10);

    const key: ApiKey = {
      key_id: generateId('ak'),
      key_hash: keyHash,
      user_id: userId,
      name,
      scopes,
      created_at: nowISO(),
      expires_at: expiresAt ? expiresAt.toISOString() : null,
      is_active: true,
    };

    this.apiKeys.set(key.key_id, key);
    logger.info('API key created', { key_id: key.key_id, user_id: userId, name });
    this.emit('apikey.created', key);

    return { ...key, key_hash: apiKey };
  }

  validateToken(token: string): AuthContext | null {
    const cacheKey = `token:${token.slice(-16)}`;
    const cached = this.tokenCache.get<AuthContext>(cacheKey);
    if (cached) {
      return cached;
    }

    try {
      const decoded = jwt.verify(token, this.jwtSecret) as Record<string, unknown>;
      const context: AuthContext = {
        user_id: decoded.user_id as string,
        roles: decoded.roles as string[],
        permissions: decoded.permissions as string[],
        tenant_id: decoded.tenant_id as string,
        token,
      };

      this.tokenCache.set(cacheKey, context, 300);
      return context;
    } catch (error) {
      logger.debug('Token validation failed', { error: (error as Error).message });
      return null;
    }
  }

  checkRateLimit(identifier: string, customConfig?: Partial<RateLimitConfig>): { allowed: boolean; remaining: number; resetMs: number } {
    const config = customConfig
      ? { ...this.defaultRateLimit, ...customConfig }
      : this.customRateLimits.get(identifier) || this.defaultRateLimit;

    const now = Date.now();
    let state = this.rateLimits.get(identifier);

    if (!state || state.reset_time <= now) {
      state = {
        count: 0,
        reset_time: now + config.window_ms,
        burst_remaining: config.burst,
      };
    }

    state.count++;

    const allowed = state.count <= config.requests_per_minute;

    this.rateLimits.set(identifier, state);

    if (!allowed) {
      logger.warn('Rate limit exceeded', { identifier, count: state.count, limit: config.requests_per_minute });
      this.emit('rate_limit.exceeded', identifier, state.count);
    }

    return {
      allowed,
      remaining: Math.max(0, config.requests_per_minute - state.count),
      resetMs: state.reset_time - now,
    };
  }

  setCustomRateLimit(identifier: string, config: Partial<RateLimitConfig>): void {
    this.customRateLimits.set(identifier, { ...this.defaultRateLimit, ...config });
    logger.info('Custom rate limit set', { identifier, config });
  }

  checkPermission(auth: AuthContext, permission: string): boolean {
    if (auth.permissions.includes('*')) return true;
    if (auth.roles.includes('admin')) return true;
    return auth.permissions.includes(permission);
  }

  checkScope(auth: AuthContext, requiredScope: string): boolean {
    if (auth.permissions.includes('*')) return true;
    return auth.permissions.some((p) => this.scopeMatches(p, requiredScope));
  }

  private scopeMatches(granted: string, required: string): boolean {
    if (granted === required) return true;
    if (granted.endsWith(':*')) {
      const prefix = granted.slice(0, -2);
      return required.startsWith(prefix);
    }
    return false;
  }

  authMiddleware(requiredPermission?: string) {
    return (req: Request, res: Response, next: NextFunction): void => {
      const authHeader = req.headers.authorization;
      if (!authHeader || !authHeader.startsWith('Bearer ')) {
        res.status(401).json({ code: 401, error: 'Missing or invalid authorization header' });
        return;
      }

      const token = authHeader.slice(7);
      const context = this.validateToken(token);

      if (!context) {
        res.status(401).json({ code: 401, error: 'Invalid or expired token' });
        return;
      }

      if (requiredPermission && !this.checkPermission(context, requiredPermission)) {
        logger.warn('Permission denied', { user_id: context.user_id, required_permission: requiredPermission });
        res.status(403).json({ code: 403, error: 'Insufficient permissions' });
        return;
      }

      (req as unknown as { auth: AuthContext }).auth = context;
      next();
    };
  }

  rateLimitMiddleware(config?: Partial<RateLimitConfig>) {
    return (req: Request, res: Response, next: NextFunction): void => {
      const identifier = this.getClientIdentifier(req);
      const result = this.checkRateLimit(identifier, config);

      res.setHeader('X-RateLimit-Limit', config?.requests_per_minute || this.defaultRateLimit.requests_per_minute);
      res.setHeader('X-RateLimit-Remaining', result.remaining);
      res.setHeader('X-RateLimit-Reset', Math.ceil(result.resetMs / 1000));

      if (!result.allowed) {
        res.setHeader('Retry-After', Math.ceil(result.resetMs / 1000));
        res.status(429).json({
          code: 429,
          error: 'Rate limit exceeded',
          retry_after: Math.ceil(result.resetMs / 1000),
        });
        return;
      }

      next();
    };
  }

  private getClientIdentifier(req: Request): string {
    const forwardedFor = req.headers['x-forwarded-for'];
    const ip = Array.isArray(forwardedFor) ? forwardedFor[0] : forwardedFor || req.ip || 'unknown';
    const auth = (req as unknown as { auth?: AuthContext }).auth;
    return auth ? `user:${auth.user_id}` : `ip:${ip}`;
  }

  createUser(
    username: string,
    password: string,
    roles: string[],
    permissions: string[],
    tenantId: string = 'default'
  ): Omit<User, 'password_hash'> | null {
    if (this.users.has(username)) {
      logger.warn('User creation failed: username exists', { username });
      return null;
    }

    const passwordHash = bcrypt.hashSync(password, 10);
    const user: User = {
      user_id: generateId('usr'),
      username,
      password_hash: passwordHash,
      roles,
      permissions,
      tenant_id: tenantId,
      created_at: nowISO(),
      is_active: true,
    };

    this.users.set(username, user);
    const { password_hash, ...userWithoutPassword } = user;

    logger.info('User created', { username, user_id: user.user_id });
    this.emit('user.created', user.user_id);

    return userWithoutPassword;
  }

  revokeApiKey(keyId: string): boolean {
    const key = this.apiKeys.get(keyId);
    if (key) {
      key.is_active = false;
      logger.info('API key revoked', { key_id: keyId });
      this.emit('apikey.revoked', keyId);
      return true;
    }
    return false;
  }

  invalidateToken(token: string): void {
    const cacheKey = `token:${token.slice(-16)}`;
    this.tokenCache.del(cacheKey);
    logger.debug('Token invalidated');
  }

  setJwtSecret(secret: string): void {
    this.jwtSecret = secret;
    logger.info('JWT secret updated');
  }

  setTokenExpiry(expiry: string): void {
    this.tokenExpiry = expiry;
    logger.info('Token expiry updated', { expiry });
  }

  cleanupExpired(): void {
    const now = new Date();
    for (const [keyId, key] of this.apiKeys.entries()) {
      if (key.expires_at && new Date(key.expires_at) <= now) {
        key.is_active = false;
        logger.info('API key expired', { key_id: keyId });
      }
    }
  }
}

export const apiGateway = new ApiGateway();
export { ApiGateway, AuthContext, User, ApiKey, RateLimitState, RateLimitConfig };
