import { RateLimitInfo } from '../../types/auth';
import { RateLimitError } from '../../common/errors';
import Redis from 'ioredis';
import NodeCache from 'node-cache';

export interface RateLimitConfig {
  windowMs: number;
  maxRequests: number;
  keyPrefix?: string;
}

export class RateLimiter {
  private config: RateLimitConfig;
  private redisClient?: Redis;
  private localCache: NodeCache;
  private useRedis: boolean;

  constructor(config: RateLimitConfig, redisClient?: Redis) {
    this.config = {
      keyPrefix: 'rate_limit:',
      ...config
    };
    this.redisClient = redisClient;
    this.localCache = new NodeCache({ stdTTL: config.windowMs / 1000 });
    this.useRedis = !!redisClient;
  }

  async checkLimit(key: string): Promise<RateLimitInfo> {
    const fullKey = `${this.config.keyPrefix}${key}`;

    if (this.useRedis && this.redisClient) {
      return this.checkLimitRedis(fullKey);
    }

    return this.checkLimitLocal(fullKey);
  }

  private async checkLimitRedis(key: string): Promise<RateLimitInfo> {
    if (!this.redisClient) {
      throw new Error('Redis client not available');
    }

    const now = Date.now();
    const windowStart = now - this.config.windowMs;
    const resetTime = now + this.config.windowMs;

    const multi = this.redisClient.multi();

    multi.zremrangebyscore(key, 0, windowStart);
    multi.zcard(key);
    multi.zadd(key, now.toString(), now.toString());
    multi.pexpire(key, this.config.windowMs);

    const results = await multi.exec();
    if (!results) {
      throw new Error('Redis operation failed');
    }

    const currentCount = results[1][1] as number;
    const remaining = Math.max(0, this.config.maxRequests - currentCount);

    if (currentCount >= this.config.maxRequests) {
      throw new RateLimitError('Rate limit exceeded', {
        limit: this.config.maxRequests,
        remaining: 0,
        resetTime
      });
    }

    return {
      key,
      limit: this.config.maxRequests,
      remaining,
      resetTime
    };
  }

  private checkLimitLocal(key: string): RateLimitInfo {
    const now = Date.now();
    const windowStart = now - this.config.windowMs;
    const resetTime = now + this.config.windowMs;

    let requests: number[] = this.localCache.get(key) || [];
    requests = requests.filter(timestamp => timestamp > windowStart);

    const currentCount = requests.length;
    const remaining = Math.max(0, this.config.maxRequests - currentCount);

    if (currentCount >= this.config.maxRequests) {
      throw new RateLimitError('Rate limit exceeded', {
        limit: this.config.maxRequests,
        remaining: 0,
        resetTime
      });
    }

    requests.push(now);
    this.localCache.set(key, requests);

    return {
      key,
      limit: this.config.maxRequests,
      remaining,
      resetTime
    };
  }

  async getRateLimitInfo(key: string): Promise<RateLimitInfo> {
    const fullKey = `${this.config.keyPrefix}${key}`;

    if (this.useRedis && this.redisClient) {
      const now = Date.now();
      const windowStart = now - this.config.windowMs;
      const resetTime = now + this.config.windowMs;

      const count = await this.redisClient.zcount(fullKey, windowStart, now);
      const remaining = Math.max(0, this.config.maxRequests - count);

      return {
        key: fullKey,
        limit: this.config.maxRequests,
        remaining,
        resetTime
      };
    }

    const now = Date.now();
    const windowStart = now - this.config.windowMs;
    const resetTime = now + this.config.windowMs;

    let requests: number[] = this.localCache.get(fullKey) || [];
    requests = requests.filter(timestamp => timestamp > windowStart);

    const currentCount = requests.length;
    const remaining = Math.max(0, this.config.maxRequests - currentCount);

    return {
      key: fullKey,
      limit: this.config.maxRequests,
      remaining,
      resetTime
    };
  }

  async resetLimit(key: string): Promise<void> {
    const fullKey = `${this.config.keyPrefix}${key}`;

    if (this.useRedis && this.redisClient) {
      await this.redisClient.del(fullKey);
    } else {
      this.localCache.del(fullKey);
    }
  }

  updateConfig(config: Partial<RateLimitConfig>): void {
    this.config = { ...this.config, ...config };
    if (config.windowMs) {
      this.localCache.options.stdTTL = config.windowMs / 1000;
    }
  }

  getConfig(): RateLimitConfig {
    return { ...this.config };
  }
}

export class MultiTenantRateLimiter {
  private limiters: Map<string, RateLimiter>;
  private defaultConfig: RateLimitConfig;
  private redisClient?: Redis;

  constructor(defaultConfig: RateLimitConfig, redisClient?: Redis) {
    this.limiters = new Map();
    this.defaultConfig = defaultConfig;
    this.redisClient = redisClient;
  }

  getLimiter(tenantId: string, customConfig?: Partial<RateLimitConfig>): RateLimiter {
    const existing = this.limiters.get(tenantId);
    if (existing) {
      if (customConfig) {
        existing.updateConfig(customConfig);
      }
      return existing;
    }

    const config: RateLimitConfig = {
      ...this.defaultConfig,
      keyPrefix: `rate_limit:${tenantId}:`,
      ...customConfig
    };

    const limiter = new RateLimiter(config, this.redisClient);
    this.limiters.set(tenantId, limiter);
    return limiter;
  }

  async checkTenantLimit(tenantId: string, customConfig?: Partial<RateLimitConfig>): Promise<RateLimitInfo> {
    const limiter = this.getLimiter(tenantId, customConfig);
    return limiter.checkLimit(tenantId);
  }

  async checkUserLimit(tenantId: string, userId: string): Promise<RateLimitInfo> {
    const limiter = this.getLimiter(tenantId);
    return limiter.checkLimit(`user:${userId}`);
  }

  async checkIpLimit(tenantId: string, ip: string): Promise<RateLimitInfo> {
    const limiter = this.getLimiter(tenantId);
    return limiter.checkLimit(`ip:${ip}`);
  }

  async checkEndpointLimit(tenantId: string, endpoint: string): Promise<RateLimitInfo> {
    const limiter = this.getLimiter(tenantId);
    return limiter.checkLimit(`endpoint:${endpoint}`);
  }

  removeLimiter(tenantId: string): void {
    this.limiters.delete(tenantId);
  }

  clearAll(): void {
    this.limiters.clear();
  }
}
