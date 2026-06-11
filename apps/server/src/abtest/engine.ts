import { v4 as uuidv4 } from 'uuid';
import crypto from 'node:crypto';
import { prisma } from '../config/database';
import { redis, RedisKeys } from '../config/redis';
import { logger } from '../config/logger';
import type {
  ABTest,
  ABTestCreateRequest,
  ABTestUpdateRequest,
  ABTestListRequest,
  AssignmentRequest,
  AssignmentResponse,
  TrackEventRequest,
  PaginatedResponse,
  ABTestResults,
  ABVariantResult,
  ABMetricResult,
  RealTimeStats,
} from '@mlops/shared';
import {
  abTestCreateRequestSchema,
  abTestUpdateRequestSchema,
  abTestListRequestSchema,
  assignmentRequestSchema,
  trackEventRequestSchema,
} from '@mlops/shared';

export class ABTestEngine {
  async createExperiment(request: ABTestCreateRequest): Promise<ABTest> {
    const validated = abTestCreateRequestSchema.parse(request);

    const variantWeights: Record<string, number> = {};
    const totalWeight = validated.variants.reduce((sum, v) => sum + v.trafficWeight, 0);

    const variants = validated.variants.map((v) => {
      const id = uuidv4();
      const percentage = (v.trafficWeight / totalWeight) * 100;
      variantWeights[id] = percentage;
      return {
        id,
        name: v.name,
        description: v.description,
        isControl: v.isControl,
        trafficWeight: v.trafficWeight,
        trafficPercentage: percentage,
        config: v.config || {},
        modelId: v.modelId,
        modelVersion: v.modelVersion,
        status: v.status || 'active',
      };
    });

    const controlCount = variants.filter((v) => v.isControl).length;
    if (controlCount === 0) {
      variants[0]!.isControl = true;
    } else if (controlCount > 1) {
      throw new Error('Only one control variant is allowed');
    }

    const experiment = await prisma.aBTest.create({
      data: {
        id: uuidv4(),
        name: validated.name,
        description: validated.description,
        projectId: validated.projectId,
        ownerId: validated.ownerId,
        team: validated.team,
        hypothesis: validated.hypothesis,
        primaryMetric: validated.primaryMetric,
        status: 'draft',
        bucketStrategy: validated.bucketStrategy,
        bucketKey: validated.bucketKey,
        trafficAllocation: {
          ...validated.trafficAllocation,
          weights: variantWeights,
        },
        targetingRules: validated.targetingRules || [],
        metrics: validated.metrics || [],
        tags: validated.tags || [],
        metadata: validated.metadata || {},
        variants: {
          create: variants,
        },
      },
      include: {
        variants: true,
      },
    });

    logger.info({ experimentId: experiment.id, name: experiment.name }, 'A/B test created');
    return this.transformExperiment(experiment);
  }

  async getExperiment(id: string): Promise<ABTest | null> {
    const experiment = await prisma.aBTest.findUnique({
      where: { id },
      include: {
        variants: true,
      },
    });

    if (!experiment) return null;
    return this.transformExperiment(experiment);
  }

  async listExperiments(request: ABTestListRequest): Promise<PaginatedResponse<ABTest>> {
    const validated = abTestListRequestSchema.parse(request);
    const page = validated.page || 1;
    const pageSize = validated.pageSize || 20;

    const where: Record<string, unknown> = {};
    if (validated.name) where.name = { contains: validated.name };
    if (validated.projectId) where.projectId = validated.projectId;
    if (validated.ownerId) where.ownerId = validated.ownerId;
    if (validated.team) where.team = validated.team;
    if (validated.status) where.status = validated.status;

    const [total, experiments] = await Promise.all([
      prisma.aBTest.count({ where }),
      prisma.aBTest.findMany({
        where,
        skip: (page - 1) * pageSize,
        take: pageSize,
        orderBy: { createdAt: 'desc' },
        include: {
          variants: true,
        },
      }),
    ]);

    return {
      data: experiments.map((e) => this.transformExperiment(e)),
      total,
      page,
      pageSize,
      totalPages: Math.ceil(total / pageSize),
    };
  }

  async updateExperiment(id: string, request: ABTestUpdateRequest): Promise<ABTest | null> {
    const validated = abTestUpdateRequestSchema.parse(request);

    const existing = await prisma.aBTest.findUnique({ where: { id } });
    if (!existing) return null;

    const updateData: Record<string, unknown> = {};
    if (validated.name) updateData.name = validated.name;
    if (validated.description !== undefined) updateData.description = validated.description;
    if (validated.hypothesis) updateData.hypothesis = validated.hypothesis;
    if (validated.primaryMetric) updateData.primaryMetric = validated.primaryMetric;
    if (validated.status) {
      updateData.status = validated.status;
      updateData.statusUpdatedAt = new Date();
      if (validated.status === 'running' && !existing.startTime) {
        updateData.startTime = new Date();
      }
      if (validated.status === 'completed' && !existing.endTime) {
        updateData.endTime = new Date();
      }
    }
    if (validated.startTime !== undefined) updateData.startTime = new Date(validated.startTime);
    if (validated.endTime !== undefined) updateData.endTime = new Date(validated.endTime);
    if (validated.trafficAllocation) updateData.trafficAllocation = validated.trafficAllocation;
    if (validated.targetingRules) updateData.targetingRules = validated.targetingRules;
    if (validated.metrics) updateData.metrics = validated.metrics;
    if (validated.tags) updateData.tags = validated.tags;
    if (validated.metadata) updateData.metadata = validated.metadata;

    if (validated.variants) {
      await prisma.aBVariant.deleteMany({ where: { experimentId: id } });
      (updateData as any).variants = {
        create: validated.variants.map((v) => ({
          id: uuidv4(),
          ...v,
        })),
      };
    }

    const experiment = await prisma.aBTest.update({
      where: { id },
      data: updateData,
      include: { variants: true },
    });

    logger.info({ experimentId: id, status: experiment.status }, 'A/B test updated');
    return this.transformExperiment(experiment);
  }

  async getAssignment(request: AssignmentRequest): Promise<AssignmentResponse> {
    const validated = assignmentRequestSchema.parse(request);

    const experiment = await prisma.aBTest.findUnique({
      where: { id: validated.experimentId },
      include: { variants: true },
    });

    if (!experiment) {
      throw new Error(`Experiment not found: ${validated.experimentId}`);
    }

    if (experiment.status !== 'running') {
      const controlVariant = experiment.variants.find((v) => v.isControl);
      if (!controlVariant) {
        throw new Error('No control variant found');
      }
      return {
        experimentId: experiment.id,
        variantId: controlVariant.id,
        variantName: controlVariant.name,
        isControl: true,
        config: controlVariant.config as Record<string, unknown>,
        modelId: controlVariant.modelId ?? undefined,
        modelVersion: controlVariant.modelVersion ?? undefined,
        assignedAt: Date.now(),
        cacheHit: false,
      };
    }

    const bucketKey = this.getBucketKey(experiment.bucketStrategy, validated);
    if (!bucketKey) {
      throw new Error('No bucket key provided for assignment');
    }

    if (validated.previewVariantId) {
      const variant = experiment.variants.find((v) => v.id === validated.previewVariantId);
      if (variant) {
        return {
          experimentId: experiment.id,
          variantId: variant.id,
          variantName: variant.name,
          isControl: variant.isControl,
          config: variant.config as Record<string, unknown>,
          modelId: variant.modelId ?? undefined,
          modelVersion: variant.modelVersion ?? undefined,
          assignedAt: Date.now(),
          cacheHit: false,
        };
      }
    }

    const cacheKey = RedisKeys.abAssignment(experiment.id, bucketKey);
    const cached = await redis.hgetall(cacheKey);

    if (cached.variantId) {
      const variant = experiment.variants.find((v) => v.id === cached.variantId);
      if (variant) {
        return {
          experimentId: experiment.id,
          variantId: variant.id,
          variantName: variant.name,
          isControl: variant.isControl,
          config: variant.config as Record<string, unknown>,
          modelId: variant.modelId ?? undefined,
          modelVersion: variant.modelVersion ?? undefined,
          assignedAt: parseInt(cached.assignedAt || '0'),
          cacheHit: true,
        };
      }
    }

    if (validated.context && !this.checkTargeting(experiment.targetingRules as any[], validated.context)) {
      const controlVariant = experiment.variants.find((v) => v.isControl)!;
      return {
        experimentId: experiment.id,
        variantId: controlVariant.id,
        variantName: controlVariant.name,
        isControl: true,
        config: controlVariant.config as Record<string, unknown>,
        modelId: controlVariant.modelId ?? undefined,
        modelVersion: controlVariant.modelVersion ?? undefined,
        assignedAt: Date.now(),
        cacheHit: false,
      };
    }

    const trafficAllocation = experiment.trafficAllocation as any;
    if (Math.random() * 100 > (trafficAllocation.totalTrafficPercentage || 100)) {
      const controlVariant = experiment.variants.find((v) => v.isControl)!;
      return {
        experimentId: experiment.id,
        variantId: controlVariant.id,
        variantName: controlVariant.name,
        isControl: true,
        config: controlVariant.config as Record<string, unknown>,
        modelId: controlVariant.modelId ?? undefined,
        modelVersion: controlVariant.modelVersion ?? undefined,
        assignedAt: Date.now(),
        cacheHit: false,
      };
    }

    const variantId = this.selectVariant(bucketKey, experiment.variants, trafficAllocation.weights);
    const variant = experiment.variants.find((v) => v.id === variantId)!;
    const assignedAt = Date.now();

    await redis.hset(cacheKey, {
      variantId,
      bucketKey,
      assignedAt: String(assignedAt),
    });
    await redis.expire(cacheKey, 86400 * 30);

    await prisma.aBTestAssignment.upsert({
      where: {
        experimentId_bucketKey: {
          experimentId: experiment.id,
          bucketKey,
        },
      },
      update: {
        variantId,
        assignedAt: new Date(assignedAt),
      },
      create: {
        id: uuidv4(),
        experimentId: experiment.id,
        variantId,
        bucketKey,
        userId: validated.userId,
        sessionId: validated.sessionId,
        assignedAt: new Date(assignedAt),
        context: validated.context,
      },
    });

    logger.debug(
      { experimentId: experiment.id, variantId, variantName: variant.name, bucketKey },
      'Assigned to variant'
    );

    return {
      experimentId: experiment.id,
      variantId: variant.id,
      variantName: variant.name,
      isControl: variant.isControl,
      config: variant.config as Record<string, unknown>,
      modelId: variant.modelId ?? undefined,
      modelVersion: variant.modelVersion ?? undefined,
      assignedAt,
      cacheHit: false,
    };
  }

  async trackEvent(request: TrackEventRequest): Promise<{ tracked: boolean; timestamp: number }> {
    const validated = trackEventRequestSchema.parse(request);
    const timestamp = validated.timestamp || Date.now();

    await prisma.aBTestEvent.create({
      data: {
        id: uuidv4(),
        experimentId: validated.experimentId,
        variantId: validated.variantId,
        eventName: validated.eventName,
        userId: validated.userId,
        sessionId: validated.sessionId,
        properties: validated.properties || {},
        timestamp: new Date(timestamp),
      },
    });

    const impressionKey = RedisKeys.abStats(
      validated.experimentId,
      validated.variantId,
      'impressions'
    );
    await redis.incr(impressionKey);

    if (validated.properties && validated.properties['conversion'] === true) {
      const conversionKey = RedisKeys.abStats(
        validated.experimentId,
        validated.variantId,
        'conversions'
      );
      await redis.incr(conversionKey);
    }

    logger.debug(
      { experimentId: validated.experimentId, variantId: validated.variantId, eventName: validated.eventName },
      'A/B test event tracked'
    );

    return { tracked: true, timestamp };
  }

  async calculateResults(experimentId: string): Promise<ABTestResults> {
    const experiment = await prisma.aBTest.findUnique({
      where: { id: experimentId },
      include: { variants: true },
    });

    if (!experiment) {
      throw new Error(`Experiment not found: ${experimentId}`);
    }

    const metrics = experiment.metrics as any[];
    const variantResults: Record<string, ABVariantResult> = {};

    for (const variant of experiment.variants) {
      const events = await prisma.aBTestEvent.findMany({
        where: {
          experimentId,
          variantId: variant.id,
        },
      });

      const metricValues: Record<string, ABMetricResult> = {};

      for (const metric of metrics) {
        const metricEvents = events.filter((e) => e.eventName === metric.name);
        const values = metricEvents.map((e) => {
          const props = e.properties as any;
          return typeof props?.value === 'number' ? props.value : 1;
        });

        if (values.length === 0) continue;

        const result = this.calculateStatisticalSignificance(values, metric);
        metricValues[metric.name] = result;
      }

      variantResults[variant.id] = {
        variantId: variant.id,
        variantName: variant.name,
        sampleSize: events.length,
        metricValues,
      };
    }

    const results: ABTestResults = {
      status: 'ready',
      lastCalculatedAt: Date.now(),
      variantResults,
    };

    await prisma.aBTest.update({
      where: { id: experimentId },
      data: { results },
    });

    return results;
  }

  async getRealTimeStats(experimentId: string): Promise<RealTimeStats> {
    const experiment = await prisma.aBTest.findUnique({
      where: { id: experimentId },
      include: { variants: true },
    });

    if (!experiment) {
      throw new Error(`Experiment not found: ${experimentId}`);
    }

    const variantStats: RealTimeStats['variantStats'] = {};

    for (const variant of experiment.variants) {
      const [impressions, conversions] = await Promise.all([
        redis.get(RedisKeys.abStats(experimentId, variant.id, 'impressions')),
        redis.get(RedisKeys.abStats(experimentId, variant.id, 'conversions')),
      ]);

      const imp = parseInt(impressions || '0');
      const conv = parseInt(conversions || '0');

      variantStats[variant.id] = {
        variantId: variant.id,
        variantName: variant.name,
        impressions: imp,
        conversions: { [experiment.primaryMetric]: conv },
        conversionRates: { [experiment.primaryMetric]: imp > 0 ? conv / imp : 0 },
        lastUpdated: Date.now(),
      };
    }

    return {
      experimentId,
      variantStats,
    };
  }

  private getBucketKey(strategy: string, request: AssignmentRequest): string | null {
    switch (strategy) {
      case 'user_id':
        return request.userId || null;
      case 'session_id':
        return request.sessionId || null;
      case 'device_id':
        return (request.context as any)?.deviceId || null;
      case 'custom':
        return request.customKey || null;
      case 'random':
        return uuidv4();
      default:
        return request.userId || request.sessionId || uuidv4();
    }
  }

  private selectVariant(
    bucketKey: string,
    variants: any[],
    weights: Record<string, number>
  ): string {
    const hash = crypto
      .createHash('sha256')
      .update(bucketKey)
      .digest('hex');
    const hashNum = parseInt(hash.slice(0, 8), 16);
    const rand = (hashNum % 10000) / 100;

    let cumulative = 0;
    for (const variant of variants) {
      cumulative += weights[variant.id] || 0;
      if (rand <= cumulative) {
        return variant.id;
      }
    }

    return variants[0]!.id;
  }

  private checkTargeting(rules: any[], context: Record<string, unknown>): boolean {
    for (const rule of rules) {
      const value = context[rule.attribute];
      if (!this.evaluateCondition(rule.operator, value, rule.value, rule.type === 'exclude')) {
        return false;
      }
    }
    return true;
  }

  private evaluateCondition(
    operator: string,
    actual: unknown,
    expected: unknown,
    negate: boolean
  ): boolean {
    let result = false;
    switch (operator) {
      case 'eq':
        result = actual === expected;
        break;
      case 'ne':
        result = actual !== expected;
        break;
      case 'gt':
        result = (actual as number) > (expected as number);
        break;
      case 'gte':
        result = (actual as number) >= (expected as number);
        break;
      case 'lt':
        result = (actual as number) < (expected as number);
        break;
      case 'lte':
        result = (actual as number) <= (expected as number);
        break;
      case 'in':
        result = (expected as unknown[]).includes(actual as never);
        break;
      case 'not_in':
        result = !(expected as unknown[]).includes(actual as never);
        break;
      case 'contains':
        result = String(actual).includes(String(expected));
        break;
      case 'regex':
        result = new RegExp(String(expected)).test(String(actual));
        break;
    }
    return negate ? !result : result;
  }

  private calculateStatisticalSignificance(values: number[], metric: any): ABMetricResult {
    const n = values.length;
    const mean = values.reduce((a, b) => a + b, 0) / n;
    const variance = values.reduce((acc, v) => acc + Math.pow(v - mean, 2), 0) / (n - 1);
    const std = Math.sqrt(variance);
    const stdError = std / Math.sqrt(n);
    const zScore = 1.96;
    const ci: [number, number] = [mean - zScore * stdError, mean + zScore * stdError];

    const pValue = this.calculatePValue(values, metric);
    const effectSize = mean;

    return {
      metricName: metric.name,
      mean,
      std,
      confidenceInterval: ci,
      pValue,
      isSignificant: pValue < (metric.significanceLevel || 0.05),
      effectSize,
      relativeChange: 0,
      relativeChangeCI: [0, 0],
    };
  }

  private calculatePValue(values: number[], metric: any): number {
    const nullMean = 0;
    const n = values.length;
    const mean = values.reduce((a, b) => a + b, 0) / n;
    const std = Math.sqrt(
      values.reduce((acc, v) => acc + Math.pow(v - mean, 2), 0) / (n - 1)
    );
    const tStat = (mean - nullMean) / (std / Math.sqrt(n));
    return Math.min(1, Math.max(0.001, 1 - this.normalCDF(Math.abs(tStat))));
  }

  private normalCDF(x: number): number {
    const t = 1 / (1 + 0.2316419 * Math.abs(x));
    const d = 0.3989423 * Math.exp((-x * x) / 2);
    const prob = d * t * (0.3193815 + t * (-0.3565638 + t * (1.781478 + t * (-1.821256 + t * 1.330274))));
    return x > 0 ? 1 - prob : prob;
  }

  private transformExperiment(prismaExp: any): ABTest {
    return {
      id: prismaExp.id,
      name: prismaExp.name,
      description: prismaExp.description ?? undefined,
      projectId: prismaExp.projectId,
      ownerId: prismaExp.ownerId,
      team: prismaExp.team,
      hypothesis: prismaExp.hypothesis,
      primaryMetric: prismaExp.primaryMetric,
      status: prismaExp.status as ABTest['status'],
      startTime: prismaExp.startTime?.getTime(),
      endTime: prismaExp.endTime?.getTime(),
      statusUpdatedAt: prismaExp.statusUpdatedAt.getTime(),
      tags: prismaExp.tags || [],
      bucketStrategy: prismaExp.bucketStrategy as ABTest['bucketStrategy'],
      bucketKey: prismaExp.bucketKey ?? undefined,
      variants: prismaExp.variants.map((v: any) => ({
        id: v.id,
        name: v.name,
        description: v.description ?? undefined,
        isControl: v.isControl,
        trafficWeight: v.trafficWeight,
        trafficPercentage: v.trafficPercentage,
        config: v.config as Record<string, unknown>,
        modelId: v.modelId ?? undefined,
        modelVersion: v.modelVersion ?? undefined,
        status: v.status as any,
      })),
      trafficAllocation: prismaExp.trafficAllocation as ABTest['trafficAllocation'],
      targetingRules: prismaExp.targetingRules as ABTest['targetingRules'],
      metrics: prismaExp.metrics as ABTest['metrics'],
      results: (prismaExp.results as ABTest['results']) ?? undefined,
      createdAt: prismaExp.createdAt.getTime(),
      updatedAt: prismaExp.updatedAt.getTime(),
      metadata: (prismaExp.metadata as Record<string, unknown>) || {},
    };
  }
}

export const abTestEngine = new ABTestEngine();
