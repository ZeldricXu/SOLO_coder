import Redis from 'ioredis';
import NodeCache from 'node-cache';
import { config } from '../config';

let redisClient: Redis | null = null;
let localCache: NodeCache | null = null;

export const getRedisClient = (): Redis | null => {
  if (!redisClient && config.redis.host) {
    try {
      redisClient = new Redis({
        host: config.redis.host,
        port: config.redis.port,
        password: config.redis.password,
        retryStrategy: (times: number) => {
          const delay = Math.min(times * 50, 2000);
          return delay;
        },
      });
    } catch (error) {
      console.error('Failed to create Redis client:', error);
      redisClient = null;
    }
  }
  return redisClient;
};

export const getLocalCache = (): NodeCache => {
  if (!localCache) {
    localCache = new NodeCache({
      stdTTL: 60,
      checkperiod: 120,
    });
  }
  return localCache;
};

export class CacheService {
  private static instance: CacheService;
  private redis: Redis | null;
  private local: NodeCache;

  private constructor() {
    this.redis = getRedisClient();
    this.local = getLocalCache();
  }

  static getInstance(): CacheService {
    if (!CacheService.instance) {
      CacheService.instance = new CacheService();
    }
    return CacheService.instance;
  }

  async get<T>(key: string): Promise<T | null> {
    if (this.redis) {
      const value = await this.redis.get(key);
      if (value) {
        return JSON.parse(value) as T;
      }
    }

    const localValue = this.local.get<T>(key);
    if (localValue) {
      return localValue;
    }

    return null;
  }

  async set(key: string, value: any, ttl: number = 3600): Promise<void> {
    const serialized = JSON.stringify(value);

    if (this.redis) {
      await this.redis.set(key, serialized, 'EX', ttl);
    }

    this.local.set(key, value, ttl);
  }

  async delete(key: string): Promise<void> {
    if (this.redis) {
      await this.redis.del(key);
    }
    this.local.del(key);
  }

  async has(key: string): Promise<boolean> {
    if (this.redis) {
      const exists = await this.redis.exists(key);
      if (exists === 1) return true;
    }
    return this.local.has(key);
  }

  async increment(key: string, amount: number = 1): Promise<number> {
    if (this.redis) {
      return await this.redis.incrby(key, amount);
    }
    
    const current = (this.local.get<number>(key) || 0) + amount;
    this.local.set(key, current);
    return current;
  }

  async expire(key: string, ttl: number): Promise<void> {
    if (this.redis) {
      await this.redis.expire(key, ttl);
    }
    this.local.ttl(key, ttl);
  }
}

export const cacheService = CacheService.getInstance();
export default cacheService;
