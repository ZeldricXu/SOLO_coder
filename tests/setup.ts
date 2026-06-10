import { beforeAll, afterAll, beforeEach, vi } from 'vitest';
import {
  PostgreSqlContainer,
  StartedPostgreSqlContainer,
} from '@testcontainers/postgresql';
import {
  ElasticsearchContainer,
  StartedElasticsearchContainer,
} from '@testcontainers/elasticsearch';
import {
  RedisContainer,
  StartedRedisContainer,
} from '@testcontainers/redis';
import { PrismaClient } from '@prisma/client';
import { execSync } from 'child_process';
import * as fs from 'fs';
import * as path from 'path';

let postgresContainer: StartedPostgreSqlContainer;
let elasticsearchContainer: StartedElasticsearchContainer;
let redisContainer: StartedRedisContainer;
let prisma: PrismaClient;

declare global {
  var __TEST_CONTAINERS__: {
    postgres: StartedPostgreSqlContainer;
    elasticsearch: StartedElasticsearchContainer;
    redis: StartedRedisContainer;
    prisma: PrismaClient;
  };
}

beforeAll(async () => {
  console.log('\n🚀 Starting test containers...');

  postgresContainer = await new PostgreSqlContainer('postgres:14-alpine')
    .withDatabase('cms_test')
    .withUsername('test')
    .withPassword('test')
    .withExposedPorts(5432)
    .start();

  console.log(`📦 PostgreSQL started on port ${postgresContainer.getPort()}`);

  elasticsearchContainer = await new ElasticsearchContainer(
    'docker.elastic.co/elasticsearch/elasticsearch:8.12.0-arm64'
  )
    .withEnvironment({
      'discovery.type': 'single-node',
      'xpack.security.enabled': 'false',
      'ES_JAVA_OPTS': '-Xms256m -Xmx256m',
    })
    .withExposedPorts(9200)
    .start();

  console.log(`🔍 Elasticsearch started on port ${elasticsearchContainer.getPort()}`);

  redisContainer = await new RedisContainer('redis:7-alpine')
    .withExposedPorts(6379)
    .start();

  console.log(`📡 Redis started on port ${redisContainer.getPort()}`);

  const databaseUrl = postgresContainer.getConnectionUri();
  const elasticsearchNode = `http://${elasticsearchContainer.getHost()}:${elasticsearchContainer.getPort()}`;
  const redisHost = redisContainer.getHost();
  const redisPort = redisContainer.getPort();

  const redisUrl = `redis://${redisHost}:${redisPort}`;
  const jwtSecret = 'test-jwt-secret-key-for-testing-purposes';
  const cdnBaseUrl = 'https://cdn.test.local';
  const cdnApiKey = 'test-cdn-api-key';
  const tenantDbTemplate = databaseUrl.replace('/cms_test', '/tenant_{tenant_id}');

  process.env.DATABASE_URL = databaseUrl;
  process.env.ELASTICSEARCH_NODE = elasticsearchNode;
  process.env.REDIS_HOST = redisHost;
  process.env.REDIS_PORT = String(redisPort);
  process.env.REDIS_URL = redisUrl;
  process.env.BULLMQ_REDIS_URL = redisUrl;
  process.env.TENANT_DATABASE_URL_TEMPLATE = tenantDbTemplate;
  process.env.JWT_SECRET = jwtSecret;
  process.env.CDN_BASE_URL = cdnBaseUrl;
  process.env.CDN_API_KEY = cdnApiKey;
  process.env.NODE_ENV = 'test';
  process.env.LOG_LEVEL = 'error';
  process.env.PORT = '3001';
  process.env.HOST = '0.0.0.0';

  const envContent = `
DATABASE_URL=${databaseUrl}
ELASTICSEARCH_NODE=${elasticsearchNode}
REDIS_HOST=${redisHost}
REDIS_PORT=${redisPort}
REDIS_URL=${redisUrl}
BULLMQ_REDIS_URL=${redisUrl}
TENANT_DATABASE_URL_TEMPLATE=${tenantDbTemplate}
JWT_SECRET=${jwtSecret}
CDN_BASE_URL=${cdnBaseUrl}
CDN_API_KEY=${cdnApiKey}
NODE_ENV=test
LOG_LEVEL=error
PORT=3001
HOST=0.0.0.0
  `.trim();

  fs.writeFileSync(path.join(__dirname, '..', '.env.test'), envContent);

  console.log('\n⚙️  Running Prisma migrations...');
  execSync('npx prisma migrate deploy', {
    cwd: path.join(__dirname, '..'),
    stdio: 'inherit',
  });

  prisma = new PrismaClient({
    datasources: {
      db: {
        url: databaseUrl,
      },
    },
  });

  await prisma.$connect();

  global.__TEST_CONTAINERS__ = {
    postgres: postgresContainer,
    elasticsearch: elasticsearchContainer,
    redis: redisContainer,
    prisma,
  };

  console.log('\n✅ All test containers started successfully\n');
}, 180000);

afterAll(async () => {
  console.log('\n🛑 Stopping test containers...');

  if (prisma) {
    await prisma.$disconnect();
  }

  if (postgresContainer) {
    await postgresContainer.stop();
    console.log('📦 PostgreSQL stopped');
  }

  if (elasticsearchContainer) {
    await elasticsearchContainer.stop();
    console.log('🔍 Elasticsearch stopped');
  }

  if (redisContainer) {
    await redisContainer.stop();
    console.log('📡 Redis stopped');
  }

  console.log('\n✅ All test containers stopped\n');
}, 60000);

beforeEach(async () => {
  if (prisma) {
    const tableNames = [
      'WebhookDelivery',
      'WebhookConfig',
      'CDNPublishStatus',
      'SearchConfig',
      'WorkflowInstance',
      'WorkflowDefinition',
      'ContentVersion',
      'ContentEntry',
      'ContentModel',
      'TenantUsage',
      'Tenant',
    ];

    for (const table of tableNames) {
      try {
        await prisma.$executeRawUnsafe(
          `TRUNCATE TABLE "${table}" RESTART IDENTITY CASCADE`
        );
      } catch (e) {
        console.warn(`⚠️  Could not truncate ${table}:`, e);
      }
    }
  }

  vi.clearAllMocks();
  vi.clearAllTimers();
});

export const getTestContainers = () => global.__TEST_CONTAINERS__;
