import 'dotenv/config';
import { z } from 'zod';

const configSchema = z.object({
  nodeEnv: z.enum(['development', 'test', 'staging', 'production']).default('development'),
  port: z.coerce.number().default(3000),
  host: z.string().default('0.0.0.0'),
  databaseUrl: z.string(),
  tenantDatabaseUrlTemplate: z.string(),
  redisUrl: z.string(),
  redisCluster: z.coerce.boolean().default(false),
  elasticsearchNode: z.string(),
  elasticsearchUsername: z.string().optional(),
  elasticsearchPassword: z.string().optional(),
  jwtSecret: z.string(),
  jwtExpiresIn: z.string().default('24h'),
  bullmqRedisUrl: z.string(),
  rateLimitWindowMs: z.coerce.number().default(60000),
  rateLimitMaxRequests: z.coerce.number().default(1000),
  cdnBaseUrl: z.string(),
  cdnApiKey: z.string(),
  cdnProvider: z.enum(['aliyun', 'qiniu', 'cloudflare', 'aws']).default('aliyun'),
  logLevel: z.enum(['trace', 'debug', 'info', 'warn', 'error', 'fatal']).default('info'),
  logPretty: z.coerce.boolean().default(false),
});

export const config = configSchema.parse({
  nodeEnv: process.env.NODE_ENV,
  port: process.env.PORT,
  host: process.env.HOST,
  databaseUrl: process.env.DATABASE_URL,
  tenantDatabaseUrlTemplate: process.env.TENANT_DATABASE_URL_TEMPLATE,
  redisUrl: process.env.REDIS_URL,
  redisCluster: process.env.REDIS_CLUSTER,
  elasticsearchNode: process.env.ELASTICSEARCH_NODE,
  elasticsearchUsername: process.env.ELASTICSEARCH_USERNAME,
  elasticsearchPassword: process.env.ELASTICSEARCH_PASSWORD,
  jwtSecret: process.env.JWT_SECRET,
  jwtExpiresIn: process.env.JWT_EXPIRES_IN,
  bullmqRedisUrl: process.env.BULLMQ_REDIS_URL,
  rateLimitWindowMs: process.env.RATE_LIMIT_WINDOW_MS,
  rateLimitMaxRequests: process.env.RATE_LIMIT_MAX_REQUESTS,
  cdnBaseUrl: process.env.CDN_BASE_URL,
  cdnApiKey: process.env.CDN_API_KEY,
  cdnProvider: process.env.CDN_PROVIDER,
  logLevel: process.env.LOG_LEVEL,
  logPretty: process.env.LOG_PRETTY,
});

export type Config = typeof config;
