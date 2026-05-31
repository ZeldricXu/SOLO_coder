import { Config } from '../types/common';
import { generateId, currentDateTime, logger } from '../utils/common';

export class ConfigManager {
  private configs: Map<string, Config> = new Map();
  private namespaceConfigs: Map<string, Config[]> = new Map();

  createConfig(
    namespace: string,
    parameters: Record<string, unknown>,
    enabled: boolean = true
  ): Config {
    const existing = this.namespaceConfigs.get(namespace) || [];
    const version = existing.length > 0
      ? Math.max(...existing.map(c => c.version)) + 1
      : 1;

    const config: Config = {
      configId: generateId('cfg_'),
      namespace,
      version,
      parameters,
      enabled,
      appliedAt: currentDateTime(),
    };

    this.configs.set(config.configId, config);
    const namespaceList = this.namespaceConfigs.get(namespace) || [];
    namespaceList.push(config);
    this.namespaceConfigs.set(namespace, namespaceList);

    logger.info(`Config created`, { configId: config.configId, namespace, version });
    return config;
  }

  getConfig(configId: string): Config | undefined {
    return this.configs.get(configId);
  }

  getLatestConfig(namespace: string): Config | undefined {
    const configs = this.namespaceConfigs.get(namespace);
    if (!configs || configs.length === 0) return undefined;
    return configs.reduce((latest, current) =>
      current.version > latest.version ? current : latest
    );
  }

  getConfigByVersion(namespace: string, version: number): Config | undefined {
    const configs = this.namespaceConfigs.get(namespace);
    return configs?.find(c => c.version === version);
  }

  updateConfig(configId: string, updates: Partial<Config>): Config | undefined {
    const config = this.configs.get(configId);
    if (!config) return undefined;

    const updated: Config = {
      ...config,
      ...updates,
      appliedAt: currentDateTime(),
    };

    this.configs.set(configId, updated);
    logger.info(`Config updated`, { configId });
    return updated;
  }

  listConfigs(namespace?: string): Config[] {
    if (namespace) {
      return this.namespaceConfigs.get(namespace) || [];
    }
    return Array.from(this.configs.values());
  }

  diffConfigs(configId1: string, configId2: string): Record<string, { old: unknown; new: unknown }> {
    const c1 = this.configs.get(configId1);
    const c2 = this.configs.get(configId2);

    if (!c1 || !c2) {
      throw new Error('Config not found');
    }

    const diff: Record<string, { old: unknown; new: unknown }> = {};
    const allKeys = new Set([
      ...Object.keys(c1.parameters),
      ...Object.keys(c2.parameters),
    ]);

    for (const key of allKeys) {
      const oldVal = c1.parameters[key];
      const newVal = c2.parameters[key];
      if (JSON.stringify(oldVal) !== JSON.stringify(newVal)) {
        diff[key] = { old: oldVal, new: newVal };
      }
    }

    return diff;
  }
}

export const configManager = new ConfigManager();
