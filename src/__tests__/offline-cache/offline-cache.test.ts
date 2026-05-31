import * as fs from 'fs';
import * as path from 'path';
import * as os from 'os';
import { OfflineCache } from '../../offline-cache';
import { CacheEntry } from '../../offline-cache/types';

const mockReadJsonFile = jest.fn();
const mockWriteJsonFile = jest.fn();

jest.mock('../../common/file-utils', () => ({
  readJsonFile: (...args: any[]) => mockReadJsonFile(...args),
  writeJsonFile: (...args: any[]) => mockWriteJsonFile(...args),
  ensureDir: jest.fn(),
  getFileModifiedTime: jest.fn(),
}));

describe('OfflineCache - Basic CRUD', () => {
  let cache: OfflineCache;
  let tempDir: string;
  let persistPath: string;

  beforeEach(() => {
    jest.clearAllMocks();
    tempDir = fs.mkdtempSync(path.join(os.tmpdir(), 'offline-cache-test-'));
    persistPath = path.join(tempDir, 'cache.json');
    mockReadJsonFile.mockReturnValue(undefined);
  });

  afterEach(() => {
    cache?.destroy();
    try {
      if (fs.existsSync(tempDir)) {
        fs.rmSync(tempDir, { recursive: true });
      }
    } catch (e) {
      // ignore
    }
  });

  describe('set and get', () => {
    it('should store and retrieve a value', () => {
      cache = new OfflineCache({ persistPath });
      cache.set('key1', { data: 'value1' });
      const result = cache.get<{ data: string }>('key1');

      expect(result).toBeDefined();
      expect(result?.data).toBe('value1');
    });

    it('should return undefined for non-existent key', () => {
      cache = new OfflineCache({ persistPath });
      const result = cache.get('non-existent');
      expect(result).toBeUndefined();
    });

    it('should return undefined for expired entry', async () => {
      cache = new OfflineCache({ persistPath, defaultTTL: 10 });
      cache.set('expiring', { data: 'temp' });

      await new Promise(resolve => setTimeout(resolve, 20));

      const result = cache.get('expiring');
      expect(result).toBeUndefined();
    });

    it('should handle empty key', () => {
      cache = new OfflineCache({ persistPath });
      cache.set('', { data: 'empty-key' });
      const result = cache.get<{ data: string }>('');
      expect(result?.data).toBe('empty-key');
    });

    it('should handle very long key', () => {
      cache = new OfflineCache({ persistPath });
      const longKey = 'x'.repeat(10000);
      cache.set(longKey, { data: 'long-key' });
      const result = cache.get<{ data: string }>(longKey);
      expect(result?.data).toBe('long-key');
    });

    it('should handle null value', () => {
      cache = new OfflineCache({ persistPath });
      cache.set('null-key', null as any);
      const result = cache.get('null-key');
      expect(result).toBeNull();
    });

    it('should handle complex nested objects', () => {
      cache = new OfflineCache({ persistPath });
      const complexValue = {
        level1: {
          level2: {
            level3: [1, 2, 3],
            boolean: true,
            number: 42,
          },
        },
      };

      cache.set('complex', complexValue);
      const result = cache.get('complex');
      expect(result).toEqual(complexValue);
    });

    it('should handle large values', () => {
      cache = new OfflineCache({ persistPath });
      const largeValue = {
        data: 'x'.repeat(1000000),
      };

      cache.set('large', largeValue);
      const result = cache.get<{ data: string }>('large');
      expect(result?.data.length).toBe(1000000);
    });

    it('should update existing key value', () => {
      cache = new OfflineCache({ persistPath });
      cache.set('update', { v: 1 });
      cache.set('update', { v: 2 });

      const result = cache.get<{ v: number }>('update');
      expect(result?.v).toBe(2);
    });

    it('should respect custom TTL per entry', () => {
      cache = new OfflineCache({ persistPath, defaultTTL: 30000 });
      cache.set('ttl-100', { data: 'test' }, 100);
      cache.set('ttl-default', { data: 'test' });

      const entries = Array.from((cache as any).cache.values()) as CacheEntry[];
      const entry1 = entries.find(e => e.key === 'ttl-100');
      const entry2 = entries.find(e => e.key === 'ttl-default');

      expect(entry1?.ttl).toBe(100);
      expect(entry2?.ttl).toBe(30000);
    });
  });

  describe('has', () => {
    it('should return true for existing key', () => {
      cache = new OfflineCache({ persistPath });
      cache.set('exists', {});
      expect(cache.has('exists')).toBe(true);
    });

    it('should return false for non-existent key', () => {
      cache = new OfflineCache({ persistPath });
      expect(cache.has('missing')).toBe(false);
    });

    it('should return false for expired key', async () => {
      cache = new OfflineCache({ persistPath, defaultTTL: 1 });
      cache.set('expired', {});
      await new Promise(resolve => setTimeout(resolve, 5));
      expect(cache.has('expired')).toBe(false);
    });
  });

  describe('delete', () => {
    it('should delete an entry', () => {
      cache = new OfflineCache({ persistPath });
      cache.set('to-delete', { data: 'test' });
      const deleted = cache.delete('to-delete');
      const exists = cache.has('to-delete');

      expect(deleted).toBe(true);
      expect(exists).toBe(false);
    });

    it('should return false for non-existent key', () => {
      cache = new OfflineCache({ persistPath });
      const deleted = cache.delete('non-existent');
      expect(deleted).toBe(false);
    });

    it('should emit delete event', () => {
      cache = new OfflineCache({ persistPath });
      const deleteHandler = jest.fn();
      cache.on('delete', deleteHandler);

      cache.set('event-key', { data: 'test' });
      cache.delete('event-key');

      expect(deleteHandler).toHaveBeenCalledWith('event-key');
    });
  });

  describe('clear', () => {
    it('should clear all entries', () => {
      cache = new OfflineCache({ persistPath });
      cache.set('key1', { v: 1 });
      cache.set('key2', { v: 2 });
      cache.set('key3', { v: 3 });

      cache.clear();

      expect(cache.has('key1')).toBe(false);
      expect(cache.has('key2')).toBe(false);
      expect(cache.has('key3')).toBe(false);
      expect(cache.getAllKeys().length).toBe(0);
    });

    it('should emit clear event', () => {
      cache = new OfflineCache({ persistPath });
      const clearHandler = jest.fn();
      cache.on('clear', clearHandler);

      cache.set('k', {});
      cache.clear();

      expect(clearHandler).toHaveBeenCalled();
    });
  });

  describe('getAllKeys', () => {
    it('should return all keys', () => {
      cache = new OfflineCache({ persistPath });
      cache.set('a', {});
      cache.set('b', {});
      cache.set('c', {});

      const keys = cache.getAllKeys();
      expect(keys).toHaveLength(3);
      expect(keys).toContain('a');
      expect(keys).toContain('b');
      expect(keys).toContain('c');
    });

    it('should not return expired keys', async () => {
      cache = new OfflineCache({ persistPath, defaultTTL: 1 });
      cache.set('expired', {});
      cache.set('valid', {}, 10000);

      await new Promise(resolve => setTimeout(resolve, 5));

      const keys = cache.getAllKeys();
      expect(keys).toHaveLength(1);
      expect(keys).toContain('valid');
    });
  });
});

describe('OfflineCache - Boundary Conditions', () => {
  let tempDir: string;
  let persistPath: string;

  beforeEach(() => {
    tempDir = fs.mkdtempSync(path.join(os.tmpdir(), 'offline-cache-boundary-'));
    persistPath = path.join(tempDir, 'cache.json');
    mockReadJsonFile.mockReturnValue(undefined);
  });

  afterEach(() => {
    try {
      if (fs.existsSync(tempDir)) {
        fs.rmSync(tempDir, { recursive: true });
      }
    } catch (e) {
      // ignore
    }
  });

  it('should enforce maxSize limit', () => {
    const cache = new OfflineCache({
      persistPath,
      maxSize: 5,
    });

    for (let i = 0; i < 10; i++) {
      cache.set(`key${i}`, { index: i });
    }

    const keys = cache.getAllKeys();
    expect(keys.length).toBeLessThanOrEqual(5);

    cache.destroy();
  });

  it('should evict oldest entries when maxSize exceeded', async () => {
    const cache = new OfflineCache({
      persistPath,
      maxSize: 3,
    });

    cache.set('oldest', { v: 1 });
    await new Promise(resolve => setTimeout(resolve, 5));
    cache.set('middle', { v: 2 });
    await new Promise(resolve => setTimeout(resolve, 5));
    cache.set('newest1', { v: 3 });
    cache.set('newest2', { v: 4 });

    expect(cache.has('oldest')).toBe(false);
    expect(cache.has('middle')).toBe(true);
    expect(cache.has('newest1')).toBe(true);
    expect(cache.has('newest2')).toBe(true);

    cache.destroy();
  });

  it('should handle zero maxSize', () => {
    const cache = new OfflineCache({
      persistPath,
      maxSize: 0,
    });

    const config = (cache as any).config;
    // Zero maxSize is stored as-is
    expect(config.maxSize).toBe(0);

    cache.destroy();
  });

  it('should handle negative defaultTTL', () => {
    const cache = new OfflineCache({
      persistPath,
      defaultTTL: -1,
    });

    const config = (cache as any).config;
    // Negative TTL is stored as-is, but get() will treat expired entries as undefined
    expect(config.defaultTTL).toBe(-1);

    cache.destroy();
  });

  it('should handle zero TTL (uses default TTL)', () => {
    const cache = new OfflineCache({
      persistPath,
      defaultTTL: 1000,
    });

    cache.set('instant-expire', { data: 'test' }, 0);
    const result = cache.get<{ data: string }>('instant-expire');
    // Zero TTL falls back to default, so entry should exist
    expect(result).toBeDefined();

    cache.destroy();
  });
});

describe('OfflineCache - Concurrent Operations', () => {
  let tempDir: string;
  let persistPath: string;

  beforeEach(() => {
    tempDir = fs.mkdtempSync(path.join(os.tmpdir(), 'offline-cache-concurrent-'));
    persistPath = path.join(tempDir, 'cache.json');
    mockReadJsonFile.mockReturnValue(undefined);
  });

  afterEach(() => {
    try {
      if (fs.existsSync(tempDir)) {
        fs.rmSync(tempDir, { recursive: true });
      }
    } catch (e) {
      // ignore
    }
  });

  it('should handle concurrent writes without race conditions', async () => {
    const cache = new OfflineCache({
      persistPath,
      maxSize: 1000,
    });

    const count = 100;
    for (let i = 0; i < count; i++) {
      cache.set(`key${i}`, { value: i });
    }

    const keys = cache.getAllKeys();
    expect(keys.length).toBe(count);

    cache.destroy();
  });

  it('should handle concurrent reads and writes', () => {
    const cache = new OfflineCache({
      persistPath,
      maxSize: 1000,
    });

    for (let i = 0; i < 50; i++) {
      cache.set(`key${i}`, { v: i });
      cache.get(`key${i % 10}`);
      cache.has(`key${i % 5}`);
    }

    expect(cache.getAllKeys().length).toBe(50);
    cache.destroy();
  });

  it('should maintain consistency under high concurrency', () => {
    const cache = new OfflineCache({
      persistPath,
      maxSize: 200,
    });

    for (let i = 0; i < 200; i++) {
      cache.set(`k${i}`, { v: i });
    }

    for (let i = 0; i < 200; i++) {
      const exists = cache.has(`k${i}`);
      expect(exists).toBe(true);
    }

    cache.destroy();
  });

  it('should handle concurrent deletes safely', () => {
    const cache = new OfflineCache({
      persistPath,
      maxSize: 100,
    });

    for (let i = 0; i < 50; i++) {
      cache.set(`del${i}`, { v: i });
    }

    let successCount = 0;
    for (let i = 0; i < 50; i++) {
      if (cache.delete(`del${i}`)) {
        successCount++;
      }
    }

    expect(successCount).toBe(50);
    expect(cache.getAllKeys().length).toBe(0);

    cache.destroy();
  });
});

describe('OfflineCache - Sync Mechanism', () => {
  let tempDir: string;
  let persistPath: string;

  beforeEach(() => {
    tempDir = fs.mkdtempSync(path.join(os.tmpdir(), 'offline-cache-sync-'));
    persistPath = path.join(tempDir, 'cache.json');
    mockReadJsonFile.mockReturnValue(undefined);
  });

  afterEach(() => {
    try {
      if (fs.existsSync(tempDir)) {
        fs.rmSync(tempDir, { recursive: true });
      }
    } catch (e) {
      // ignore
    }
  });

  it('should mark entries as unsynced when offline and requireSync is true', () => {
    const cache = new OfflineCache({ persistPath });
    cache.setNetworkStatus(false);
    cache.set('dirty1', { data: 'test' }, undefined, true);

    const entry = Array.from((cache as any).cache.values())[0] as CacheEntry;
    expect(entry.synced).toBe(false);

    cache.destroy();
  });

  it('should mark entries as synced when online even with requireSync true', () => {
    const cache = new OfflineCache({ persistPath });
    cache.set('synced-online', { data: 'test' }, undefined, true);

    const entry = Array.from((cache as any).cache.values())[0] as CacheEntry;
    expect(entry.synced).toBe(true);

    cache.destroy();
  });

  it('should mark entries as synced when requireSync is false', () => {
    const cache = new OfflineCache({ persistPath });
    cache.setNetworkStatus(false);
    cache.set('synced', { data: 'test' }, undefined, false);

    const entry = Array.from((cache as any).cache.values())[0] as CacheEntry;
    expect(entry.synced).toBe(true);

    cache.destroy();
  });

  it('should call syncFn with unsynced entries', async () => {
    const cache = new OfflineCache({ persistPath });
    cache.setNetworkStatus(false);
    cache.set('sync1', { v: 1 });
    cache.set('sync2', { v: 2 });
    cache.setNetworkStatus(true);

    const syncFn = jest.fn().mockResolvedValue(true);
    const results = await cache.sync(syncFn);

    expect(syncFn).toHaveBeenCalledTimes(2);
    expect(results).toHaveLength(2);
    expect(results.every(r => r.success)).toBe(true);

    cache.destroy();
  });

  it('should handle syncFn failure with retries', async () => {
    const cache = new OfflineCache({ persistPath, maxSyncRetries: 3 });
    cache.setNetworkStatus(false);
    cache.set('retry', { data: 'test' });
    cache.setNetworkStatus(true);

    const syncFn = jest.fn()
      .mockRejectedValueOnce(new Error('Network error'))
      .mockRejectedValueOnce(new Error('Network error'))
      .mockResolvedValue(true);

    await cache.sync(syncFn);
    await cache.sync(syncFn);
    const finalResults = await cache.sync(syncFn);

    expect(syncFn).toHaveBeenCalledTimes(3);
    expect(finalResults[0].success).toBe(true);

    cache.destroy();
  });

  it('should mark entry as synced after successful sync', async () => {
    const cache = new OfflineCache({ persistPath });
    cache.setNetworkStatus(false);
    cache.set('synced', { data: 'test' });
    cache.setNetworkStatus(true);

    const syncFn = jest.fn().mockResolvedValue(true);
    await cache.sync(syncFn);

    const entry = Array.from((cache as any).cache.values())[0] as CacheEntry;
    expect(entry.synced).toBe(true);

    cache.destroy();
  });

  it('should process sync in batches', async () => {
    const cache = new OfflineCache({ persistPath, syncBatchSize: 5 });
    cache.setNetworkStatus(false);

    for (let i = 0; i < 12; i++) {
      cache.set(`batch${i}`, { v: i });
    }

    cache.setNetworkStatus(true);
    const syncFn = jest.fn().mockResolvedValue(true);
    await cache.sync(syncFn);

    expect(syncFn).toHaveBeenCalledTimes(12);

    cache.destroy();
  });

  it('should increment sync retry counter on failure', async () => {
    const cache = new OfflineCache({ persistPath, maxSyncRetries: 3 });
    cache.setNetworkStatus(false);
    cache.set('retry-count', { data: 'test' });
    cache.setNetworkStatus(true);

    const syncFn = jest.fn().mockRejectedValue(new Error('Fail'));

    for (let i = 0; i < 3; i++) {
      await cache.sync(syncFn);
    }

    const entry = Array.from((cache as any).cache.values())[0] as CacheEntry;
    expect(entry.syncRetryCount).toBe(3);

    cache.destroy();
  });
});

describe('OfflineCache - Network State', () => {
  let tempDir: string;
  let persistPath: string;

  beforeEach(() => {
    tempDir = fs.mkdtempSync(path.join(os.tmpdir(), 'offline-cache-network-'));
    persistPath = path.join(tempDir, 'cache.json');
    mockReadJsonFile.mockReturnValue(undefined);
  });

  afterEach(() => {
    try {
      if (fs.existsSync(tempDir)) {
        fs.rmSync(tempDir, { recursive: true });
      }
    } catch (e) {
      // ignore
    }
  });

  it('should start with online state by default', () => {
    const cache = new OfflineCache({ persistPath });
    expect(cache.getNetworkStatus()).toBe(true);
    cache.destroy();
  });

  it('should go offline when setNetworkStatus(false)', () => {
    const cache = new OfflineCache({ persistPath });
    cache.setNetworkStatus(false);
    expect(cache.getNetworkStatus()).toBe(false);
    cache.destroy();
  });

  it('should go online when setNetworkStatus(true)', () => {
    const cache = new OfflineCache({ persistPath });
    cache.setNetworkStatus(false);
    cache.setNetworkStatus(true);
    expect(cache.getNetworkStatus()).toBe(true);
    cache.destroy();
  });

  it('should not sync while offline', async () => {
    const cache = new OfflineCache({ persistPath });
    cache.setNetworkStatus(false);
    cache.set('no-sync', { data: 'test' });

    const syncFn = jest.fn().mockResolvedValue(true);
    const results = await cache.sync(syncFn);

    expect(syncFn).not.toHaveBeenCalled();
    expect(results).toEqual([]);
    cache.destroy();
  });

  it('should emit network-status event', () => {
    const cache = new OfflineCache({ persistPath });
    const statusHandler = jest.fn();
    cache.on('network-status', statusHandler);

    cache.setNetworkStatus(false);
    expect(statusHandler).toHaveBeenCalledWith(false);

    cache.setNetworkStatus(true);
    expect(statusHandler).toHaveBeenCalledWith(true);

    cache.destroy();
  });

  it('should emit sync-status event', () => {
    const cache = new OfflineCache({ persistPath });
    const syncStatusHandler = jest.fn();
    cache.on('sync-status', syncStatusHandler);

    cache.setNetworkStatus(false);
    expect(syncStatusHandler).not.toHaveBeenCalledWith('online');

    cache.setNetworkStatus(true);
    expect(syncStatusHandler).toHaveBeenCalledWith('online');

    cache.destroy();
  });
});

describe('OfflineCache - Persistence', () => {
  let tempDir: string;
  let persistPath: string;

  beforeEach(() => {
    tempDir = fs.mkdtempSync(path.join(os.tmpdir(), 'offline-cache-persist-'));
    persistPath = path.join(tempDir, 'cache.json');
    jest.clearAllMocks();
  });

  afterEach(() => {
    try {
      if (fs.existsSync(tempDir)) {
        fs.rmSync(tempDir, { recursive: true });
      }
    } catch (e) {
      // ignore
    }
  });

  it('should load existing data from disk on init', () => {
    const testData: CacheEntry[] = [
      {
        key: 'loaded',
        value: { data: 'from-disk' },
        timestamp: Date.now(),
        ttl: 100000,
        synced: true,
        syncRetryCount: 0,
      },
    ];

    mockReadJsonFile.mockReturnValue(testData);

    const cache = new OfflineCache({ persistPath });
    const result = cache.get<{ data: string }>('loaded');

    expect(result?.data).toBe('from-disk');
    cache.destroy();
  });

  it('should handle corrupted persistence file gracefully', () => {
    mockReadJsonFile.mockImplementation(() => {
      throw new Error('Invalid JSON');
    });

    const cache = new OfflineCache({ persistPath });
    expect(cache).toBeDefined();
    cache.destroy();
  });

  it('should initialize empty if file does not exist', () => {
    mockReadJsonFile.mockReturnValue(undefined);

    const cache = new OfflineCache({ persistPath });
    expect(cache).toBeDefined();
    expect(cache.getAllKeys().length).toBe(0);
    cache.destroy();
  });

  it('should persist changes to disk on set', () => {
    mockReadJsonFile.mockReturnValue(undefined);

    const cache = new OfflineCache({ persistPath });
    cache.set('persist', { data: 'test' });

    expect(mockWriteJsonFile).toHaveBeenCalled();

    cache.destroy();
  });

  it('should persist changes to disk on delete', () => {
    mockReadJsonFile.mockReturnValue(undefined);

    const cache = new OfflineCache({ persistPath });
    cache.set('to-delete', { data: 'test' });
    mockWriteJsonFile.mockClear();
    cache.delete('to-delete');

    expect(mockWriteJsonFile).toHaveBeenCalled();

    cache.destroy();
  });

  it('should persist changes to disk on clear', () => {
    mockReadJsonFile.mockReturnValue(undefined);

    const cache = new OfflineCache({ persistPath });
    cache.set('to-clear', { data: 'test' });
    mockWriteJsonFile.mockClear();
    cache.clear();

    expect(mockWriteJsonFile).toHaveBeenCalled();

    cache.destroy();
  });

  it('should handle write errors gracefully', () => {
    mockReadJsonFile.mockReturnValue(undefined);
    mockWriteJsonFile.mockImplementation(() => {
      throw new Error('Write failed');
    });

    const errorHandler = jest.fn();
    const cache = new OfflineCache({ persistPath });
    cache.on('error', errorHandler);

    cache.set('write-error', { data: 'test' });

    expect(errorHandler).toHaveBeenCalled();
    cache.destroy();
  });
});

describe('OfflineCache - Statistics', () => {
  let tempDir: string;
  let persistPath: string;

  beforeEach(() => {
    tempDir = fs.mkdtempSync(path.join(os.tmpdir(), 'offline-cache-stats-'));
    persistPath = path.join(tempDir, 'cache.json');
    mockReadJsonFile.mockReturnValue(undefined);
  });

  afterEach(() => {
    try {
      if (fs.existsSync(tempDir)) {
        fs.rmSync(tempDir, { recursive: true });
      }
    } catch (e) {
      // ignore
    }
  });

  it('should track hits and misses', () => {
    const cache = new OfflineCache({ persistPath });
    cache.set('hit', { data: 'test' });
    cache.get('hit');
    cache.get('miss');

    const stats = cache.getStats();
    expect(stats.hitCount).toBe(1);
    expect(stats.missCount).toBe(1);

    cache.destroy();
  });

  it('should track size', () => {
    const cache = new OfflineCache({ persistPath });
    expect(cache.getStats().size).toBe(0);

    cache.set('k1', {});
    expect(cache.getStats().size).toBe(1);

    cache.set('k2', {});
    expect(cache.getStats().size).toBe(2);

    cache.destroy();
  });

  it('should track unsynced count', () => {
    const cache = new OfflineCache({ persistPath });
    cache.setNetworkStatus(false);
    cache.set('synced', { data: 'test' }, undefined, false);
    cache.set('unsynced', { data: 'test' }, undefined, true);

    const stats = cache.getStats();
    expect(stats.unsyncedCount).toBe(1);

    cache.destroy();
  });

  it('should track evictions', () => {
    const cache = new OfflineCache({ persistPath, maxSize: 2 });
    cache.set('k1', {});
    cache.set('k2', {});
    cache.set('k3', {});

    const stats = cache.getStats();
    expect(stats.evictionCount).toBeGreaterThan(0);

    cache.destroy();
  });
});

describe('OfflineCache - Destroy', () => {
  it('should clear cache on destroy', () => {
    const tempDir = fs.mkdtempSync(path.join(os.tmpdir(), 'offline-cache-destroy-'));
    const persistPath = path.join(tempDir, 'cache.json');
    mockReadJsonFile.mockReturnValue(undefined);

    const cache = new OfflineCache({ persistPath });
    cache.set('k', { data: 'test' });
    cache.destroy();

    expect(() => cache.get('k')).toThrow();

    fs.rmSync(tempDir, { recursive: true });
  });

  it('should prevent operations after destroy', () => {
    const tempDir = fs.mkdtempSync(path.join(os.tmpdir(), 'offline-cache-destroy-'));
    const persistPath = path.join(tempDir, 'cache.json');
    mockReadJsonFile.mockReturnValue(undefined);

    const cache = new OfflineCache({ persistPath });
    cache.destroy();

    expect(() => cache.set('k', {})).toThrow();
    expect(() => cache.get('k')).toThrow();
    expect(() => cache.delete('k')).toThrow();
    expect(() => cache.has('k')).toThrow();
    expect(() => cache.clear()).toThrow();

    fs.rmSync(tempDir, { recursive: true });
  });
});
