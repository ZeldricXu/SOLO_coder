export class LRUCache<K, V> {
  private maxSize: number;
  private map: Map<K, V>;

  constructor(maxSize: number) {
    this.maxSize = maxSize;
    this.map = new Map();
  }

  get(key: K): V | undefined {
    const value = this.map.get(key);
    if (value !== undefined) {
      this.map.delete(key);
      this.map.set(key, value);
    }
    return value;
  }

  set(key: K, value: V): void {
    if (this.map.has(key)) {
      this.map.delete(key);
    }
    this.map.set(key, value);
    while (this.map.size > this.maxSize) {
      const firstKey = this.map.keys().next().value;
      if (firstKey !== undefined) {
        this.map.delete(firstKey);
      }
    }
  }

  has(key: K): boolean {
    return this.map.has(key);
  }

  delete(key: K): boolean {
    return this.map.delete(key);
  }

  clear(): void {
    this.map.clear();
  }

  get size(): number {
    return this.map.size;
  }

  invalidate(predicate: (key: K, value: V) => boolean): number {
    let count = 0;
    const toDelete: K[] = [];
    for (const [key, value] of this.map) {
      if (predicate(key, value)) {
        toDelete.push(key);
      }
    }
    for (const key of toDelete) {
      if (this.map.delete(key)) {
        count++;
      }
    }
    return count;
  }

  entries(): IterableIterator<[K, V]> {
    return this.map.entries();
  }

  keys(): IterableIterator<K> {
    return this.map.keys();
  }

  values(): IterableIterator<V> {
    return this.map.values();
  }

  forEach(callbackfn: (value: V, key: K, map: Map<K, V>) => void): void {
    this.map.forEach(callbackfn);
  }
}
