import NodeCache from 'node-cache';
import type { CachePort, CacheConfig } from '@shared/cache';

export class NodeCacheAdapter implements CachePort {
  private cache: NodeCache;
  private namespace: string;

  constructor(config: CacheConfig = { defaultTTL: 3600, namespace: 'app' }) {
    this.cache = new NodeCache({
      stdTTL: config.defaultTTL,
      checkperiod: 120,
      useClones: false,
    });
    this.namespace = config.namespace || 'app';
  }

  private getKey(key: string): string {
    return `${this.namespace}:${key}`;
  }

  async get<T = unknown>(key: string): Promise<T | null> {
    const value = this.cache.get<T>(this.getKey(key));
    return value ?? null;
  }

  async set<T = unknown>(key: string, value: T, ttl?: number): Promise<void> {
    if (ttl !== undefined) {
      this.cache.set(this.getKey(key), value, ttl);
    } else {
      this.cache.set(this.getKey(key), value);
    }
  }

  async delete(key: string): Promise<void> {
    this.cache.del(this.getKey(key));
  }

  async exists(key: string): Promise<boolean> {
    return this.cache.has(this.getKey(key));
  }

  async clear(): Promise<void> {
    this.cache.flushAll();
  }

  getStats(): { keys: number; hits: number; misses: number } {
    const stats = this.cache.getStats();
    return {
      keys: stats.keys,
      hits: stats.hits,
      misses: stats.misses,
    };
  }
}
