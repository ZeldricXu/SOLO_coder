import { faker } from '@faker-js/faker';
import { v4 as uuidv4 } from 'uuid';
import type { Model, ModelVersion, ModelFormat, StorageBackend, Status } from '@mlops/shared';
import type {
  FeatureSet,
  FeatureSetVersion,
  FeatureStorageMode,
} from '@mlops/shared';
import type { ABTest, ABVariant, ABTestStatus, BucketingStrategy } from '@mlops/shared';
import type {
  InferenceRequest,
  InferenceResponse,
  BatchConfig,
} from '@mlops/shared';

export function createMockModel(overrides: Partial<Model> = {}): Model {
  const now = new Date();
  return {
    id: uuidv4(),
    name: faker.commerce.productName().replace(/\s+/g, '-').toLowerCase(),
    description: faker.lorem.sentence(),
    owner: faker.person.fullName(),
    team: faker.commerce.department(),
    tags: [faker.word.noun(), faker.word.noun()],
    status: 'ACTIVE' as Status,
    latestVersionId: null,
    createdAt: now,
    updatedAt: now,
    ...overrides,
  };
}

export function createMockModelVersion(
  modelId: string,
  overrides: Partial<ModelVersion> = {}
): ModelVersion {
  const formats: ModelFormat[] = ['pkl', 'onnx', 'pt', 'joblib', 'h5', 'pb'];
  const now = new Date();
  return {
    id: uuidv4(),
    modelId,
    version: `${faker.number.int({ min: 1, max: 10 })}.${faker.number.int({ min: 0, max: 20 })}.${faker.number.int({ min: 0, max: 100 })}`,
    format: faker.helpers.arrayElement(formats),
    storageBackend: 'LOCAL' as StorageBackend,
    storagePath: `/tmp/mlops-test/${modelId}/${uuidv4()}.pkl`,
    checksum: faker.string.alphanumeric(64),
    sizeBytes: faker.number.int({ min: 1024, max: 1024 * 1024 * 100 }),
    metrics: [
      { key: 'accuracy', value: faker.number.float({ min: 0.7, max: 0.99 }), timestamp: now },
      { key: 'f1_score', value: faker.number.float({ min: 0.6, max: 0.95 }), timestamp: now },
    ],
    hyperparameters: [
      { key: 'learning_rate', value: faker.number.float({ min: 0.0001, max: 0.1 }) },
      { key: 'epochs', value: faker.number.int({ min: 10, max: 1000 }) },
    ],
    schema: {
      input: { type: 'object', properties: { features: { type: 'array' } } },
      output: { type: 'object', properties: { prediction: { type: 'number' } } },
    },
    isLatest: false,
    isStable: false,
    createdAt: now,
    ...overrides,
  };
}

export function createMockModelFile(format: ModelFormat = 'pkl'): Buffer {
  const headers: Record<ModelFormat, Buffer> = {
    pkl: Buffer.from([0x80, 0x04, 0x95]),
    onnx: Buffer.from('onnx'),
    pt: Buffer.from([0x80, 0x02, 0x8a]),
    joblib: Buffer.from([0x80, 0x04, 0x95]),
    h5: Buffer.from([0x89, 0x48, 0x44, 0x46]),
    pb: Buffer.from([0x0a, 0x0b, 0x08]),
    custom: Buffer.from('custom-model'),
  };
  const header = headers[format] || headers.pkl;
  const content = Buffer.alloc(1024);
  header.copy(content);
  return content;
}

export function createCorruptedModelFile(): Buffer {
  return Buffer.from('this is not a valid model file');
}

export function createMockFeatureSet(overrides: Partial<FeatureSet> = {}): FeatureSet {
  const now = new Date();
  return {
    id: uuidv4(),
    name: faker.commerce.productName().replace(/\s+/g, '_').toLowerCase() + '_features',
    description: faker.lorem.sentence(),
    owner: faker.person.fullName(),
    entityKey: faker.helpers.arrayElement(['user_id', 'session_id', 'device_id']),
    ttlSeconds: faker.number.int({ min: 3600, max: 86400 * 30 }),
    storageMode: 'DUAL' as FeatureStorageMode,
    latestVersionId: null,
    createdAt: now,
    updatedAt: now,
    ...overrides,
  };
}

export function createMockFeatureSetVersion(
  featureSetId: string,
  overrides: Partial<FeatureSetVersion> = {}
): FeatureSetVersion {
  const now = new Date();
  return {
    id: uuidv4(),
    featureSetId,
    version: faker.number.int({ min: 1, max: 100 }),
    features: [
      {
        name: faker.finance.accountName().replace(/\s+/g, '_').toLowerCase(),
        type: 'float',
        description: faker.lorem.sentence(),
        defaultValue: faker.number.float(),
      },
      {
        name: faker.commerce.productMaterial().replace(/\s+/g, '_').toLowerCase(),
        type: 'int',
        description: faker.lorem.sentence(),
        defaultValue: faker.number.int(),
      },
    ],
    storageConfig: {
      online: { enabled: true, redisKeyPrefix: `feature:${featureSetId}` },
      offline: { enabled: true, s3Bucket: 'features', s3Prefix: `${featureSetId}/` },
    },
    statistics: [
      { featureName: 'feature_1', mean: faker.number.float(), stddev: faker.number.float() },
    ],
    isLatest: true,
    createdAt: now,
    ...overrides,
  };
}

export function createMockFeatureData(count: number = 10): Record<string, any>[] {
  return Array.from({ length: count }, () => ({
    user_id: uuidv4(),
    feature_1: faker.number.float({ min: 0, max: 100 }),
    feature_2: faker.number.int({ min: 0, max: 1000 }),
    feature_3: faker.datatype.boolean() ? 1.0 : 0.0,
  }));
}

export function createMockABTest(overrides: Partial<ABTest> = {}): ABTest {
  const now = new Date();
  const future = faker.date.future({ years: 1 });
  return {
    id: uuidv4(),
    name: `ab-test-${faker.commerce.productName().replace(/\s+/g, '-').toLowerCase()}`,
    description: faker.lorem.sentence(),
    owner: faker.person.fullName(),
    hypothesis: faker.lorem.sentence(),
    primaryMetric: 'conversion_rate',
    status: 'DRAFT' as ABTestStatus,
    bucketingStrategy: 'USER_ID' as BucketingStrategy,
    bucketKey: 'user_id',
    seed: faker.number.int(),
    variants: [],
    startTime: now,
    endTime: future,
    createdAt: now,
    updatedAt: now,
    ...overrides,
  };
}

export function createMockVariants(count: number = 3): ABVariant[] {
  const baseWeight = Math.floor(100 / count);
  const weights = Array(count).fill(baseWeight);
  weights[0] += 100 - baseWeight * count;
  return Array.from({ length: count }, (_, i) => ({
    id: uuidv4(),
    name: i === 0 ? 'control' : `variant_${i}`,
    description: faker.lorem.sentence(),
    weight: weights[i],
    isControl: i === 0,
    trafficAllocation: weights[i],
    modelVersionId: uuidv4(),
  }));
}

export function createMockInferenceRequest(
  modelId: string,
  overrides: Partial<InferenceRequest> = {}
): InferenceRequest {
  return {
    modelId,
    version: faker.helpers.arrayElement(['latest', 'stable', '1.0.0']),
    inputs: {
      features: Array.from({ length: 10 }, () => faker.number.float({ min: 0, max: 1 })),
    },
    params: {
      maxBatchSize: faker.number.int({ min: 8, max: 64 }),
      timeoutMs: faker.number.int({ min: 10, max: 100 }),
    },
    requestId: uuidv4(),
    timestamp: new Date(),
    ...overrides,
  };
}

export function createMockBatchConfig(overrides: Partial<BatchConfig> = {}): BatchConfig {
  return {
    maxBatchSize: faker.helpers.arrayElement([8, 16, 32, 64]),
    maxTimeoutMs: faker.helpers.arrayElement([10, 20, 50, 100]),
    dynamicBatchEnabled: true,
    cacheEnabled: true,
    cacheTtlSeconds: faker.number.int({ min: 60, max: 3600 }),
    ...overrides,
  };
}

export function generateUserId(): string {
  return `user_${faker.string.alphanumeric(32)}`;
}

export function generateMillionUserIds(count: number = 1000000): string[] {
  const ids = new Set<string>();
  while (ids.size < count) {
    ids.add(generateUserId());
  }
  return Array.from(ids);
}

export function delay(ms: number): Promise<void> {
  return new Promise(resolve => setTimeout(resolve, ms));
}

export async function concurrent<T>(
  count: number,
  factory: (index: number) => Promise<T>
): Promise<T[]> {
  return Promise.all(Array.from({ length: count }, (_, i) => factory(i)));
}

export function calculateMean(values: number[]): number {
  if (values.length === 0) return 0;
  return values.reduce((a, b) => a + b, 0) / values.length;
}

export function calculateStddev(values: number[], mean?: number): number {
  if (values.length === 0) return 0;
  const m = mean ?? calculateMean(values);
  const squareDiffs = values.map(v => Math.pow(v - m, 2));
  return Math.sqrt(calculateMean(squareDiffs));
}

export function calculatePercentile(values: number[], percentile: number): number {
  if (values.length === 0) return 0;
  const sorted = [...values].sort((a, b) => a - b);
  const index = Math.ceil((percentile / 100) * sorted.length);
  return sorted[Math.min(index, sorted.length - 1)];
}
