import NodeCache from 'node-cache';
import Redis from 'ioredis';
import { EventEmitter, AsyncQueue } from '../utils';
import logger from '../utils/logger';
import { CacheConfig, CacheEntry } from '../types';

export interface CacheStats {
  hits: number;
  misses: number;
  evictions: number;
  sets: number;
  size: number;
  max_size: number;
}

export interface CacheEventData<T = any> {
  key: string;
  value?: T;
  ttl?: number;
  hit?: boolean;
  reason?: string;
  timestamp: number;
  cacheName: string;
}

export interface CacheInvalidationEvent {
  event: string;
  data: any;
  affectedCaches: string[];
  timestamp: number;
}

export interface CacheOperationEvent {
  operation: 'get' | 'set' | 'delete' | 'clear' | 'evict' | 'expire';
  key: string;
  cacheName: string;
  success: boolean;
  duration: number;
  error?: string;
}

export interface CacheEvents<T> {
  'cache.set': CacheEventData<T>;
  'cache.get': CacheEventData<T>;
  'cache.delete': CacheEventData<T>;
  'cache.evict': CacheEventData<T>;
  'cache.expire': CacheEventData<T>;
  'cache.clear': { cacheName: string; timestamp: number };
  'cache.operation': CacheOperationEvent;
  'cache.invalidation': CacheInvalidationEvent;
}

export type EventHandler<T = any> = (data: T) => void | Promise<void>;

export interface EventSubscription {
  id: string;
  event: string;
  handler: EventHandler;
  once: boolean;
}

export class EventBus {
  private subscribers: Map<string, Set<EventHandler>> = new Map();
  private asyncHandlers: Map<string, AsyncQueue<{ event: string; data: any }>> = new Map();
  private processing: Map<string, boolean> = new Map();

  constructor() {
    this.startAsyncProcessing();
  }

  on<T = any>(event: string, handler: EventHandler<T>): () => void {
    if (!this.subscribers.has(event)) {
      this.subscribers.set(event, new Set());
    }
    this.subscribers.get(event)!.add(handler as EventHandler);
    return () => this.off(event, handler);
  }

  off<T = any>(event: string, handler: EventHandler<T>): void {
    this.subscribers.get(event)?.delete(handler as EventHandler);
  }

  once<T = any>(event: string, handler: EventHandler<T>): () => void {
    const wrappedHandler = (data: T) => {
      handler(data);
      this.off(event, wrappedHandler);
    };
    return this.on(event, wrappedHandler);
  }

  emit<T = any>(event: string, data: T): void {
    this.syncEmit(event, data);
    this.asyncEmit(event, data);
  }

  syncEmit<T = any>(event: string, data: T): void {
    const handlers = this.subscribers.get(event);
    if (handlers) {
      for (const handler of handlers) {
        try {
          handler(data);
        } catch (error) {
          logger.error(`Error in sync event handler for ${event}:`, error);
        }
      }
    }
  }

  asyncEmit<T = any>(event: string, data: T): void {
    if (!this.asyncHandlers.has(event)) {
      this.asyncHandlers.set(event, new AsyncQueue());
    }
    this.asyncHandlers.get(event)!.enqueue({ event, data });
  }

  private startAsyncProcessing(): void {
    const processQueue = async (event: string) => {
      const queue = this.asyncHandlers.get(event);
      if (!queue) return;

      const process = async () => {
        while (true) {
          const item = await queue.dequeue();
          const handlers = this.subscribers.get(item.event);
          if (handlers) {
            for (const handler of handlers) {
              try {
                await handler(item.data);
              } catch (error) {
                logger.error(`Error in async event handler for ${item.event}:`, error);
              }
            }
          }
        }
      };

      if (!this.processing.get(event)) {
        this.processing.set(event, true);
        process();
      }
    };

    for (const event of this.asyncHandlers.keys()) {
      processQueue(event);
    }
  }

  getSubscriberCount(event: string): number {
    return this.subscribers.get(event)?.size || 0;
  }

  clear(): void {
    this.subscribers.clear();
    this.asyncHandlers.clear();
    this.processing.clear();
  }
}

export const globalEventBus = new EventBus();

export abstract class CacheLayer<T> extends EventEmitter<CacheEvents<T>> {
  protected name: string;
  protected stats: CacheStats;
  protected eventBus?: EventBus;

  constructor(name: string, eventBus?: EventBus) {
    super();
    this.name = name;
    this.eventBus = eventBus;
    this.stats = {
      hits: 0,
      misses: 0,
      evictions: 0,
      sets: 0,
      size: 0,
      max_size: 0,
    };
  }

  abstract get(key: string): Promise<T | undefined>;
  abstract set(key: string, value: T, ttl?: number): Promise<void>;
  abstract delete(key: string): Promise<boolean>;
  abstract has(key: string): Promise<boolean>;
  abstract clear(): Promise<void>;
  abstract keys(): Promise<string[]>;
  abstract getSize(): Promise<number>;

  getStats(): CacheStats {
    return { ...this.stats };
  }

  resetStats(): void {
    this.stats = {
      hits: 0,
      misses: 0,
      evictions: 0,
      sets: 0,
      size: 0,
      max_size: this.stats.max_size,
    };
  }

  getName(): string {
    return this.name;
  }

  protected emitGlobal(event: keyof CacheEvents<T>, data: any): void {
    if (this.eventBus) {
      this.eventBus.emit(event as string, data);
    }
  }

  protected createEventData(data: Partial<CacheEventData<T>>): CacheEventData<T> {
    return {
      timestamp: Date.now(),
      cacheName: this.name,
      ...data,
    } as CacheEventData<T>;
  }
}

export class InMemoryCache<T> extends CacheLayer<T> {
  private cache: Map<string, CacheEntry<T>>;
  private config: CacheConfig;

  constructor(name: string, config: Partial<CacheConfig> = {}, eventBus?: EventBus) {
    super(name, eventBus);
    this.config = {
      default_ttl: config.default_ttl || 300000,
      max_size: config.max_size || 10000,
      eviction_policy: config.eviction_policy || 'lru',
      namespace: config.namespace,
    };
    this.cache = new Map();
    this.stats.max_size = this.config.max_size;
  }

  async get(key: string): Promise<T | undefined> {
    const startTime = Date.now();
    const entry = this.cache.get(key);
    let hit = false;
    let value: T | undefined;

    if (!entry) {
      this.stats.misses++;
    } else if (Date.now() > entry.expires_at) {
      this.cache.delete(key);
      this.stats.size--;
      this.emit('cache.expire', this.createEventData({ key }));
      this.emitGlobal('cache.expire', this.createEventData({ key }));
      this.stats.misses++;
    } else {
      entry.access_count++;
      entry.last_accessed = Date.now();
      this.stats.hits++;
      hit = true;
      value = entry.value;
    }

    const duration = Date.now() - startTime;
    const eventData = this.createEventData({ key, hit });
    this.emit('cache.get', eventData);
    this.emitGlobal('cache.get', eventData);
    this.emit('cache.operation', {
      operation: 'get',
      key,
      cacheName: this.name,
      success: true,
      duration,
    });

    return value;
  }

  async set(key: string, value: T, ttl?: number): Promise<void> {
    const startTime = Date.now();
    const effectiveTtl = ttl || this.config.default_ttl;
    const existing = this.cache.get(key);
    let success = true;
    let error: string | undefined;

    try {
      if (existing) {
        this.cache.set(key, {
          ...existing,
          value,
          expires_at: Date.now() + effectiveTtl,
          access_count: existing.access_count + 1,
          last_accessed: Date.now(),
        });
      } else {
        if (this.cache.size >= this.config.max_size) {
          await this.evict();
        }
        this.cache.set(key, {
          key,
          value,
          created_at: Date.now(),
          expires_at: Date.now() + effectiveTtl,
          access_count: 1,
          last_accessed: Date.now(),
        });
        this.stats.size++;
      }
      this.stats.sets++;
      const eventData = this.createEventData({ key, value, ttl: effectiveTtl });
      this.emit('cache.set', eventData);
      this.emitGlobal('cache.set', eventData);
    } catch (e) {
      success = false;
      error = e instanceof Error ? e.message : String(e);
      logger.error(`InMemoryCache set error for key ${key}:`, e);
    }

    const duration = Date.now() - startTime;
    this.emit('cache.operation', {
      operation: 'set',
      key,
      cacheName: this.name,
      success,
      duration,
      error,
    });
  }

  async delete(key: string): Promise<boolean> {
    const startTime = Date.now();
    const deleted = this.cache.delete(key);
    if (deleted) {
      this.stats.size--;
      const eventData = this.createEventData({ key });
      this.emit('cache.delete', eventData);
      this.emitGlobal('cache.delete', eventData);
    }

    const duration = Date.now() - startTime;
    this.emit('cache.operation', {
      operation: 'delete',
      key,
      cacheName: this.name,
      success: deleted,
      duration,
    });

    return deleted;
  }

  async has(key: string): Promise<boolean> {
    const entry = this.cache.get(key);
    if (!entry) return false;
    if (Date.now() > entry.expires_at) {
      this.cache.delete(key);
      this.stats.size--;
      return false;
    }
    return true;
  }

  async clear(): Promise<void> {
    const startTime = Date.now();
    this.cache.clear();
    this.stats.size = 0;

    const duration = Date.now() - startTime;
    this.emit('cache.clear', { cacheName: this.name, timestamp: Date.now() });
    this.emitGlobal('cache.clear', { cacheName: this.name, timestamp: Date.now() });
    this.emit('cache.operation', {
      operation: 'clear',
      key: '*',
      cacheName: this.name,
      success: true,
      duration,
    });
  }

  async keys(): Promise<string[]> {
    return Array.from(this.cache.keys());
  }

  async getSize(): Promise<number> {
    return this.cache.size;
  }

  private async evict(): Promise<void> {
    if (this.cache.size === 0) return;

    const entries = Array.from(this.cache.values());
    let evictedEntry: CacheEntry<T>;

    switch (this.config.eviction_policy) {
      case 'lru':
        evictedEntry = entries.reduce((min, entry) =>
          entry.last_accessed < min.last_accessed ? entry : min
        );
        break;
      case 'lfu':
        evictedEntry = entries.reduce((min, entry) =>
          entry.access_count < min.access_count ? entry : min
        );
        break;
      case 'fifo':
        evictedEntry = entries.reduce((min, entry) =>
          entry.created_at < min.created_at ? entry : min
        );
        break;
      default:
        evictedEntry = entries.reduce((min, entry) =>
          entry.last_accessed < min.last_accessed ? entry : min
        );
    }

    this.cache.delete(evictedEntry.key);
    this.stats.size--;
    this.stats.evictions++;
    const eventData = this.createEventData({ key: evictedEntry.key, reason: this.config.eviction_policy });
    this.emit('cache.evict', eventData);
    this.emitGlobal('cache.evict', eventData);
    logger.debug(`Evicted key ${evictedEntry.key} from cache ${this.name}');
  }

  getEntry(key: string): CacheEntry<T> | undefined {
    return this.cache.get(key);
  }

  async cleanupExpired(): Promise<number> {
    const now = Date.now();
    let cleaned = 0;
    for (const [key, entry] of this.cache.entries()) {
      if (now > entry.expires_at) {
        this.cache.delete(key);
        this.stats.size--;
        cleaned++;
        const eventData = this.createEventData({ key });
        this.emit('cache.expire', eventData);
        this.emitGlobal('cache.expire', eventData);
      }
    }
    if (cleaned > 0) {
      logger.debug(`Cleaned ${cleaned} expired entries from cache ${this.name}`);
    }
    return cleaned;
  }
}

export class RedisCache<T> extends CacheLayer<T> {
  private redis: Redis;
  private config: CacheConfig;
  private keyPrefix: string;

  constructor(name: string, redisClient: Redis, config: Partial<CacheConfig> = {}, eventBus?: EventBus) {
    super(name, eventBus);
    this.redis = redisClient;
    this.config = {
      default_ttl: config.default_ttl || 300000,
      max_size: config.max_size || 100000,
      eviction_policy: config.eviction_policy || 'lru',
      namespace: config.namespace || 'cache',
    };
    this.keyPrefix = `${this.config.namespace}:${name}:`;
    this.stats.max_size = this.config.max_size;
  }

  private getFullKey(key: string): string {
    return `${this.keyPrefix}${key}`;
  }

  async get(key: string): Promise<T | undefined> {
    const startTime = Date.now();
    const fullKey = this.getFullKey(key);
    let value: T | undefined;
    let hit = false;
    let success = true;
    let error: string | undefined;

    try {
      const result = await this.redis.get(fullKey);
      if (result === null) {
        this.stats.misses++;
      } else {
        value = JSON.parse(result) as T;
        this.stats.hits++;
        hit = true;
      }
    } catch (e) {
      success = false;
      error = e instanceof Error ? e.message : String(e);
      logger.error(`Redis get error for key ${key}:`, e);
      this.stats.misses++;
    }

    const duration = Date.now() - startTime;
    const eventData = this.createEventData({ key, hit });
    this.emit('cache.get', eventData);
    this.emitGlobal('cache.get', eventData);
    this.emit('cache.operation', {
      operation: 'get',
      key,
      cacheName: this.name,
      success,
      duration,
      error,
    });

    return value;
  }

  async set(key: string, value: T, ttl?: number): Promise<void> {
    const startTime = Date.now();
    const fullKey = this.getFullKey(key);
    const effectiveTtl = ttl || this.config.default_ttl;
    let success = true;
    let error: string | undefined;

    try {
      await this.redis.set(fullKey, JSON.stringify(value), 'PX', effectiveTtl);
      this.stats.sets++;
      this.stats.size++;
      const eventData = this.createEventData({ key, value, ttl: effectiveTtl });
      this.emit('cache.set', eventData);
      this.emitGlobal('cache.set', eventData);
    } catch (e) {
      success = false;
      error = e instanceof Error ? e.message : String(e);
      logger.error(`Redis set error for key ${key}:`, e);
    }

    const duration = Date.now() - startTime;
    this.emit('cache.operation', {
      operation: 'set',
      key,
      cacheName: this.name,
      success,
      duration,
      error,
    });
  }

  async delete(key: string): Promise<boolean> {
    const startTime = Date.now();
    const fullKey = this.getFullKey(key);
    let deleted = false;
    let success = true;
    let error: string | undefined;

    try {
      const result = await this.redis.del(fullKey);
      deleted = result > 0;
      if (deleted) {
        this.stats.size--;
        const eventData = this.createEventData({ key });
        this.emit('cache.delete', eventData);
        this.emitGlobal('cache.delete', eventData);
      }
    } catch (e) {
      success = false;
      error = e instanceof Error ? e.message : String(e);
      logger.error(`Redis delete error for key ${key}:`, e);
    }

    const duration = Date.now() - startTime;
    this.emit('cache.operation', {
      operation: 'delete',
      key,
      cacheName: this.name,
      success,
      duration,
      error,
    });

    return deleted;
  }

  async has(key: string): Promise<boolean> {
    const fullKey = this.getFullKey(key);
    try {
      const result = await this.redis.exists(fullKey);
      return result > 0;
    } catch (error) {
      logger.error(`Redis exists error for key ${key}:`, error);
      return false;
    }
  }

  async clear(): Promise<void> {
    const startTime = Date.now();
    const pattern = `${this.keyPrefix}*`;
    let success = true;
    let error: string | undefined;

    try {
      const keys = await this.redis.keys(pattern);
      if (keys.length > 0) {
        await this.redis.del(...keys);
        this.stats.size = 0;
      }
      this.emit('cache.clear', { cacheName: this.name, timestamp: Date.now() });
      this.emitGlobal('cache.clear', { cacheName: this.name, timestamp: Date.now() });
    } catch (e) {
      success = false;
      error = e instanceof Error ? e.message : String(e);
      logger.error('Redis clear error:', e);
    }

    const duration = Date.now() - startTime;
    this.emit('cache.operation', {
      operation: 'clear',
      key: '*',
      cacheName: this.name,
      success,
      duration,
      error,
    });
  }

  async keys(): Promise<string[]> {
    const pattern = `${this.keyPrefix}*`;
    try {
      const keys = await this.redis.keys(pattern);
      return keys.map((k) => k.replace(this.keyPrefix, ''));
    } catch (error) {
      logger.error('Redis keys error:', error);
      return [];
    }
  }

  async getSize(): Promise<number> {
    const pattern = `${this.keyPrefix}*`;
    try {
      const keys = await this.redis.keys(pattern);
      this.stats.size = keys.length;
      return keys.length;
    } catch (error) {
      logger.error('Redis size error:', error);
      return 0;
    }
  }

  getRedisClient(): Redis {
    return this.redis;
  }
}

export class MultiLevelCache<T> extends CacheLayer<T> {
  private layers: CacheLayer<T>[];
  private config: {
    write_through: boolean;
    sync_on_miss: boolean;
  };

  constructor(
    name: string,
    layers: CacheLayer<T>[],
    options: Partial<{ write_through: boolean; sync_on_miss: boolean }> = {},
    eventBus?: EventBus
  ) {
    super(name, eventBus);
    this.layers = layers;
    this.config = {
      write_through: options.write_through ?? true,
      sync_on_miss: options.sync_on_miss ?? true,
    };
  }

  async get(key: string): Promise<T | undefined> {
    for (let i = 0; i < this.layers.length; i++) {
      const value = await this.layers[i].get(key);
      if (value !== undefined) {
        if (this.config.sync_on_miss && i > 0) {
          for (let j = 0; j < i; j++) {
            await this.layers[j].set(key, value);
          }
        }
        return value;
      }
    }
    return undefined;
  }

  async set(key: string, value: T, ttl?: number): Promise<void> {
    const promises = this.layers.map((layer) => layer.set(key, value, ttl));
    if (this.config.write_through) {
      await Promise.all(promises);
    } else {
      promises[0];
    }
  }

  async delete(key: string): Promise<boolean> {
    const results = await Promise.all(this.layers.map((layer) => layer.delete(key)));
    return results.some((r) => r);
  }

  async has(key: string): Promise<boolean> {
    for (const layer of this.layers) {
      if (await layer.has(key)) {
        return true;
      }
    }
    return false;
  }

  async clear(): Promise<void> {
    await Promise.all(this.layers.map((layer) => layer.clear()));
  }

  async keys(): Promise<string[]> {
    const allKeys = new Set<string>();
    for (const layer of this.layers) {
      const keys = await layer.keys();
      keys.forEach((k) => allKeys.add(k));
    }
    return Array.from(allKeys);
  }

  async getSize(): Promise<number> {
    const sizes = await Promise.all(this.layers.map((layer) => layer.getSize()));
    return sizes.reduce((sum, s) => sum + s, 0);
  }

  getLayer(index: number): CacheLayer<T> | undefined {
    return this.layers[index];
  }

  getLayerCount(): number {
    return this.layers.length;
  }

  override getStats(): CacheStats {
    const combined: CacheStats = {
      hits: 0,
      misses: 0,
      evictions: 0,
      sets: 0,
      size: 0,
      max_size: 0,
    };
    for (const layer of this.layers) {
      const stats = layer.getStats();
      combined.hits += stats.hits;
      combined.misses += stats.misses;
      combined.evictions += stats.evictions;
      combined.sets += stats.sets;
      combined.size += stats.size;
      combined.max_size += stats.max_size;
    }
    return combined;
  }
}

export class CacheInvalidationManager {
  private invalidationRules: Map<string, Array<{ pattern: RegExp; action: string; options?: any }>>;
  private eventBus: EventBus;

  constructor(eventBus?: EventBus) {
    this.invalidationRules = new Map();
    this.eventBus = eventBus || globalEventBus;
  }

  registerInvalidationRule(cacheName: string, pattern: RegExp, action: string, options?: any): void {
    if (!this.invalidationRules.has(cacheName)) {
      this.invalidationRules.set(cacheName, []);
    }
    this.invalidationRules.get(cacheName)!.push({ pattern, action, options });
    logger.info(`Registered invalidation rule for cache ${cacheName}: ${pattern}`);
  }

  async handleInvalidation(event: string, data: any, caches: Map<string, CacheLayer<any>>): Promise<void> {
    const affectedCaches: string[] = [];

    for (const [cacheName, rules] of this.invalidationRules.entries()) {
      const cache = caches.get(cacheName);
      if (!cache) continue;

      for (const rule of rules) {
        if (rule.pattern.test(event)) {
          await this.executeInvalidation(cache, rule, data);
          affectedCaches.push(cacheName);
        }
      }
    }

    if (affectedCaches.length > 0) {
      this.eventBus.emit('cache.invalidation', {
        event,
        data,
        affectedCaches,
        timestamp: Date.now(),
      });
    }
  }

  private async executeInvalidation(cache: CacheLayer<any>, rule: any, data: any): Promise<void> {
    switch (rule.action) {
      case 'delete':
        if (data.key) {
          await cache.delete(data.key);
          logger.debug(`Invalidated key ${data.key} in cache ${cache.getName()}`);
        }
        break;
      case 'delete_pattern':
        if (data.pattern) {
          const keys = await cache.keys();
          const regex = new RegExp(data.pattern);
          for (const key of keys) {
            if (regex.test(key)) {
              await cache.delete(key);
            }
          }
          logger.debug(`Invalidated keys matching ${data.pattern} in cache ${cache.getName()}`);
        }
        break;
      case 'clear':
        await cache.clear();
        logger.debug(`Cleared cache ${cache.getName()}`);
        break;
      default:
        logger.warn(`Unknown invalidation action: ${rule.action}`);
    }
  }

  clearRules(cacheName?: string): void {
    if (cacheName) {
      this.invalidationRules.delete(cacheName);
    } else {
      this.invalidationRules.clear();
    }
  }
}

export interface CacheEventOptions {
  eventBus?: EventBus;
  autoCleanup?: boolean;
  cleanupInterval?: number;
}

export class CacheManager {
  private caches: Map<string, CacheLayer<any>>;
  private invalidationManager: CacheInvalidationManager;
  private cleanupInterval: NodeJS.Timeout | null = null;
  private eventBus: EventBus;
  private options: CacheEventOptions;

  constructor(options: CacheEventOptions = {}) {
    this.options = {
      autoCleanup: false,
      cleanupInterval: 60000,
      ...options,
    };
    this.caches = new Map();
    this.eventBus = this.options.eventBus || globalEventBus;
    this.invalidationManager = new CacheInvalidationManager(this.eventBus);

    if (this.options.autoCleanup) {
      this.startCleanup(this.options.cleanupInterval);
    }
  }

  createInMemoryCache<T>(name: string, config?: Partial<CacheConfig>): InMemoryCache<T> {
    const cache = new InMemoryCache<T>(name, config, this.eventBus);
    this.caches.set(name, cache);
    return cache;
  }

  createRedisCache<T>(name: string, redisClient: Redis, config?: Partial<CacheConfig>): RedisCache<T> {
    const cache = new RedisCache<T>(name, redisClient, config, this.eventBus);
    this.eventBus = this.options.eventBus || globalEventBus;
    this.caches.set(name, cache);
    return cache;
  }

  createMultiLevelCache<T>(
    name: string,
    layers: CacheLayer<T>[],
    options?: any
  ): MultiLevelCache<T> {
    const cache = new MultiLevelCache<T>(name, layers, options, this.eventBus);
    this.caches.set(name, cache);
    return cache;
  }

  getCache<T>(name: string): CacheLayer<T> | undefined {
    return this.caches.get(name) as CacheLayer<T>;
  }

  removeCache(name: string): boolean {
    const cache = this.caches.get(name);
    if (cache) {
      cache.clear();
      this.caches.delete(name);
      return true;
    }
    return false;
  }

  getInvalidationManager(): CacheInvalidationManager {
    return this.invalidationManager;
  }

  getAllCacheNames(): string[] {
    return Array.from(this.caches.keys());
  }

  getAllStats(): Map<string, CacheStats> {
    const stats = new Map<string, CacheStats>();
    for (const [name, cache] of this.caches.entries()) {
      stats.set(name, cache.getStats());
    }
    return stats;
  }

  getEventBus(): EventBus {
    return this.eventBus;
  }

  on<T = any>(event: string, handler: EventHandler<T>): () => void {
    return this.eventBus.on(event, handler);
  }

  off<T = any>(event: string, handler: EventHandler<T>): void {
    this.eventBus.off(event, handler);
  }

  once<T = any>(event: string, handler: EventHandler<T>): () => void {
    return this.eventBus.once(event, handler);
  }

  emit<T = any>(event: string, data: T): void {
    this.eventBus.emit(event, data);
  }

  startCleanup(intervalMs: number = 60000): void {
    if (this.cleanupInterval) {
      clearInterval(this.cleanupInterval);
    }
    this.cleanupInterval = setInterval(() => {
      this.cleanupExpired();
    }, intervalMs);
    logger.info(`Started cache cleanup with interval: ${intervalMs}ms`);
  }

  stopCleanup(): void {
    if (this.cleanupInterval) {
      clearInterval(this.cleanupInterval);
      this.cleanupInterval = null;
      logger.info('Stopped cache cleanup');
    }
  }

  private async cleanupExpired(): Promise<void> {
    for (const [name, cache] of this.caches.entries()) {
      if (cache instanceof InMemoryCache) {
        await cache.cleanupExpired();
      }
    }
  }

  async clearAll(): Promise<void> {
    for (const cache of this.caches.values()) {
      await cache.clear();
    }
    this.stopCleanup();
    logger.info('Cleared all caches');
  }
}

const cacheManager = new CacheManager();

export default cacheManager;
