import Redis, { RedisOptions } from 'ioredis';
import NodeCache from 'node-cache';
import { getConfig } from '../config';

export interface CacheClient {
  get: <T>(key: string) => Promise<T | null>;
  set: (key: string, value: unknown, ttl?: number) => Promise<void>;
  del: (key: string) => Promise<void>;
  has: (key: string) => Promise<boolean>;
  incr: (key: string) => Promise<number>;
  expire: (key: string, seconds: number) => Promise<void>;
  getKeys: (pattern: string) => Promise<string[]>;
}

class RedisCacheClient implements CacheClient {
  private client: Redis;

  constructor(redisUrl: string) {
    const options: RedisOptions = {
      enableReadyCheck: true,
      maxRetriesPerRequest: 3,
      lazyConnect: true,
      reconnectOnError: () => 1 as 1
    };
    this.client = new Redis(redisUrl, options);
  }

  async connect(): Promise<void> {
    await this.client.connect();
  }

  async disconnect(): Promise<void> {
    await this.client.quit();
  }

  async get<T>(key: string): Promise<T | null> {
    const value = await this.client.get(key);
    if (!value) return null;
    try {
      return JSON.parse(value) as T;
    } catch {
      return value as unknown as T;
    }
  }

  async set(key: string, value: unknown, ttl?: number): Promise<void> {
    const serialized = typeof value === 'string' ? value : JSON.stringify(value);
    if (ttl) {
      await this.client.set(key, serialized, 'EX', ttl);
    } else {
      await this.client.set(key, serialized);
    }
  }

  async del(key: string): Promise<void> {
    await this.client.del(key);
  }

  async has(key: string): Promise<boolean> {
    const result = await this.client.exists(key);
    return result === 1;
  }

  async incr(key: string): Promise<number> {
    return this.client.incr(key);
  }

  async expire(key: string, seconds: number): Promise<void> {
    await this.client.expire(key, seconds);
  }

  async getKeys(pattern: string): Promise<string[]> {
    return this.client.keys(pattern);
  }

  getNativeClient(): Redis {
    return this.client;
  }
}

class MemoryCacheClient implements CacheClient {
  private cache: NodeCache;

  constructor() {
    this.cache = new NodeCache({
      stdTTL: 300,
      checkperiod: 600,
      useClones: false
    });
  }

  async get<T>(key: string): Promise<T | null> {
    return this.cache.get<T>(key) ?? null;
  }

  async set(key: string, value: unknown, ttl?: number): Promise<void> {
    if (ttl) {
      this.cache.set(key, value, ttl);
    } else {
      this.cache.set(key, value);
    }
  }

  async del(key: string): Promise<void> {
    this.cache.del(key);
  }

  async has(key: string): Promise<boolean> {
    return this.cache.has(key);
  }

  async incr(key: string): Promise<number> {
    const current = this.cache.get<number>(key) ?? 0;
    const next = current + 1;
    this.cache.set(key, next);
    return next;
  }

  async expire(key: string, seconds: number): Promise<void> {
    const value = this.cache.get(key);
    if (value !== undefined) {
      this.cache.set(key, value, seconds);
    }
  }

  async getKeys(pattern: string): Promise<string[]> {
    const regex = new RegExp(pattern.replace(/\*/g, '.*'));
    return this.cache.keys().filter(k => regex.test(k));
  }
}

let cacheClient: CacheClient | null = null;

export const createCacheClient = (): CacheClient => {
  const config = getConfig();
  
  if (config.env === 'production' || config.env === 'staging') {
    cacheClient = new RedisCacheClient(config.redisUrl);
  } else {
    cacheClient = new MemoryCacheClient();
  }
  
  return cacheClient;
};

export const getCacheClient = (): CacheClient => {
  if (!cacheClient) {
    return createCacheClient();
  }
  return cacheClient;
};

export const generateCacheKey = (prefix: string, ...parts: string[]): string => {
  return `${prefix}:${parts.join(':')}`;
};

export const TTL = {
  SHORT: 60,
  MEDIUM: 300,
  LONG: 3600,
  DAY: 86400,
  WEEK: 604800
} as const;
