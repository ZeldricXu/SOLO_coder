import { FastifyInstance } from 'fastify';
import { createApp } from '@/app';
import { getTestContainers } from './setup';
import { setTestPrisma } from './factories';
import { Redis } from 'ioredis';
import { Client } from '@elastic/elasticsearch';

export interface TestContext {
  app: FastifyInstance;
  prisma: ReturnType<typeof getTestContainers>['prisma'];
  redis: Redis;
  es: Client;
}

let app: FastifyInstance | null = null;
let redisClient: Redis | null = null;
let esClient: Client | null = null;

export const setupTestApp = async (): Promise<TestContext> => {
  const containers = getTestContainers();

  setTestPrisma(containers.prisma);

  if (!app) {
    app = createApp({
      logger: false,
    });
  }

  if (!redisClient) {
    redisClient = new Redis({
      host: containers.redis.getHost(),
      port: containers.redis.getPort(),
    });
  }

  if (!esClient) {
    esClient = new Client({
      node: `http://${containers.elasticsearch.getHost()}:${containers.elasticsearch.getPort()}`,
    });
  }

  return {
    app,
    prisma: containers.prisma,
    redis: redisClient,
    es: esClient,
  };
};

export const flushRedis = async () => {
  if (redisClient) {
    await redisClient.flushall();
  }
};

export const closeTestApp = async () => {
  if (app) {
    await app.close();
    app = null;
  }
  if (redisClient) {
    await redisClient.quit();
    redisClient = null;
  }
  if (esClient) {
    await esClient.close();
    esClient = null;
  }
};

export const waitForEsIndex = async (
  es: Client,
  index: string,
  timeoutMs = 10000
): Promise<boolean> => {
  const startTime = Date.now();

  while (Date.now() - startTime < timeoutMs) {
    try {
      const exists = await es.indices.exists({ index });
      if (exists) {
        await es.indices.refresh({ index });
        return true;
      }
    } catch (e) {
      // ignore
    }
    await new Promise(resolve => setTimeout(resolve, 500));
  }

  return false;
};

export const waitForEsDocuments = async (
  es: Client,
  index: string,
  minCount = 1,
  timeoutMs = 10000
): Promise<boolean> => {
  const startTime = Date.now();

  while (Date.now() - startTime < timeoutMs) {
    try {
      const result = await es.count({ index });
      if (result.count >= minCount) {
        return true;
      }
    } catch (e) {
      // ignore
    }
    await new Promise(resolve => setTimeout(resolve, 500));
  }

  return false;
};

export const clearEsIndex = async (es: Client, pattern: string) => {
  try {
    const indices = await es.indices.getAlias({ index: pattern });
    for (const index of Object.keys(indices)) {
      await es.indices.delete({ index });
    }
  } catch (e) {
    // index may not exist
  }
};

export const simulateConcurrentRequests = async <T>(
  requests: (() => Promise<T>)[],
  concurrency = 10
): Promise<T[]> => {
  const results: T[] = [];
  const executing: Promise<void>[] = [];

  for (const request of requests) {
    const promise = request().then(result => {
      results.push(result);
    });
    executing.push(promise);

    if (executing.length >= concurrency) {
      await Promise.race(executing);
      for (let i = executing.length - 1; i >= 0; i--) {
        try {
          await Promise.race([executing[i], Promise.resolve()]);
        } catch (e) {
          // ignore, will be caught by results
        }
      }
    }
  }

  await Promise.all(executing);
  return results;
};

export const expectStatus = (response: any, expectedStatus: number) => {
  if (response.statusCode !== expectedStatus) {
    throw new Error(
      `Expected status ${expectedStatus}, got ${response.statusCode}. ` +
      `Response: ${JSON.stringify(response.json ? response.json() : response.body)}`
    );
  }
};
