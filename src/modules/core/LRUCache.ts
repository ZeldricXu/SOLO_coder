import { CacheEntry, CacheStats } from './types';
import { ICacheService } from './interfaces';

interface CacheNode<K, V> {
  key: K;
  entry: CacheEntry<V>;
  prev?: CacheNode<K, V>;
  next?: CacheNode<K, V>;
}

export class LRUCache<K, V> implements ICacheService {
  private cache: Map<K, CacheNode<K, V>> = new Map();
  private head?: CacheNode<K, V>;
  private tail?: CacheNode<K, V>;
  private maxSize: number;
  private ttl: number;
  private stats = {
    hits: 0,
    misses: 0,
    evictions: 0,
  };
  private hotKeys: Map<K, number> = new Map();
  private readonly hotKeyCacheSize: number = 10;

  constructor(maxSize: number = 1000, ttl: number = 3600000) {
    this.maxSize = maxSize;
    this.ttl = ttl;
  }

  get(key: K): V | undefined {
    const node = this.cache.get(key);
    if (!node) {
      this.stats.misses++;
      return undefined;
    }

    const now = Date.now();
    if (now - node.entry.createdAt > this.ttl) {
      this.removeNode(node);
      this.cache.delete(key);
      this.stats.misses++;
      this.stats.evictions++;
      return undefined;
    }

    this.moveToHead(node);
    node.entry.accessedAt = now;
    node.entry.accessCount++;
    this.stats.hits++;

    this.updateHotKey(key, node.entry.accessCount);

    return node.entry.value;
  }

  set(key: K, value: V, hash?: string): void {
    const now = Date.now();
    
    const existingNode = this.cache.get(key);
    if (existingNode) {
      existingNode.entry.value = value;
      existingNode.entry.hash = hash || '';
      existingNode.entry.createdAt = now;
      existingNode.entry.accessedAt = now;
      existingNode.entry.accessCount = 1;
      this.moveToHead(existingNode);
      return;
    }

    if (this.cache.size >= this.maxSize) {
      this.evictLRU();
    }

    const newNode: CacheNode<K, V> = {
      key,
      entry: {
        key: String(key),
        value,
        createdAt: now,
        accessedAt: now,
        accessCount: 1,
        hash: hash || '',
      },
    };

    this.addToHead(newNode);
    this.cache.set(key, newNode);
    this.updateHotKey(key, 1);
  }

  has(key: K): boolean {
    const node = this.cache.get(key);
    if (!node) return false;
    
    const now = Date.now();
    if (now - node.entry.createdAt > this.ttl) {
      this.removeNode(node);
      this.cache.delete(key);
      return false;
    }
    
    return true;
  }

  delete(key: K): boolean {
    const node = this.cache.get(key);
    if (!node) return false;
    
    this.removeNode(node);
    this.cache.delete(key);
    this.hotKeys.delete(key);
    return true;
  }

  clear(): void {
    this.cache.clear();
    this.head = undefined;
    this.tail = undefined;
    this.hotKeys.clear();
    this.stats = {
      hits: 0,
      misses: 0,
      evictions: 0,
    };
  }

  invalidate(pattern?: string): number {
    if (!pattern) {
      const count = this.cache.size;
      this.clear();
      return count;
    }

    const regex = new RegExp(pattern);
    let invalidated = 0;
    const keysToDelete: K[] = [];

    for (const key of this.cache.keys()) {
      if (regex.test(String(key))) {
        keysToDelete.push(key);
      }
    }

    for (const key of keysToDelete) {
      this.delete(key);
      invalidated++;
    }

    return invalidated;
  }

  private addToHead(node: CacheNode<K, V>): void {
    if (!this.head) {
      this.head = node;
      this.tail = node;
      return;
    }

    node.next = this.head;
    this.head.prev = node;
    this.head = node;
    node.prev = undefined;
  }

  private removeNode(node: CacheNode<K, V>): void {
    if (node.prev) {
      node.prev.next = node.next;
    } else {
      this.head = node.next;
    }

    if (node.next) {
      node.next.prev = node.prev;
    } else {
      this.tail = node.prev;
    }
  }

  private moveToHead(node: CacheNode<K, V>): void {
    this.removeNode(node);
    this.addToHead(node);
  }

  private evictLRU(): void {
    if (!this.tail) return;

    const lruKey = this.tail.key;
    this.cache.delete(lruKey);
    this.removeNode(this.tail);
    this.hotKeys.delete(lruKey);
    this.stats.evictions++;
  }

  private updateHotKey(key: K, accessCount: number): void {
    this.hotKeys.set(key, accessCount);
    
    if (this.hotKeys.size > this.hotKeyCacheSize * 2) {
      const entries = Array.from(this.hotKeys.entries())
        .sort((a, b) => b[1] - a[1])
        .slice(0, this.hotKeyCacheSize);
      this.hotKeys = new Map(entries);
    }
  }

  getStats(): CacheStats {
    const total = this.stats.hits + this.stats.misses;
    const sortedHotKeys = Array.from(this.hotKeys.entries())
      .sort((a, b) => b[1] - a[1])
      .slice(0, 10)
      .map(([key]) => String(key));

    return {
      ...this.stats,
      size: this.cache.size,
      hitRate: total > 0 ? this.stats.hits / total : 0,
      hotKeys: sortedHotKeys,
    };
  }

  getEntries(): Array<{ key: K; entry: CacheEntry<V> }> {
    const result: Array<{ key: K; entry: CacheEntry<V> }> = [];
    let current = this.head;
    
    while (current) {
      result.push({ key: current.key, entry: current.entry });
      current = current.next;
    }
    
    return result;
  }

  getMaxSize(): number {
    return this.maxSize;
  }

  getTTL(): number {
    return this.ttl;
  }
}
