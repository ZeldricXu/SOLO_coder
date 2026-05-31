export interface ConfigDefinition {
  config_id: string;
  namespace: string;
  version: number;
  parameters: Record<string, unknown>;
  enabled: boolean;
  applied_at: string;
  created_by?: string;
  description?: string;
}

export interface ConfigSource {
  type: 'env' | 'file' | 'redis' | 'database' | 'http';
  priority: number;
  name: string;
  options?: Record<string, unknown>;
}

export interface ConfigChangeEvent {
  config_id: string;
  namespace: string;
  old_value: unknown;
  new_value: unknown;
  timestamp: string;
  source: string;
}

export interface ConfigListener {
  (event: ConfigChangeEvent): void | Promise<void>;
}

export interface DatabaseConfig {
  url: string;
  poolMin: number;
  poolMax: number;
  idleTimeout: number;
  connectionTimeout: number;
  queryTimeout: number;
}

export interface RedisConfig {
  host: string;
  port: number;
  password?: string;
  db: number;
  keyPrefix?: string;
}

export interface RateLimitConfig {
  windowMs: number;
  maxRequests: number;
  message: string;
  statusCode: number;
}

export interface LogConfig {
  level: 'debug' | 'info' | 'warn' | 'error' | 'fatal';
  filePath?: string;
  maxSize?: string;
  maxFiles?: number;
}

export interface BillingConfig {
  cycleDays: number;
  pricePerApiCall: number;
  pricePerStorageGb: number;
  pricePerComputeUnit: number;
  currency: string;
}

export interface AppConfig {
  env: string;
  port: number;
  jwtSecret: string;
  jwtExpiresIn: string;
  database: DatabaseConfig;
  redis: RedisConfig;
  rateLimit: RateLimitConfig;
  log: LogConfig;
  billing: BillingConfig;
  backupPath: string;
  backupSchedule: string;
}
