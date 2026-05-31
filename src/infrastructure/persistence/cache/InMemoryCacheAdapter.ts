import { ICachePort } from '../../../application/shared/ports/ICachePort';
import { injectable } from 'tsyringe';

interface CacheEntry {
  value: unknown;
  expiresAt?: number;
}

@injectable()
export class InMemoryCacheAdapter implements ICachePort {
  private cache: Map<string, CacheEntry> = new Map();

  async get<T>(key: string): Promise<T | null> {
    const entry = this.cache.get(key);
    if (!entry) return null;

    if (entry.expiresAt && Date.now() > entry.expiresAt) {
      this.cache.delete(key);
      return null;
    }

    return entry.value as T;
  }

  async set<T>(key: string, value: T, ttlSeconds?: number): Promise<void> {
    const entry: CacheEntry = { value };
    if (ttlSeconds) {
      entry.expiresAt = Date.now() + ttlSeconds * 1000;
    }
    this.cache.set(key, entry);
  }

  async del(key: string): Promise<void> {
    this.cache.delete(key);
  }

  async delPattern(pattern: string): Promise<void> {
    const regex = new RegExp(pattern.replace(/\*/g, '.*'));
    for (const key of this.cache.keys()) {
      if (regex.test(key)) {
        this.cache.delete(key);
      }
    }
  }

  async exists(key: string): Promise<boolean> {
    return this.cache.has(key);
  }

  async increment(key: string, amount: number = 1): Promise<number> {
    const current = (await this.get<number>(key)) || 0;
    const newValue = current + amount;
    await this.set(key, newValue);
    return newValue;
  }

  async expire(key: string, ttlSeconds: number): Promise<void> {
    const entry = this.cache.get(key);
    if (entry) {
      entry.expiresAt = Date.now() + ttlSeconds * 1000;
    }
  }

  clear(): void {
    this.cache.clear();
  }
}
