import { vi } from 'vitest';
import type { PrismaClient } from '@prisma/client';
import type Redis from 'ioredis';
import type { IStorageBackend } from '../src/storage';
import type { IModelLoader } from '../src/model/loader';

export const mockPrisma = {
  model: {
    create: vi.fn(),
    findMany: vi.fn(),
    findUnique: vi.fn(),
    update: vi.fn(),
    delete: vi.fn(),
    count: vi.fn(),
  },
  modelVersion: {
    create: vi.fn(),
    findMany: vi.fn(),
    findUnique: vi.fn(),
    update: vi.fn(),
    delete: vi.fn(),
    count: vi.fn(),
    findFirst: vi.fn(),
  },
  modelMetric: {
    createMany: vi.fn(),
    findMany: vi.fn(),
  },
  modelHyperParameter: {
    createMany: vi.fn(),
    findMany: vi.fn(),
  },
  featureSet: {
    create: vi.fn(),
    findMany: vi.fn(),
    findUnique: vi.fn(),
    update: vi.fn(),
    delete: vi.fn(),
    count: vi.fn(),
  },
  featureSetVersion: {
    create: vi.fn(),
    findMany: vi.fn(),
    findUnique: vi.fn(),
    update: vi.fn(),
    findFirst: vi.fn(),
  },
  featureStatistics: {
    createMany: vi.fn(),
    findMany: vi.fn(),
  },
  aBTest: {
    create: vi.fn(),
    findMany: vi.fn(),
    findUnique: vi.fn(),
    update: vi.fn(),
    delete: vi.fn(),
    count: vi.fn(),
  },
  aBVariant: {
    createMany: vi.fn(),
    findMany: vi.fn(),
    findUnique: vi.fn(),
    update: vi.fn(),
  },
  aBTestAssignment: {
    create: vi.fn(),
    findMany: vi.fn(),
    findUnique: vi.fn(),
    upsert: vi.fn(),
  },
  aBTestEvent: {
    create: vi.fn(),
    findMany: vi.fn(),
    count: vi.fn(),
    groupBy: vi.fn(),
  },
  alert: {
    create: vi.fn(),
    findMany: vi.fn(),
    findUnique: vi.fn(),
    update: vi.fn(),
  },
  alertEvent: {
    create: vi.fn(),
    findMany: vi.fn(),
  },
  driftDetectionConfig: {
    create: vi.fn(),
    findMany: vi.fn(),
    findUnique: vi.fn(),
  },
  driftDetectionResult: {
    create: vi.fn(),
    findMany: vi.fn(),
  },
  inferenceMetrics: {
    create: vi.fn(),
    findMany: vi.fn(),
    groupBy: vi.fn(),
    aggregate: vi.fn(),
  },
  $transaction: vi.fn((fn) => fn(mockPrisma)),
  $connect: vi.fn(),
  $disconnect: vi.fn(),
} as unknown as PrismaClient;

export const mockRedis = {
  get: vi.fn(),
  set: vi.fn(),
  setex: vi.fn(),
  del: vi.fn(),
  exists: vi.fn(),
  expire: vi.fn(),
  ttl: vi.fn(),
  incr: vi.fn(),
  incrby: vi.fn(),
  hget: vi.fn(),
  hset: vi.fn(),
  hgetall: vi.fn(),
  pipeline: vi.fn().mockReturnThis(),
  exec: vi.fn(),
  multi: vi.fn().mockReturnThis(),
  ping: vi.fn(),
  on: vi.fn(),
  disconnect: vi.fn(),
  quit: vi.fn(),
} as unknown as Redis;

export const mockStorageBackend: IStorageBackend = {
  upload: vi.fn(),
  download: vi.fn(),
  downloadStream: vi.fn(),
  exists: vi.fn(),
  delete: vi.fn(),
  list: vi.fn(),
  getSignedUrl: vi.fn(),
  getType: () => 'LOCAL',
};

export const mockModelLoader: IModelLoader = {
  predict: vi.fn(),
  batchPredict: vi.fn(),
  unload: vi.fn(),
  getModelInfo: vi.fn().mockReturnValue({
    format: 'onnx',
    inputShape: [-1, 10],
    outputShape: [-1, 1],
  }),
};

export function resetAllMocks(): void {
  vi.clearAllMocks();
  
  Object.values(mockPrisma).forEach((value: any) => {
    if (typeof value === 'object' && value !== null) {
      Object.values(value).forEach((fn: any) => {
        if (typeof fn === 'function') {
          fn.mockClear();
        }
      });
    }
  });

  Object.values(mockRedis).forEach((value: any) => {
    if (typeof value === 'function') {
      value.mockClear();
    }
  });

  Object.values(mockStorageBackend).forEach((value: any) => {
    if (typeof value === 'function') {
      value.mockClear();
    }
  });

  Object.values(mockModelLoader).forEach((value: any) => {
    if (typeof value === 'function') {
      value.mockClear();
    }
  });
}

export function createMockLogger() {
  return {
    info: vi.fn(),
    error: vi.fn(),
    warn: vi.fn(),
    debug: vi.fn(),
    trace: vi.fn(),
    fatal: vi.fn(),
    child: vi.fn().mockReturnThis(),
  };
}
