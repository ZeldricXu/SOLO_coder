import NodeCache from 'node-cache';
import { Cache, CacheEntry, CacheOptions, CacheStats } from '../../core/ports';

export class MemoryCache implements Cache {
  private cache: NodeCache;
  private hits = 0;
  private misses = 0;
  private evictions = 0;
  private tagIndex: Map<string, Set<string>> = new Map();

  constructor(defaultTTL: number = 3600) {
    this.cache = new NodeCache({
      stdTTL: defaultTTL,
      checkperiod: 600,
      useClones: false
    });

    this.cache.on('expired', (key: string) => this.handleEviction(key));
    this.cache.on('del', (key: string) => this.handleEviction(key));
  }

  private handleEviction(key: string): void {
    this.evictions++;
    this.removeFromTagIndex(key);
  }

  private removeFromTagIndex(key: string): void {
    for (const tags of this.tagIndex.values()) {
      tags.delete(key);
    }
  }

  private buildKey(key: string, namespace?: string): string {
    return namespace ? `${namespace}:${key}` : key;
  }

  async get<T>(key: string): Promise<T | null> {
    const entry = this.cache.get<CacheEntry<T>>(key);
    if (!entry) {
      this.misses++;
      return null;
    }

    if (entry.expiresAt < Date.now()) {
      this.cache.del(key);
      this.misses++;
      return null;
    }

    this.hits++;
    return entry.value;
  }

  async set<T>(key: string, value: T, options: CacheOptions = {}): Promise<void> {
    const fullKey = this.buildKey(key, options.namespace);
    const ttl = options.ttl || 3600;
    const entry: CacheEntry<T> = {
      value,
      expiresAt: Date.now() + ttl * 1000,
      createdAt: Date.now()
    };

    this.cache.set(fullKey, entry, ttl);

    if (options.tags) {
      for (const tag of options.tags) {
        if (!this.tagIndex.has(tag)) {
          this.tagIndex.set(tag, new Set());
        }
        this.tagIndex.get(tag)!.add(fullKey);
      }
    }
  }

  async delete(key: string): Promise<boolean> {
    const deleted = this.cache.del(key) > 0;
    if (deleted) {
      this.removeFromTagIndex(key);
    }
    return deleted;
  }

  async has(key: string): Promise<boolean> {
    return this.cache.has(key);
  }

  async clear(): Promise<void> {
    this.cache.flushAll();
    this.tagIndex.clear();
    this.hits = 0;
    this.misses = 0;
    this.evictions = 0;
  }

  async invalidateByTag(tag: string): Promise<number> {
    const keys = this.tagIndex.get(tag);
    if (!keys || keys.size === 0) {
      return 0;
    }

    const keyArray = Array.from(keys);
    const deleted = this.cache.del(keyArray);
    keys.clear();
    return deleted;
  }

  async invalidateByPattern(pattern: string): Promise<number> {
    const regex = new RegExp(pattern);
    const allKeys = this.cache.keys();
    const matchingKeys = allKeys.filter(key => regex.test(key));

    if (matchingKeys.length === 0) {
      return 0;
    }

    const deleted = this.cache.del(matchingKeys);
    for (const key of matchingKeys) {
      this.removeFromTagIndex(key);
    }
    return deleted;
  }

  getStats(): CacheStats {
    const size = this.cache.keys().length;
    const total = this.hits + this.misses;
    return {
      hits: this.hits,
      misses: this.misses,
      evictions: this.evictions,
      size,
      hitRate: total > 0 ? this.hits / total : 0
    };
  }
}
