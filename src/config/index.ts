import { EventEmitter } from 'events';
import NodeCache from 'node-cache';
import { ConfigDefinition } from '../types';
import { generateId, nowISO, deepClone, mergeDeep } from '../shared/utils';
import { logger } from '../logging';

interface ConfigVersion {
  version: number;
  config: ConfigDefinition;
  applied_at: string;
  applied_by: string;
  rollback_from?: number;
}

interface ConfigDiff {
  path: string;
  oldValue: unknown;
  newValue: unknown;
  operation: 'added' | 'removed' | 'modified';
}

interface RollbackRecord {
  rollback_id: string;
  config_id: string;
  from_version: number;
  to_version: number;
  timestamp: string;
  reason: string;
  operator: string;
}

class ConfigurationManager extends EventEmitter {
  private configs: Map<string, ConfigVersion[]> = new Map();
  private activeVersions: Map<string, number> = new Map();
  private rollbackHistory: RollbackRecord[] = [];
  private cache: NodeCache;
  private maxVersionsPerConfig = 50;
  private maxRollbackHistory = 200;

  constructor() {
    super();
    this.cache = new NodeCache({ stdTTL: 300, checkperiod: 60 });
  }

  createConfig(
    namespace: string,
    parameters: Record<string, unknown>,
    operator: string = 'system',
    enabled: boolean = true
  ): ConfigDefinition {
    const configId = generateId('cfg');
    const version = 1;
    const config: ConfigDefinition = {
      config_id: configId,
      namespace,
      version,
      parameters,
      enabled,
      applied_at: nowISO(),
    };

    this.configs.set(configId, [
      {
        version,
        config,
        applied_at: config.applied_at,
        applied_by: operator,
      },
    ]);
    this.activeVersions.set(configId, version);
    this.cache.set(this.getCacheKey(configId), config);

    logger.info('Config created', { config_id: configId, namespace, version }, operator);
    this.emit('config.created', config);

    return config;
  }

  updateConfig(
    configId: string,
    parameters: Record<string, unknown>,
    operator: string = 'system',
    enabled?: boolean
  ): ConfigDefinition | null {
    const versions = this.configs.get(configId);
    if (!versions || versions.length === 0) {
      logger.warn('Config not found for update', { config_id: configId }, operator);
      return null;
    }

    const latest = versions[versions.length - 1];
    const newVersion = latest.version + 1;

    const mergedParameters = mergeDeep(latest.config.parameters, parameters);

    const config: ConfigDefinition = {
      ...latest.config,
      version: newVersion,
      parameters: mergedParameters,
      enabled: enabled !== undefined ? enabled : latest.config.enabled,
      applied_at: nowISO(),
    };

    versions.push({
      version: newVersion,
      config,
      applied_at: config.applied_at,
      applied_by: operator,
    });

    if (versions.length > this.maxVersionsPerConfig) {
      versions.shift();
    }

    this.activeVersions.set(configId, newVersion);
    this.cache.set(this.getCacheKey(configId), config);

    const diff = this.calculateDiff(latest.config.parameters, config.parameters);
    logger.info('Config updated', { config_id: configId, version: newVersion, diff_count: diff.length }, operator);
    this.emit('config.updated', config, diff);

    return config;
  }

  rollbackConfig(configId: string, targetVersion: number, reason: string, operator: string): ConfigDefinition | null {
    const versions = this.configs.get(configId);
    if (!versions || versions.length === 0) {
      logger.warn('Config not found for rollback', { config_id: configId }, operator);
      return null;
    }

    const targetConfigVersion = versions.find((v) => v.version === targetVersion);
    if (!targetConfigVersion) {
      logger.warn('Target version not found for rollback', { config_id: configId, target_version: targetVersion }, operator);
      return null;
    }

    const latest = versions[versions.length - 1];
    const newVersion = latest.version + 1;

    const config: ConfigDefinition = {
      ...targetConfigVersion.config,
      version: newVersion,
      applied_at: nowISO(),
      rollback_from: latest.version,
    };

    versions.push({
      version: newVersion,
      config,
      applied_at: config.applied_at,
      applied_by: operator,
      rollback_from: latest.version,
    });

    this.activeVersions.set(configId, newVersion);
    this.cache.set(this.getCacheKey(configId), config);

    const rollbackRecord: RollbackRecord = {
      rollback_id: generateId('rbk'),
      config_id: configId,
      from_version: latest.version,
      to_version: targetVersion,
      timestamp: nowISO(),
      reason,
      operator,
    };

    this.rollbackHistory.push(rollbackRecord);
    if (this.rollbackHistory.length > this.maxRollbackHistory) {
      this.rollbackHistory.shift();
    }

    logger.info('Config rolled back', { config_id: configId, from_version: latest.version, to_version: targetVersion }, operator);
    this.emit('config.rolledback', config, rollbackRecord);

    return config;
  }

  getConfig(configId: string): ConfigDefinition | null {
    const cached = this.cache.get<ConfigDefinition>(this.getCacheKey(configId));
    if (cached) {
      return cached;
    }

    const versions = this.configs.get(configId);
    if (!versions || versions.length === 0) {
      return null;
    }

    const activeVersion = this.activeVersions.get(configId) || versions[versions.length - 1].version;
    const active = versions.find((v) => v.version === activeVersion) || versions[versions.length - 1];

    this.cache.set(this.getCacheKey(configId), active.config);
    return active.config;
  }

  getConfigVersion(configId: string, version: number): ConfigDefinition | null {
    const versions = this.configs.get(configId);
    if (!versions) return null;

    const found = versions.find((v) => v.version === version);
    return found ? found.config : null;
  }

  listConfigVersions(configId: string): ConfigDefinition[] {
    const versions = this.configs.get(configId);
    if (!versions) return [];
    return versions.map((v) => v.config);
  }

  listConfigs(namespace?: string): ConfigDefinition[] {
    const result: ConfigDefinition[] = [];
    for (const [configId, versions] of this.configs.entries()) {
      const activeVersion = this.activeVersions.get(configId) || versions[versions.length - 1].version;
      const active = versions.find((v) => v.version === activeVersion);
      if (active && (!namespace || active.config.namespace === namespace)) {
        result.push(active.config);
      }
    }
    return result;
  }

  deleteConfig(configId: string): boolean {
    const existed = this.configs.has(configId);
    if (existed) {
      this.configs.delete(configId);
      this.activeVersions.delete(configId);
      this.cache.del(this.getCacheKey(configId));
      logger.info('Config deleted', { config_id: configId });
      this.emit('config.deleted', configId);
    }
    return existed;
  }

  enableConfig(configId: string): ConfigDefinition | null {
    return this.updateConfig(configId, {}, 'system', true);
  }

  disableConfig(configId: string): ConfigDefinition | null {
    return this.updateConfig(configId, {}, 'system', false);
  }

  diffConfigs(configId: string, versionA: number, versionB: number): ConfigDiff[] {
    const cfgA = this.getConfigVersion(configId, versionA);
    const cfgB = this.getConfigVersion(configId, versionB);

    if (!cfgA || !cfgB) {
      return [];
    }

    return this.calculateDiff(cfgA.parameters, cfgB.parameters);
  }

  getRollbackHistory(configId?: string): RollbackRecord[] {
    if (configId) {
      return this.rollbackHistory.filter((r) => r.config_id === configId);
    }
    return [...this.rollbackHistory];
  }

  validateConfig(parameters: Record<string, unknown>, schema?: (params: unknown) => boolean): boolean {
    if (schema) {
      try {
        return schema(parameters);
      } catch {
        return false;
      }
    }
    return typeof parameters === 'object' && parameters !== null;
  }

  private calculateDiff(oldObj: Record<string, unknown>, newObj: Record<string, unknown>, path: string = ''): ConfigDiff[] {
    const diffs: ConfigDiff[] = [];
    const allKeys = new Set([...Object.keys(oldObj), ...Object.keys(newObj)]);

    for (const key of allKeys) {
      const currentPath = path ? `${path}.${key}` : key;
      const oldVal = oldObj[key];
      const newVal = newObj[key];

      if (oldVal === undefined) {
        diffs.push({ path: currentPath, oldValue: undefined, newValue: newVal, operation: 'added' });
      } else if (newVal === undefined) {
        diffs.push({ path: currentPath, oldValue: oldVal, newValue: undefined, operation: 'removed' });
      } else if (
        typeof oldVal === 'object' &&
        oldVal !== null &&
        typeof newVal === 'object' &&
        newVal !== null &&
        !Array.isArray(oldVal) &&
        !Array.isArray(newVal)
      ) {
        diffs.push(...this.calculateDiff(oldObj as Record<string, unknown>, newObj as Record<string, unknown>, currentPath));
      } else if (JSON.stringify(oldVal) !== JSON.stringify(newVal)) {
        diffs.push({ path: currentPath, oldValue: oldVal, newValue: newVal, operation: 'modified' });
      }
    }

    return diffs;
  }

  private getCacheKey(configId: string): string {
    return `config:${configId}`;
  }

  clearCache(): void {
    this.cache.flushAll();
    logger.debug('Config cache cleared');
  }
}

export const configManager = new ConfigurationManager();
export { ConfigurationManager, ConfigVersion, ConfigDiff, RollbackRecord };
