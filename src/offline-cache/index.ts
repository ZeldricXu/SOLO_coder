import { CacheEntry, OfflineCacheConfig, SyncResult, CacheStats, SyncFunction } from './types';
import { BaseService } from '../common/base-service';
import { readJsonFile, writeJsonFile } from '../common/file-utils';
import { fromError } from '../common/errors';

const DEFAULT_CONFIG: OfflineCacheConfig = {
  maxSize: 10000,
  defaultTTL: 3600000,
  persistPath: './data/cache.json',
  syncInterval: 30000,
  maxSyncRetries: 5,
  syncBatchSize: 50,
};

export class OfflineCache extends BaseService {
  private readonly cache: Map<string, CacheEntry> = new Map();
  private readonly config: OfflineCacheConfig;
  private networkOnline: boolean = true;
  private readonly stats: CacheStats = {
    size: 0,
    unsyncedCount: 0,
    hitCount: 0,
    missCount: 0,
    evictionCount: 0,
  };

  constructor(config: Partial<OfflineCacheConfig> = {}) {
    super('OfflineCache');
    this.config = { ...DEFAULT_CONFIG, ...config };
    this.loadFromDisk();
    this.startAutoSync();
  }

  set<T>(key: string, value: T, ttl?: number, requireSync: boolean = true): void {
    this.assertNotDestroyed();
    this.evictIfNeeded();

    const entry: CacheEntry<T> = {
      key,
      value,
      timestamp: Date.now(),
      ttl: ttl || this.config.defaultTTL,
      synced: !requireSync || this.networkOnline,
      syncRetryCount: 0,
    };

    this.cache.set(key, entry);
    this.stats.size = this.cache.size;

    if (!entry.synced) {
      this.stats.unsyncedCount++;
    }

    this.emit('set', key, value);
    this.saveToDisk();
  }

  get<T>(key: string): T | undefined {
    this.assertNotDestroyed();

    const entry = this.cache.get(key);
    if (!entry) {
      this.stats.missCount++;
      return undefined;
    }

    if (this.isEntryExpired(entry)) {
      this.cache.delete(key);
      this.stats.size = this.cache.size;
      this.stats.missCount++;
      return undefined;
    }

    this.stats.hitCount++;
    return entry.value as T;
  }

  delete(key: string): boolean {
    this.assertNotDestroyed();

    const deleted = this.cache.delete(key);
    if (deleted) {
      this.stats.size = this.cache.size;
      this.emit('delete', key);
      this.saveToDisk();
    }
    return deleted;
  }

  has(key: string): boolean {
    this.assertNotDestroyed();

    const entry = this.cache.get(key);
    return entry !== undefined && !this.isEntryExpired(entry);
  }

  clear(): void {
    this.assertNotDestroyed();

    this.cache.clear();
    this.stats.size = 0;
    this.stats.unsyncedCount = 0;
    this.emit('clear');
    this.saveToDisk();
  }

  getAllKeys(): string[] {
    return Array.from(this.cache.keys()).filter(key => {
      const entry = this.cache.get(key);
      return entry && !this.isEntryExpired(entry);
    });
  }

  getUnsyncedEntries(): CacheEntry[] {
    return Array.from(this.cache.values()).filter(entry => !entry.synced);
  }

  async sync<T>(syncFn: SyncFunction<T>): Promise<SyncResult[]> {
    this.assertNotDestroyed();

    if (!this.networkOnline) {
      this.emit('sync-status', 'offline');
      return [];
    }

    this.emit('sync-status', 'syncing');
    const unsynced = this.getUnsyncedEntries();
    const results: SyncResult[] = [];
    const { syncBatchSize } = this.config;

    for (let i = 0; i < unsynced.length; i += syncBatchSize) {
      const batch = unsynced.slice(i, i + syncBatchSize);
      const batchResults = await this.processSyncBatch(batch, syncFn);
      results.push(...batchResults);
      this.saveToDisk();
    }

    const allSynced = this.getUnsyncedEntries().length === 0;
    this.emit('sync-status', allSynced ? 'idle' : 'error');
    this.emit('sync-complete', results);

    return results;
  }

  private async processSyncBatch<T>(
    batch: CacheEntry[],
    syncFn: SyncFunction<T>
  ): Promise<SyncResult[]> {
    return Promise.all(
      batch.map(async entry => {
        try {
          const success = await syncFn(entry as CacheEntry<T>);
          if (success) {
            entry.synced = true;
            this.stats.unsyncedCount--;
            return { key: entry.key, success: true };
          } else {
            this.incrementSyncRetry(entry);
            return { key: entry.key, success: false, error: 'Sync failed' };
          }
        } catch (error) {
          this.incrementSyncRetry(entry);
          const errorResult = fromError(error);
          return {
            key: entry.key,
            success: false,
            error: errorResult.error || 'Unknown error',
          };
        }
      })
    );
  }

  private incrementSyncRetry(entry: CacheEntry): void {
    entry.syncRetryCount++;
    entry.lastSyncAttempt = Date.now();
  }

  setNetworkStatus(online: boolean): void {
    this.assertNotDestroyed();

    this.networkOnline = online;
    this.emit('network-status', online);
    if (online) {
      this.emit('sync-status', 'online');
    }
  }

  getNetworkStatus(): boolean {
    return this.networkOnline;
  }

  getStats(): CacheStats {
    return { ...this.stats };
  }

  private isEntryExpired(entry: CacheEntry): boolean {
    return Date.now() - entry.timestamp > entry.ttl;
  }

  private evictIfNeeded(): void {
    if (this.cache.size < this.config.maxSize) return;

    const entries = Array.from(this.cache.values()).sort((a, b) => a.timestamp - b.timestamp);
    const evictCount = Math.ceil(this.config.maxSize * 0.1);
    const toEvict = entries.slice(0, evictCount);

    for (const entry of toEvict) {
      if (!entry.synced) {
        this.stats.unsyncedCount--;
      }
      this.cache.delete(entry.key);
      this.stats.evictionCount++;
    }

    this.stats.size = this.cache.size;
    this.emit('evict', toEvict.map(e => e.key));
  }

  private saveToDisk(): void {
    try {
      const data = Array.from(this.cache.values());
      writeJsonFile(this.config.persistPath, data, true);
    } catch (error) {
      this.emitError(error);
    }
  }

  private loadFromDisk(): void {
    try {
      const entries = readJsonFile<CacheEntry[]>(this.config.persistPath);
      if (!entries) return;

      for (const entry of entries) {
        if (!this.isEntryExpired(entry)) {
          this.cache.set(entry.key, entry);
        }
      }

      this.stats.size = this.cache.size;
      this.stats.unsyncedCount = this.getUnsyncedEntries().length;
    } catch (error) {
      this.emitError(error);
    }
  }

  private startAutoSync(): void {
    const timer = setInterval(() => {
      if (this.networkOnline && this.getUnsyncedEntries().length > 0) {
        this.emit('auto-sync-trigger');
      }
    }, this.config.syncInterval);

    this.addTimer(timer);
  }

  override destroy(): void {
    this.saveToDisk();
    super.destroy();
  }
}

export * from './types';
