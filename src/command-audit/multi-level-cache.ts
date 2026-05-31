import { logger } from '../logging';
import NodeCache from 'node-cache';
import Redis from 'ioredis';

export type CacheLevel = 'L1' | 'L2' | 'BOTH';

export interface CacheConfig {
  l1TTL?: number;
  l2TTL?: number;
  l1MaxSize?: number;
  redisConfig?: {
    host: string;
    port: number;
    password?: string;
    db?: number;
  };
  preheatKeys?: string[];
  invalidationPatterns?: string[];
}

export interface CacheEntry<T> {
  value: T;
  cachedAt: number;
  expiresAt: number;
  source: CacheLevel;
  hitCount: number;
}

export interface CacheStats {
  l1Hits: number;
  l1Misses: number;
  l2Hits: number;
  l2Misses: number;
  l1Size: number;
  l2Size: number;
  preheatedKeys: number;
  invalidations: number;
}

export class MultiLevelCache<T = any> {
  private l1Cache: NodeCache;
  private l2Cache?: Redis;
  private config: Required<CacheConfig>;
  private stats: CacheStats = {
    l1Hits: 0,
    l1Misses: 0,
    l2Hits: 0,
    l2Misses: 0,
    l1Size: 0,
    l2Size: 0,
    preheatedKeys: 0,
    invalidations: 0
  };
  private keyPrefix = 'cmd_audit:';
  private l1Keys: Set<string> = new Set();

  constructor(config: CacheConfig = {}) {
    this.config = {
      l1TTL: 300,
      l2TTL: 3600,
      l1MaxSize: 10000,
      redisConfig: { host: 'localhost', port: 6379, db: 0 },
      preheatKeys: [],
      invalidationPatterns: [],
      ...config
    };

    this.l1Cache = new NodeCache({
      stdTTL: this.config.l1TTL,
      checkperiod: 60,
      maxKeys: this.config.l1MaxSize
    });

    this.l1Cache.on('expired', (key) => {
      this.l1Keys.delete(key);
    });

    if (this.config.redisConfig) {
      this.connectL2Cache();
    }
  }

  private async connectL2Cache(): Promise<void> {
    try {
      this.l2Cache = new Redis({
        host: this.config.redisConfig.host,
        port: this.config.redisConfig.port,
        password: this.config.redisConfig.password,
        db: this.config.redisConfig.db,
        retryStrategy: (times) => Math.min(times * 50, 2000),
        enableReadyCheck: true
      });

      this.l2Cache.on('connect', () => {
        logger.info('L2 Redis cache connected');
      });

      this.l2Cache.on('error', (error) => {
        logger.error('L2 Redis cache error', error);
      });

      this.l2Cache.on('ready', async () => {
        logger.info('L2 Redis cache ready');
        await this.preheatCache();
      });
    } catch (error) {
      logger.warn('Failed to initialize L2 cache, running with L1 only', { error: (error as Error).message });
    }
  }

  private async preheatCache(): Promise<void> {
    if (this.config.preheatKeys.length === 0 || !this.l2Cache) return;

    logger.info('Starting cache preheating', { keyCount: this.config.preheatKeys.length });

    for (const key of this.config.preheatKeys) {
      try {
        const value = await this.l2Cache.get(this.getL2Key(key));
        if (value) {
          const parsed = JSON.parse(value);
          this.l1Cache.set(key, parsed, this.config.l1TTL);
          this.l1Keys.add(key);
          this.stats.preheatedKeys++;
        }
      } catch (error) {
        logger.warn('Failed to preheat key', { key, error: (error as Error).message });
      }
    }

    logger.info('Cache preheating completed', { preheatedKeys: this.stats.preheatedKeys });
  }

  private getL2Key(key: string): string {
    return `${this.keyPrefix}${key}`;
  }

  async get(key: string, level: CacheLevel = 'BOTH'): Promise<CacheEntry<T> | null> {
    if (level === 'L1' || level === 'BOTH') {
      const l1Value = this.l1Cache.get<T>(key);
      if (l1Value !== undefined) {
        this.stats.l1Hits++;
        return this.createEntry(l1Value, 'L1');
      }
      this.stats.l1Misses++;
    }

    if ((level === 'L2' || level === 'BOTH') && this.l2Cache) {
      try {
        const l2Value = await this.l2Cache.get(this.getL2Key(key));
        if (l2Value) {
          this.stats.l2Hits++;
          const parsed = JSON.parse(l2Value);
          this.l1Cache.set(key, parsed, this.config.l1TTL);
          this.l1Keys.add(key);
          return this.createEntry(parsed, 'L2');
        }
      } catch (error) {
        logger.warn('L2 cache read failed', { error: (error as Error).message });
      }
      this.stats.l2Misses++;
    }

    return null;
  }

  async set(key: string, value: T, level: CacheLevel = 'BOTH', ttl?: { l1?: number; l2?: number }): Promise<void> {
    const l1TTL = ttl?.l1 || this.config.l1TTL;
    const l2TTL = ttl?.l2 || this.config.l2TTL;

    if (level === 'L1' || level === 'BOTH') {
      this.l1Cache.set(key, value, l1TTL);
      this.l1Keys.add(key);
    }

    if ((level === 'L2' || level === 'BOTH') && this.l2Cache) {
      try {
        await this.l2Cache.set(this.getL2Key(key), JSON.stringify(value), 'EX', l2TTL);
      } catch (error) {
        logger.warn('L2 cache write failed', { error: (error as Error).message });
      }
    }

    this.updateStats();
  }

  async delete(key: string, level: CacheLevel = 'BOTH'): Promise<boolean> {
    let deleted = false;

    if (level === 'L1' || level === 'BOTH') {
      deleted = this.l1Cache.del(key) > 0;
      this.l1Keys.delete(key);
    }

    if ((level === 'L2' || level === 'BOTH') && this.l2Cache) {
      try {
        const result = await this.l2Cache.del(this.getL2Key(key));
        deleted = deleted || result > 0;
      } catch (error) {
        logger.warn('L2 cache delete failed', { error: (error as Error).message });
      }
    }

    this.stats.invalidations++;
    return deleted;
  }

  async deletePattern(pattern: string): Promise<number> {
    let deletedCount = 0;

    for (const key of Array.from(this.l1Keys)) {
      if (key.includes(pattern)) {
        this.l1Cache.del(key);
        this.l1Keys.delete(key);
        deletedCount++;
      }
    }

    if (this.l2Cache) {
      try {
        const keys = await this.l2Cache.keys(`${this.keyPrefix}*${pattern}*`);
        if (keys.length > 0) {
          await this.l2Cache.del(...keys);
          deletedCount += keys.length;
        }
      } catch (error) {
        logger.warn('L2 pattern delete failed', { error: (error as Error).message });
      }
    }

    this.stats.invalidations += deletedCount;
    return deletedCount;
  }

  async invalidateByPatterns(): Promise<number> {
    let totalDeleted = 0;
    for (const pattern of this.config.invalidationPatterns) {
      totalDeleted += await this.deletePattern(pattern);
    }
    return totalDeleted;
  }

  async clear(level: CacheLevel = 'BOTH'): Promise<void> {
    if (level === 'L1' || level === 'BOTH') {
      this.l1Cache.flushAll();
      this.l1Keys.clear();
    }

    if ((level === 'L2' || level === 'BOTH') && this.l2Cache) {
      try {
        const keys = await this.l2Cache.keys(`${this.keyPrefix}*`);
        if (keys.length > 0) {
          await this.l2Cache.del(...keys);
        }
      } catch (error) {
        logger.warn('L2 cache clear failed', { error: (error as Error).message });
      }
    }
  }

  private createEntry(value: T, source: CacheLevel): CacheEntry<T> {
    const ttl = source === 'L1' ? this.config.l1TTL : this.config.l2TTL;
    return {
      value,
      cachedAt: Date.now(),
      expiresAt: Date.now() + ttl * 1000,
      source,
      hitCount: 1
    };
  }

  private updateStats(): void {
    this.stats.l1Size = this.l1Keys.size;
  }

  getStats(): CacheStats {
    this.updateStats();
    return { ...this.stats };
  }

  getL1Keys(): string[] {
    return Array.from(this.l1Keys);
  }

  async getL2Keys(pattern: string = '*'): Promise<string[]> {
    if (!this.l2Cache) return [];
    try {
      const keys = await this.l2Cache.keys(`${this.keyPrefix}${pattern}`);
      return keys.map(k => k.replace(this.keyPrefix, ''));
    } catch {
      return [];
    }
  }

  async close(): Promise<void> {
    this.l1Cache.close();
    if (this.l2Cache) {
      await this.l2Cache.quit();
    }
    logger.info('Multi-level cache closed');
  }
}

export const createMultiLevelCache = <T>(config?: CacheConfig): MultiLevelCache<T> => {
  return new MultiLevelCache<T>(config);
};
