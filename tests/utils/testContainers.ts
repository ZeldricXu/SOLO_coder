import { PostgreSqlContainer, StartedPostgreSqlContainer } from '@testcontainers/postgresql';
import { RedisContainer, StartedRedisContainer } from '@testcontainers/redis';
import { Pool } from 'pg';
import Redis from 'ioredis';
import { db } from '../../src/db';
import { runMigrations } from '../../src/db/migrate';
import { logger } from '../../src/utils/logger';

export interface TestInfrastructure {
  postgresContainer: StartedPostgreSqlContainer;
  redisContainer: StartedRedisContainer;
  pgPool: Pool;
  redisClient: Redis;
  postgresUrl: string;
  redisUrl: string;
}

let testInfrastructure: TestInfrastructure | null = null;

export async function startTestInfrastructure(): Promise<TestInfrastructure> {
  if (testInfrastructure) {
    return testInfrastructure;
  }

  logger.info('Starting test infrastructure...');

  const postgresContainer = await new PostgreSqlContainer('postgres:15-alpine')
    .withDatabase('notification_test')
    .withUsername('test')
    .withPassword('test')
    .withExposedPorts(5432)
    .start();

  const redisContainer = await new RedisContainer('redis:7-alpine')
    .withExposedPorts(6379)
    .start();

  const postgresUrl = postgresContainer.getConnectionUri();
  const redisUrl = `redis://${redisContainer.getHost()}:${redisContainer.getMappedPort(6379)}`;

  process.env.DATABASE_URL = postgresUrl;
  process.env.REDIS_URL = redisUrl;

  const pgPool = new Pool({ connectionString: postgresUrl });
  const redisClient = new Redis(redisUrl);

  await pgPool.query('CREATE EXTENSION IF NOT EXISTS "uuid-ossp"');

  await runMigrations();

  testInfrastructure = {
    postgresContainer,
    redisContainer,
    pgPool,
    redisClient,
    postgresUrl,
    redisUrl,
  };

  logger.info('Test infrastructure started successfully');

  return testInfrastructure;
}

export async function stopTestInfrastructure(): Promise<void> {
  if (!testInfrastructure) {
    return;
  }

  logger.info('Stopping test infrastructure...');

  try {
    await testInfrastructure.pgPool.end();
    await testInfrastructure.redisClient.disconnect();
    await db.close();
  } catch (err) {
    logger.warn('Error cleaning up database connections', err);
  }

  try {
    await testInfrastructure.postgresContainer.stop();
    await testInfrastructure.redisContainer.stop();
  } catch (err) {
    logger.warn('Error stopping containers', err);
  }

  testInfrastructure = null;
  logger.info('Test infrastructure stopped');
}

export async function resetTestDatabase(): Promise<void> {
  if (!testInfrastructure) {
    throw new Error('Test infrastructure not started');
  }

  const tables = [
    'delivery_logs',
    'templates',
    'user_preferences',
    'webhook_logs',
    'webhook_endpoints',
    'routing_rules',
    'channel_health',
    'audit_logs',
  ];

  for (const table of tables) {
    await testInfrastructure.pgPool.query(`TRUNCATE TABLE ${table} CASCADE`);
  }
}

export async function flushTestRedis(): Promise<void> {
  if (!testInfrastructure) {
    throw new Error('Test infrastructure not started');
  }

  await testInfrastructure.redisClient.flushdb();
}
