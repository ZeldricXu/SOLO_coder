import Redis from 'ioredis';
import NodeCache from 'node-cache';
import type { IRateLimiter } from '@ports/index';
import { config } from '@config/index';
import { rootLogger } from '@modules/logging';
import { nowEpoch } from '@utils/index';

interface RateLimitResult {
  allowed: boolean;
  remaining: number;
  resetTime: number;
}

const MEMORY_CACHE_CHECK_PERIOD = 60;

export class RateLimiter implements IRateLimiter {
  private readonly logger = rootLogger.child({ module: 'RateLimiter' });
  private readonly redisClient: Redis | null = null;
  private readonly memoryCache: NodeCache | null = null;
  private readonly useRedis: boolean;

  constructor(useRedis: boolean = false) {
    this.useRedis = useRedis;
    if (useRedis) {
      this.redisClient = this.createRedisClient();
    } else {
      this.memoryCache = this.createMemoryCache();
    }
  }

  async checkLimit(key: string): Promise<RateLimitResult> {
    const fullKey = this.buildFullKey(key);
    const now = nowEpoch();
    const windowStart = now - config.rateLimit.windowMs;

    const result = this.useRedis && this.redisClient
      ? await this.checkRedisLimit(fullKey, now, windowStart)
      : this.checkMemoryLimit(fullKey, now, windowStart);

    this.logLimitResult(key, result);
    return result;
  }

  async resetLimit(key: string): Promise<void> {
    const fullKey = this.buildFullKey(key);
    if (this.useRedis && this.redisClient) {
      await this.redisClient.del(fullKey);
    } else if (this.memoryCache) {
      this.memoryCache.del(fullKey);
    }
    this.logger.info('Rate limit reset', { key });
  }

  async disconnect(): Promise<void> {
    if (this.redisClient) {
      await this.redisClient.quit();
    }
    if (this.memoryCache) {
      this.memoryCache.flushAll();
      this.memoryCache.close();
    }
  }

  private createRedisClient(): Redis {
    const client = new Redis({
      host: config.redis.host,
      port: config.redis.port,
      db: config.redis.db,
    });
    client.on('error', (err) => {
      this.logger.error('Redis connection error', { error: err.message });
    });
    return client;
  }

  private createMemoryCache(): NodeCache {
    return new NodeCache({
      checkperiod: MEMORY_CACHE_CHECK_PERIOD,
    });
  }

  private buildFullKey(key: string): string {
    return `${config.rateLimit.keyPrefix}${key}`;
  }

  private async checkRedisLimit(
    key: string,
    now: number,
    windowStart: number,
  ): Promise<RateLimitResult> {
    const pipeline = this.redisClient!.pipeline();
    pipeline.zremrangebyscore(key, 0, windowStart);
    pipeline.zcard(key);
    pipeline.zadd(key, now, `${now}-${Math.random()}`);
    pipeline.pexpire(key, config.rateLimit.windowMs);

    const results = await pipeline.exec();
    const count = (results?.[1]?.[1] as number) || 0;

    return this.buildResult(count, now);
  }

  private checkMemoryLimit(
    key: string,
    now: number,
    windowStart: number,
  ): RateLimitResult {
    const timestamps = (this.memoryCache!.get(key) as number[]) || [];
    const filtered = timestamps.filter((ts) => ts > windowStart);

    const count = filtered.length;
    if (count < config.rateLimit.maxRequests) {
      filtered.push(now);
      this.memoryCache!.set(key, filtered, config.rateLimit.windowMs / 1000);
    }

    return this.buildResult(count, now);
  }

  private buildResult(count: number, now: number): RateLimitResult {
    const allowed = count < config.rateLimit.maxRequests;
    const remaining = Math.max(
      0,
      config.rateLimit.maxRequests - count - (allowed ? 1 : 0),
    );
    const resetTime = now + config.rateLimit.windowMs;

    return { allowed, remaining, resetTime };
  }

  private logLimitResult(key: string, result: RateLimitResult): void {
    if (!result.allowed) {
      this.logger.warn('Rate limit exceeded', {
        key,
        limit: config.rateLimit.maxRequests,
      });
    }
  }
}

export const createRateLimiter = (useRedis: boolean = false): IRateLimiter => {
  return new RateLimiter(useRedis);
};
