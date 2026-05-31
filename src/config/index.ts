import { v4 as uuidv4 } from 'uuid';
import { Config } from '../types';
import { ProcessingPipeline } from '../core';

export interface ConfigVersion {
  version: number;
  config: Config;
  createdAt: string;
  description?: string;
}

export interface ConfigDiff {
  added: string[];
  removed: string[];
  modified: { key: string; oldValue: unknown; newValue: unknown }[];
}

export class ConfigStore {
  private configs: Map<string, ConfigVersion[]> = new Map();

  create(
    namespace: string,
    parameters: Record<string, unknown>,
    description?: string
  ): Config {
    const key = namespace;
    const existing = this.configs.get(key) || [];
    const newVersion = existing.length + 1;

    const config: Config = {
      config_id: uuidv4(),
      namespace,
      version: newVersion,
      parameters,
      enabled: true,
      applied_at: new Date().toISOString(),
    };

    existing.push({
      version: newVersion,
      config,
      createdAt: new Date().toISOString(),
      description,
    });

    this.configs.set(key, existing);
    return config;
  }

  get(namespace: string, version?: number): Config | null {
    const versions = this.configs.get(namespace);
    if (!versions || versions.length === 0) return null;

    if (version !== undefined) {
      const found = versions.find(v => v.version === version);
      return found ? found.config : null;
    }

    return versions[versions.length - 1].config;
  }

  update(
    namespace: string,
    parameters: Record<string, unknown>,
    description?: string
  ): Config | null {
    const existing = this.configs.get(namespace);
    if (!existing || existing.length === 0) return null;

    const current = existing[existing.length - 1];
    const newVersion = current.version + 1;

    const config: Config = {
      ...current.config,
      version: newVersion,
      parameters,
      applied_at: new Date().toISOString(),
    };

    existing.push({
      version: newVersion,
      config,
      createdAt: new Date().toISOString(),
      description,
    });

    return config;
  }

  delete(namespace: string): boolean {
    return this.configs.delete(namespace);
  }

  rollback(namespace: string, targetVersion: number): Config | null {
    const versions = this.configs.get(namespace);
    if (!versions) return null;

    const target = versions.find(v => v.version === targetVersion);
    if (!target) return null;

    return this.update(namespace, target.config.parameters, `Rollback to version ${targetVersion}`);
  }

  listVersions(namespace: string): ConfigVersion[] {
    return this.configs.get(namespace) || [];
  }

  listNamespaces(): string[] {
    return Array.from(this.configs.keys());
  }

  diff(namespace: string, version1: number, version2: number): ConfigDiff | null {
    const versions = this.configs.get(namespace);
    if (!versions) return null;

    const v1 = versions.find(v => v.version === version1);
    const v2 = versions.find(v => v.version === version2);

    if (!v1 || !v2) return null;

    return this.compareConfigs(v1.config.parameters, v2.config.parameters);
  }

  private compareConfigs(oldParams: Record<string, unknown>, newParams: Record<string, unknown>): ConfigDiff {
    const added: string[] = [];
    const removed: string[] = [];
    const modified: ConfigDiff['modified'] = [];

    const allKeys = new Set([...Object.keys(oldParams), ...Object.keys(newParams)]);

    for (const key of allKeys) {
      const inOld = key in oldParams;
      const inNew = key in newParams;

      if (inOld && !inNew) {
        removed.push(key);
      } else if (!inOld && inNew) {
        added.push(key);
      } else if (JSON.stringify(oldParams[key]) !== JSON.stringify(newParams[key])) {
        modified.push({
          key,
          oldValue: oldParams[key],
          newValue: newParams[key],
        });
      }
    }

    return { added, removed, modified };
  }
}

export class ConfigValidator {
  static validate(parameters: Record<string, unknown>, schema: Record<string, unknown>): boolean {
    for (const [key, type] of Object.entries(schema)) {
      if (!(key in parameters)) {
        return false;
      }
      if (typeof parameters[key] !== type) {
        return false;
      }
    }
    return true;
  }

  static validateWithErrors(
    parameters: Record<string, unknown>,
    schema: Record<string, unknown>
  ): { valid: boolean; errors: string[] } {
    const errors: string[] = [];

    for (const [key, type] of Object.entries(schema)) {
      if (!(key in parameters)) {
        errors.push(`Missing required field: ${key}`);
      } else if (typeof parameters[key] !== type) {
        errors.push(`Field ${key} should be of type ${type}`);
      }
    }

    return { valid: errors.length === 0, errors };
  }
}

export class ConfigCache {
  private cache: Map<string, { config: Config; expiresAt: number }> = new Map();
  private ttl: number;

  constructor(ttlMs: number = 60000) {
    this.ttl = ttlMs;
  }

  get(namespace: string): Config | null {
    const cached = this.cache.get(namespace);
    if (!cached) return null;

    if (Date.now() > cached.expiresAt) {
      this.cache.delete(namespace);
      return null;
    }

    return cached.config;
  }

  set(namespace: string, config: Config): void {
    this.cache.set(namespace, {
      config,
      expiresAt: Date.now() + this.ttl,
    });
  }

  invalidate(namespace: string): boolean {
    return this.cache.delete(namespace);
  }

  invalidateAll(): void {
    this.cache.clear();
  }
}

export class ConfigManager {
  private store: ConfigStore;
  private cache: ConfigCache;
  private schemas: Map<string, Record<string, unknown>> = new Map();
  private pipeline: ProcessingPipeline<{ namespace: string; parameters: Record<string, unknown>; description?: string }, Config>;

  constructor(store: ConfigStore, cache: ConfigCache) {
    this.store = store;
    this.cache = cache;
    this.pipeline = this.buildPipeline();
  }

  private buildPipeline(): ProcessingPipeline<{ namespace: string; parameters: Record<string, unknown>; description?: string }, Config> {
    return new ProcessingPipeline<{ namespace: string; parameters: Record<string, unknown>; description?: string }, Config>()
      .addStage({
        name: 'validation',
        process: async (input) => {
          const schema = this.schemas.get(input.namespace);
          if (schema) {
            const validation = ConfigValidator.validateWithErrors(input.parameters, schema);
            if (!validation.valid) {
              throw new Error(`Config validation failed: ${validation.errors.join(', ')}`);
            }
          }
          return input;
        },
      })
      .addStage({
        name: 'storage',
        process: async (input) => {
          const existing = this.store.get(input.namespace);
          if (existing) {
            const updated = this.store.update(input.namespace, input.parameters, input.description);
            if (!updated) throw new Error('Failed to update config');
            return updated;
          } else {
            return this.store.create(input.namespace, input.parameters, input.description);
          }
        },
      })
      .addStage({
        name: 'cache_update',
        process: async (config) => {
          this.cache.set(config.namespace, config);
          return config;
        },
      });
  }

  async set(
    namespace: string,
    parameters: Record<string, unknown>,
    description?: string
  ): Promise<Config> {
    const result = await this.pipeline.execute({ namespace, parameters, description });
    if (!result.success || !result.data) {
      throw new Error(result.error || 'Failed to save config');
    }
    return result.data;
  }

  get(namespace: string, version?: number): Config | null {
    if (version === undefined) {
      const cached = this.cache.get(namespace);
      if (cached) return cached;
    }

    const config = this.store.get(namespace, version);
    if (config && version === undefined) {
      this.cache.set(namespace, config);
    }
    return config;
  }

  delete(namespace: string): boolean {
    this.cache.invalidate(namespace);
    return this.store.delete(namespace);
  }

  rollback(namespace: string, targetVersion: number): Config | null {
    const config = this.store.rollback(namespace, targetVersion);
    if (config) {
      this.cache.set(namespace, config);
    }
    return config;
  }

  listVersions(namespace: string): ConfigVersion[] {
    return this.store.listVersions(namespace);
  }

  listNamespaces(): string[] {
    return this.store.listNamespaces();
  }

  diff(namespace: string, version1: number, version2: number): ConfigDiff | null {
    return this.store.diff(namespace, version1, version2);
  }

  registerSchema(namespace: string, schema: Record<string, unknown>): void {
    this.schemas.set(namespace, schema);
  }

  getParameter(namespace: string, key: string, defaultValue?: unknown): unknown {
    const config = this.get(namespace);
    if (!config) return defaultValue;
    return key in config.parameters ? config.parameters[key] : defaultValue;
  }

  async setParameter(namespace: string, key: string, value: unknown): Promise<Config> {
    const config = this.get(namespace);
    const parameters = config ? { ...config.parameters } : {};
    parameters[key] = value;
    return this.set(namespace, parameters, `Update parameter: ${key}`);
  }

  enable(namespace: string): Config | null {
    const config = this.get(namespace);
    if (!config) return null;
    return this.store.update(namespace, config.parameters, 'Enabled');
  }

  disable(namespace: string): Config | null {
    const config = this.get(namespace);
    if (!config) return null;
    const updated = this.store.update(namespace, config.parameters, 'Disabled');
    if (updated) {
      updated.enabled = false;
    }
    return updated;
  }
}

export function createConfigModule(): {
  store: ConfigStore;
  cache: ConfigCache;
  manager: ConfigManager;
} {
  const store = new ConfigStore();
  const cache = new ConfigCache();
  const manager = new ConfigManager(store, cache);

  return {
    store,
    cache,
    manager,
  };
}
