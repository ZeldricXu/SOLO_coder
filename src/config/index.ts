import dotenv from 'dotenv';
import path from 'path';

const envFile = process.env.NODE_ENV
  ? `.env.${process.env.NODE_ENV}`
  : '.env';

dotenv.config({
  path: path.resolve(process.cwd(), envFile),
  override: true,
});

dotenv.config({
  path: path.resolve(process.cwd(), '.env'),
  override: false,
});

export const config = {
  env: process.env.NODE_ENV || 'development',
  server: {
    port: parseInt(process.env.SERVER_PORT || '3000', 10),
    host: process.env.SERVER_HOST || '0.0.0.0',
  },
  jwt: {
    secret: process.env.JWT_SECRET || 'dev-secret-change-in-production',
    expiresIn: process.env.JWT_EXPIRES_IN || '1h',
    issuer: process.env.JWT_ISSUER || 'metricplatform',
  },
  rateLimit: {
    maxRequests: parseInt(process.env.RATE_LIMIT_MAX || '100', 10),
    windowMs: parseInt(process.env.RATE_LIMIT_WINDOW || '60000', 10),
    keyPrefix: 'rate_limit:',
  },
  redis: {
    host: process.env.REDIS_HOST || 'localhost',
    port: parseInt(process.env.REDIS_PORT || '6379', 10),
    db: parseInt(process.env.REDIS_DB || '0', 10),
  },
  logging: {
    level: process.env.LOG_LEVEL || 'info',
    prettyPrint: process.env.LOG_PRETTY === 'true',
  },
  storage: {
    type: process.env.STORAGE_TYPE || 'memory',
    s3: {
      accessKeyId: process.env.S3_ACCESS_KEY || '',
      secretAccessKey: process.env.S3_SECRET_KEY || '',
      region: process.env.S3_REGION || 'us-east-1',
      bucket: process.env.S3_BUCKET || 'metricplatform',
    },
  },
  profiling: {
    cpuSamplingInterval: parseInt(process.env.CPU_SAMPLING_INTERVAL || '10', 10),
    memorySamplingInterval: parseInt(process.env.MEMORY_SAMPLING_INTERVAL || '1000', 10),
    maxProfileDuration: parseInt(process.env.MAX_PROFILE_DURATION || '300000', 10),
  },
  notification: {
    maxRetries: parseInt(process.env.NOTIFICATION_MAX_RETRIES || '3', 10),
    retryDelayMs: parseInt(process.env.NOTIFICATION_RETRY_DELAY || '5000', 10),
    channels: {
      email: { enabled: process.env.EMAIL_ENABLED === 'true' },
      webhook: { enabled: process.env.WEBHOOK_ENABLED === 'true' },
      slack: { enabled: process.env.SLACK_ENABLED === 'true' },
    },
  },
  metrics: {
    aggregationWindow: parseInt(process.env.METRICS_WINDOW || '60000', 10),
    maxDataPoints: parseInt(process.env.METRICS_MAX_POINTS || '10000', 10),
    storageType: process.env.METRICS_STORAGE || 'memory',
  },
};
