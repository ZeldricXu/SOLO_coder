import Redis from 'ioredis';
import NodeCache from 'node-cache';
import { ICache } from '@ports/index';
import { rootLogger } from '@modules/logging';
import { config } from '@config/index';

export class RedisCache implements ICache {
  private logger = rootLogger.child({ module: 'RedisCache' });
  private client: Redis;

  constructor() {
    this.client = new Redis({
      host: config.redis.host,
      port: config.redis.port,
      db: config.redis.db,
    });

    this.client.on('error', (err) => {
      this.logger.error('Redis cache error', { error: err.message });
    });
  }

  async get<T>(key: string): Promise<T | null> {
    try {
      const value = await this.client.get(key);
      return value ? JSON.parse(value) : null;
    } catch (error) {
      this.logger.error('Cache get failed', { key, error: (error as Error).message });
      return null;
    }
  }

  async set<T>(key: string, value: T, ttlMs?: number): Promise<void> {
    try {
      const serialized = JSON.stringify(value);
      if (ttlMs) {
        await this.client.set(key, serialized, 'PX', ttlMs);
      } else {
        await this.client.set(key, serialized);
      }
    } catch (error) {
      this.logger.error('Cache set failed', { key, error: (error as Error).message });
    }
  }

  async delete(key: string): Promise<boolean> {
    try {
      const result = await this.client.del(key);
      return result > 0;
    } catch (error) {
      this.logger.error('Cache delete failed', { key, error: (error as Error).message });
      return false;
    }
  }

  async exists(key: string): Promise<boolean> {
    try {
      const result = await this.client.exists(key);
      return result > 0;
    } catch (error) {
      this.logger.error('Cache exists failed', { key, error: (error as Error).message });
      return false;
    }
  }

  async incr(key: string, amount: number = 1): Promise<number> {
    try {
      return await this.client.incrby(key, amount);
    } catch (error) {
      this.logger.error('Cache incr failed', { key, error: (error as Error).message });
      return -1;
    }
  }

  async disconnect(): Promise<void> {
    await this.client.quit();
  }
}

export class MemoryCache implements ICache {
  private logger = rootLogger.child({ module: 'MemoryCache' });
  private cache: NodeCache;

  constructor(stdTTL: number = 0) {
    this.cache = new NodeCache({ stdTTL, checkperiod: 60 });
  }

  async get<T>(key: string): Promise<T | null> {
    const value = this.cache.get<T>(key);
    return value || null;
  }

  async set<T>(key: string, value: T, ttlMs?: number): Promise<void> {
    if (ttlMs) {
      this.cache.set(key, value, ttlMs / 1000);
    } else {
      this.cache.set(key, value);
    }
  }

  async delete(key: string): Promise<boolean> {
    return this.cache.del(key) > 0;
  }

  async exists(key: string): Promise<boolean> {
    return this.cache.has(key);
  }

  async incr(key: string, amount: number = 1): Promise<number> {
    const current = (this.cache.get<number>(key) || 0) + amount;
    this.cache.set(key, current);
    return current;
  }

  clear(): void {
    this.cache.flushAll();
  }
}

export const memoryCache = new MemoryCache();
