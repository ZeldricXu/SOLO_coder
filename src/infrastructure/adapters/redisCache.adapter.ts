import type { RedisOptions } from 'ioredis';
import type { CachePort, CacheConfig } from '@shared/cache';

interface RedisClient {
  get(key: string): Promise<string | null>;
  set(key: string, value: string): Promise<'OK' | null>;
  setex(key: string, seconds: number, value: string): Promise<'OK' | null>;
  del(...keys: string[]): Promise<number>;
  exists(key: string): Promise<number>;
  keys(pattern: string): Promise<string[]>;
  quit(): Promise<string>;
}

class SimpleRedisClient implements RedisClient {
  private store = new Map<string, { value: string; expiresAt?: number }>();

  async get(key: string): Promise<string | null> {
    const entry = this.store.get(key);
    if (!entry) return null;
    if (entry.expiresAt && Date.now() > entry.expiresAt) {
      this.store.delete(key);
      return null;
    }
    return entry.value;
  }

  async set(key: string, value: string): Promise<'OK'> {
    this.store.set(key, { value });
    return 'OK';
  }

  async setex(key: string, seconds: number, value: string): Promise<'OK'> {
    this.store.set(key, { value, expiresAt: Date.now() + seconds * 1000 });
    return 'OK';
  }

  async del(...keys: string[]): Promise<number> {
    let count = 0;
    for (const key of keys) {
      if (this.store.has(key)) {
        this.store.delete(key);
        count++;
      }
    }
    return count;
  }

  async exists(key: string): Promise<number> {
    const entry = this.store.get(key);
    if (!entry) return 0;
    if (entry.expiresAt && Date.now() > entry.expiresAt) {
      this.store.delete(key);
      return 0;
    }
    return 1;
  }

  async keys(pattern: string): Promise<string[]> {
    const regex = new RegExp('^' + pattern.replace(/\*/g, '.*') + '$');
    return Array.from(this.store.keys()).filter(k => regex.test(k));
  }

  async quit(): Promise<string> {
    return 'OK';
  }
}

export class RedisCacheAdapter implements CachePort {
  private client: RedisClient;
  private namespace: string;

  constructor(
    private readonly redisOptions: RedisOptions,
    config: CacheConfig = { defaultTTL: 3600, namespace: 'app' }
  ) {
    this.client = new SimpleRedisClient();
    this.namespace = config.namespace || 'app';
  }

  private getKey(key: string): string {
    return `${this.namespace}:${key}`;
  }

  async get<T = unknown>(key: string): Promise<T | null> {
    const value = await this.client.get(this.getKey(key));
    if (!value) return null;

    try {
      return JSON.parse(value) as T;
    } catch {
      return value as unknown as T;
    }
  }

  async set<T = unknown>(key: string, value: T, ttl?: number): Promise<void> {
    const serialized = typeof value === 'string' ? value : JSON.stringify(value);

    if (ttl !== undefined) {
      await this.client.setex(this.getKey(key), ttl, serialized);
    } else {
      await this.client.set(this.getKey(key), serialized);
    }
  }

  async delete(key: string): Promise<void> {
    await this.client.del(this.getKey(key));
  }

  async exists(key: string): Promise<boolean> {
    const result = await this.client.exists(this.getKey(key));
    return result > 0;
  }

  async clear(): Promise<void> {
    const keys = await this.client.keys(`${this.namespace}:*`);
    if (keys.length > 0) {
      await this.client.del(...keys);
    }
  }

  async disconnect(): Promise<void> {
    await this.client.quit();
  }

  getClient(): RedisClient {
    return this.client;
  }
}
