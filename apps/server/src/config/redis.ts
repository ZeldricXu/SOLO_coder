import Redis from 'ioredis';
import { env } from './env';
import { logger } from './logger';

let redis: Redis;
let redisCache: Redis;

export function connectRedis(): void {
  redis = new Redis(env.REDIS_URL, {
    lazyConnect: true,
    enableReadyCheck: true,
    maxRetriesPerRequest: 3,
    reconnectOnError: (err) => {
      logger.warn({ error: err.message }, 'Redis reconnecting');
      return true;
    },
  });

  redis.on('connect', () => {
    logger.info('Redis connected successfully');
  });

  redis.on('error', (err) => {
    logger.error({ error: err.message }, 'Redis connection error');
  });

  redisCache = redis.duplicate();

  redis.connect().catch((err) => {
    logger.error({ error: err.message }, 'Failed to connect to Redis');
  });
}

export async function disconnectRedis(): Promise<void> {
  try {
    await redis.quit();
    logger.info('Redis disconnected successfully');
  } catch (error) {
    logger.error({ error }, 'Failed to disconnect from Redis');
  }
}

export { redis, redisCache };

export const RedisKeys = {
  inferenceCache: (modelId: string, version: string, hash: string) =>
    `inference:cache:${modelId}:${version}:${hash}`,
  abAssignment: (experimentId: string, bucketKey: string) =>
    `ab:assignment:${experimentId}:${bucketKey}`,
  abStats: (experimentId: string, variantId: string, metric: string) =>
    `ab:stats:${experimentId}:${variantId}:${metric}`,
  featureValue: (featureSetId: string, version: string, entityKey: string) =>
    `feature:value:${featureSetId}:${version}:${entityKey}`,
  modelLoadLock: (modelId: string, version: string) =>
    `model:load:lock:${modelId}:${version}`,
  batcherQueue: (modelId: string, version: string) =>
    `batcher:queue:${modelId}:${version}`,
  metricsWindow: (modelId: string, window: string) =>
    `metrics:window:${modelId}:${window}`,
};
