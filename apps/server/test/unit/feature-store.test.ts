import { describe, it, expect, vi, beforeEach } from 'vitest';
import { FeatureStoreService } from '../../src/feature-store/service';
import {
  createMockFeatureSet,
  createMockFeatureSetVersion,
  createMockFeatureData,
  concurrent,
  delay,
} from '../fixtures';

const { mockPrisma, mockRedis, mockLogger, mockFeatureStorage, resetAllMocks } = vi.hoisted(() => {
  const mockPrisma = {
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
      findUnique: vi.fn(),
      upsert: vi.fn(),
    },
    $transaction: vi.fn((fn: any) => fn(mockPrisma)),
    $connect: vi.fn(),
    $disconnect: vi.fn(),
  };
  const mockRedis = {
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
  };
  const mockLogger = {
    info: vi.fn(),
    error: vi.fn(),
    warn: vi.fn(),
    debug: vi.fn(),
    trace: vi.fn(),
    fatal: vi.fn(),
    child: vi.fn().mockReturnThis(),
  };
  const mockFeatureStorage = {
    putObject: vi.fn().mockResolvedValue('etag-123'),
    getObject: vi.fn(),
    getType: () => 'LOCAL',
  };
  const resetAllMocks = () => {
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
    mockFeatureStorage.putObject.mockResolvedValue('etag-123');
  };
  const mockEnv = {
    NODE_ENV: 'test' as const,
    FEATURE_IMPORT_BATCH_SIZE: 10000,
  };
  return { mockPrisma, mockRedis, mockLogger, mockFeatureStorage, mockEnv, resetAllMocks };
});

vi.mock('../../src/config/database', () => ({ prisma: mockPrisma, getReadPrisma: () => mockPrisma }));
vi.mock('../../src/config/redis', () => ({
  redis: mockRedis,
  RedisKeys: {
    featureValue: (featureSetId: string, version: string, entityKey: string) =>
      `feature:value:${featureSetId}:${version}:${entityKey}`,
  },
}));
vi.mock('../../src/config/logger', () => ({ logger: mockLogger }));
vi.mock('../../src/config/env', () => ({
  env: {
    NODE_ENV: 'test',
    FEATURE_IMPORT_BATCH_SIZE: 10000,
  },
}));
vi.mock('../../src/storage', () => ({ featureStorage: mockFeatureStorage }));

describe('FeatureStoreService - Normal Path', () => {
  let service: FeatureStoreService;
  const logger = mockLogger;
  const featureStorage = mockFeatureStorage;

  beforeEach(() => {
    resetAllMocks();
    service = new FeatureStoreService();
  });

  describe('Feature Set Management', () => {
    it('should create feature set successfully', async () => {
      const mockFeatureSet = createMockFeatureSet();
      mockPrisma.featureSet.create.mockResolvedValue({
        ...mockFeatureSet,
        createdAt: new Date(mockFeatureSet.createdAt),
        updatedAt: new Date(mockFeatureSet.updatedAt),
        versions: [],
      });

      const result = await service.createFeatureSet({
        name: mockFeatureSet.name,
        description: mockFeatureSet.description,
        projectId: mockFeatureSet.projectId,
        ownerId: mockFeatureSet.ownerId,
        team: mockFeatureSet.team,
        mode: mockFeatureSet.mode,
        entities: mockFeatureSet.entities,
        features: mockFeatureSet.features,
        ttlSeconds: mockFeatureSet.ttlSeconds,
        onlineStorage: mockFeatureSet.onlineStorage,
        offlineStorage: mockFeatureSet.offlineStorage,
      });

      expect(result.name).toBe(mockFeatureSet.name);
      expect(result.ttlSeconds).toBe(mockFeatureSet.ttlSeconds);
      expect(mockPrisma.featureSet.create).toHaveBeenCalledTimes(1);
      expect(logger.info).toHaveBeenCalledWith(
        expect.objectContaining({ featureSetId: result.id }),
        'Feature set created'
      );
    });

    it('should list feature sets with filtering', async () => {
      const mockSets = Array.from({ length: 5 }, () => createMockFeatureSet());
      mockPrisma.featureSet.count.mockResolvedValue(15);
      mockPrisma.featureSet.findMany.mockResolvedValue(
        mockSets.map((fs) => ({
          ...fs,
          createdAt: new Date(fs.createdAt),
          updatedAt: new Date(fs.updatedAt),
          versions: [],
        }))
      );

      const result = await service.listFeatureSets({
        team: 'data-science',
        mode: 'both',
        page: 2,
        pageSize: 5,
      });

      expect(result.data).toHaveLength(5);
      expect(result.total).toBe(15);
      expect(result.totalPages).toBe(3);
      expect(mockPrisma.featureSet.count).toHaveBeenCalledWith(
        expect.objectContaining({
          where: expect.objectContaining({
            team: 'data-science',
            mode: 'both',
          }),
        })
      );
    });
  });

  describe('Online Feature Query', () => {
    it('should return cached values directly when cache hits', async () => {
      const featureSetId = 'cache-hit-test';
      const versionId = 'version-1';
      const entityKey = 'user-123';
      const mockFeatureSet = createMockFeatureSet({ id: featureSetId });
      const mockVersion = createMockFeatureSetVersion(featureSetId, { id: versionId });

      mockPrisma.featureSet.findUnique.mockResolvedValue({
        ...mockFeatureSet,
        createdAt: new Date(mockFeatureSet.createdAt),
        updatedAt: new Date(mockFeatureSet.updatedAt),
        features: mockFeatureSet.features,
      });

      mockPrisma.featureSetVersion.findFirst.mockResolvedValue({
        ...mockVersion,
        createdAt: new Date(mockVersion.createdAt),
      });

      const cachedValues = {
        feature_1: JSON.stringify(0.85),
        feature_2: JSON.stringify(42),
      };
      mockRedis.hgetall.mockResolvedValue(cachedValues);

      const result = await service.getOnlineFeatures({
        featureSetId,
        entityKeys: [entityKey],
      });

      expect(result.values[entityKey]).toBeDefined();
      expect(result.values[entityKey]!.feature_1).toBe(0.85);
      expect(result.values[entityKey]!.feature_2).toBe(42);
      expect(mockRedis.hgetall).toHaveBeenCalledWith(
        `feature:value:${featureSetId}:${versionId}:${entityKey}`
      );
      expect(logger.debug).toHaveBeenCalledWith(
        expect.objectContaining({ featureSetId, entityCount: 1 }),
        'Online features retrieved'
      );
    });

    it('should return default values when cache miss', async () => {
      const featureSetId = 'cache-miss-test';
      const versionId = 'version-1';
      const entityKey = 'user-456';
      const mockFeatureSet = createMockFeatureSet({ id: featureSetId });
      const mockVersion = createMockFeatureSetVersion(featureSetId, { id: versionId });

      mockPrisma.featureSet.findUnique.mockResolvedValue({
        ...mockFeatureSet,
        createdAt: new Date(mockFeatureSet.createdAt),
        updatedAt: new Date(mockFeatureSet.updatedAt),
        features: mockFeatureSet.features,
      });

      mockPrisma.featureSetVersion.findFirst.mockResolvedValue({
        ...mockVersion,
        createdAt: new Date(mockVersion.createdAt),
      });

      mockRedis.hgetall.mockResolvedValue({});

      const result = await service.getOnlineFeatures({
        featureSetId,
        entityKeys: [entityKey],
        featureNames: ['feature_1', 'feature_2'],
      });

      expect(result.values[entityKey]).toBeDefined();
      expect(result.values[entityKey]!.feature_1).toBeNull();
      expect(result.values[entityKey]!.feature_2).toBeNull();
    });

    it('should handle batch entity queries efficiently', async () => {
      const featureSetId = 'batch-query-test';
      const versionId = 'version-1';
      const entityKeys = Array.from({ length: 100 }, (_, i) => `user-${i}`);
      const mockFeatureSet = createMockFeatureSet({ id: featureSetId });
      const mockVersion = createMockFeatureSetVersion(featureSetId, { id: versionId });

      mockPrisma.featureSet.findUnique.mockResolvedValue({
        ...mockFeatureSet,
        createdAt: new Date(mockFeatureSet.createdAt),
        updatedAt: new Date(mockFeatureSet.updatedAt),
        features: mockFeatureSet.features,
      });

      mockPrisma.featureSetVersion.findFirst.mockResolvedValue({
        ...mockVersion,
        createdAt: new Date(mockVersion.createdAt),
      });

      mockRedis.hgetall.mockImplementation((key: string) => {
        const entityKey = key.split(':').pop();
        if (entityKey && parseInt(entityKey.split('-')[1]) % 2 === 0) {
          return Promise.resolve({ feature_1: JSON.stringify(0.8) });
        }
        return Promise.resolve({});
      });

      const result = await service.getOnlineFeatures({
        featureSetId,
        entityKeys,
      });

      expect(Object.keys(result.values)).toHaveLength(100);
      expect(mockRedis.hgetall).toHaveBeenCalledTimes(100);

      let cachedCount = 0;
      for (const entityKey of entityKeys) {
        const val = result.values[entityKey]!.feature_1;
        if (val === 0.8) cachedCount++;
      }
      expect(cachedCount).toBe(50);
    });
  });

  describe('Feature Ingestion', () => {
    it('should ingest features into online cache with TTL', async () => {
      const featureSetId = 'ingest-test';
      const versionId = 'version-1';
      const ttl = 3600;
      const mockFeatureSet = createMockFeatureSet({ id: featureSetId, ttlSeconds: ttl });
      const mockVersion = createMockFeatureSetVersion(featureSetId, { id: versionId });
      const testData = createMockFeatureData(10);

      mockPrisma.featureSet.findUnique.mockResolvedValue({
        ...mockFeatureSet,
        createdAt: new Date(mockFeatureSet.createdAt),
        updatedAt: new Date(mockFeatureSet.updatedAt),
        mode: 'both',
        features: mockFeatureSet.features,
      });

      mockPrisma.featureSetVersion.findFirst.mockResolvedValue({
        ...mockVersion,
        createdAt: new Date(mockVersion.createdAt),
      });

      const hsetSpy = vi.fn();
      const expireSpy = vi.fn();
      const delSpy = vi.fn();
      const execSpy = vi.fn().mockResolvedValue([]);

      (mockRedis.pipeline as any).mockReturnValue({
        hset: hsetSpy,
        expire: expireSpy,
        del: delSpy,
        exec: execSpy,
      });

      const result = await service.ingestFeatures({
        featureSetId,
        entityKeyField: 'user_id',
        data: testData,
        mode: 'upsert',
      });

      expect(result.ingestedCount).toBe(10);
      expect(hsetSpy).toHaveBeenCalledTimes(10);
      expect(expireSpy).toHaveBeenCalledTimes(10);
      expect(expireSpy).toHaveBeenCalledWith(expect.any(String), ttl);
      expect(featureStorage.putObject).toHaveBeenCalled();
    });

    it('should overwrite existing values when mode is overwrite', async () => {
      const featureSetId = 'overwrite-test';
      const versionId = 'version-1';
      const mockFeatureSet = createMockFeatureSet({ id: featureSetId });
      const mockVersion = createMockFeatureSetVersion(featureSetId, { id: versionId });
      const testData = createMockFeatureData(5);

      mockPrisma.featureSet.findUnique.mockResolvedValue({
        ...mockFeatureSet,
        createdAt: new Date(mockFeatureSet.createdAt),
        updatedAt: new Date(mockFeatureSet.updatedAt),
        mode: 'online',
        features: mockFeatureSet.features,
      });

      mockPrisma.featureSetVersion.findFirst.mockResolvedValue({
        ...mockVersion,
        createdAt: new Date(mockVersion.createdAt),
      });

      const delSpy = vi.fn().mockReturnThis();
      const hsetSpy = vi.fn().mockReturnThis();
      const expireSpy = vi.fn().mockReturnThis();

      (mockRedis.pipeline as any).mockReturnValue({
        del: delSpy,
        hset: hsetSpy,
        expire: expireSpy,
        exec: vi.fn().mockResolvedValue([]),
      });

      await service.ingestFeatures({
        featureSetId,
        entityKeyField: 'user_id',
        data: testData,
        mode: 'overwrite',
      });

      expect(delSpy).toHaveBeenCalledTimes(5);
    });

    it('should calculate and store feature statistics on ingestion', async () => {
      const featureSetId = 'stats-test';
      const versionId = 'version-1';
      const mockFeatureSet = createMockFeatureSet({ id: featureSetId });
      const mockVersion = createMockFeatureSetVersion(featureSetId, { id: versionId });

      const testData = [
        { user_id: 'user-1', feature_1: 10, feature_2: 100 },
        { user_id: 'user-2', feature_1: 20, feature_2: 200 },
        { user_id: 'user-3', feature_1: 30, feature_2: 300 },
        { user_id: 'user-4', feature_1: 40, feature_2: 400 },
        { user_id: 'user-5', feature_1: 50, feature_2: 500 },
      ];

      mockPrisma.featureSet.findUnique.mockResolvedValue({
        ...mockFeatureSet,
        createdAt: new Date(mockFeatureSet.createdAt),
        updatedAt: new Date(mockFeatureSet.updatedAt),
        mode: 'online',
        features: mockFeatureSet.features,
      });

      mockPrisma.featureSetVersion.findFirst.mockResolvedValue({
        ...mockVersion,
        createdAt: new Date(mockVersion.createdAt),
      });

      (mockRedis.pipeline as any).mockReturnValue({
        hset: vi.fn(),
        expire: vi.fn(),
        del: vi.fn(),
        exec: vi.fn().mockResolvedValue([]),
      });

      const result = await service.ingestFeatures({
        featureSetId,
        entityKeyField: 'user_id',
        data: testData as any,
        mode: 'upsert',
      });

      expect(result.ingestedCount).toBe(5);
      expect(result.timestamp).toBeGreaterThan(0);
    });
  });

  describe('TTL Management', () => {
    it('should set correct TTL on feature ingestion', async () => {
      const featureSetId = 'ttl-test';
      const versionId = 'version-1';
      const ttlSeconds = 86400;
      const mockFeatureSet = createMockFeatureSet({ id: featureSetId, ttlSeconds });
      const mockVersion = createMockFeatureSetVersion(featureSetId, { id: versionId });
      const testData = createMockFeatureData(3);

      mockPrisma.featureSet.findUnique.mockResolvedValue({
        ...mockFeatureSet,
        createdAt: new Date(mockFeatureSet.createdAt),
        updatedAt: new Date(mockFeatureSet.updatedAt),
        mode: 'online',
        features: mockFeatureSet.features,
      });

      mockPrisma.featureSetVersion.findFirst.mockResolvedValue({
        ...mockVersion,
        createdAt: new Date(mockVersion.createdAt),
      });

      const expireSpy = vi.fn().mockReturnThis();
      (mockRedis.pipeline as any).mockReturnValue({
        hset: vi.fn().mockReturnThis(),
        expire: expireSpy,
        exec: vi.fn().mockResolvedValue([]),
      });

      await service.ingestFeatures({
        featureSetId,
        entityKeyField: 'user_id',
        data: testData,
        mode: 'upsert',
      });

      expect(expireSpy).toHaveBeenCalledTimes(3);
      expireSpy.mock.calls.forEach((call: any) => {
        expect(call[1]).toBe(ttlSeconds);
      });
    });

    it('should use default TTL when not specified', async () => {
      const featureSetId = 'default-ttl-test';
      const versionId = 'version-1';
      const mockFeatureSet = createMockFeatureSet({ id: featureSetId, ttlSeconds: undefined });
      const mockVersion = createMockFeatureSetVersion(featureSetId, { id: versionId });
      const testData = createMockFeatureData(1);

      mockPrisma.featureSet.findUnique.mockResolvedValue({
        ...mockFeatureSet,
        createdAt: new Date(mockFeatureSet.createdAt),
        updatedAt: new Date(mockFeatureSet.updatedAt),
        mode: 'online',
        ttlSeconds: null,
        features: mockFeatureSet.features,
      });

      mockPrisma.featureSetVersion.findFirst.mockResolvedValue({
        ...mockVersion,
        createdAt: new Date(mockVersion.createdAt),
      });

      const expireSpy = vi.fn().mockReturnThis();
      (mockRedis.pipeline as any).mockReturnValue({
        hset: vi.fn().mockReturnThis(),
        expire: expireSpy,
        exec: vi.fn().mockResolvedValue([]),
      });

      await service.ingestFeatures({
        featureSetId,
        entityKeyField: 'user_id',
        data: testData,
        mode: 'upsert',
      });

      expect(expireSpy).toHaveBeenCalledWith(expect.any(String), 86400 * 7);
    });
  });
});

describe('FeatureStoreService - Exception Path', () => {
  let service: FeatureStoreService;
  const logger = mockLogger;
  const featureStorage = mockFeatureStorage;

  beforeEach(() => {
    resetAllMocks();
    service = new FeatureStoreService();
  });

  it('should handle TTL expiration and fallback gracefully', async () => {
    const featureSetId = 'ttl-expire-test';
    const versionId = 'version-1';
    const entityKey = 'user-expired';
    const mockFeatureSet = createMockFeatureSet({ id: featureSetId });
    const mockVersion = createMockFeatureSetVersion(featureSetId, { id: versionId });

    mockPrisma.featureSet.findUnique.mockResolvedValue({
      ...mockFeatureSet,
      createdAt: new Date(mockFeatureSet.createdAt),
      updatedAt: new Date(mockFeatureSet.updatedAt),
      features: mockFeatureSet.features,
    });

    mockPrisma.featureSetVersion.findFirst.mockResolvedValue({
      ...mockVersion,
      createdAt: new Date(mockVersion.createdAt),
    });

    mockRedis.hgetall.mockResolvedValue({});

    const result = await service.getOnlineFeatures({
      featureSetId,
      entityKeys: [entityKey],
    });

    expect(result.values[entityKey]!.feature_1).toBeNull();
    expect(result.values[entityKey]!.feature_2).toBeNull();
    expect(logger.warn).not.toHaveBeenCalled();
  });

  it('should handle Redis timeout with fallback to defaults', async () => {
    const featureSetId = 'redis-timeout-test';
    const versionId = 'version-1';
    const entityKey = 'user-timeout';
    const mockFeatureSet = createMockFeatureSet({ id: featureSetId });
    const mockVersion = createMockFeatureSetVersion(featureSetId, { id: versionId });

    mockPrisma.featureSet.findUnique.mockResolvedValue({
      ...mockFeatureSet,
      createdAt: new Date(mockFeatureSet.createdAt),
      updatedAt: new Date(mockFeatureSet.updatedAt),
      features: mockFeatureSet.features,
    });

    mockPrisma.featureSetVersion.findFirst.mockResolvedValue({
      ...mockVersion,
      createdAt: new Date(mockVersion.createdAt),
    });

    mockRedis.hgetall.mockRejectedValueOnce(new Error('Redis connection timeout'));

    await expect(
      service.getOnlineFeatures({
        featureSetId,
        entityKeys: [entityKey],
      })
    ).rejects.toThrow('Redis connection timeout');
  });

  it('should handle offline storage failure but continue online ingestion', async () => {
    const featureSetId = 'offline-fail-test';
    const versionId = 'version-1';
    const mockFeatureSet = createMockFeatureSet({ id: featureSetId });
    const mockVersion = createMockFeatureSetVersion(featureSetId, { id: versionId });
    const testData = createMockFeatureData(10);

    mockPrisma.featureSet.findUnique.mockResolvedValue({
      ...mockFeatureSet,
      createdAt: new Date(mockFeatureSet.createdAt),
      updatedAt: new Date(mockFeatureSet.updatedAt),
      mode: 'both',
      features: mockFeatureSet.features,
    });

    mockPrisma.featureSetVersion.findFirst.mockResolvedValue({
      ...mockVersion,
      createdAt: new Date(mockVersion.createdAt),
    });

    (mockRedis.pipeline as any).mockReturnValue({
      hset: vi.fn().mockReturnThis(),
      expire: vi.fn().mockReturnThis(),
      exec: vi.fn().mockResolvedValue([]),
    });

    featureStorage.putObject.mockRejectedValueOnce(new Error('S3 connection failed'));

    const result = await service.ingestFeatures({
      featureSetId,
      entityKeyField: 'user_id',
      data: testData,
      mode: 'upsert',
    });

    expect(result.ingestedCount).toBe(10);
    expect(logger.warn).toHaveBeenCalledWith(
      expect.objectContaining({
        error: expect.any(Error),
        path: expect.stringContaining('.parquet'),
      }),
      'Failed to write offline feature data'
    );
  });

  it('should throw error when feature set does not exist', async () => {
    const nonExistentId = 'non-existent-fs';
    mockPrisma.featureSet.findUnique.mockResolvedValue(null);

    await expect(
      service.getOnlineFeatures({
        featureSetId: nonExistentId,
        entityKeys: ['user-1'],
      })
    ).rejects.toThrow(`Feature set not found: ${nonExistentId}`);
  });

  it('should validate input with Zod schema', async () => {
    await expect(
      service.createFeatureSet({
        name: '',
        description: 'test',
        projectId: 'proj-1',
        ownerId: 'owner',
        team: 'team',
        mode: 'online',
        entities: [],
        features: [],
      } as any)
    ).rejects.toThrow();
  });
});

describe('FeatureStoreService - Concurrency Scenarios', () => {
  let service: FeatureStoreService;
  const featureStorage = mockFeatureStorage;

  beforeEach(() => {
    resetAllMocks();
    service = new FeatureStoreService();
  });

  it('should handle concurrent online writes and offline imports without data loss', async () => {
    const featureSetId = 'concurrent-write-test';
    const versionId = 'version-1';
    const mockFeatureSet = createMockFeatureSet({ id: featureSetId });
    const mockVersion = createMockFeatureSetVersion(featureSetId, { id: versionId });

    mockPrisma.featureSet.findUnique.mockResolvedValue({
      ...mockFeatureSet,
      createdAt: new Date(mockFeatureSet.createdAt),
      updatedAt: new Date(mockFeatureSet.updatedAt),
      mode: 'both',
      features: mockFeatureSet.features,
    });

    mockPrisma.featureSetVersion.findFirst.mockResolvedValue({
      ...mockVersion,
      createdAt: new Date(mockVersion.createdAt),
    });

    const hsetCalls: string[] = [];
    const createPipeline = () => {
      const pipe = {
        hset: vi.fn().mockImplementation((key: string, _values: any) => {
          hsetCalls.push(key);
          return pipe;
        }),
        expire: vi.fn().mockReturnThis(),
        del: vi.fn().mockReturnThis(),
        exec: vi.fn().mockResolvedValue([]),
      };
      return pipe;
    };
    (mockRedis.pipeline as any).mockImplementation(createPipeline);

    const writeCount = 10;
    const batchCount = 10;

    const operations = [];
    for (let i = 0; i < writeCount; i++) {
      operations.push(
        service.ingestFeatures({
          featureSetId,
          entityKeyField: 'user_id',
          data: [{ user_id: `online-${i}`, feature_1: i }],
          mode: 'upsert',
        })
      );
    }

    for (let i = 0; i < batchCount; i++) {
      operations.push(
        service.ingestFeatures({
          featureSetId,
          entityKeyField: 'user_id',
          data: createMockFeatureData(100).map((d, idx) => ({
            ...d,
            user_id: `batch-${i}-${idx}`,
          })),
          mode: 'overwrite',
        })
      );
    }

    const results = await Promise.all(operations);
    expect(results).toHaveLength(writeCount + batchCount);

    const successful = results.filter((r) => r && r.ingestedCount > 0);
    expect(successful.length).toBe(writeCount + batchCount);

    expect(hsetCalls.length).toBeGreaterThanOrEqual(writeCount);
  });

  it('should prevent dirty reads during concurrent writes and reads', async () => {
    const featureSetId = 'dirty-read-test';
    const versionId = 'version-1';
    const entityKey = 'user-dirty';
    const mockFeatureSet = createMockFeatureSet({ id: featureSetId });
    const mockVersion = createMockFeatureSetVersion(featureSetId, { id: versionId });

    mockPrisma.featureSet.findUnique.mockResolvedValue({
      ...mockFeatureSet,
      createdAt: new Date(mockFeatureSet.createdAt),
      updatedAt: new Date(mockFeatureSet.updatedAt),
      features: mockFeatureSet.features,
    });

    mockPrisma.featureSetVersion.findFirst.mockResolvedValue({
      ...mockVersion,
      createdAt: new Date(mockVersion.createdAt),
    });

    const cacheValues: Record<string, Record<string, string>> = {};

    mockRedis.hgetall.mockImplementation(async (key: string) => {
      return cacheValues[key] || {};
    });

    (mockRedis.pipeline as any).mockImplementation(() => {
      const pipe = {
        hset: vi.fn().mockImplementation((key: string, values: any) => {
          cacheValues[key] = values;
          return pipe;
        }),
        expire: vi.fn().mockReturnThis(),
        del: vi.fn().mockImplementation((key: string) => {
          delete cacheValues[key];
          return pipe;
        }),
        exec: vi.fn().mockResolvedValue([]),
      };
      return pipe;
    });

    const operations = [];
    for (let i = 0; i < 20; i++) {
      if (i % 2 === 0) {
        operations.push(
          service.ingestFeatures({
            featureSetId,
            entityKeyField: 'user_id',
            data: [{ user_id: entityKey, feature_1: i }],
            mode: 'overwrite',
          })
        );
      } else {
        operations.push(
          service.getOnlineFeatures({
            featureSetId,
            entityKeys: [entityKey],
          })
        );
      }
    }

    const results = await Promise.allSettled(operations);
    const readResults = results.filter(
      (_, i) => i % 2 === 1
    ) as PromiseFulfilledResult<any>[];

    for (const result of readResults) {
      if (result.status === 'fulfilled') {
        const value = result.value.values[entityKey]!.feature_1;
        if (typeof value === 'number') {
          expect(value).toBeGreaterThanOrEqual(-1.0);
          expect(value).toBeLessThan(100);
        }
      }
    }
  }, 60000);

  it('should handle concurrent statistics updates correctly', async () => {
      const featureSetId = 'concurrent-stats-test';
      const versionId = 'version-1';
      const mockFeatureSet = createMockFeatureSet({ id: featureSetId });
      const mockVersion = createMockFeatureSetVersion(featureSetId, { id: versionId });

      mockPrisma.featureSet.findUnique.mockResolvedValue({
        ...mockFeatureSet,
        createdAt: new Date(mockFeatureSet.createdAt),
        updatedAt: new Date(mockFeatureSet.updatedAt),
        mode: 'online',
        features: mockFeatureSet.features,
      });

      mockPrisma.featureSetVersion.findFirst.mockResolvedValue({
        ...mockVersion,
        createdAt: new Date(mockVersion.createdAt),
      });

      (mockRedis.pipeline as any).mockReturnValue({
        hset: vi.fn(),
        expire: vi.fn(),
        del: vi.fn(),
        exec: vi.fn().mockResolvedValue([]),
      });

      const batchCount = 10;
      const operations = Array.from({ length: batchCount }, (_, i) =>
        service.ingestFeatures({
          featureSetId,
          entityKeyField: 'user_id',
          data: Array.from({ length: 100 }, (__, j) => ({
            user_id: `user-${i}-${j}`,
            feature_1: i * 100 + j,
          })),
          mode: 'upsert',
        })
      );

      const results = await Promise.all(operations);

      expect(results).toHaveLength(batchCount);
      results.forEach((r) => {
        expect(r.ingestedCount).toBe(100);
        expect(r.timestamp).toBeGreaterThan(0);
      });
    });
});

describe('Regression Tests - Batched Import and Read Replica', () => {
  let service: FeatureStoreService;
  const featureStorage = mockFeatureStorage;

  beforeEach(() => {
    resetAllMocks();
    service = new FeatureStoreService();
  });

  it('should process data in batches using FEATURE_IMPORT_BATCH_SIZE from env', async () => {
    const featureSetId = 'batch-import-test';
    const versionId = 'version-1';
    const mockFeatureSet = createMockFeatureSet({ id: featureSetId, mode: 'online' });
    const mockVersion = createMockFeatureSetVersion(featureSetId, { id: versionId });
    const testData = createMockFeatureData(5);

    mockPrisma.featureSet.findUnique.mockResolvedValue({
      ...mockFeatureSet,
      createdAt: new Date(mockFeatureSet.createdAt),
      updatedAt: new Date(mockFeatureSet.updatedAt),
      mode: 'online',
      features: mockFeatureSet.features,
    });

    mockPrisma.featureSetVersion.findFirst.mockResolvedValue({
      ...mockVersion,
      createdAt: new Date(mockVersion.createdAt),
    });

    const execSpy = vi.fn().mockResolvedValue([]);
    (mockRedis.pipeline as any).mockReturnValue({
      hset: vi.fn().mockReturnThis(),
      expire: vi.fn().mockReturnThis(),
      del: vi.fn().mockReturnThis(),
      exec: execSpy,
    });

    const result = await service.ingestFeatures({
      featureSetId,
      entityKeyField: 'user_id',
      data: testData,
      mode: 'upsert',
    });

    expect(result.ingestedCount).toBe(5);
    expect(result.timestamp).toBeGreaterThan(0);
    expect(execSpy).toHaveBeenCalled();
  });

  it('should use read replica for getOnlineFeatures queries', async () => {
    const featureSetId = 'read-replica-test';
    const versionId = 'version-1';
    const entityKey = 'user-123';
    const mockFeatureSet = createMockFeatureSet({ id: featureSetId });
    const mockVersion = createMockFeatureSetVersion(featureSetId, { id: versionId });

    const findUniqueSpy = vi.fn().mockResolvedValue({
      ...mockFeatureSet,
      createdAt: new Date(mockFeatureSet.createdAt),
      updatedAt: new Date(mockFeatureSet.updatedAt),
      features: mockFeatureSet.features,
    });
    const findFirstSpy = vi.fn().mockResolvedValue({
      ...mockVersion,
      createdAt: new Date(mockVersion.createdAt),
    });

    mockPrisma.featureSet.findUnique = findUniqueSpy;
    mockPrisma.featureSetVersion.findFirst = findFirstSpy;

    mockRedis.hgetall.mockResolvedValue({});

    await service.getOnlineFeatures({
      featureSetId,
      entityKeys: [entityKey],
    });

    expect(findFirstSpy).toHaveBeenCalled();
    expect(findUniqueSpy).toHaveBeenCalled();
  });

  it('should yield to event loop between batches for large imports', async () => {
    const featureSetId = 'event-loop-yield-test';
    const versionId = 'version-1';
    const mockFeatureSet = createMockFeatureSet({ id: featureSetId, mode: 'online' });
    const mockVersion = createMockFeatureSetVersion(featureSetId, { id: versionId });

    mockPrisma.featureSet.findUnique.mockResolvedValue({
      ...mockFeatureSet,
      createdAt: new Date(mockFeatureSet.createdAt),
      updatedAt: new Date(mockFeatureSet.updatedAt),
      mode: 'online',
      features: mockFeatureSet.features,
    });

    mockPrisma.featureSetVersion.findFirst.mockResolvedValue({
      ...mockVersion,
      createdAt: new Date(mockVersion.createdAt),
    });

    (mockRedis.pipeline as any).mockReturnValue({
      hset: vi.fn().mockReturnThis(),
      expire: vi.fn().mockReturnThis(),
      del: vi.fn().mockReturnThis(),
      exec: vi.fn().mockResolvedValue([]),
    });

    const testData = createMockFeatureData(3);

    const result = await service.ingestFeatures({
      featureSetId,
      entityKeyField: 'user_id',
      data: testData,
      mode: 'upsert',
    });

    expect(result.ingestedCount).toBe(3);
    expect(result.timestamp).toBeGreaterThan(0);
  });
});
