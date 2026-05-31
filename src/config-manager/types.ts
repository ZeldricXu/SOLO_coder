export interface ConfigSource {
  type: 'env' | 'file' | 'remote' | 'database';
  priority: number;
  options: Record<string, unknown>;
}

export interface ConfigEntry<T = unknown> {
  key: string;
  value: T;
  namespace: string;
  version: number;
  description?: string;
  schema?: Record<string, unknown>;
  created_at: string;
  updated_at: string;
}

export interface ConfigChangeEvent<T = unknown> {
  key: string;
  oldValue?: T;
  newValue: T;
  namespace: string;
  timestamp: string;
}

export interface ConfigManagerConfig {
  sources: ConfigSource[];
  defaultNamespace: string;
  watchInterval: number;
  enableHotReload: boolean;
}

export type ConfigValue = string | number | boolean | null | Record<string, unknown> | unknown[];

export const MAX_HISTORY_SIZE = 10;
