import Redis, { RedisOptions } from 'ioredis';
import { ICachePort } from '../../../application/shared/ports/ICachePort';
import { getConfig } from '../../config/AppConfig';
import { injectable } from 'tsyringe';

@injectable()
export class RedisCacheAdapter implements ICachePort {
  private client: Redis;

  constructor() {
    const config = getConfig();
    const options: RedisOptions = {
      enableReadyCheck: true,
      maxRetriesPerRequest: 3,
      lazyConnect: true,
      reconnectOnError: () => 1 as 1
    };
    this.client = new Redis(config.redisUrl, options);
  }

  async connect(): Promise<void> {
    await this.client.connect();
  }

  async disconnect(): Promise<void> {
    await this.client.disconnect();
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

  async set<T>(key: string, value: T, ttlSeconds?: number): Promise<void> {
    const serialized = typeof value === 'string' ? value : JSON.stringify(value);
    if (ttlSeconds) {
      await this.client.setex(key, ttlSeconds, serialized);
    } else {
      await this.client.set(key, serialized);
    }
  }

  async del(key: string): Promise<void> {
    await this.client.del(key);
  }

  async delPattern(pattern: string): Promise<void> {
    const keys = await this.client.keys(pattern);
    if (keys.length > 0) {
      await this.client.del(...keys);
    }
  }

  async exists(key: string): Promise<boolean> {
    const result = await this.client.exists(key);
    return result > 0;
  }

  async increment(key: string, amount: number = 1): Promise<number> {
    return this.client.incrby(key, amount);
  }

  async expire(key: string, ttlSeconds: number): Promise<void> {
    await this.client.expire(key, ttlSeconds);
  }
}
