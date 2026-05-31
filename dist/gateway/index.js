"use strict";
var __importDefault = (this && this.__importDefault) || function (mod) {
    return (mod && mod.__esModule) ? mod : { "default": mod };
};
Object.defineProperty(exports, "__esModule", { value: true });
exports.ApiGateway = exports.apiGateway = void 0;
const jsonwebtoken_1 = __importDefault(require("jsonwebtoken"));
const bcryptjs_1 = __importDefault(require("bcryptjs"));
const node_cache_1 = __importDefault(require("node-cache"));
const events_1 = require("events");
const utils_1 = require("../shared/utils");
const logging_1 = require("../logging");
class ApiGateway extends events_1.EventEmitter {
    users = new Map();
    apiKeys = new Map();
    rateLimits = new Map();
    tokenCache;
    defaultRateLimit = {
        requests_per_minute: 100,
        burst: 50,
        window_ms: 60000,
    };
    customRateLimits = new Map();
    jwtSecret = process.env.JWT_SECRET || 'default-secret-change-in-production';
    tokenExpiry = '24h';
    constructor() {
        super();
        this.tokenCache = new node_cache_1.default({ stdTTL: 300, checkperiod: 60 });
        this.initializeDefaultUser();
    }
    initializeDefaultUser() {
        const defaultPassword = 'admin123';
        const passwordHash = bcryptjs_1.default.hashSync(defaultPassword, 10);
        const user = {
            user_id: (0, utils_1.generateId)('usr'),
            username: 'admin',
            password_hash: passwordHash,
            roles: ['admin'],
            permissions: ['*'],
            tenant_id: 'default',
            created_at: (0, utils_1.nowISO)(),
            is_active: true,
        };
        this.users.set(user.username, user);
        logging_1.logger.info('Default user created', { username: 'admin', user_id: user.user_id });
    }
    async authenticateUser(username, password) {
        const user = this.users.get(username);
        if (!user || !user.is_active) {
            logging_1.logger.warn('Authentication failed: user not found or inactive', { username });
            return null;
        }
        const isValid = await bcryptjs_1.default.compare(password, user.password_hash);
        if (!isValid) {
            logging_1.logger.warn('Authentication failed: invalid password', { username, user_id: user.user_id });
            return null;
        }
        const tokenPayload = {
            user_id: user.user_id,
            username: user.username,
            roles: user.roles,
            permissions: user.permissions,
            tenant_id: user.tenant_id,
        };
        const token = jsonwebtoken_1.default.sign(tokenPayload, this.jwtSecret, { expiresIn: this.tokenExpiry });
        const { password_hash, ...userWithoutPassword } = user;
        logging_1.logger.info('User authenticated', { username, user_id: user.user_id });
        this.emit('user.authenticated', user.user_id);
        return { token, user: userWithoutPassword };
    }
    createApiKey(userId, name, scopes, expiresAt) {
        const apiKey = (0, utils_1.generateId)('sk');
        const keyHash = bcryptjs_1.default.hashSync(apiKey, 10);
        const key = {
            key_id: (0, utils_1.generateId)('ak'),
            key_hash: keyHash,
            user_id: userId,
            name,
            scopes,
            created_at: (0, utils_1.nowISO)(),
            expires_at: expiresAt ? expiresAt.toISOString() : null,
            is_active: true,
        };
        this.apiKeys.set(key.key_id, key);
        logging_1.logger.info('API key created', { key_id: key.key_id, user_id: userId, name });
        this.emit('apikey.created', key);
        return { ...key, key_hash: apiKey };
    }
    validateToken(token) {
        const cacheKey = `token:${token.slice(-16)}`;
        const cached = this.tokenCache.get(cacheKey);
        if (cached) {
            return cached;
        }
        try {
            const decoded = jsonwebtoken_1.default.verify(token, this.jwtSecret);
            const context = {
                user_id: decoded.user_id,
                roles: decoded.roles,
                permissions: decoded.permissions,
                tenant_id: decoded.tenant_id,
                token,
            };
            this.tokenCache.set(cacheKey, context, 300);
            return context;
        }
        catch (error) {
            logging_1.logger.debug('Token validation failed', { error: error.message });
            return null;
        }
    }
    checkRateLimit(identifier, customConfig) {
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
            logging_1.logger.warn('Rate limit exceeded', { identifier, count: state.count, limit: config.requests_per_minute });
            this.emit('rate_limit.exceeded', identifier, state.count);
        }
        return {
            allowed,
            remaining: Math.max(0, config.requests_per_minute - state.count),
            resetMs: state.reset_time - now,
        };
    }
    setCustomRateLimit(identifier, config) {
        this.customRateLimits.set(identifier, { ...this.defaultRateLimit, ...config });
        logging_1.logger.info('Custom rate limit set', { identifier, config });
    }
    checkPermission(auth, permission) {
        if (auth.permissions.includes('*'))
            return true;
        if (auth.roles.includes('admin'))
            return true;
        return auth.permissions.includes(permission);
    }
    checkScope(auth, requiredScope) {
        if (auth.permissions.includes('*'))
            return true;
        return auth.permissions.some((p) => this.scopeMatches(p, requiredScope));
    }
    scopeMatches(granted, required) {
        if (granted === required)
            return true;
        if (granted.endsWith(':*')) {
            const prefix = granted.slice(0, -2);
            return required.startsWith(prefix);
        }
        return false;
    }
    authMiddleware(requiredPermission) {
        return (req, res, next) => {
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
                logging_1.logger.warn('Permission denied', { user_id: context.user_id, required_permission: requiredPermission });
                res.status(403).json({ code: 403, error: 'Insufficient permissions' });
                return;
            }
            req.auth = context;
            next();
        };
    }
    rateLimitMiddleware(config) {
        return (req, res, next) => {
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
    getClientIdentifier(req) {
        const forwardedFor = req.headers['x-forwarded-for'];
        const ip = Array.isArray(forwardedFor) ? forwardedFor[0] : forwardedFor || req.ip || 'unknown';
        const auth = req.auth;
        return auth ? `user:${auth.user_id}` : `ip:${ip}`;
    }
    createUser(username, password, roles, permissions, tenantId = 'default') {
        if (this.users.has(username)) {
            logging_1.logger.warn('User creation failed: username exists', { username });
            return null;
        }
        const passwordHash = bcryptjs_1.default.hashSync(password, 10);
        const user = {
            user_id: (0, utils_1.generateId)('usr'),
            username,
            password_hash: passwordHash,
            roles,
            permissions,
            tenant_id: tenantId,
            created_at: (0, utils_1.nowISO)(),
            is_active: true,
        };
        this.users.set(username, user);
        const { password_hash, ...userWithoutPassword } = user;
        logging_1.logger.info('User created', { username, user_id: user.user_id });
        this.emit('user.created', user.user_id);
        return userWithoutPassword;
    }
    revokeApiKey(keyId) {
        const key = this.apiKeys.get(keyId);
        if (key) {
            key.is_active = false;
            logging_1.logger.info('API key revoked', { key_id: keyId });
            this.emit('apikey.revoked', keyId);
            return true;
        }
        return false;
    }
    invalidateToken(token) {
        const cacheKey = `token:${token.slice(-16)}`;
        this.tokenCache.del(cacheKey);
        logging_1.logger.debug('Token invalidated');
    }
    setJwtSecret(secret) {
        this.jwtSecret = secret;
        logging_1.logger.info('JWT secret updated');
    }
    setTokenExpiry(expiry) {
        this.tokenExpiry = expiry;
        logging_1.logger.info('Token expiry updated', { expiry });
    }
    cleanupExpired() {
        const now = new Date();
        for (const [keyId, key] of this.apiKeys.entries()) {
            if (key.expires_at && new Date(key.expires_at) <= now) {
                key.is_active = false;
                logging_1.logger.info('API key expired', { key_id: keyId });
            }
        }
    }
}
exports.ApiGateway = ApiGateway;
exports.apiGateway = new ApiGateway();
//# sourceMappingURL=index.js.map