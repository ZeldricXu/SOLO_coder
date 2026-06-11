import { describe, it, expect, vi, beforeEach } from 'vitest';
import { ModelRegistryService } from '../../src/model/registry';
import { modelLoaderRegistry } from '../../src/model/loader';
import {
  createMockModel,
  createMockModelVersion,
  createMockModelFile,
  createCorruptedModelFile,
  concurrent,
} from '../fixtures';
import type { ModelFormat, ModelVersionStatus } from '@mlops/shared';

const { mockPrisma, mockLogger, mockModelStorage, resetAllMocks } = vi.hoisted(() => {
  const mockPrisma = {
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
    $transaction: vi.fn((fn: any) => fn(mockPrisma)),
    $connect: vi.fn(),
    $disconnect: vi.fn(),
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
  const mockModelStorage = {
    putObject: vi.fn().mockResolvedValue('etag-123'),
    getObject: vi.fn(),
    deleteObject: vi.fn(),
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
    mockModelStorage.putObject.mockResolvedValue('etag-123');
  };
  return { mockPrisma, mockLogger, mockModelStorage, resetAllMocks };
});

vi.mock('../../src/config/database', () => ({ prisma: mockPrisma }));
vi.mock('../../src/config/logger', () => ({ logger: mockLogger }));
vi.mock('../../src/storage', () => ({ modelStorage: mockModelStorage }));

const defaultDataSchema = {
  inputs: [{ name: 'features', type: 'float32' as const, shape: [10] }],
  outputs: [{ name: 'prediction', type: 'float32' as const, shape: [1] }],
};

describe('ModelRegistryService - Normal Path', () => {
  let service: ModelRegistryService;
  const logger = mockLogger;
  const modelStorage = mockModelStorage;

  beforeEach(() => {
    resetAllMocks();
    service = new ModelRegistryService();
  });

  describe('Model Management', () => {
    it('should create a new model successfully', async () => {
      const mockModel = createMockModel();
      mockPrisma.model.create.mockResolvedValue({
        ...mockModel,
        createdAt: new Date(mockModel.createdAt),
        updatedAt: new Date(mockModel.updatedAt),
        versions: [],
      });

      const result = await service.createModel({
        name: mockModel.name,
        description: mockModel.description,
        ownerId: mockModel.ownerId,
        team: mockModel.team,
        tags: mockModel.tags,
      });

      expect(result.name).toBe(mockModel.name);
      expect(result.ownerId).toBe(mockModel.ownerId);
      expect(mockPrisma.model.create).toHaveBeenCalledTimes(1);
      expect(logger.info).toHaveBeenCalledWith(
        expect.objectContaining({ modelId: result.id }),
        'Model created'
      );
    });

    it('should list models with pagination', async () => {
      const mockModels = Array.from({ length: 5 }, () => createMockModel());
      mockPrisma.model.count.mockResolvedValue(25);
      mockPrisma.model.findMany.mockResolvedValue(
        mockModels.map((m) => ({
          ...m,
          createdAt: new Date(m.createdAt),
          updatedAt: new Date(m.updatedAt),
          versions: [],
        }))
      );

      const result = await service.listModels({ page: 2, pageSize: 5 });

      expect(result.data).toHaveLength(5);
      expect(result.total).toBe(25);
      expect(result.page).toBe(2);
      expect(result.pageSize).toBe(5);
      expect(result.totalPages).toBe(5);
    });

    it('should filter models by team and tags', async () => {
      mockPrisma.model.count.mockResolvedValue(3);
      mockPrisma.model.findMany.mockResolvedValue([]);

      await service.listModels({
        team: 'data-science',
        tags: ['recommendation', 'production'],
      });

      expect(mockPrisma.model.count).toHaveBeenCalledWith(
        expect.objectContaining({
          where: expect.objectContaining({
            team: 'data-science',
            tags: expect.objectContaining({ hasEvery: ['recommendation', 'production'] }),
          }),
        })
      );
    });
  });

  describe('Model Version Management', () => {
    it('should auto-detect model format from file extension', async () => {
      const formats: { ext: string; expected: ModelFormat }[] = [
        { ext: 'model.pkl', expected: 'pkl' },
        { ext: 'model.onnx', expected: 'onnx' },
        { ext: 'model.pt', expected: 'pt' },
        { ext: 'model.joblib', expected: 'joblib' },
        { ext: 'model.h5', expected: 'h5' },
        { ext: 'model.pb', expected: 'pb' },
        { ext: 'model.pth', expected: 'pt' },
        { ext: 'model.custom', expected: 'custom' },
      ];

      for (const { ext, expected } of formats) {
        const detected = modelLoaderRegistry.autoDetect(ext);
        expect(detected).toBe(expected);
      }
    });

    it('should register multiple versions and return latest', async () => {
      const modelId = 'test-model-id';
      const mockModel = createMockModel({ id: modelId });
      const versions = [
        createMockModelVersion(modelId, { version: '1.0.0', semanticVersion: '1.0.0' }),
        createMockModelVersion(modelId, { version: '1.1.0', semanticVersion: '1.1.0' }),
        createMockModelVersion(modelId, { version: '2.0.0', semanticVersion: '2.0.0' }),
      ];

      mockPrisma.model.findUnique.mockResolvedValue({
        ...mockModel,
        createdAt: new Date(mockModel.createdAt),
        updatedAt: new Date(mockModel.updatedAt),
      });
      modelStorage.putObject.mockResolvedValue('etag-123');

      let callCount = 0;
      mockPrisma.modelVersion.create.mockImplementation(() => {
        const v = versions[callCount++];
        return Promise.resolve({
          ...v,
          createdAt: new Date(v.createdAt),
          sizeBytes: BigInt(v.sizeBytes),
          metrics: v.metrics.map(m => ({ ...m, timestamp: new Date(m.timestamp) })),
          hyperParameters: Object.entries(v.hyperParameters).map(([name, value]) => ({
            id: 'hp-id',
            name,
            value: String(value),
            type: typeof value === 'number' ? 'number' : 'string',
          })),
        });
      });

      mockPrisma.model.update.mockResolvedValue({
        ...mockModel,
        createdAt: new Date(mockModel.createdAt),
        updatedAt: new Date(),
        versions: [],
      });

      for (const v of versions) {
        const fileBuffer = createMockModelFile(v.format);
        await service.createModelVersion({
          modelId,
          version: v.version,
          semanticVersion: v.semanticVersion,
          format: v.format,
          dataSchema: v.dataSchema,
          fileBuffer,
          fileName: `model-${v.version}.${v.format}`,
          metrics: v.metrics.map(m => ({
            ...m,
            timestamp: m.timestamp,
          })),
          hyperParameters: v.hyperParameters,
        });
      }

      mockPrisma.modelVersion.findFirst.mockResolvedValue({
        ...versions[2],
        createdAt: new Date(versions[2].createdAt),
        sizeBytes: BigInt(versions[2].sizeBytes),
        metrics: versions[2].metrics.map(m => ({ ...m, timestamp: new Date(m.timestamp) })),
        hyperParameters: Object.entries(versions[2].hyperParameters).map(([name, value]) => ({
          id: 'hp-id',
          name,
          value: String(value),
          type: typeof value === 'number' ? 'number' : 'string',
        })),
      });

      const latest = await service.getLatestModelVersion(modelId);
      expect(latest?.version).toBe('2.0.0');
      expect(latest?.format).toBe(versions[2].format);
    });

    it('should correctly route to latest stable version', async () => {
      const modelId = 'routing-test-model';
      const mockModel = createMockModel({ id: modelId });

      const versions = [
        createMockModelVersion(modelId, { version: '1.0.0', semanticVersion: '1.0.0' }),
        createMockModelVersion(modelId, { version: '2.0.0-beta', semanticVersion: '2.0.0' }),
      ];

      mockPrisma.model.findUnique.mockResolvedValue({
        ...mockModel,
        createdAt: new Date(mockModel.createdAt),
        updatedAt: new Date(mockModel.updatedAt),
      });

      mockPrisma.modelVersion.findFirst.mockImplementation(({ where, orderBy }: any) => {
        if (where?.status === 'ready') {
          return Promise.resolve({
            ...versions[0],
            createdAt: new Date(versions[0].createdAt),
            sizeBytes: BigInt(versions[0].sizeBytes),
            metrics: versions[0].metrics.map(m => ({ ...m, timestamp: new Date(m.timestamp) })),
            hyperParameters: Object.entries(versions[0].hyperParameters).map(([name, value]) => ({
              id: 'hp-id',
              name,
              value: String(value),
              type: typeof value === 'number' ? 'number' : 'string',
            })),
          });
        }
        return Promise.resolve(null);
      });

      const latestReady = await service.getLatestModelVersion(modelId);
      expect(latestReady?.version).toBe('1.0.0');
    });

    it('should calculate correct SHA256 checksum', async () => {
      const modelId = 'checksum-test';
      const mockModel = createMockModel({ id: modelId });
      const fileBuffer = Buffer.from('test model content');

      mockPrisma.model.findUnique.mockResolvedValue({
        ...mockModel,
        createdAt: new Date(mockModel.createdAt),
        updatedAt: new Date(mockModel.updatedAt),
      });
      modelStorage.putObject.mockResolvedValue(null);
      mockPrisma.modelVersion.create.mockImplementation(({ data }: any) => {
        expect(data.checksum).not.toBeNull();
        expect(data.checksum).toHaveLength(64);
        return Promise.resolve({
          ...data,
          metrics: [],
          hyperParameters: [],
          createdAt: new Date(),
        });
      });
      mockPrisma.model.update.mockResolvedValue({
        ...mockModel,
        createdAt: new Date(mockModel.createdAt),
        updatedAt: new Date(),
        versions: [],
      });

      await service.createModelVersion({
        modelId,
        version: '1.0.0',
        semanticVersion: '1.0.0',
        format: 'pkl',
        dataSchema: defaultDataSchema,
        fileBuffer,
        fileName: 'test.pkl',
      });
    });
  });
});

describe('ModelRegistryService - Exception Path', () => {
  let service: ModelRegistryService;
  const logger = mockLogger;
  const modelStorage = mockModelStorage;

  beforeEach(() => {
    resetAllMocks();
    service = new ModelRegistryService();
  });

  it('should reject corrupted model files and record error event', async () => {
    const modelId = 'corrupted-model-test';
    const mockModel = createMockModel({ id: modelId });
    const corruptedFile = createCorruptedModelFile();

    mockPrisma.model.findUnique.mockResolvedValue({
      ...mockModel,
      createdAt: new Date(mockModel.createdAt),
      updatedAt: new Date(mockModel.updatedAt),
    });

    modelStorage.putObject.mockRejectedValue(new Error('File validation failed: invalid format'));

    await expect(
      service.createModelVersion({
        modelId,
        version: '1.0.0',
        semanticVersion: '1.0.0',
        format: 'pkl',
        dataSchema: defaultDataSchema,
        fileBuffer: corruptedFile,
        fileName: 'corrupted.pkl',
      })
    ).rejects.toThrow('File validation failed');

    expect(mockPrisma.modelVersion.create).not.toHaveBeenCalled();
  });

  it('should throw error when model does not exist', async () => {
    const nonExistentId = 'non-existent-id';
    mockPrisma.model.findUnique.mockResolvedValue(null);

    await expect(
      service.createModelVersion({
        modelId: nonExistentId,
        version: '1.0.0',
        semanticVersion: '1.0.0',
        format: 'pkl',
        dataSchema: defaultDataSchema,
        fileBuffer: createMockModelFile(),
        fileName: 'test.pkl',
      })
    ).rejects.toThrow(`Model not found: ${nonExistentId}`);

    expect(mockPrisma.modelVersion.create).not.toHaveBeenCalled();
  });

  it('should handle storage backend failure gracefully', async () => {
    const modelId = 'storage-failure-test';
    const mockModel = createMockModel({ id: modelId });

    mockPrisma.model.findUnique.mockResolvedValue({
      ...mockModel,
      createdAt: new Date(mockModel.createdAt),
      updatedAt: new Date(mockModel.updatedAt),
    });

    modelStorage.putObject.mockRejectedValue(new Error('S3 connection timeout'));

    await expect(
      service.createModelVersion({
        modelId,
        version: '1.0.0',
        semanticVersion: '1.0.0',
        format: 'pkl',
        dataSchema: defaultDataSchema,
        fileBuffer: createMockModelFile(),
        fileName: 'test.pkl',
      })
    ).rejects.toThrow('S3 connection timeout');

    expect(mockPrisma.modelVersion.create).not.toHaveBeenCalled();
  });

  it('should validate input with Zod schema', async () => {
    await expect(
      service.createModel({
        name: '',
        description: 'test',
        ownerId: 'owner',
        team: 'team',
      } as any)
    ).rejects.toThrow();
  });

  it('should return null for non-existent version download', async () => {
    const nonExistentVersionId = 'non-existent-version';
    mockPrisma.modelVersion.findUnique.mockResolvedValue(null);

    await expect(service.downloadModelVersion(nonExistentVersionId)).rejects.toThrow(
      `Model version not found: ${nonExistentVersionId}`
    );
  });

  it('should handle model deletion when storage delete fails', async () => {
    const modelId = 'delete-failure-test';
    const mockModel = createMockModel({ id: modelId });
    const version = createMockModelVersion(modelId);

    mockPrisma.model.findUnique.mockResolvedValue({
      ...mockModel,
      createdAt: new Date(mockModel.createdAt),
      updatedAt: new Date(mockModel.updatedAt),
      versions: [
        {
          ...version,
          createdAt: new Date(version.createdAt),
          sizeBytes: BigInt(version.sizeBytes),
        },
      ],
    });

    modelStorage.deleteObject.mockRejectedValue(new Error('Storage delete failed'));
    mockPrisma.model.delete.mockResolvedValue({});

    await expect(service.deleteModel(modelId)).resolves.not.toThrow();
    expect(mockPrisma.model.delete).toHaveBeenCalled();
    expect(logger.error).not.toHaveBeenCalled();
  });
});

describe('ModelRegistryService - Concurrency Scenarios', () => {
  let service: ModelRegistryService;
  const modelStorage = mockModelStorage;

  beforeEach(() => {
    resetAllMocks();
    service = new ModelRegistryService();
  });

  it('should handle concurrent version uploads without data corruption', async () => {
    const modelId = 'concurrent-upload-test';
    const mockModel = createMockModel({ id: modelId });

    mockPrisma.model.findUnique.mockResolvedValue({
      ...mockModel,
      createdAt: new Date(mockModel.createdAt),
      updatedAt: new Date(mockModel.updatedAt),
    });

    let versionCount = 0;
    const createdVersions: string[] = [];

    mockPrisma.modelVersion.create.mockImplementation(({ data }: any) => {
      versionCount++;
      createdVersions.push(data.version);
      return Promise.resolve({
        ...data,
        id: `version-${versionCount}`,
        metrics: [],
        hyperParameters: [],
        createdAt: new Date(),
      });
    });

    mockPrisma.model.update.mockImplementation(({ data }: any) => {
      expect(data.latestVersionId).toBeTruthy();
      return Promise.resolve({
        ...mockModel,
        createdAt: new Date(mockModel.createdAt),
        updatedAt: new Date(),
        versions: [],
      });
    });

    modelStorage.putObject.mockResolvedValue('etag-concurrent');

    const uploadCount = 10;
    const results = await concurrent(uploadCount, async (i) => {
      const version = `1.${i}.0`;
      return service.createModelVersion({
        modelId,
        version,
        semanticVersion: version,
        format: 'pkl',
        dataSchema: defaultDataSchema,
        fileBuffer: createMockModelFile(),
        fileName: `model-${version}.pkl`,
      });
    });

    expect(results).toHaveLength(uploadCount);
    expect(mockPrisma.modelVersion.create).toHaveBeenCalledTimes(uploadCount);
    expect(mockPrisma.model.update).toHaveBeenCalledTimes(uploadCount);

    const uniqueVersions = new Set(createdVersions);
    expect(uniqueVersions.size).toBe(uploadCount);
  });

  it('should handle concurrent model creation with same name', async () => {
    const duplicateName = 'duplicate-model-name';
    let createCount = 0;

    mockPrisma.model.create.mockImplementation(({ data }: any) => {
      createCount++;
      return Promise.resolve({
        ...data,
        id: `model-${createCount}`,
        createdAt: new Date(),
        updatedAt: new Date(),
        versions: [],
      });
    });

    const results = await concurrent(5, async () => {
      return service.createModel({
        name: duplicateName,
        description: 'test',
        ownerId: 'owner',
        team: 'team',
      });
    });

    expect(results).toHaveLength(5);
    const uniqueIds = new Set(results.map((r) => r.id));
    expect(uniqueIds.size).toBe(5);
  });

  it('should handle concurrent read operations consistently', async () => {
    const modelId = 'concurrent-read-test';
    const mockModel = createMockModel({ id: modelId });
    const version = createMockModelVersion(modelId);

    mockPrisma.model.findUnique.mockResolvedValue({
      ...mockModel,
      createdAt: new Date(mockModel.createdAt),
      updatedAt: new Date(mockModel.updatedAt),
      versions: [
        {
          ...version,
          createdAt: new Date(version.createdAt),
          sizeBytes: BigInt(version.sizeBytes),
          metrics: version.metrics.map(m => ({ ...m, timestamp: new Date(m.timestamp) })),
          hyperParameters: [],
        },
      ],
    });

    mockPrisma.modelVersion.findUnique.mockResolvedValue({
      ...version,
      createdAt: new Date(version.createdAt),
      sizeBytes: BigInt(version.sizeBytes),
      metrics: version.metrics.map(m => ({ ...m, timestamp: new Date(m.timestamp) })),
      hyperParameters: [],
    });

    const readCount = 50;
    const promises = Array.from({ length: readCount }, () => service.getModel(modelId));
    const results = await Promise.all(promises);

    expect(results).toHaveLength(readCount);
    results.forEach((result) => {
      expect(result).not.toBeNull();
      expect(result?.id).toBe(modelId);
    });
  });
});
