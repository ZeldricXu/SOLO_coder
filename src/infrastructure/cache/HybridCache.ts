import { Cache, CacheOptions, CacheStats } from '../../core/ports';
import { MemoryCache } from './MemoryCache';
import { RedisCache } from './RedisCache';

export class HybridCache implements Cache {
  private memoryCache: MemoryCache;
  private redisCache: RedisCache;
  private useMemoryFirst: boolean;

  constructor(memoryCache: MemoryCache, redisCache: RedisCache, useMemoryFirst: boolean = true) {
    this.memoryCache = memoryCache;
    this.redisCache = redisCache;
    this.useMemoryFirst = useMemoryFirst;
  }

  async get<T>(key: string): Promise<T | null> {
    if (this.useMemoryFirst) {
      const memoryValue = await this.memoryCache.get<T>(key);
      if (memoryValue !== null) {
        return memoryValue;
      }

      const redisValue = await this.redisCache.get<T>(key);
      if (redisValue !== null) {
        await this.memoryCache.set(key, redisValue, { ttl: 60 });
        return redisValue;
      }
    } else {
      const redisValue = await this.redisCache.get<T>(key);
      if (redisValue !== null) {
        return redisValue;
      }

      const memoryValue = await this.memoryCache.get<T>(key);
      if (memoryValue !== null) {
        return memoryValue;
      }
    }

    return null;
  }

  async set<T>(key: string, value: T, options: CacheOptions = {}): Promise<void> {
    await Promise.all([
      this.memoryCache.set(key, value, options),
      this.redisCache.set(key, value, options)
    ]);
  }

  async delete(key: string): Promise<boolean> {
    const [memoryResult, redisResult] = await Promise.all([
      this.memoryCache.delete(key),
      this.redisCache.delete(key)
    ]);
    return memoryResult || redisResult;
  }

  async has(key: string): Promise<boolean> {
    const [memoryHas, redisHas] = await Promise.all([
      this.memoryCache.has(key),
      this.redisCache.has(key)
    ]);
    return memoryHas || redisHas;
  }

  async clear(): Promise<void> {
    await Promise.all([
      this.memoryCache.clear(),
      this.redisCache.clear()
    ]);
  }

  async invalidateByTag(tag: string): Promise<number> {
    const [memoryCount, redisCount] = await Promise.all([
      this.memoryCache.invalidateByTag(tag),
      this.redisCache.invalidateByTag(tag)
    ]);
    return memoryCount + redisCount;
  }

  async invalidateByPattern(pattern: string): Promise<number> {
    const [memoryCount, redisCount] = await Promise.all([
      this.memoryCache.invalidateByPattern(pattern),
      this.redisCache.invalidateByPattern(pattern)
    ]);
    return memoryCount + redisCount;
  }

  getStats(): CacheStats {
    const memoryStats = this.memoryCache.getStats();
    const redisStats = this.redisCache.getStats();

    const totalHits = memoryStats.hits + redisStats.hits;
    const totalMisses = memoryStats.misses + redisStats.misses;

    return {
      hits: totalHits,
      misses: totalMisses,
      evictions: memoryStats.evictions + redisStats.evictions,
      size: memoryStats.size,
      hitRate: totalHits + totalMisses > 0 ? totalHits / (totalHits + totalMisses) : 0
    };
  }
}
