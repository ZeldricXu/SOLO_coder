import { v4 as uuidv4 } from 'uuid';
import {
  ConfigEntry,
  ConfigManagerConfig,
  ConfigChangeEvent,
  ConfigValue,
  MAX_HISTORY_SIZE,
} from './types';
import { createSourceLoader, ConfigSourceLoader } from './sources';
import { BaseService } from '../common/base-service';
import { NotFoundError } from '../common/errors';

export class ConfigManager extends BaseService {
  private readonly entries: Map<string, ConfigEntry> = new Map();
  private readonly sourceLoaders: Map<string, ConfigSourceLoader> = new Map();
  private readonly versions: Map<string, number> = new Map();
  private readonly history: Map<string, ConfigEntry[]> = new Map();
  private readonly managerConfig: ConfigManagerConfig;

  constructor(config: ConfigManagerConfig) {
    super('ConfigManager');
    this.managerConfig = config;
    this.initializeSources();
  }

  private initializeSources(): void {
    for (const source of this.managerConfig.sources) {
      const loader = createSourceLoader(source);
      const key = this.getSourceKey(source.type, source.priority);
      this.sourceLoaders.set(key, loader);

      if (this.managerConfig.enableHotReload && loader.watch) {
        loader.watch(async () => {
          await this.loadFromSource(key, loader);
        });
      }
    }
  }

  async load(): Promise<void> {
    this.assertNotDestroyed();

    const sortedSources = this.getSortedSources();
    for (const [key, loader] of sortedSources) {
      await this.loadFromSource(key, loader);
    }

    this.emit('loaded');
  }

  private getSortedSources(): [string, ConfigSourceLoader][] {
    return Array.from(this.sourceLoaders.entries()).sort((a, b) => {
      const priorityA = this.extractPriority(a[0]);
      const priorityB = this.extractPriority(b[0]);
      return priorityB - priorityA;
    });
  }

  private getSourceKey(type: string, priority: number): string {
    return `${type}:${priority}`;
  }

  private extractPriority(sourceKey: string): number {
    return parseInt(sourceKey.split(':')[1], 10);
  }

  private async loadFromSource(key: string, loader: ConfigSourceLoader): Promise<void> {
    const data = await loader.load();
    const namespace = key.split(':')[0];

    for (const [configKey, value] of Object.entries(data)) {
      this.set(configKey, value, namespace);
    }
  }

  set<T extends ConfigValue>(
    key: string,
    value: T,
    namespace: string = this.managerConfig.defaultNamespace,
    description?: string
  ): ConfigEntry<T> {
    this.assertNotDestroyed();

    const fullKey = this.getFullKey(key, namespace);
    const currentVersion = this.versions.get(fullKey) || 0;
    const oldEntry = this.entries.get(fullKey);

    const entry: ConfigEntry<T> = {
      key,
      value,
      namespace,
      version: currentVersion + 1,
      description,
      created_at: oldEntry?.created_at || new Date().toISOString(),
      updated_at: new Date().toISOString(),
    };

    this.entries.set(fullKey, entry);
    this.versions.set(fullKey, entry.version);
    this.updateHistory(fullKey, oldEntry);
    this.emitChangeEvents(entry, oldEntry);

    return entry;
  }

  private getFullKey(key: string, namespace: string): string {
    return `${namespace}:${key}`;
  }

  private updateHistory(fullKey: string, oldEntry?: ConfigEntry): void {
    if (!oldEntry) return;

    const history = this.history.get(fullKey) || [];
    history.push(oldEntry);
    if (history.length > MAX_HISTORY_SIZE) {
      history.shift();
    }
    this.history.set(fullKey, history);
  }

  private emitChangeEvents(newEntry: ConfigEntry, oldEntry?: ConfigEntry): void {
    const changeEvent: ConfigChangeEvent = {
      key: newEntry.key,
      oldValue: oldEntry?.value,
      newValue: newEntry.value,
      namespace: newEntry.namespace,
      timestamp: newEntry.updated_at,
    };

    this.emit('change', changeEvent);
    this.emit(`change:${this.getFullKey(newEntry.key, newEntry.namespace)}`, changeEvent);
  }

  get<T extends ConfigValue>(key: string, namespace?: string): T | undefined {
    this.assertNotDestroyed();

    const ns = namespace || this.managerConfig.defaultNamespace;
    const entry = this.entries.get(this.getFullKey(key, ns));
    return entry?.value as T;
  }

  getEntry<T extends ConfigValue>(key: string, namespace?: string): ConfigEntry<T> | undefined {
    this.assertNotDestroyed();

    const ns = namespace || this.managerConfig.defaultNamespace;
    return this.entries.get(this.getFullKey(key, ns)) as ConfigEntry<T>;
  }

  getOrThrow<T extends ConfigValue>(key: string, namespace?: string): T {
    const value = this.get<T>(key, namespace);
    if (value === undefined) {
      throw new NotFoundError(`Config key not found: ${key}`);
    }
    return value;
  }

  delete(key: string, namespace?: string): boolean {
    this.assertNotDestroyed();

    const ns = namespace || this.managerConfig.defaultNamespace;
    const fullKey = this.getFullKey(key, ns);
    const entry = this.entries.get(fullKey);

    if (entry) {
      this.entries.delete(fullKey);
      this.versions.delete(fullKey);
      this.emit('delete', { key, namespace: ns, timestamp: new Date().toISOString() });
      return true;
    }

    return false;
  }

  has(key: string, namespace?: string): boolean {
    this.assertNotDestroyed();

    const ns = namespace || this.managerConfig.defaultNamespace;
    return this.entries.has(this.getFullKey(key, ns));
  }

  list(namespace?: string): ConfigEntry[] {
    this.assertNotDestroyed();

    const entries = Array.from(this.entries.values());
    return namespace ? entries.filter(e => e.namespace === namespace) : entries;
  }

  getHistory(key: string, namespace?: string): ConfigEntry[] {
    this.assertNotDestroyed();

    const ns = namespace || this.managerConfig.defaultNamespace;
    return this.history.get(this.getFullKey(key, ns)) || [];
  }

  rollback(key: string, version: number, namespace?: string): ConfigEntry | null {
    this.assertNotDestroyed();

    const ns = namespace || this.managerConfig.defaultNamespace;
    const history = this.getHistory(key, ns);
    const targetEntry = history.find(e => e.version === version);

    if (targetEntry) {
      return this.set(key, targetEntry.value, ns, targetEntry.description);
    }

    return null;
  }

  subscribe(key: string, callback: (event: ConfigChangeEvent) => void): () => void {
    const handler = (event: ConfigChangeEvent) => {
      if (event.key === key) callback(event);
    };
    this.on('change', handler);
    return () => this.off('change', handler);
  }

  startWatch(interval?: number): void {
    this.assertNotDestroyed();

    if (this.timers.size > 0) return;

    const watchInterval = setInterval(
      async () => {
        await this.load();
      },
      interval || this.managerConfig.watchInterval
    );

    this.addTimer(watchInterval);
  }

  stopWatch(): void {
    this.clearTimers();
  }

  export(namespace?: string): Record<string, ConfigValue> {
    this.assertNotDestroyed();

    const entries = namespace ? this.list(namespace) : this.list();
    return entries.reduce((acc, entry) => {
      acc[entry.key] = entry.value;
      return acc;
    }, {} as Record<string, ConfigValue>);
  }

  import(data: Record<string, ConfigValue>, namespace: string): void {
    this.assertNotDestroyed();

    for (const [key, value] of Object.entries(data)) {
      this.set(key, value, namespace);
    }
  }

  override destroy(): void {
    this.stopWatch();
    super.destroy();
  }
}

export * from './types';
export * from './sources';
