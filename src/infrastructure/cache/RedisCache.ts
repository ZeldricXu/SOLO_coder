import Redis, { Cluster } from 'ioredis';
import { Cache, CacheEntry, CacheOptions, CacheStats } from '../../core/ports';
import { logger } from '../../common';

export class RedisCache implements Cache {
  private client: Redis | Cluster;
  private hits = 0;
  private misses = 0;
  private evictions = 0;

  constructor(redisOptions: { host: string; port: number; cluster?: boolean; nodes?: Array<{ host: string; port: number }> }) {
    if (redisOptions.cluster && redisOptions.nodes) {
      this.client = new Cluster(redisOptions.nodes);
    } else {
      this.client = new Redis({
        host: redisOptions.host,
        port: redisOptions.port,
        enableReadyCheck: true,
        maxRetriesPerRequest: 3
      });
    }

    this.client.on('error', (error) => {
      logger.error('Redis connection error', { error: error.message });
    });

    this.client.on('connect', () => {
      logger.info('Redis connected successfully');
    });
  }

  private buildKey(key: string, namespace?: string): string {
    return namespace ? `${namespace}:${key}` : key;
  }

  private getTagKey(tag: string): string {
    return `tag:${tag}`;
  }

  async get<T>(key: string): Promise<T | null> {
    try {
      const data = await this.client.get(key);
      if (!data) {
        this.misses++;
        return null;
      }

      const entry: CacheEntry<T> = JSON.parse(data);
      if (entry.expiresAt < Date.now()) {
        await this.client.del(key);
        this.misses++;
        return null;
      }

      this.hits++;
      return entry.value;
    } catch (error) {
      logger.error('Redis get error', { error: (error as Error).message, key });
      this.misses++;
      return null;
    }
  }

  async set<T>(key: string, value: T, options: CacheOptions = {}): Promise<void> {
    const fullKey = this.buildKey(key, options.namespace);
    const ttl = options.ttl || 3600;
    const entry: CacheEntry<T> = {
      value,
      expiresAt: Date.now() + ttl * 1000,
      createdAt: Date.now()
    };

    try {
      await this.client.setex(fullKey, ttl, JSON.stringify(entry));

      if (options.tags) {
        for (const tag of options.tags) {
          await this.client.sadd(this.getTagKey(tag), fullKey);
          await this.client.expire(this.getTagKey(tag), ttl);
        }
      }
    } catch (error) {
      logger.error('Redis set error', { error: (error as Error).message, key: fullKey });
    }
  }

  async delete(key: string): Promise<boolean> {
    try {
      const result = await this.client.del(key);
      return result > 0;
    } catch (error) {
      logger.error('Redis delete error', { error: (error as Error).message, key });
      return false;
    }
  }

  async has(key: string): Promise<boolean> {
    try {
      const result = await this.client.exists(key);
      return result > 0;
    } catch (error) {
      logger.error('Redis exists error', { error: (error as Error).message, key });
      return false;
    }
  }

  async clear(): Promise<void> {
    try {
      await this.client.flushdb();
      this.hits = 0;
      this.misses = 0;
      this.evictions = 0;
    } catch (error) {
      logger.error('Redis clear error', { error: (error as Error).message });
    }
  }

  async invalidateByTag(tag: string): Promise<number> {
    try {
      const tagKey = this.getTagKey(tag);
      const keys = await this.client.smembers(tagKey);

      if (keys.length === 0) {
        return 0;
      }

      const pipeline = this.client.pipeline();
      for (const key of keys) {
        pipeline.del(key);
      }
      pipeline.del(tagKey);

      const results = await pipeline.exec();
      const deletedCount = results?.filter(r => r[0] === null && r[1] as number > 0).length || 0;
      this.evictions += deletedCount;

      return deletedCount;
    } catch (error) {
      logger.error('Redis invalidateByTag error', { error: (error as Error).message, tag });
      return 0;
    }
  }

  async invalidateByPattern(pattern: string): Promise<number> {
    try {
      const keys: string[] = [];
      let cursor = '0';

      do {
        const result = await this.client.scan(cursor, 'MATCH', pattern, 'COUNT', 100);
        cursor = result[0];
        keys.push(...result[1]);
      } while (cursor !== '0');

      if (keys.length === 0) {
        return 0;
      }

      const deleted = await this.client.del(...keys);
      this.evictions += deleted;
      return deleted;
    } catch (error) {
      logger.error('Redis invalidateByPattern error', { error: (error as Error).message, pattern });
      return 0;
    }
  }

  getStats(): CacheStats {
    return {
      hits: this.hits,
      misses: this.misses,
      evictions: this.evictions,
      size: -1,
      hitRate: this.hits + this.misses > 0 ? this.hits / (this.hits + this.misses) : 0
    };
  }

  async disconnect(): Promise<void> {
    await this.client.quit();
  }
}
