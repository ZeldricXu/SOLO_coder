import * as dotenv from 'dotenv';
import * as fs from 'fs';
import * as path from 'path';

export type Environment = 'development' | 'testing' | 'production';

export interface EnvConfig {
  env: Environment;
  isDevelopment: boolean;
  isTesting: boolean;
  isProduction: boolean;
}

export function loadEnvironmentConfig(): EnvConfig {
  const env = (process.env.NODE_ENV as Environment) || 'development';

  const envFiles = [
    `.env.${env}.local`, `.env.${env}`, '.env.local', '.env'
  ]
  .map(file => path.resolve(process.cwd(), file))
  .filter(file => fs.existsSync(file));

  for (const envFile of envFiles) {
    dotenv.config({ path: envFile, override: false });
  }

  process.env.NODE_ENV = env;

  return {
    env,
    isDevelopment: env === 'development',
    isTesting: env === 'testing',
    isProduction: env === 'production'
  };
}

export function getEnv(key: string, defaultValue?: string): string | undefined {
  return process.env[key] ?? defaultValue;
}

export function getEnvNumber(key: string, defaultValue: number): number {
  const value = process.env[key];
  if (value === undefined || value === '') return defaultValue;
  const parsed = parseInt(value, 10);
  return isNaN(parsed) ? defaultValue : parsed;
}

export function getEnvBoolean(key: string, defaultValue: boolean): boolean {
  const value = process.env[key];
  if (value === undefined || value === '') return defaultValue;
  return value === 'true' || value === '1' || value === 'yes';
}

export function getEnvArray(key: string, separator: string = ','): string[] {
  const value = process.env[key];
  if (!value) return [];
  return value.split(separator).map(s => s.trim()).filter(Boolean);
}

export function getConfig<T>(key: string, parser: (value: string) => T | undefined): T | undefined {
  const value = process.env[key];
  if (!value) return undefined;
  try {
    return parser(value);
  } catch {
    return undefined;
  }
}

export const envConfig = loadEnvironmentConfig();

export const config = {
  app: {
    env: envConfig.env,
    isDev: envConfig.isDevelopment,
    isTest: envConfig.isTesting,
    isProd: envConfig.isProduction,
    port: getEnvNumber('PORT', 3000),
    host: getEnv('HOST', '0.0.0.0'),
    debug: getEnvBoolean('DEBUG', false),
    traceEnabled: getEnvBoolean('TRACE_ENABLED', false)
  },
  log: {
    level: getEnv('LOG_LEVEL', 'info') as any,
    json: getEnvBoolean('LOG_JSON', true),
    dir: getEnv('LOG_DIR', './logs')
  },
  jwt: {
    secret: getEnv('JWT_SECRET', 'change-me'),
    expiresIn: getEnv('JWT_EXPIRES_IN', '15m'),
    refreshExpiresIn: getEnv('JWT_REFRESH_EXPIRES_IN', '7d')
  },
  rateLimit: {
    max: getEnvNumber('RATE_LIMIT_MAX', 100),
    windowMs: getEnvNumber('RATE_LIMIT_WINDOW', 60000),
    tenantMax: getEnvNumber('TENANT_RATE_LIMIT_MAX', 1000),
    tenantWindowMs: getEnvNumber('TENANT_RATE_LIMIT_WINDOW', 60000),
    userMax: getEnvNumber('USER_RATE_LIMIT_MAX', 200),
    userWindowMs: getEnvNumber('USER_RATE_LIMIT_WINDOW', 60000)
  },
  redis: {
    host: getEnv('REDIS_HOST', 'localhost'),
    port: getEnvNumber('REDIS_PORT', 6379),
    password: getEnv('REDIS_PASSWORD'),
    db: getEnvNumber('REDIS_DB', 0),
    clusterEnabled: getEnvBoolean('REDIS_CLUSTER_ENABLED', false),
    tlsEnabled: getEnvBoolean('REDIS_TLS_ENABLED', false)
  },
  database: {
    url: getEnv('DATABASE_URL'),
    poolMin: getEnvNumber('DATABASE_POOL_MIN', 2),
    poolMax: getEnvNumber('DATABASE_POOL_MAX', 20),
    timeout: getEnvNumber('DATABASE_TIMEOUT', 30000),
    sslEnabled: getEnvBoolean('DATABASE_SSL_ENABLED', false)
  },
  concurrency: {
    maxConcurrent: getEnvNumber('MAX_CONCURRENT', 100),
    schedulerMaxConcurrent: getEnvNumber('SCHEDULER_MAX_CONCURRENT', 10),
    schedulerRetry: getEnvNumber('SCHEDULER_RETRY', 3)
  },
  billing: {
    priceApiCall: getEnvNumber('PRICE_API_CALL', 0.001),
    priceStorage: getEnvNumber('PRICE_STORAGE', 0.05),
    priceCompute: getEnvNumber('PRICE_COMPUTE', 0.01),
    priceBandwidth: getEnvNumber('PRICE_BANDWIDTH', 0.02),
    currency: getEnv('BILLING_CURRENCY', 'CNY'),
    cycleDays: getEnvNumber('BILLING_CYCLE_DAYS', 30)
  },
  backup: {
    dir: getEnv('BACKUP_DIR', './backups'),
    compression: getEnvNumber('BACKUP_COMPRESSION', 6),
    encryption: getEnvBoolean('BACKUP_ENCRYPTION', false),
    encryptionKey: getEnv('BACKUP_ENCRYPTION_KEY'),
    retentionDays: getEnvNumber('BACKUP_RETENTION_DAYS', 30)
  },
  cors: {
    origin: getEnv('CORS_ORIGIN', '*'),
    credentials: getEnvBoolean('CORS_CREDENTIALS', true)
  },
  metrics: {
    enabled: getEnvBoolean('METRICS_ENABLED', true),
    port: getEnvNumber('METRICS_PORT', 9090),
    healthCheckInterval: getEnvNumber('HEALTH_CHECK_INTERVAL', 5000)
  },
  security: {
    sessionSecret: getEnv('SESSION_SECRET'),
    encryptionKey: getEnv('ENCRYPTION_KEY'),
    apiKeyHeader: getEnv('API_KEY_HEADER', 'x-api-key'),
    enableHsts: getEnvBoolean('ENABLE_HSTS', false),
    hstsMaxAge: getEnvNumber('HSTS_MAX_AGE', 31536000)
  }
};
