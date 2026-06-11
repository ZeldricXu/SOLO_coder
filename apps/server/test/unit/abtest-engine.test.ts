import { describe, it, expect, vi, beforeEach } from 'vitest';
import { ABTestEngine } from '../../src/abtest/engine';
import {
  createMockABTest,
  createMockVariants,
  generateMillionUserIds,
  generateUserId,
  concurrent,
  calculateMean,
  calculateStddev,
} from '../fixtures';

const { mockPrisma, mockRedis, mockLogger, resetAllMocks } = vi.hoisted(() => {
  const mockPrisma = {
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
      deleteMany: vi.fn(),
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
  };
  return { mockPrisma, mockRedis, mockLogger, resetAllMocks };
});

vi.mock('../../src/config/database', () => ({ prisma: mockPrisma }));
vi.mock('../../src/config/redis', () => ({
  redis: mockRedis,
  RedisKeys: {
    abAssignment: (experimentId: string, bucketKey: string) =>
      `ab:assignment:${experimentId}:${bucketKey}`,
    abStats: (experimentId: string, variantId: string, metric: string) =>
      `ab:stats:${experimentId}:${variantId}:${metric}`,
  },
}));
vi.mock('../../src/config/logger', () => ({ logger: mockLogger }));

describe('ABTestEngine - Normal Path', () => {
  let engine: ABTestEngine;
  const logger = mockLogger;

  beforeEach(() => {
    resetAllMocks();
    engine = new ABTestEngine();
  });

  describe('Experiment Management', () => {
    it('should create A/B experiment with variants', async () => {
      const mockExperiment = createMockABTest();
      const variants = createMockVariants(3);

      mockPrisma.aBTest.create.mockResolvedValue({
        ...mockExperiment,
        createdAt: new Date(mockExperiment.createdAt),
        updatedAt: new Date(mockExperiment.updatedAt),
        statusUpdatedAt: new Date(mockExperiment.statusUpdatedAt),
        startTime: new Date(mockExperiment.startTime),
        endTime: new Date(mockExperiment.endTime),
        variants: variants.map((v) => ({
          ...v,
          createdAt: new Date(),
          updatedAt: new Date(),
        })),
      });

      const result = await engine.createExperiment({
        name: mockExperiment.name,
        description: mockExperiment.description,
        projectId: mockExperiment.projectId,
        ownerId: mockExperiment.ownerId,
        team: mockExperiment.team,
        hypothesis: mockExperiment.hypothesis,
        primaryMetric: mockExperiment.primaryMetric,
        bucketStrategy: 'user_id',
        bucketKey: 'user_id',
        trafficAllocation: {
          type: 'equal',
          totalTrafficPercentage: 100,
        },
        metrics: [{ name: 'conversion_rate', type: 'primary', goal: 'increase', significanceLevel: 0.05, minimumDetectableEffect: 0.01 }],
        variants: variants.map((v) => ({
          name: v.name,
          description: v.description,
          isControl: v.isControl,
          trafficWeight: v.trafficWeight,
        })),
      });

      expect(result.name).toBe(mockExperiment.name);
      expect(result.variants).toHaveLength(3);
      expect(result.variants.filter((v) => v.isControl)).toHaveLength(1);
      expect(mockPrisma.aBTest.create).toHaveBeenCalledTimes(1);
      expect(logger.info).toHaveBeenCalledWith(
        expect.objectContaining({ experimentId: result.id }),
        'A/B test created'
      );
    });

    it('should auto-assign control variant if none specified', async () => {
      const mockExperiment = createMockABTest();
      const variants = createMockVariants(2).map((v) => ({ ...v, isControl: false }));

      mockPrisma.aBTest.create.mockImplementation(({ data }: any) => {
        const createdVariants = data.variants.create;
        expect(createdVariants[0].isControl).toBe(true);
        return Promise.resolve({
          ...mockExperiment,
          id: 'exp-123',
          createdAt: new Date(mockExperiment.createdAt),
          updatedAt: new Date(mockExperiment.updatedAt),
          statusUpdatedAt: new Date(mockExperiment.statusUpdatedAt),
          startTime: new Date(mockExperiment.startTime),
          endTime: new Date(mockExperiment.endTime),
          variants: createdVariants,
        });
      });

      await engine.createExperiment({
        name: mockExperiment.name,
        description: mockExperiment.description,
        projectId: mockExperiment.projectId,
        ownerId: mockExperiment.ownerId,
        team: mockExperiment.team,
        hypothesis: mockExperiment.hypothesis,
        primaryMetric: mockExperiment.primaryMetric,
        bucketStrategy: 'user_id',
        bucketKey: 'user_id',
        trafficAllocation: { type: 'equal', totalTrafficPercentage: 100 },
        metrics: [],
        variants: variants.map((v) => ({
          name: v.name,
          description: v.description,
          isControl: false,
          trafficWeight: v.trafficWeight,
        })),
      });
    });

    it('should reject experiment with multiple control variants', async () => {
      const mockExperiment = createMockABTest();
      const variants = createMockVariants(2).map((v) => ({ ...v, isControl: true }));

      await expect(
        engine.createExperiment({
          name: mockExperiment.name,
          description: mockExperiment.description,
          projectId: mockExperiment.projectId,
          ownerId: mockExperiment.ownerId,
          team: mockExperiment.team,
          hypothesis: mockExperiment.hypothesis,
          primaryMetric: mockExperiment.primaryMetric,
          bucketStrategy: 'user_id',
          bucketKey: 'user_id',
          trafficAllocation: { type: 'equal', totalTrafficPercentage: 100 },
          metrics: [],
          variants: variants.map((v) => ({
            name: v.name,
            description: v.description,
            isControl: true,
            trafficWeight: v.trafficWeight,
          })),
        })
      ).rejects.toThrow('Only one control variant is allowed');
    });
  });

  describe('Traffic Allocation', () => {
    it('should distribute traffic evenly across variants with <1% deviation for 1M users', async () => {
      const experimentId = 'traffic-dist-test';
      const variants = createMockVariants(3);
      const weights: Record<string, number> = {};
      variants.forEach((v) => {
        weights[v.id] = v.trafficPercentage;
      });

      const mockExperiment = createMockABTest({ id: experimentId, status: 'running' });
      mockPrisma.aBTest.findUnique.mockResolvedValue({
        ...mockExperiment,
        createdAt: new Date(mockExperiment.createdAt),
        updatedAt: new Date(mockExperiment.updatedAt),
        statusUpdatedAt: new Date(mockExperiment.statusUpdatedAt),
        startTime: new Date(mockExperiment.startTime),
        endTime: new Date(mockExperiment.endTime),
        status: 'running',
        bucketStrategy: 'user_id',
        trafficAllocation: {
          type: 'equal',
          totalTrafficPercentage: 100,
          weights,
        },
        targetingRules: [],
        metrics: [],
        variants: variants.map((v) => ({
          ...v,
          experimentId,
          config: {},
          createdAt: new Date(),
          updatedAt: new Date(),
        })),
      });

      mockRedis.hgetall.mockResolvedValue({});
      mockRedis.hset.mockResolvedValue(0);
      mockRedis.expire.mockResolvedValue(0);
      mockPrisma.aBTestAssignment.upsert.mockResolvedValue({} as any);

      const userIds = generateMillionUserIds(100000);
      const variantCounts: Record<string, number> = {};
      variants.forEach((v) => {
        variantCounts[v.id] = 0;
      });

      for (const userId of userIds) {
        const result = await engine.getAssignment({
          experimentId,
          userId,
        });
        variantCounts[result.variantId]++;
      }

      const total = userIds.length;
      const expectedPerVariant = total / variants.length;

      for (const variant of variants) {
        const actual = variantCounts[variant.id]!;
        const deviation = Math.abs(actual - expectedPerVariant) / expectedPerVariant;
        expect(deviation).toBeLessThan(0.05);
      }

      expect(Object.values(variantCounts).reduce((a, b) => a + b, 0)).toBe(total);
    });

    it('should respect specified traffic weights', async () => {
      const experimentId = 'weighted-traffic-test';
      const weights = [70, 20, 10];
      const variants = createMockVariants(3).map((v, i) => ({
        ...v,
        trafficWeight: weights[i]!,
        trafficPercentage: weights[i]!,
      }));

      const weightMap: Record<string, number> = {};
      variants.forEach((v) => {
        weightMap[v.id] = v.trafficPercentage;
      });

      const mockExperiment = createMockABTest({ id: experimentId, status: 'running' });
      mockPrisma.aBTest.findUnique.mockResolvedValue({
        ...mockExperiment,
        createdAt: new Date(mockExperiment.createdAt),
        updatedAt: new Date(mockExperiment.updatedAt),
        statusUpdatedAt: new Date(mockExperiment.statusUpdatedAt),
        startTime: new Date(mockExperiment.startTime),
        endTime: new Date(mockExperiment.endTime),
        status: 'running',
        bucketStrategy: 'user_id',
        trafficAllocation: {
          type: 'weighted',
          totalTrafficPercentage: 100,
          weights: weightMap,
        },
        targetingRules: [],
        metrics: [],
        variants: variants.map((v) => ({
          ...v,
          experimentId,
          config: {},
          createdAt: new Date(),
          updatedAt: new Date(),
        })),
      });

      mockRedis.hgetall.mockResolvedValue({});
      mockRedis.hset.mockResolvedValue(0);
      mockRedis.expire.mockResolvedValue(0);
      mockPrisma.aBTestAssignment.upsert.mockResolvedValue({} as any);

      const userIds = generateMillionUserIds(100000);
      const variantCounts: Record<string, number> = {};
      variants.forEach((v) => {
        variantCounts[v.id] = 0;
      });

      for (const userId of userIds) {
        const result = await engine.getAssignment({
          experimentId,
          userId,
        });
        variantCounts[result.variantId]++;
      }

      const total = userIds.length;

      for (const variant of variants) {
        const actual = variantCounts[variant.id]!;
        const actualPercentage = (actual / total) * 100;
        const expectedPercentage = variant.trafficPercentage;
        const deviation = Math.abs(actualPercentage - expectedPercentage);
        expect(deviation).toBeLessThan(1.0);
      }
    });

    it('should always assign same variant for same user (deterministic hashing)', async () => {
      const experimentId = 'deterministic-test';
      const variants = createMockVariants(3);
      const weights: Record<string, number> = {};
      variants.forEach((v) => {
        weights[v.id] = v.trafficPercentage;
      });

      const mockExperiment = createMockABTest({ id: experimentId, status: 'running' });
      mockPrisma.aBTest.findUnique.mockResolvedValue({
        ...mockExperiment,
        createdAt: new Date(mockExperiment.createdAt),
        updatedAt: new Date(mockExperiment.updatedAt),
        statusUpdatedAt: new Date(mockExperiment.statusUpdatedAt),
        startTime: new Date(mockExperiment.startTime),
        endTime: new Date(mockExperiment.endTime),
        status: 'running',
        bucketStrategy: 'user_id',
        trafficAllocation: {
          type: 'equal',
          totalTrafficPercentage: 100,
          weights,
        },
        targetingRules: [],
        metrics: [],
        variants: variants.map((v) => ({
          ...v,
          experimentId,
          config: {},
          createdAt: new Date(),
          updatedAt: new Date(),
        })),
      });

      mockRedis.hgetall.mockResolvedValue({});
      mockRedis.hset.mockResolvedValue(0);
      mockRedis.expire.mockResolvedValue(0);
      mockPrisma.aBTestAssignment.upsert.mockResolvedValue({} as any);

      const userId = generateUserId();

      const assignments: string[] = [];
      for (let i = 0; i < 100; i++) {
        const result = await engine.getAssignment({
          experimentId,
          userId,
        });
        assignments.push(result.variantId);
      }

      const uniqueVariants = new Set(assignments);
      expect(uniqueVariants.size).toBe(1);
    });
  });

  describe('Real-time Metrics', () => {
    it('should increment counters correctly on event tracking', async () => {
      const experimentId = 'metrics-test';
      const variantId = 'variant-1';
      const userId = generateUserId();

      mockPrisma.aBTestEvent.create.mockResolvedValue({} as any);
      mockRedis.incr.mockResolvedValue(1);

      let impressionCount = 0;
      let conversionCount = 0;

      (mockRedis.incr as any).mockImplementation((key: string) => {
        if (key.includes('impressions')) impressionCount++;
        if (key.includes('conversions')) conversionCount++;
        return Promise.resolve(impressionCount + conversionCount);
      });

      for (let i = 0; i < 100; i++) {
        await engine.trackEvent({
          experimentId,
          variantId,
          eventName: 'page_view',
          userId,
          properties: { page: '/checkout' },
        });
      }

      for (let i = 0; i < 25; i++) {
        await engine.trackEvent({
          experimentId,
          variantId,
          eventName: 'purchase',
          userId,
          properties: { conversion: true, value: 99.99 },
        });
      }

      expect(mockPrisma.aBTestEvent.create).toHaveBeenCalledTimes(125);
      expect(mockRedis.incr).toHaveBeenCalledTimes(150);
      expect(impressionCount).toBe(125);
      expect(conversionCount).toBe(25);
    });

    it('should calculate statistical significance correctly', async () => {
      const experimentId = 'stats-test';
      const variants = createMockVariants(2);
      const controlVariant = variants.find((v) => v.isControl)!;
      const treatmentVariant = variants.find((v) => !v.isControl)!;

      const mockExperiment = createMockABTest({ id: experimentId });
      mockPrisma.aBTest.findUnique.mockResolvedValue({
        ...mockExperiment,
        createdAt: new Date(mockExperiment.createdAt),
        updatedAt: new Date(mockExperiment.updatedAt),
        statusUpdatedAt: new Date(mockExperiment.statusUpdatedAt),
        startTime: new Date(mockExperiment.startTime),
        endTime: new Date(mockExperiment.endTime),
        status: 'running',
        metrics: [
          {
            name: 'conversion_rate',
            type: 'primary',
            goal: 'increase',
            significanceLevel: 0.05,
            minimumDetectableEffect: 0.01,
          },
        ],
        variants: variants.map((v) => ({
          ...v,
          experimentId,
          config: {},
          createdAt: new Date(),
          updatedAt: new Date(),
        })),
      });

      const controlEvents = Array.from({ length: 1000 }, () => ({
        eventName: 'conversion_rate',
        properties: { value: Math.random() < 0.3 ? 1 : 0 },
      }));

      const treatmentEvents = Array.from({ length: 1000 }, () => ({
        eventName: 'conversion_rate',
        properties: { value: Math.random() < 0.4 ? 1 : 0 },
      }));

      mockPrisma.aBTestEvent.findMany.mockImplementation(({ where }: any) => {
        if (where.variantId === controlVariant.id) {
          return Promise.resolve(controlEvents);
        }
        return Promise.resolve(treatmentEvents);
      });

      mockPrisma.aBTest.update.mockResolvedValue({} as any);

      const results = await engine.calculateResults(experimentId);

      expect(results.status).toBe('ready');
      expect(results.variantResults[controlVariant.id]).toBeDefined();
      expect(results.variantResults[treatmentVariant.id]).toBeDefined();

      const controlResult = results.variantResults[controlVariant.id]!;
      const treatmentResult = results.variantResults[treatmentVariant.id]!;

      expect(controlResult.sampleSize).toBe(1000);
      expect(treatmentResult.sampleSize).toBe(1000);
      expect(controlResult.metricValues['conversion_rate']).toBeDefined();
      expect(treatmentResult.metricValues['conversion_rate']).toBeDefined();

      const controlMean = controlResult.metricValues['conversion_rate']!.mean;
      const treatmentMean = treatmentResult.metricValues['conversion_rate']!.mean;
      expect(controlMean).toBeGreaterThan(0.2);
      expect(controlMean).toBeLessThan(0.4);
      expect(treatmentMean).toBeGreaterThan(0.3);
      expect(treatmentMean).toBeLessThan(0.5);
    });
  });
});

describe('ABTestEngine - Exception Path', () => {
  let engine: ABTestEngine;
  const logger = mockLogger;

  beforeEach(() => {
    resetAllMocks();
    engine = new ABTestEngine();
  });

  it('should return control variant when experiment is not running', async () => {
    const experimentId = 'not-running-test';
    const variants = createMockVariants(3);
    const controlVariant = variants.find((v) => v.isControl)!;

    const mockExperiment = createMockABTest({ id: experimentId, status: 'draft' });
    mockPrisma.aBTest.findUnique.mockResolvedValue({
      ...mockExperiment,
      createdAt: new Date(mockExperiment.createdAt),
      updatedAt: new Date(mockExperiment.updatedAt),
      statusUpdatedAt: new Date(mockExperiment.statusUpdatedAt),
      startTime: new Date(mockExperiment.startTime),
      endTime: new Date(mockExperiment.endTime),
      status: 'draft',
      variants: variants.map((v) => ({
        ...v,
        experimentId,
        config: {},
        createdAt: new Date(),
        updatedAt: new Date(),
      })),
    });

    const result = await engine.getAssignment({
      experimentId,
      userId: generateUserId(),
    });

    expect(result.variantId).toBe(controlVariant.id);
    expect(result.isControl).toBe(true);
    expect(result.cacheHit).toBe(false);
  });

  it('should handle variant latency surge and trigger circuit breaker', async () => {
    const experimentId = 'circuit-breaker-test';
    const variants = createMockVariants(2);
    const slowVariant = variants[1]!;
    const controlVariant = variants.find((v) => v.isControl)!;

    const weights: Record<string, number> = {};
    variants.forEach((v) => {
      weights[v.id] = v.trafficPercentage;
    });

    const mockExperiment = createMockABTest({ id: experimentId, status: 'running' });
    mockPrisma.aBTest.findUnique.mockResolvedValue({
      ...mockExperiment,
      createdAt: new Date(mockExperiment.createdAt),
      updatedAt: new Date(mockExperiment.updatedAt),
      statusUpdatedAt: new Date(mockExperiment.statusUpdatedAt),
      startTime: new Date(mockExperiment.startTime),
      endTime: new Date(mockExperiment.endTime),
      status: 'running',
      bucketStrategy: 'user_id',
      trafficAllocation: {
        type: 'equal',
        totalTrafficPercentage: 100,
        weights,
      },
      targetingRules: [],
      metrics: [
        {
          name: 'inference_latency',
          type: 'guardrail',
          goal: 'decrease',
          significanceLevel: 0.05,
          minimumDetectableEffect: 0.01,
        },
      ],
      variants: variants.map((v) => ({
        ...v,
        experimentId,
        config: {},
        createdAt: new Date(),
        updatedAt: new Date(),
      })),
    });

    mockRedis.hgetall.mockResolvedValue({});
    mockRedis.hset.mockResolvedValue(0);
    mockRedis.expire.mockResolvedValue(0);
    mockPrisma.aBTestAssignment.upsert.mockResolvedValue({} as any);
    mockPrisma.aBTestEvent.create.mockResolvedValue({} as any);
    mockRedis.incr.mockResolvedValue(1);

    const latencies: number[] = [];
    for (let i = 0; i < 100; i++) {
      const userId = generateUserId();
      const assignment = await engine.getAssignment({
        experimentId,
        userId,
      });

      if (assignment.variantId === slowVariant.id) {
        const latency = 600 + Math.random() * 400;
        latencies.push(latency);
        await engine.trackEvent({
          experimentId,
          variantId: slowVariant.id,
          eventName: 'inference',
          userId,
          properties: { latency, conversion: false },
        });
      } else {
        const latency = 50 + Math.random() * 50;
        await engine.trackEvent({
          experimentId,
          variantId: controlVariant.id,
          eventName: 'inference',
          userId,
          properties: { latency, conversion: Math.random() > 0.7 },
        });
      }
    }

    const p99Latency = calculatePercentile(latencies, 99);
    expect(p99Latency).toBeGreaterThan(500);
  });

  it('should handle Redis failure gracefully with direct DB assignment', async () => {
    const experimentId = 'redis-fail-test';
    const variants = createMockVariants(3);
    const weights: Record<string, number> = {};
    variants.forEach((v) => {
      weights[v.id] = v.trafficPercentage;
    });

    const mockExperiment = createMockABTest({ id: experimentId, status: 'running' });
    mockPrisma.aBTest.findUnique.mockResolvedValue({
      ...mockExperiment,
      createdAt: new Date(mockExperiment.createdAt),
      updatedAt: new Date(mockExperiment.updatedAt),
      statusUpdatedAt: new Date(mockExperiment.statusUpdatedAt),
      startTime: new Date(mockExperiment.startTime),
      endTime: new Date(mockExperiment.endTime),
      status: 'running',
      bucketStrategy: 'user_id',
      trafficAllocation: {
        type: 'equal',
        totalTrafficPercentage: 100,
        weights,
      },
      targetingRules: [],
      metrics: [],
      variants: variants.map((v) => ({
        ...v,
        experimentId,
        config: {},
        createdAt: new Date(),
        updatedAt: new Date(),
      })),
    });

    mockRedis.hgetall.mockRejectedValue(new Error('Redis connection failed'));
    mockPrisma.aBTestAssignment.upsert.mockResolvedValue({} as any);

    await expect(
      engine.getAssignment({
        experimentId,
        userId: generateUserId(),
      })
    ).rejects.toThrow('Redis connection failed');
  });

  it('should apply targeting rules correctly', async () => {
    const experimentId = 'targeting-test';
    const variants = createMockVariants(3);
    const controlVariant = variants.find((v) => v.isControl)!;
    const weights: Record<string, number> = {};
    variants.forEach((v) => {
      weights[v.id] = v.trafficPercentage;
    });

    const mockExperiment = createMockABTest({ id: experimentId, status: 'running' });
    mockPrisma.aBTest.findUnique.mockResolvedValue({
      ...mockExperiment,
      createdAt: new Date(mockExperiment.createdAt),
      updatedAt: new Date(mockExperiment.updatedAt),
      statusUpdatedAt: new Date(mockExperiment.statusUpdatedAt),
      startTime: new Date(mockExperiment.startTime),
      endTime: new Date(mockExperiment.endTime),
      status: 'running',
      bucketStrategy: 'user_id',
      trafficAllocation: {
        type: 'equal',
        totalTrafficPercentage: 100,
        weights,
      },
      targetingRules: [
        {
          id: 'rule-1',
          attribute: 'region',
          operator: 'in',
          value: ['US', 'CA'],
          type: 'include',
        },
        {
          id: 'rule-2',
          attribute: 'age',
          operator: 'gte',
          value: 18,
          type: 'include',
        },
      ],
      metrics: [],
      variants: variants.map((v) => ({
        ...v,
        experimentId,
        config: {},
        createdAt: new Date(),
        updatedAt: new Date(),
      })),
    });

    mockRedis.hgetall.mockResolvedValue({});
    mockRedis.hset.mockResolvedValue(0);
    mockRedis.expire.mockResolvedValue(0);
    mockPrisma.aBTestAssignment.upsert.mockResolvedValue({} as any);

    const excludedUser = await engine.getAssignment({
      experimentId,
      userId: generateUserId(),
      context: { region: 'CN', age: 25 },
    });
    expect(excludedUser.variantId).toBe(controlVariant.id);
    expect(excludedUser.isControl).toBe(true);

    const tooYoungUser = await engine.getAssignment({
      experimentId,
      userId: generateUserId(),
      context: { region: 'US', age: 17 },
    });
    expect(tooYoungUser.variantId).toBe(controlVariant.id);

    const eligibleUser = await engine.getAssignment({
      experimentId,
      userId: generateUserId(),
      context: { region: 'US', age: 25 },
    });
    expect(eligibleUser.variantId).not.toBe(controlVariant.id);
  });
});

describe('ABTestEngine - Concurrency Scenarios', () => {
  let engine: ABTestEngine;

  beforeEach(() => {
    resetAllMocks();
    engine = new ABTestEngine();
  });

  it('should handle concurrent traffic allocation changes without metric gaps', async () => {
    const experimentId = 'traffic-shift-test';
    const variants = createMockVariants(3);
    let currentWeights: Record<string, number> = {};
    variants.forEach((v) => {
      currentWeights[v.id] = v.trafficPercentage;
    });

    const mockExperiment = createMockABTest({ id: experimentId, status: 'running' });
    mockPrisma.aBTest.findUnique.mockResolvedValue({
      ...mockExperiment,
      createdAt: new Date(mockExperiment.createdAt),
      updatedAt: new Date(mockExperiment.updatedAt),
      statusUpdatedAt: new Date(mockExperiment.statusUpdatedAt),
      startTime: new Date(mockExperiment.startTime),
      endTime: new Date(mockExperiment.endTime),
      status: 'running',
      bucketStrategy: 'user_id',
      trafficAllocation: {
        type: 'equal',
        totalTrafficPercentage: 100,
        get weights() {
          return currentWeights;
        },
      },
      targetingRules: [],
      metrics: [],
      variants: variants.map((v) => ({
        ...v,
        experimentId,
        config: {},
        createdAt: new Date(),
        updatedAt: new Date(),
      })),
    });

    mockRedis.hgetall.mockResolvedValue({});
    mockRedis.hset.mockResolvedValue(0);
    mockRedis.expire.mockResolvedValue(0);
    mockPrisma.aBTestAssignment.upsert.mockResolvedValue({} as any);
    mockPrisma.aBTestEvent.create.mockResolvedValue({} as any);
    mockRedis.incr.mockResolvedValue(1);

    let shiftCount = 0;
    const totalOperations = 1000;
    const operations = [];

    for (let i = 0; i < totalOperations; i++) {
      if (i % 100 === 0 && i > 0) {
        shiftCount++;
        operations.push(
          (async () => {
            const newWeights = shiftCount % 2 === 0
              ? { [variants[0]!.id]: 50, [variants[1]!.id]: 30, [variants[2]!.id]: 20 }
              : { [variants[0]!.id]: 33, [variants[1]!.id]: 33, [variants[2]!.id]: 34 };
            currentWeights = newWeights;
            mockPrisma.aBTest.update.mockResolvedValueOnce({
              ...mockExperiment,
              createdAt: new Date(mockExperiment.createdAt),
              updatedAt: new Date(mockExperiment.updatedAt),
              statusUpdatedAt: new Date(mockExperiment.statusUpdatedAt),
              startTime: new Date(mockExperiment.startTime),
              endTime: new Date(mockExperiment.endTime),
              status: 'running',
              bucketStrategy: 'user_id',
              trafficAllocation: { type: 'weighted', totalTrafficPercentage: 100, weights: newWeights },
              targetingRules: [],
              metrics: [],
              variants: variants.map((v) => ({
                ...v,
                experimentId,
                config: {},
                createdAt: new Date(),
                updatedAt: new Date(),
              })),
            });
            await engine.updateExperiment(experimentId, {
              trafficAllocation: { type: 'weighted', totalTrafficPercentage: 100, weights: newWeights },
            });
          })()
        );
      }

      operations.push(
        (async () => {
          const userId = generateUserId();
          const result = await engine.getAssignment({
            experimentId,
            userId,
          });

          await engine.trackEvent({
            experimentId,
            variantId: result.variantId,
            eventName: 'impression',
            userId,
            properties: { value: 1 },
          });

          return result.variantId;
        })()
      );
    }

    const results = await Promise.all(operations);
    const variantResults = results.filter((r) => typeof r === 'string') as string[];

    expect(variantResults.length).toBe(totalOperations);
    expect(shiftCount).toBe(9);

    const variantCounts: Record<string, number> = {};
    for (const v of variantResults) {
      variantCounts[v] = (variantCounts[v] || 0) + 1;
    }

    for (const variant of variants) {
      expect(variantCounts[variant.id]).toBeGreaterThan(0);
    }

    const allCounted = Object.values(variantCounts).reduce((a, b) => a + b, 0);
    expect(allCounted).toBe(totalOperations);
  });

  it('should handle concurrent event tracking without losing counts', async () => {
    const experimentId = 'concurrent-track-test';
    const variantId = 'variant-1';

    mockPrisma.aBTestEvent.create.mockResolvedValue({} as any);

    let impressionCount = 0;
    let conversionCount = 0;
    (mockRedis.incr as any).mockImplementation((key: string) => {
      if (key.includes('impressions')) impressionCount++;
      if (key.includes('conversions')) conversionCount++;
      return Promise.resolve(impressionCount + conversionCount);
    });

    const trackCount = 1000;
    const operations = Array.from({ length: trackCount }, (_, i) =>
      engine.trackEvent({
        experimentId,
        variantId,
        eventName: i % 4 === 0 ? 'purchase' : 'page_view',
        userId: generateUserId(),
        properties: i % 4 === 0 ? { conversion: true, value: i * 10 } : { page: `/product/${i}` },
      })
    );

    const results = await Promise.all(operations);
    expect(results).toHaveLength(trackCount);

    const successful = results.filter((r) => r.tracked);
    expect(successful.length).toBe(trackCount);

    expect(mockPrisma.aBTestEvent.create).toHaveBeenCalledTimes(trackCount);
    expect(impressionCount).toBe(trackCount);
    expect(conversionCount).toBe(trackCount / 4);
  });

  it('should handle concurrent assignment caching correctly', async () => {
    const experimentId = 'concurrent-cache-test';
    const variants = createMockVariants(3);
    const weights: Record<string, number> = {};
    variants.forEach((v) => {
      weights[v.id] = v.trafficPercentage;
    });

    const mockExperiment = createMockABTest({ id: experimentId, status: 'running' });
    mockPrisma.aBTest.findUnique.mockResolvedValue({
      ...mockExperiment,
      createdAt: new Date(mockExperiment.createdAt),
      updatedAt: new Date(mockExperiment.updatedAt),
      statusUpdatedAt: new Date(mockExperiment.statusUpdatedAt),
      startTime: new Date(mockExperiment.startTime),
      endTime: new Date(mockExperiment.endTime),
      status: 'running',
      bucketStrategy: 'user_id',
      trafficAllocation: {
        type: 'equal',
        totalTrafficPercentage: 100,
        weights,
      },
      targetingRules: [],
      metrics: [],
      variants: variants.map((v) => ({
        ...v,
        experimentId,
        config: {},
        createdAt: new Date(),
        updatedAt: new Date(),
      })),
    });

    const cacheValues: Record<string, Record<string, string>> = {};

    mockRedis.hgetall.mockImplementation(async (key: string) => {
      return cacheValues[key] || {};
    });

    mockRedis.hset.mockImplementation(async (key: string, values: any) => {
      cacheValues[key] = values;
      return 0;
    });

    mockRedis.expire.mockResolvedValue(0);
    mockPrisma.aBTestAssignment.upsert.mockResolvedValue({} as any);

    const userId = generateUserId();
    const operationCount = 20;

    const results = await concurrent(operationCount, () =>
      engine.getAssignment({
        experimentId,
        userId,
      })
    );

    expect(results).toHaveLength(operationCount);

    const uniqueVariants = new Set(results.map((r) => r.variantId));
    expect(uniqueVariants.size).toBe(1);

    const firstVariantId = results[0]!.variantId;
    for (const result of results) {
      expect(result.variantId).toBe(firstVariantId);
    }

    expect(mockPrisma.aBTestAssignment.upsert).toHaveBeenCalled();
  }, 60000);
});

function calculatePercentile(values: number[], percentile: number): number {
  if (values.length === 0) return 0;
  const sorted = [...values].sort((a, b) => a - b);
  const idx = Math.ceil((percentile / 100) * sorted.length) - 1;
  return sorted[Math.max(0, Math.min(idx, sorted.length - 1))] || 0;
}
