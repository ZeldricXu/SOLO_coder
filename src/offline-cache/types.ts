export interface CacheEntry<T = unknown> {
  key: string;
  value: T;
  timestamp: number;
  ttl: number;
  synced: boolean;
  syncRetryCount: number;
  lastSyncAttempt?: number;
}

export interface OfflineCacheConfig {
  maxSize: number;
  defaultTTL: number;
  persistPath: string;
  syncInterval: number;
  maxSyncRetries: number;
  syncBatchSize: number;
}

export interface SyncResult {
  key: string;
  success: boolean;
  error?: string;
}

export type SyncStatus = 'idle' | 'syncing' | 'error' | 'online' | 'offline';

export interface CacheStats {
  size: number;
  unsyncedCount: number;
  hitCount: number;
  missCount: number;
  evictionCount: number;
}

export interface SyncFunction<T = unknown> {
  (entry: CacheEntry<T>): Promise<boolean>;
}
