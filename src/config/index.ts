import { z } from 'zod';
import { EventEmitter } from '../utils';
import logger from '../utils/logger';
import { ConfigDefinition } from '../types';

const ConfigSchema = z.object({
  config_id: z.string(),
  namespace: z.string(),
  version: z.number().int().positive(),
  parameters: z.record(z.any()),
  enabled: z.boolean(),
  applied_at: z.string(),
});

export interface ConfigUpdateEvent {
  config: ConfigDefinition;
  previousVersion?: ConfigDefinition;
}

interface ConfigStoreEvents {
  'config.updated': ConfigUpdateEvent;
  'config.added': ConfigDefinition;
  'config.deleted': ConfigDefinition;
}

export class ConfigurationManager extends EventEmitter<ConfigStoreEvents> {
  private configs: Map<string, Map<string, ConfigDefinition>> = new Map();
  private sourceLoaders: Map<string, () => Promise<ConfigDefinition[]>> = new Map();
  private updateInterval: NodeJS.Timeout | null = null;

  constructor() {
    super();
  }

  registerSource(name: string, loader: () => Promise<ConfigDefinition[]>): void {
    this.sourceLoaders.set(name, loader);
    logger.info(`Registered config source: ${name}`);
  }

  unregisterSource(name: string): void {
    this.sourceLoaders.delete(name);
    logger.info(`Unregistered config source: ${name}`);
  }

  async loadFromAllSources(): Promise<void> {
    logger.info('Loading configuration from all sources...');
    for (const [name, loader] of this.sourceLoaders.entries()) {
      try {
        const configs = await loader();
        for (const config of configs) {
          this.addOrUpdateConfig(config);
        }
        logger.info(`Loaded ${configs.length} configs from source: ${name}`);
      } catch (error) {
        logger.error(`Failed to load configs from source ${name}:`, error);
      }
    }
  }

  addOrUpdateConfig(config: ConfigDefinition): boolean {
    const validation = ConfigSchema.safeParse(config);
    if (!validation.success) {
      logger.error('Invalid config format:', validation.error);
      return false;
    }

    const { namespace, config_id } = config;
    if (!this.configs.has(namespace)) {
      this.configs.set(namespace, new Map());
    }

    const namespaceConfigs = this.configs.get(namespace)!;
    const existing = namespaceConfigs.get(config_id);

    if (existing && existing.version >= config.version) {
      logger.debug(`Skipping config update for ${config_id}, version is not newer`);
      return false;
    }

    namespaceConfigs.set(config_id, config);

    if (existing) {
      logger.info(`Updated config: ${config_id} in namespace: ${namespace} to version: ${config.version}`);
      this.emit('config.updated', { config, previousVersion: existing });
    } else {
      logger.info(`Added new config: ${config_id} in namespace: ${namespace}`);
      this.emit('config.added', config);
    }

    return true;
  }

  getConfig(namespace: string, configId: string): ConfigDefinition | undefined {
    return this.configs.get(namespace)?.get(configId);
  }

  getConfigsByNamespace(namespace: string): ConfigDefinition[] {
    const namespaceConfigs = this.configs.get(namespace);
    return namespaceConfigs ? Array.from(namespaceConfigs.values()) : [];
  }

  getAllConfigs(): ConfigDefinition[] {
    const allConfigs: ConfigDefinition[] = [];
    for (const namespaceConfigs of this.configs.values()) {
      allConfigs.push(...namespaceConfigs.values());
    }
    return allConfigs;
  }

  deleteConfig(namespace: string, configId: string): boolean {
    const namespaceConfigs = this.configs.get(namespace);
    if (!namespaceConfigs) return false;

    const config = namespaceConfigs.get(configId);
    if (!config) return false;

    namespaceConfigs.delete(configId);
    logger.info(`Deleted config: ${configId} from namespace: ${namespace}`);
    this.emit('config.deleted', config);
    return true;
  }

  getParameter<T>(namespace: string, configId: string, key: string, defaultValue?: T): T | undefined {
    const config = this.getConfig(namespace, configId);
    if (!config) return defaultValue;
    return (config.parameters[key] as T) ?? defaultValue;
  }

  enableAutoRefresh(intervalMs: number): void {
    if (this.updateInterval) {
      clearInterval(this.updateInterval);
    }
    this.updateInterval = setInterval(() => {
      this.loadFromAllSources().catch((error) => {
        logger.error('Auto refresh failed:', error);
      });
    }, intervalMs);
    logger.info(`Enabled auto config refresh with interval: ${intervalMs}ms`);
  }

  disableAutoRefresh(): void {
    if (this.updateInterval) {
      clearInterval(this.updateInterval);
      this.updateInterval = null;
      logger.info('Disabled auto config refresh');
    }
  }

  compareConfigs(config1: ConfigDefinition, config2: ConfigDefinition): string[] {
    const differences: string[] = [];

    if (config1.version !== config2.version) {
      differences.push(`version: ${config1.version} -> ${config2.version}`);
    }
    if (config1.enabled !== config2.enabled) {
      differences.push(`enabled: ${config1.enabled} -> ${config2.enabled}`);
    }

    const allKeys = new Set([
      ...Object.keys(config1.parameters),
      ...Object.keys(config2.parameters),
    ]);
    for (const key of allKeys) {
      const v1 = JSON.stringify(config1.parameters[key]);
      const v2 = JSON.stringify(config2.parameters[key]);
      if (v1 !== v2) {
        differences.push(`parameters.${key}: ${v1} -> ${v2}`);
      }
    }

    return differences;
  }

  diffNamespaces(namespace1: string, namespace2: string): Map<string, { left?: ConfigDefinition; right?: ConfigDefinition; diffs: string[] }> {
    const configs1 = this.getConfigsByNamespace(namespace1);
    const configs2 = this.getConfigsByNamespace(namespace2);
    const map1 = new Map(configs1.map((c) => [c.config_id, c]));
    const map2 = new Map(configs2.map((c) => [c.config_id, c]));
    const result = new Map();

    for (const [id, left] of map1) {
      const right = map2.get(id);
      if (!right) {
        result.set(id, { left, diffs: ['Only in ' + namespace1] });
      } else {
        const diffs = this.compareConfigs(left, right);
        if (diffs.length > 0) {
          result.set(id, { left, right, diffs });
        }
      }
    }

    for (const [id, right] of map2) {
      if (!map1.has(id)) {
        result.set(id, { right, diffs: ['Only in ' + namespace2] });
      }
    }

    return result;
  }

  clear(): void {
    this.configs.clear();
    this.sourceLoaders.clear();
    this.disableAutoRefresh();
    logger.info('Configuration manager cleared');
  }
}

export function createEnvironmentConfigLoader(): () => Promise<ConfigDefinition[]> {
  return async (): Promise<ConfigDefinition[]> => {
    const configs: ConfigDefinition[] = [];
    const envPrefix = 'SLO_CONFIG_';

    for (const [key, value] of Object.entries(process.env)) {
      if (key.startsWith(envPrefix) && value) {
        try {
          const parsed = JSON.parse(value);
          if (ConfigSchema.safeParse(parsed).success) {
            configs.push(parsed);
          }
        } catch {
          logger.debug(`Skipping invalid env config: ${key}`);
        }
      }
    }

    return configs;
  };
}

export function createStaticConfigLoader(configs: ConfigDefinition[]): () => Promise<ConfigDefinition[]> {
  return async () => configs;
}

const configManager = new ConfigurationManager();

export default configManager;
