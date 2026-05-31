import { Cache, CacheManager, CacheStats, CacheType } from '../../core/ports';
import { MemoryCache } from './MemoryCache';
import { RedisCache } from './RedisCache';
import { HybridCache } from './HybridCache';

export class DefaultCacheManager implements CacheManager {
  private caches: Map<string, Cache> = new Map();
  private memoryCache: MemoryCache;
  private redisCache?: RedisCache;

  constructor(redisConfig?: { host: string; port: number; cluster?: boolean; nodes?: Array<{ host: string; port: number }> }) {
    this.memoryCache = new MemoryCache();

    if (redisConfig) {
      this.redisCache = new RedisCache(redisConfig);
    }
  }

  getCache(type: CacheType, namespace?: string): Cache {
    const cacheKey = `${type}:${namespace || 'default'}`;

    if (this.caches.has(cacheKey)) {
      return this.caches.get(cacheKey)!;
    }

    let cache: Cache;

    switch (type) {
      case 'memory':
        cache = this.memoryCache;
        break;
      case 'redis':
        if (!this.redisCache) {
          throw new Error('Redis cache is not configured');
        }
        cache = this.redisCache;
        break;
      case 'hybrid':
        if (!this.redisCache) {
          throw new Error('Redis cache is not configured for hybrid mode');
        }
        cache = new HybridCache(this.memoryCache, this.redisCache);
        break;
      default:
        cache = this.memoryCache;
    }

    this.caches.set(cacheKey, cache);
    return cache;
  }

  async invalidateAll(): Promise<void> {
    await Promise.all(
      Array.from(this.caches.values()).map(cache => cache.clear())
    );
  }

  getCombinedStats(): CacheStats {
    const allStats = Array.from(this.caches.values()).map(cache => cache.getStats());

    if (allStats.length === 0) {
      return {
        hits: 0,
        misses: 0,
        evictions: 0,
        size: 0,
        hitRate: 0
      };
    }

    const combined = allStats.reduce(
      (acc, stats) => ({
        hits: acc.hits + stats.hits,
        misses: acc.misses + stats.misses,
        evictions: acc.evictions + stats.evictions,
        size: acc.size + (stats.size > 0 ? stats.size : 0),
        hitRate: 0
      }),
      { hits: 0, misses: 0, evictions: 0, size: 0, hitRate: 0 }
    );

    const total = combined.hits + combined.misses;
    combined.hitRate = total > 0 ? combined.hits / total : 0;

    return combined;
  }
}
