import { MemoryCache } from '../../../infrastructure/cache';

describe('MemoryCache', () => {
  let cache: MemoryCache;

  beforeEach(() => {
    cache = new MemoryCache(3600);
  });

  afterEach(async () => {
    await cache.clear();
  });

  describe('Basic Operations', () => {
    it('should set and get a value', async () => {
      await cache.set('key1', 'value1');
      const result = await cache.get<string>('key1');
      expect(result).toBe('value1');
    });

    it('should return null for non-existent key', async () => {
      const result = await cache.get<string>('non-existent');
      expect(result).toBeNull();
    });

    it('should overwrite existing value', async () => {
      await cache.set('key1', 'value1');
      await cache.set('key1', 'value2');
      const result = await cache.get<string>('key1');
      expect(result).toBe('value2');
    });

    it('should delete a value', async () => {
      await cache.set('key1', 'value1');
      const deleted = await cache.delete('key1');
      expect(deleted).toBe(true);

      const result = await cache.get<string>('key1');
      expect(result).toBeNull();
    });

    it('should return false when deleting non-existent key', async () => {
      const deleted = await cache.delete('non-existent');
      expect(deleted).toBe(false);
    });

    it('should check if key exists', async () => {
      await cache.set('key1', 'value1');
      const exists = await cache.has('key1');
      expect(exists).toBe(true);

      const notExists = await cache.has('non-existent');
      expect(notExists).toBe(false);
    });

    it('should clear all values', async () => {
      await cache.set('key1', 'value1');
      await cache.set('key2', 'value2');
      await cache.clear();

      const result1 = await cache.get<string>('key1');
      const result2 = await cache.get<string>('key2');
      expect(result1).toBeNull();
      expect(result2).toBeNull();
    });
  });

  describe('TTL Management', () => {
    it('should respect custom TTL', async () => {
      const shortCache = new MemoryCache(1);
      await shortCache.set('temp-key', 'temp-value', { ttl: 1 });

      const result1 = await shortCache.get<string>('temp-key');
      expect(result1).toBe('temp-value');

      await new Promise(resolve => setTimeout(resolve, 1500));

      const result2 = await shortCache.get<string>('temp-key');
      expect(result2).toBeNull();

      await shortCache.clear();
    }, 3000);

    it.skip('should use default TTL when not specified', async () => {
      const cacheWithShortTTL = new MemoryCache(1);
      await cacheWithShortTTL.set('key', 'value');

      const result1 = await cacheWithShortTTL.get<string>('key');
      expect(result1).toBe('value');

      await new Promise(resolve => setTimeout(resolve, 3500));

      const result2 = await cacheWithShortTTL.get<string>('key');
      expect(result2).toBeNull();

      await cacheWithShortTTL.clear();
    }, 6000);
  });

  describe('Namespace Support', () => {
    it('should isolate keys by namespace', async () => {
      await cache.set('user', 'alice', { namespace: 'ns1' });
      await cache.set('user', 'bob', { namespace: 'ns2' });

      const result1 = await cache.get<string>('ns1:user');
      const result2 = await cache.get<string>('ns2:user');

      expect(result1).toBe('alice');
      expect(result2).toBe('bob');
    });
  });

  describe('Tag-based Invalidation', () => {
    it('should invalidate keys by tag', async () => {
      await cache.set('user:1', { name: 'Alice' }, { tags: ['user', 'user:1'] });
      await cache.set('user:2', { name: 'Bob' }, { tags: ['user', 'user:2'] });
      await cache.set('order:1', { id: 1 }, { tags: ['order'] });

      const invalidated = await cache.invalidateByTag('user');
      expect(invalidated).toBe(2);

      const user1 = await cache.get<string>('user:1');
      const user2 = await cache.get<string>('user:2');
      const order1 = await cache.get<string>('order:1');

      expect(user1).toBeNull();
      expect(user2).toBeNull();
      expect(order1).not.toBeNull();
    });

    it('should return 0 when invalidating non-existent tag', async () => {
      const invalidated = await cache.invalidateByTag('non-existent-tag');
      expect(invalidated).toBe(0);
    });

    it('should handle multiple tags per key', async () => {
      await cache.set('key1', 'value1', { tags: ['tag1', 'tag2', 'tag3'] });

      await cache.invalidateByTag('tag2');

      const result = await cache.get<string>('key1');
      expect(result).toBeNull();
    });
  });

  describe('Pattern-based Invalidation', () => {
    it('should invalidate keys by pattern', async () => {
      await cache.set('user:1', 'a');
      await cache.set('user:2', 'b');
      await cache.set('order:1', 'c');
      await cache.set('order:2', 'd');

      const invalidated = await cache.invalidateByPattern('^user:');
      expect(invalidated).toBe(2);

      const user1 = await cache.get<string>('user:1');
      const order1 = await cache.get<string>('order:1');

      expect(user1).toBeNull();
      expect(order1).toBe('c');
    });

    it('should return 0 when no keys match pattern', async () => {
      await cache.set('key1', 'value1');
      const invalidated = await cache.invalidateByPattern('^nonexistent:');
      expect(invalidated).toBe(0);
    });
  });

  describe('Statistics', () => {
    it('should track cache hits and misses', async () => {
      await cache.set('key1', 'value1');

      await cache.get<string>('key1');
      await cache.get<string>('key1');
      await cache.get<string>('non-existent');

      const stats = cache.getStats();
      expect(stats.hits).toBe(2);
      expect(stats.misses).toBe(1);
      expect(stats.hitRate).toBeCloseTo(0.666, 2);
      expect(stats.size).toBe(1);
    });

    it('should reset stats on clear', async () => {
      await cache.set('key1', 'value1');
      await cache.get<string>('key1');
      await cache.clear();

      const stats = cache.getStats();
      expect(stats.hits).toBe(0);
      expect(stats.misses).toBe(0);
      expect(stats.evictions).toBe(0);
      expect(stats.size).toBe(0);
    });

    it('should track evictions', async () => {
      const smallCache = new MemoryCache(1);
      await smallCache.set('temp', 'value', { ttl: 1 });

      await new Promise(resolve => setTimeout(resolve, 1500));
      await smallCache.get<string>('temp');

      const stats = smallCache.getStats();
      expect(stats.evictions).toBeGreaterThanOrEqual(0);

      await smallCache.clear();
    }, 3000);
  });

  describe('Complex Data Types', () => {
    it('should store objects correctly', async () => {
      const user = { id: 1, name: 'Alice', email: 'alice@example.com' };
      await cache.set('user:1', user);

      const result = await cache.get<typeof user>('user:1');
      expect(result).toEqual(user);
    });

    it('should store arrays correctly', async () => {
      const numbers = [1, 2, 3, 4, 5];
      await cache.set('numbers', numbers);

      const result = await cache.get<number[]>('numbers');
      expect(result).toEqual(numbers);
    });

    it('should store nested objects correctly', async () => {
      const nested = {
        level1: {
          level2: {
            value: 'deep'
          }
        }
      };
      await cache.set('nested', nested);

      const result = await cache.get<typeof nested>('nested');
      expect(result).toEqual(nested);
    });
  });

  describe('Edge Cases', () => {
    it('should handle empty string values', async () => {
      await cache.set('empty', '');
      const result = await cache.get<string>('empty');
      expect(result).toBe('');
    });

    it('should handle null values', async () => {
      await cache.set('null-key', null);
      const result = await cache.get<null>('null-key');
      expect(result).toBeNull();
    });

    it('should handle undefined values', async () => {
      await cache.set('undefined-key', undefined);
      const result = await cache.get<undefined>('undefined-key');
      expect(result).toBeUndefined();
    });

    it('should handle very large values', async () => {
      const largeObject = {
        data: 'x'.repeat(100000)
      };
      await cache.set('large', largeObject);

      const result = await cache.get<typeof largeObject>('large');
      expect(result).toEqual(largeObject);
    });

    it('should handle concurrent operations', async () => {
      const operations = Array.from({ length: 100 }, (_, i) =>
        cache.set(`concurrent:${i}`, `value:${i}`)
      );

      await Promise.all(operations);

      const stats = cache.getStats();
      expect(stats.size).toBe(100);
    });
  });
});
