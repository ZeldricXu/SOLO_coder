import 'dotenv/config';
import { z } from 'zod';

const envSchema = z.object({
  NODE_ENV: z.enum(['development', 'production', 'test']).default('development'),
  DATABASE_URL: z.string().min(1),
  DATABASE_READ_REPLICA_URL: z.string().optional(),
  REDIS_URL: z.string().min(1),
  S3_ENDPOINT: z.string().min(1),
  S3_REGION: z.string().min(1),
  S3_ACCESS_KEY: z.string().min(1),
  S3_SECRET_KEY: z.string().min(1),
  S3_BUCKET_MODELS: z.string().min(1).default('models'),
  S3_BUCKET_FEATURES: z.string().min(1).default('features'),
  S3_BUCKET_ARTIFACTS: z.string().min(1).default('artifacts'),
  STORAGE_BACKEND: z.enum(['s3', 'local']).default('local'),
  LOCAL_STORAGE_PATH: z.string().min(1).default('./storage'),
  SERVER_HOST: z.string().default('0.0.0.0'),
  SERVER_PORT: z.coerce.number().default(3001),
  GRPC_PORT: z.coerce.number().default(50051),
  LOG_LEVEL: z.enum(['trace', 'debug', 'info', 'warn', 'error', 'fatal']).default('info'),
  INFERENCE_BATCH_MAX_SIZE: z.coerce.number().default(32),
  INFERENCE_BATCH_TIMEOUT_MS: z.coerce.number().default(10),
  FEATURE_IMPORT_BATCH_SIZE: z.coerce.number().default(10000),
  INFERENCE_CACHE_TTL_SECONDS: z.coerce.number().default(300),
  METRICS_RETENTION_DAYS: z.coerce.number().default(30),
  DRIFT_DETECTION_INTERVAL_MINUTES: z.coerce.number().default(60),
  DRIFT_THRESHOLD_P_VALUE: z.coerce.number().default(0.05),
});

export const env = envSchema.parse(process.env);

export type Env = typeof env;
