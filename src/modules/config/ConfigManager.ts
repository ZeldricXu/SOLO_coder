import { ConfigDefinition, ConfigChangeEvent, ConfigListener, AppConfig } from '../../types/config';
import { IConfigSource, EnvConfigSource } from './ConfigSource';
import { generateId, getCurrentTimestamp, deepClone, parseJsonSafe } from '../../common/utils';
import { ConfigError, NotFoundError } from '../../common/errors';
import { EventEmitter } from 'events';

interface FlatCacheEntry {
  value: unknown;
  path: string;
}

export class ConfigManager extends EventEmitter {
  private sources: IConfigSource[];
  private config: Record<string, unknown>;
  private flatCache: Map<string, unknown>;
  private cacheDirty: boolean;
  private configs: Map<string, ConfigDefinition>;
  private changeListeners: Map<string, Set<ConfigListener>>;
  private initialized: boolean;
  private stats: {
    cacheHits: number;
    cacheMisses: number;
    totalGets: number;
  };

  constructor() {
    super();
    this.sources = [];
    this.config = {};
    this.flatCache = new Map();
    this.cacheDirty = true;
    this.configs = new Map();
    this.changeListeners = new Map();
    this.initialized = false;
    this.stats = {
      cacheHits: 0,
      cacheMisses: 0,
      totalGets: 0
    };
  }

  addSource(source: IConfigSource): void {
    this.sources.push(source);
    this.sources.sort((a, b) => b.priority - a.priority);
  }

  removeSource(name: string): void {
    const index = this.sources.findIndex(s => s.name === name);
    if (index !== -1) {
      const source = this.sources[index];
      source.stopWatching?.();
      this.sources.splice(index, 1);
    }
  }

  getSources(): IConfigSource[] {
    return [...this.sources];
  }

  async initialize(): Promise<void> {
    if (this.initialized) return;

    await this.loadAll();
    this.setupWatchers();
    this.initialized = true;
  }

  private async loadAll(): Promise<void> {
    const mergedConfig: Record<string, unknown> = {};

    for (const source of this.sources) {
      try {
        const config = await source.load();
        this.mergeConfig(mergedConfig, config);
      } catch (error) {
        console.warn(`加载配置源失败 [${source.name}]:`, error);
      }
    }

    const changes = this.compareConfigs(this.config, mergedConfig);
    this.config = mergedConfig;
    this.cacheDirty = true;

    if (changes.length > 0) {
      for (const change of changes) {
        this.emitChange(change);
      }
    }
  }

  private rebuildFlatCache(): void {
    if (!this.cacheDirty) return;

    this.flatCache.clear();
    this.flattenObject(this.config, '');
    this.cacheDirty = false;
  }

  private flattenObject(obj: Record<string, unknown>, prefix: string): void {
    const entries = Object.entries(obj);
    for (let i = 0; i < entries.length; i++) {
      const [key, value] = entries[i];
      const fullPath = prefix ? `${prefix}.${key}` : key;

      if (
        typeof value === 'object' &&
        value !== null &&
        !Array.isArray(value)
      ) {
        this.flattenObject(value as Record<string, unknown>, fullPath);
      } else {
        this.flatCache.set(fullPath, value);
      }
    }
  }

  private mergeConfig(target: Record<string, unknown>, source: Record<string, unknown>): void {
    const sourceKeys = Object.keys(source);
    for (let i = 0; i < sourceKeys.length; i++) {
      const key = sourceKeys[i];
      const value = source[key];
      const targetValue = target[key];

      if (
        typeof value === 'object' &&
        value !== null &&
        !Array.isArray(value) &&
        typeof targetValue === 'object' &&
        targetValue !== null &&
        !Array.isArray(targetValue)
      ) {
        this.mergeConfig(
          targetValue as Record<string, unknown>,
          value as Record<string, unknown>
        );
      } else {
        target[key] = value;
      }
    }
  }

  private compareConfigs(
    oldConfig: Record<string, unknown>,
    newConfig: Record<string, unknown>,
    path: string = ''
  ): ConfigChangeEvent[] {
    const changes: ConfigChangeEvent[] = [];
    const allKeys = new Set([...Object.keys(oldConfig), ...Object.keys(newConfig)]);
    const keys = Array.from(allKeys);

    for (let i = 0; i < keys.length; i++) {
      const key = keys[i];
      const fullPath = path ? `${path}.${key}` : key;
      const oldValue = oldConfig[key];
      const newValue = newConfig[key];

      if (oldValue === newValue) continue;

      const isOldObject = this.isPlainObject(oldValue);
      const isNewObject = this.isPlainObject(newValue);

      if (isOldObject && isNewObject) {
        const nestedChanges = this.compareConfigs(
          oldValue as Record<string, unknown>,
          newValue as Record<string, unknown>,
          fullPath
        );
        for (let j = 0; j < nestedChanges.length; j++) {
          changes.push(nestedChanges[j]);
        }
      } else {
        changes.push({
          config_id: generateId('cfg'),
          namespace: 'default',
          old_value: oldValue,
          new_value: newValue,
          timestamp: getCurrentTimestamp(),
          source: 'config-manager'
        });
      }
    }

    return changes;
  }

  private isPlainObject(value: unknown): value is Record<string, unknown> {
    return (
      typeof value === 'object' &&
      value !== null &&
      !Array.isArray(value) &&
      Object.prototype.toString.call(value) === '[object Object]'
    );
  }

  private setupWatchers(): void {
    for (let i = 0; i < this.sources.length; i++) {
      const source = this.sources[i];
      if (source.watch) {
        source.watch(async (newConfig) => {
          const currentConfig = deepClone(this.config);
          this.mergeConfig(currentConfig, newConfig);

          const changes = this.compareConfigs(this.config, currentConfig);
          this.config = currentConfig;
          this.cacheDirty = true;

          for (let i = 0; i < changes.length; i++) {
            this.emitChange(changes[i]);
          }
        });
      }
    }
  }

  private emitChange(event: ConfigChangeEvent): void {
    this.emit('change', event);

    const listeners = this.changeListeners.get(event.config_id) || this.changeListeners.get('*');
    if (listeners) {
      const listenerArray = Array.from(listeners);
      for (let i = 0; i < listenerArray.length; i++) {
        try {
          listenerArray[i](event);
        } catch (error) {
          console.error('配置变化监听器执行失败:', error);
        }
      }
    }
  }

  get<T = unknown>(key: string, defaultValue?: T): T {
    this.stats.totalGets++;

    if (this.cacheDirty) {
      this.rebuildFlatCache();
    }

    if (this.flatCache.has(key)) {
      this.stats.cacheHits++;
      return this.flatCache.get(key) as T;
    }

    this.stats.cacheMisses++;

    const keys = key.split('.');
    let value: unknown = this.config;

    for (let i = 0; i < keys.length; i++) {
      const k = keys[i];
      if (value === null || value === undefined || typeof value !== 'object') {
        return defaultValue as T;
      }
      value = (value as Record<string, unknown>)[k];
    }

    if (value !== undefined) {
      this.flatCache.set(key, value);
    }

    return (value !== undefined ? value : defaultValue) as T;
  }

  getMany<T = unknown>(keys: string[]): Map<string, T | undefined> {
    if (this.cacheDirty) {
      this.rebuildFlatCache();
    }

    const result = new Map<string, T | undefined>();
    for (let i = 0; i < keys.length; i++) {
      const key = keys[i];
      if (this.flatCache.has(key)) {
        this.stats.cacheHits++;
        result.set(key, this.flatCache.get(key) as T);
      } else {
        this.stats.cacheMisses++;
        result.set(key, this.get<T>(key));
      }
    }
    return result;
  }

  getAll(): Record<string, unknown> {
    return deepClone(this.config);
  }

  getCached(key: string): unknown | undefined {
    if (this.cacheDirty) {
      this.rebuildFlatCache();
    }
    return this.flatCache.get(key);
  }

  set(key: string, value: unknown): void {
    const keys = key.split('.');
    let target = this.config;

    for (let i = 0; i < keys.length - 1; i++) {
      const k = keys[i];
      if (!this.isPlainObject(target[k])) {
        target[k] = {};
      }
      target = target[k] as Record<string, unknown>;
    }

    const lastKey = keys[keys.length - 1];
    const oldValue = target[lastKey];
    target[lastKey] = value;
    this.cacheDirty = true;

    if (oldValue !== value) {
      const event: ConfigChangeEvent = {
        config_id: generateId('cfg'),
        namespace: 'default',
        old_value: oldValue,
        new_value: value,
        timestamp: getCurrentTimestamp(),
        source: 'manual'
      };
      this.emitChange(event);
    }
  }

  setMany(entries: Array<{ key: string; value: unknown }>): void {
    const changes: ConfigChangeEvent[] = [];
    const now = getCurrentTimestamp();

    for (let i = 0; i < entries.length; i++) {
      const { key, value } = entries[i];
      const keys = key.split('.');
      let target = this.config;

      for (let j = 0; j < keys.length - 1; j++) {
        const k = keys[j];
        if (!this.isPlainObject(target[k])) {
          target[k] = {};
        }
        target = target[k] as Record<string, unknown>;
      }

      const lastKey = keys[keys.length - 1];
      const oldValue = target[lastKey];
      target[lastKey] = value;

      if (oldValue !== value) {
        changes.push({
          config_id: generateId('cfg'),
          namespace: 'default',
          old_value: oldValue,
          new_value: value,
          timestamp: now,
          source: 'batch'
        });
      }
    }

    this.cacheDirty = true;

    for (let i = 0; i < changes.length; i++) {
      this.emitChange(changes[i]);
    }
  }

  saveConfig(config: Omit<ConfigDefinition, 'config_id' | 'applied_at' | 'version'> & { version?: number }): ConfigDefinition {
    const existing = Array.from(this.configs.values()).find(
      c => c.namespace === config.namespace
    );

    const now = getCurrentTimestamp();
    const newConfig: ConfigDefinition = {
      ...config,
      config_id: existing?.config_id || generateId('cfg'),
      version: (existing?.version || 0) + 1,
      applied_at: now
    };

    this.configs.set(newConfig.config_id, newConfig);
    this.mergeConfig(this.config, config.parameters);
    this.cacheDirty = true;

    const event: ConfigChangeEvent = {
      config_id: newConfig.config_id,
      namespace: config.namespace,
      old_value: existing?.parameters || {},
      new_value: config.parameters,
      timestamp: now,
      source: 'save'
    };
    this.emitChange(event);

    return newConfig;
  }

  getConfig(configId: string): ConfigDefinition {
    const config = this.configs.get(configId);
    if (!config) {
      throw new NotFoundError(`配置不存在: ${configId}`);
    }
    return config;
  }

  getConfigByNamespace(namespace: string): ConfigDefinition | undefined {
    return Array.from(this.configs.values()).find(c => c.namespace === namespace);
  }

  listConfigs(): ConfigDefinition[] {
    return Array.from(this.configs.values());
  }

  deleteConfig(configId: string): void {
    const config = this.configs.get(configId);
    if (!config) {
      throw new NotFoundError(`配置不存在: ${configId}`);
    }
    this.configs.delete(configId);
  }

  onChange(listener: ConfigListener): () => void;
  onChange(configId: string, listener: ConfigListener): () => void;
  onChange(
    idOrListener: string | ConfigListener,
    maybeListener?: ConfigListener
  ): () => void {
    const configId = typeof idOrListener === 'string' ? idOrListener : '*';
    const listener = typeof idOrListener === 'function' ? idOrListener : maybeListener!;

    if (!this.changeListeners.has(configId)) {
      this.changeListeners.set(configId, new Set());
    }
    this.changeListeners.get(configId)!.add(listener);

    return () => {
      this.changeListeners.get(configId)?.delete(listener);
    };
  }

  async refresh(): Promise<void> {
    await this.loadAll();
  }

  invalidateCache(): void {
    this.cacheDirty = true;
    this.flatCache.clear();
  }

  getStats(): {
    cacheHits: number;
    cacheMisses: number;
    totalGets: number;
    cacheHitRate: number;
    cacheSize: number;
    totalConfigs: number;
  } {
    return {
      cacheHits: this.stats.cacheHits,
      cacheMisses: this.stats.cacheMisses,
      totalGets: this.stats.totalGets,
      cacheHitRate: this.stats.totalGets > 0
        ? this.stats.cacheHits / this.stats.totalGets
        : 0,
      cacheSize: this.flatCache.size,
      totalConfigs: this.configs.size
    };
  }

  resetStats(): void {
    this.stats = {
      cacheHits: 0,
      cacheMisses: 0,
      totalGets: 0
    };
  }

  getAppConfig(): AppConfig {
    if (this.cacheDirty) {
      this.rebuildFlatCache();
    }

    return {
      env: this.get<string>('node_env', 'development'),
      port: this.get<number>('port', 3000),
      jwtSecret: this.get<string>('jwt_secret', 'change-me'),
      jwtExpiresIn: this.get<string>('jwt_expires_in', '24h'),
      database: {
        url: this.get<string>('database.url', ''),
        poolMin: this.get<number>('database.pool_min', 2),
        poolMax: this.get<number>('database.pool_max', 20),
        idleTimeout: this.get<number>('database.idle_timeout', 30000),
        connectionTimeout: this.get<number>('database.connection_timeout', 10000),
        queryTimeout: this.get<number>('database.query_timeout', 30000)
      },
      redis: {
        host: this.get<string>('redis.host', 'localhost'),
        port: this.get<number>('redis.port', 6379),
        password: this.get<string | undefined>('redis.password'),
        db: this.get<number>('redis.db', 0),
        keyPrefix: this.get<string | undefined>('redis.key_prefix')
      },
      rateLimit: {
        windowMs: this.get<number>('rate_limit.window_ms', 60000),
        maxRequests: this.get<number>('rate_limit.max_requests', 100),
        message: this.get<string>('rate_limit.message', 'Rate limit exceeded'),
        statusCode: this.get<number>('rate_limit.status_code', 429)
      },
      log: {
        level: this.get<'debug' | 'info' | 'warn' | 'error' | 'fatal'>('log.level', 'info'),
        filePath: this.get<string | undefined>('log.file_path'),
        maxSize: this.get<string | undefined>('log.max_size'),
        maxFiles: this.get<number | undefined>('log.max_files')
      },
      billing: {
        cycleDays: this.get<number>('billing.cycle_days', 30),
        pricePerApiCall: this.get<number>('billing.price_per_api_call', 0.001),
        pricePerStorageGb: this.get<number>('billing.price_per_storage_gb', 0.05),
        pricePerComputeUnit: this.get<number>('billing.price_per_compute_unit', 0.02),
        currency: this.get<string>('billing.currency', 'CNY')
      },
      backupPath: this.get<string>('backup_path', './backups'),
      backupSchedule: this.get<string>('backup_schedule', '0 2 * * *')
    };
  }

  destroy(): void {
    for (let i = 0; i < this.sources.length; i++) {
      this.sources[i].stopWatching?.();
    }
    this.sources = [];
    this.changeListeners.clear();
    this.flatCache.clear();
    this.removeAllListeners();
  }
}

export const configManager = new ConfigManager();

export function createConfigManager(envPrefix?: string): ConfigManager {
  const manager = new ConfigManager();

  const envSource = new EnvConfigSource('env', 100, envPrefix);
  manager.addSource(envSource);

  return manager;
}
