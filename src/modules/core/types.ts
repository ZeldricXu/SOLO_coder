export interface TransformRule {
  id: string;
  name: string;
  sourceField: string;
  targetField: string;
  transform: 'uppercase' | 'lowercase' | 'trim' | 'number' | 'date' | 'custom';
  customFn?: string;
  enabled: boolean;
}

export interface StandardizationConfig {
  charset: 'utf-8' | 'ascii' | 'gbk';
  dateFormat: string;
  timezone: string;
  decimalPlaces: number;
  trimWhitespace: boolean;
  nullHandling: 'keep' | 'remove' | 'default';
  defaultValue?: any;
}

export interface ProcessingConfig {
  poolSize: number;
  timeout: number;
  retries: number;
  rules: TransformRule[];
  standardization: StandardizationConfig;
  batchSize: number;
  concurrency: number;
}

export interface ProcessRequest {
  traceId: string;
  namespace: string;
  params: Record<string, any>;
  payload: any;
}

export interface CacheConfig {
  enabled: boolean;
  maxSize: number;
  ttl: number;
  warmupOnStartup: boolean;
  warmupKeys: string[];
  cacheByNamespace: boolean;
  hotDataThreshold: number;
}

export interface CacheEntry<T = any> {
  key: string;
  value: T;
  createdAt: number;
  accessedAt: number;
  accessCount: number;
  hash: string;
}

export interface CacheStats {
  hits: number;
  misses: number;
  evictions: number;
  size: number;
  hitRate: number;
  hotKeys: string[];
}

export interface WarmupResult {
  preloadedKeys: number;
  failedKeys: number;
  duration: number;
}
