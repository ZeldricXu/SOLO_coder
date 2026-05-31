import NodeCache from 'node-cache';
import Redis from 'ioredis';
import { RateLimitInfo, RateLimitResult } from './types';
import { BaseService } from '../common/base-service';

interface RateLimitStore {
  increment(key: string): Promise<number>;
  reset(key: string): Promise<void>;
  getRemaining(key: string, max: number): Promise<number>;
  getResetTime(key: string, windowMs: number): Promise<number>;
}

class MemoryStore implements RateLimitStore {
  private readonly cache: NodeCache;

  constructor(windowMs: number) {
    this.cache = new NodeCache({
      stdTTL: windowMs / 1000,
      checkperiod: windowMs / 1000 / 2,
    });
  }

  async increment(key: string): Promise<number> {
    const current = (this.cache.get<number>(key) || 0) + 1;
    this.cache.set(key, current);
    return current;
  }

  async reset(key: string): Promise<void> {
    this.cache.del(key);
  }

  async getRemaining(key: string, max: number): Promise<number> {
    const current = this.cache.get<number>(key) || 0;
    return Math.max(0, max - current);
  }

  async getResetTime(key: string, windowMs: number): Promise<number> {
    const ttl = this.cache.getTtl(key);
    return ttl ? Math.ceil((ttl - Date.now()) / 1000) : Math.ceil(windowMs / 1000);
  }
}

class RedisStore implements RateLimitStore {
  private readonly redis: Redis;

  constructor(redisUrl: string) {
    this.redis = new Redis(redisUrl);
  }

  async increment(key: string): Promise<number> {
    const count = await this.redis.incr(key);
    if (count === 1) {
      await this.redis.expire(key, 60);
    }
    return count;
  }

  async reset(key: string): Promise<void> {
    await this.redis.del(key);
  }

  async getRemaining(key: string, max: number): Promise<number> {
    const current = await this.redis.get(key);
    return Math.max(0, max - (parseInt(current || '0', 10)));
  }

  async getResetTime(key: string, _windowMs: number): Promise<number> {
    const ttl = await this.redis.ttl(key);
    return ttl > 0 ? ttl : 60;
  }
}

export interface RateLimitConfig {
  windowMs: number;
  maxRequests: number;
  keyPrefix: string;
}

export class RateLimiter extends BaseService {
  private readonly store: RateLimitStore;
  private readonly config: RateLimitConfig;

  constructor(config: RateLimitConfig, redisUrl?: string) {
    super('RateLimiter');
    this.config = config;
    this.store = redisUrl ? new RedisStore(redisUrl) : new MemoryStore(config.windowMs);
  }

  async checkLimit(clientId: string): Promise<RateLimitResult> {
    this.assertNotDestroyed();

    const key = `${this.config.keyPrefix}:${clientId}`;
    const count = await this.store.increment(key);
    const remaining = await this.store.getRemaining(key, this.config.maxRequests);
    const resetTime = await this.store.getResetTime(key, this.config.windowMs);

    return {
      allowed: count <= this.config.maxRequests,
      info: {
        remaining: Math.max(0, remaining),
        resetTime,
        limit: this.config.maxRequests,
      },
    };
  }

  async resetLimit(clientId: string): Promise<void> {
    this.assertNotDestroyed();
    const key = `${this.config.keyPrefix}:${clientId}`;
    await this.store.reset(key);
  }
}
