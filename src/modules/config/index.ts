import * as fs from 'fs-extra';
import * as path from 'path';
import * as yaml from 'yaml';
import { ConfigDefinition } from '../types';
import logger from '../utils/logger';
import { eventBus } from '../utils/eventBus';
import { currentTimestamp, generateId } from '../utils/helpers';

export interface ConfigSource {
  type: 'file' | 'env' | 'remote';
  path?: string;
  url?: string;
  prefix?: string;
}

export interface ConfigUpdateEvent {
  namespace: string;
  oldConfig: ConfigDefinition | null;
  newConfig: ConfigDefinition;
}

export class ConfigManager {
  private configs: Map<string, ConfigDefinition> = new Map();
  private sources: ConfigSource[] = [];
  private watchInterval?: NodeJS.Timeout;
  private lastModified: Map<string, number> = new Map();

  constructor(sources: ConfigSource[] = []) {
    this.sources = sources;
  }

  async initialize(): Promise<void> {
    for (const source of this.sources) {
      await this.loadFromSource(source);
    }
    this.startWatcher();
    logger.info('ConfigManager initialized', { sourceCount: this.sources.length });
  }

  private async loadFromSource(source: ConfigSource): Promise<void> {
    try {
      switch (source.type) {
        case 'file':
          await this.loadFromFile(source.path!);
          break;
        case 'env':
          this.loadFromEnv(source.prefix || 'APP_');
          break;
        case 'remote':
          await this.loadFromRemote(source.url!);
          break;
      }
    } catch (error) {
      logger.error('Failed to load config from source', { source, error });
    }
  }

  private async loadFromFile(filePath: string): Promise<void> {
    const absolutePath = path.resolve(filePath);
    if (!await fs.pathExists(absolutePath)) {
      logger.warn('Config file not found', { path: absolutePath });
      return;
    }

    const stat = await fs.stat(absolutePath);
    const lastMod = this.lastModified.get(absolutePath);
    if (lastMod && lastMod >= stat.mtimeMs) {
      return;
    }
    this.lastModified.set(absolutePath, stat.mtimeMs);

    const content = await fs.readFile(absolutePath, 'utf-8');
    let parsed: Record<string, any>;

    if (absolutePath.endsWith('.yaml') || absolutePath.endsWith('.yml')) {
      parsed = yaml.parse(content);
    } else if (absolutePath.endsWith('.json')) {
      parsed = JSON.parse(content);
    } else {
      logger.warn('Unsupported config file format', { path: absolutePath });
      return;
    }

    await this.processConfigObject(parsed);
    logger.debug('Loaded config from file', { path: absolutePath });
  }

  private loadFromEnv(prefix: string): void {
    const envConfig: Record<string, any> = {};
    
    for (const [key, value] of Object.entries(process.env)) {
      if (key.startsWith(prefix)) {
        const configKey = key.slice(prefix.length).toLowerCase().replace(/_/g, '.');
        const parts = configKey.split('.');
        let current = envConfig;
        for (let i = 0; i < parts.length - 1; i++) {
          if (!current[parts[i]]) {
            current[parts[i]] = {};
          }
          current = current[parts[i]];
        }
        current[parts[parts.length - 1]] = this.parseEnvValue(value);
      }
    }

    if (Object.keys(envConfig).length > 0) {
      this.processConfigObject({ namespace: 'env', parameters: envConfig });
      logger.debug('Loaded config from environment', { prefix });
    }
  }

  private parseEnvValue(value: string): any {
    if (value === 'true') return true;
    if (value === 'false') return false;
    if (value === 'null') return null;
    if (/^-?\d+$/.test(value)) return parseInt(value, 10);
    if (/^-?\d+\.\d+$/.test(value)) return parseFloat(value);
    return value;
  }

  private async loadFromRemote(url: string): Promise<void> {
    try {
      const response = await fetch(url);
      if (!response.ok) {
        throw new Error(`HTTP ${response.status}`);
      }
      const data = await response.json();
      await this.processConfigObject(data);
      logger.debug('Loaded config from remote', { url });
    } catch (error) {
      logger.error('Failed to load remote config', { url, error });
    }
  }

  private async processConfigObject(obj: Record<string, any>): Promise<void> {
    const namespace = obj.namespace || 'default';
    const existing = this.configs.get(namespace);
    const newVersion = existing ? existing.version + 1 : 1;

    const config: ConfigDefinition = {
      config_id: existing?.config_id || generateId('cfg_'),
      namespace,
      version: newVersion,
      parameters: obj.parameters || obj,
      enabled: obj.enabled !== false,
      applied_at: currentTimestamp(),
    };

    this.configs.set(namespace, config);

    if (existing && JSON.stringify(existing.parameters) !== JSON.stringify(config.parameters)) {
      eventBus.emit('config.updated', {
        namespace,
        oldConfig: existing,
        newConfig: config,
      } as ConfigUpdateEvent);
      logger.info('Config updated', { namespace, version: newVersion });
    }
  }

  getConfig(namespace: string = 'default'): ConfigDefinition | undefined {
    return this.configs.get(namespace);
  }

  getParameter<T = any>(key: string, namespace: string = 'default', defaultValue?: T): T {
    const config = this.configs.get(namespace);
    if (!config) {
      return defaultValue as T;
    }
    const parts = key.split('.');
    let value: any = config.parameters;
    for (const part of parts) {
      if (value && typeof value === 'object' && part in value) {
        value = value[part];
      } else {
        return defaultValue as T;
      }
    }
    return value as T;
  }

  async setConfig(namespace: string, parameters: Record<string, any>): Promise<ConfigDefinition> {
    const existing = this.configs.get(namespace);
    const newVersion = existing ? existing.version + 1 : 1;

    const config: ConfigDefinition = {
      config_id: existing?.config_id || generateId('cfg_'),
      namespace,
      version: newVersion,
      parameters,
      enabled: true,
      applied_at: currentTimestamp(),
    };

    this.configs.set(namespace, config);

    eventBus.emit('config.updated', {
      namespace,
      oldConfig: existing || null,
      newConfig: config,
    } as ConfigUpdateEvent);

    return config;
  }

  listConfigs(): ConfigDefinition[] {
    return Array.from(this.configs.values());
  }

  private startWatcher(): void {
    this.watchInterval = setInterval(async () => {
      for (const source of this.sources) {
        if (source.type === 'file' && source.path) {
          await this.loadFromFile(source.path);
        }
      }
    }, 30000);
  }

  stopWatcher(): void {
    if (this.watchInterval) {
      clearInterval(this.watchInterval);
    }
  }

  onConfigUpdate(namespace: string, handler: (event: ConfigUpdateEvent) => void): void {
    eventBus.on('config.updated', (event: ConfigUpdateEvent) => {
      if (event.namespace === namespace) {
        handler(event);
      }
    });
  }
}

export const configManager = new ConfigManager([
  { type: 'file', path: './config/config.yaml' },
  { type: 'env', prefix: 'APP_' },
]);
