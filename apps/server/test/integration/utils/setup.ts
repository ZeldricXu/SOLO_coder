import {
  PostgreSqlContainer,
  StartedPostgreSqlContainer,
} from '@testcontainers/postgresql';
import { RedisContainer, StartedRedisContainer } from '@testcontainers/redis';
import { PrismaClient } from '@prisma/client';
import Redis from 'ioredis';
import { exec } from 'child_process';
import { promisify } from 'util';

const execAsync = promisify(exec);

export interface TestInfrastructure {
  postgresContainer: StartedPostgreSqlContainer;
  redisContainer: StartedRedisContainer;
  prisma: PrismaClient;
  redis: Redis;
  cleanup: () => Promise<void>;
}

export async function setupTestInfrastructure(): Promise<TestInfrastructure> {
  const postgresContainer = await new PostgreSqlContainer('postgres:16-alpine')
    .withDatabase('mlops_test')
    .withUsername('mlops')
    .withPassword('mlops123')
    .withExposedPorts(5432)
    .start();

  const redisContainer = await new RedisContainer('redis:7-alpine')
    .withExposedPorts(6379)
    .start();

  const databaseUrl = postgresContainer.getConnectionUri();
  const redisUrl = `redis://${redisContainer.getHost()}:${redisContainer.getMappedPort(6379)}`;

  process.env.DATABASE_URL = databaseUrl;
  process.env.REDIS_URL = redisUrl;
  process.env.NODE_ENV = 'test';
  process.env.STORAGE_BACKEND = 'local';
  process.env.LOCAL_STORAGE_PATH = '/tmp/mlops-integration-test';

  await execAsync(`cd ${process.cwd()} && npx prisma db push --schema ./prisma/schema.prisma`, {
    env: {
      ...process.env,
      DATABASE_URL: databaseUrl,
    },
  });

  const prisma = new PrismaClient({
    datasources: {
      db: {
        url: databaseUrl,
      },
    },
  });

  await prisma.$connect();

  const redis = new Redis(redisUrl);

  const cleanup = async () => {
    try {
      await redis.flushall();
      await redis.quit();
      await prisma.$disconnect();
      await postgresContainer.stop();
      await redisContainer.stop();
    } catch (e) {
      console.error('Cleanup error:', e);
    }
  };

  return {
    postgresContainer,
    redisContainer,
    prisma,
    redis,
    cleanup,
  };
}

export async function seedTestData(prisma: PrismaClient): Promise<{
  modelId: string;
  versionId: string;
  featureSetId: string;
  featureVersionId: string;
  experimentId: string;
  variants: { id: string; name: string; isControl: boolean }[];
}> {
  const model = await prisma.model.create({
    data: {
      id: 'test-model-1',
      name: 'recommendation-model',
      description: 'Test recommendation model',
      ownerId: 'test-owner',
      team: 'data-science',
      tags: ['recommendation', 'test'],
      metadata: {},
    },
  });

  const version = await prisma.modelVersion.create({
    data: {
      id: 'test-version-1',
      modelId: model.id,
      version: '1.0.0',
      semanticVersion: '1.0.0',
      format: 'onnx',
      sizeBytes: BigInt(1024 * 1024),
      storageBackend: 'local',
      storagePath: 'test-model-1/1.0.0/model.onnx',
      checksum: 'test-checksum-12345',
      status: 'ready',
      dataSchema: {
        input: { type: 'object', properties: { features: { type: 'array' } } },
        output: { type: 'object', properties: { prediction: { type: 'number' } } },
      },
      loaderConfig: {},
      metrics: {
        create: [
          {
            id: 'metric-1',
            name: 'accuracy',
            value: 0.95,
            timestamp: new Date(),
          },
        ],
      },
      hyperParameters: {
        create: [
          {
            id: 'hp-1',
            name: 'learning_rate',
            value: '0.001',
            type: 'number',
          },
        ],
      },
    },
  });

  await prisma.model.update({
    where: { id: model.id },
    data: { latestVersionId: version.id },
  });

  const featureSet = await prisma.featureSet.create({
    data: {
      id: 'test-feature-set-1',
      name: 'user_features',
      description: 'User behavior features',
      projectId: 'proj-1',
      ownerId: 'test-owner',
      team: 'data-science',
      mode: 'both',
      entities: [{ name: 'user_id', type: 'string' }],
      features: [
        { name: 'feature_1', type: 'float', defaultValue: 0.0 },
        { name: 'feature_2', type: 'int', defaultValue: 0 },
        { name: 'feature_3', type: 'bool', defaultValue: false },
      ],
      ttlSeconds: 3600,
      onlineStorage: { enabled: true },
      offlineStorage: { enabled: true },
      tags: ['user', 'behavior'],
    },
  });

  const featureVersion = await prisma.featureSetVersion.create({
    data: {
      id: 'test-feature-version-1',
      featureSetId: featureSet.id,
      version: 1,
      featureSchema: { type: 'object', properties: {} },
      status: 'active',
    },
  });

  await prisma.featureSet.update({
    where: { id: featureSet.id },
    data: { latestVersionId: featureVersion.id },
  });

  const variantIds = ['control-variant-1', 'treatment-variant-1', 'treatment-variant-2'];
  const variants = [
    { id: variantIds[0], name: 'control', isControl: true, weight: 50 },
    { id: variantIds[1], name: 'variant_a', isControl: false, weight: 30 },
    { id: variantIds[2], name: 'variant_b', isControl: false, weight: 20 },
  ];

  const weights: Record<string, number> = {};
  variants.forEach((v) => {
    weights[v.id] = v.weight;
  });

  const experiment = await prisma.aBTest.create({
    data: {
      id: 'test-experiment-1',
      name: 'button-color-test',
      description: 'Test different button colors',
      projectId: 'proj-1',
      ownerId: 'test-owner',
      team: 'growth',
      hypothesis: 'Red button will increase conversion',
      primaryMetric: 'conversion_rate',
      status: 'running',
      bucketStrategy: 'user_id',
      bucketKey: 'user_id',
      trafficAllocation: {
        totalTrafficPercentage: 100,
        weights,
      },
      targetingRules: [],
      metrics: [
        {
          name: 'conversion_rate',
          type: 'binomial',
          significanceLevel: 0.05,
          minSampleSize: 100,
        },
      ],
      tags: ['conversion', 'ui'],
      metadata: {},
      startTime: new Date(),
      variants: {
        create: variants.map((v) => ({
          id: v.id,
          name: v.name,
          description: `${v.name} variant`,
          isControl: v.isControl,
          trafficWeight: v.weight,
          trafficPercentage: v.weight,
          config: {},
        })),
      },
    },
    include: { variants: true },
  });

  return {
    modelId: model.id,
    versionId: version.id,
    featureSetId: featureSet.id,
    featureVersionId: featureVersion.id,
    experimentId: experiment.id,
    variants: experiment.variants.map((v) => ({
      id: v.id,
      name: v.name,
      isControl: v.isControl,
    })),
  };
}

export async function clearTestData(prisma: PrismaClient): Promise<void> {
  await prisma.$transaction([
    prisma.alertEvent.deleteMany(),
    prisma.alert.deleteMany(),
    prisma.driftDetectionResult.deleteMany(),
    prisma.driftDetectionConfig.deleteMany(),
    prisma.inferenceMetrics.deleteMany(),
    prisma.aBTestEvent.deleteMany(),
    prisma.aBTestAssignment.deleteMany(),
    prisma.aBVariant.deleteMany(),
    prisma.aBTest.deleteMany(),
    prisma.featureStatistics.deleteMany(),
    prisma.featureSetVersion.deleteMany(),
    prisma.featureSet.deleteMany(),
    prisma.modelMetric.deleteMany(),
    prisma.modelHyperParameter.deleteMany(),
    prisma.modelVersion.deleteMany(),
    prisma.model.deleteMany(),
    prisma.lineageEdge.deleteMany(),
    prisma.runMetric.deleteMany(),
    prisma.runHyperParameter.deleteMany(),
    prisma.experimentRun.deleteMany(),
    prisma.experiment.deleteMany(),
  ]);
}
