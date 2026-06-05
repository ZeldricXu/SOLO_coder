import { v4 as uuidv4 } from 'uuid';
import type { FastifyRequest, FastifyReply } from 'fastify';
import { prisma } from '../config/database';
import { redis, RedisKeys } from '../config/redis';
import { logger } from '../config/logger';
import { featureStorage } from '../storage';
import type {
  FeatureSet,
  FeatureSetVersion,
  FeatureSetCreateRequest,
  FeatureVersionCreateRequest,
  FeatureListRequest,
  FeatureGetRequest,
  FeatureGetResponse,
  FeatureIngestRequest,
  PaginatedResponse,
  FeatureDistributionResponse,
} from '@mlops/shared';
import {
  featureSetCreateRequestSchema,
  featureVersionCreateRequestSchema,
  featureListRequestSchema,
  featureGetRequestSchema,
  featureIngestRequestSchema,
} from '@mlops/shared';

export class FeatureStoreService {
  async createFeatureSet(request: FeatureSetCreateRequest): Promise<FeatureSet> {
    const validated = featureSetCreateRequestSchema.parse(request);

    const featureSet = await prisma.featureSet.create({
      data: {
        id: uuidv4(),
        name: validated.name,
        description: validated.description,
        projectId: validated.projectId,
        ownerId: validated.ownerId,
        team: validated.team,
        mode: validated.mode,
        entities: validated.entities,
        features: validated.features,
        ttlSeconds: validated.ttlSeconds,
        onlineStorage: validated.onlineStorage,
        offlineStorage: validated.offlineStorage,
        tags: validated.tags || [],
      },
      include: {
        versions: {
          orderBy: { createdAt: 'desc' },
          take: 1,
        },
      },
    });

    logger.info({ featureSetId: featureSet.id, name: featureSet.name }, 'Feature set created');
    return this.transformFeatureSet(featureSet);
  }

  async getFeatureSet(id: string): Promise<FeatureSet | null> {
    const featureSet = await prisma.featureSet.findUnique({
      where: { id },
      include: {
        versions: {
          orderBy: { createdAt: 'desc' },
          take: 10,
        },
      },
    });

    if (!featureSet) return null;
    return this.transformFeatureSet(featureSet);
  }

  async listFeatureSets(request: FeatureListRequest): Promise<PaginatedResponse<FeatureSet>> {
    const validated = featureListRequestSchema.parse(request);
    const page = validated.page || 1;
    const pageSize = validated.pageSize || 20;

    const where: Record<string, unknown> = {};
    if (validated.name) where.name = { contains: validated.name };
    if (validated.projectId) where.projectId = validated.projectId;
    if (validated.ownerId) where.ownerId = validated.ownerId;
    if (validated.team) where.team = validated.team;
    if (validated.mode) where.mode = validated.mode;
    if (validated.status) where.status = validated.status;
    if (validated.tags && validated.tags.length > 0) {
      where.tags = { hasEvery: validated.tags };
    }

    const [total, featureSets] = await Promise.all([
      prisma.featureSet.count({ where }),
      prisma.featureSet.findMany({
        where,
        skip: (page - 1) * pageSize,
        take: pageSize,
        orderBy: { createdAt: 'desc' },
        include: {
          versions: {
            orderBy: { createdAt: 'desc' },
            take: 1,
          },
        },
      }),
    ]);

    return {
      data: featureSets.map((fs) => this.transformFeatureSet(fs)),
      total,
      page,
      pageSize,
      totalPages: Math.ceil(total / pageSize),
    };
  }

  async createVersion(request: FeatureVersionCreateRequest): Promise<FeatureSetVersion> {
    const validated = featureVersionCreateRequestSchema.parse(request);

    const featureSet = await prisma.featureSet.findUnique({
      where: { id: validated.featureSetId },
    });

    if (!featureSet) {
      throw new Error(`Feature set not found: ${validated.featureSetId}`);
    }

    const version = await prisma.featureSetVersion.create({
      data: {
        id: uuidv4(),
        featureSetId: validated.featureSetId,
        version: validated.version,
        featureSchema: validated.featureSchema,
        sourceUri: validated.sourceUri,
        rowCount: validated.rowCount ? BigInt(validated.rowCount) : undefined,
        sizeBytes: validated.sizeBytes ? BigInt(validated.sizeBytes) : undefined,
      },
    });

    await prisma.featureSet.update({
      where: { id: validated.featureSetId },
      data: {
        latestVersionId: version.id,
        updatedAt: new Date(),
      },
    });

    logger.info(
      { featureSetId: validated.featureSetId, versionId: version.id, version: validated.version },
      'Feature set version created'
    );

    return this.transformVersion(version);
  }

  async getOnlineFeatures(request: FeatureGetRequest): Promise<FeatureGetResponse> {
    const validated = featureGetRequestSchema.parse(request);

    let versionId = validated.version;
    if (!versionId) {
      const latest = await prisma.featureSetVersion.findFirst({
        where: { featureSetId: validated.featureSetId, status: 'active' },
        orderBy: { createdAt: 'desc' },
      });
      if (!latest) {
        throw new Error(`No active version found for feature set: ${validated.featureSetId}`);
      }
      versionId = latest.id;
    }

    const values: Record<string, Record<string, unknown>> = {};
    const featureSet = await prisma.featureSet.findUnique({
      where: { id: validated.featureSetId },
    });

    if (!featureSet) {
      throw new Error(`Feature set not found: ${validated.featureSetId}`);
    }

    const featureNames = validated.featureNames || featureSet.features.map((f: any) => f.name);

    for (const entityKey of validated.entityKeys) {
      const key = RedisKeys.featureValue(validated.featureSetId, versionId, entityKey);
      const cached = await redis.hgetall(key);

      if (Object.keys(cached).length > 0) {
        values[entityKey] = {};
        for (const fname of featureNames) {
          if (cached[fname] !== undefined) {
            try {
              values[entityKey]![fname] = JSON.parse(cached[fname]!);
            } catch {
              values[entityKey]![fname] = cached[fname];
            }
          }
        }
      } else {
        values[entityKey] = {};
        for (const fname of featureNames) {
          const feature = (featureSet.features as any[]).find((f) => f.name === fname);
          values[entityKey]![fname] = feature?.defaultValue ?? null;
        }
      }
    }

    logger.debug(
      { featureSetId: validated.featureSetId, entityCount: validated.entityKeys.length },
      'Online features retrieved'
    );

    return {
      featureSetId: validated.featureSetId,
      version: versionId,
      values,
      timestamp: Date.now(),
    };
  }

  async ingestFeatures(request: FeatureIngestRequest): Promise<{ ingestedCount: number; timestamp: number }> {
    const validated = featureIngestRequestSchema.parse(request);

    let versionId = validated.version;
    if (!versionId) {
      const latest = await prisma.featureSetVersion.findFirst({
        where: { featureSetId: validated.featureSetId, status: 'active' },
        orderBy: { createdAt: 'desc' },
      });
      if (!latest) {
        throw new Error(`No active version found for feature set: ${validated.featureSetId}`);
      }
      versionId = latest.id;
    }

    const featureSet = await prisma.featureSet.findUnique({
      where: { id: validated.featureSetId },
    });

    if (!featureSet) {
      throw new Error(`Feature set not found: ${validated.featureSetId}`);
    }

    const ttl = featureSet.ttlSeconds || 86400 * 7;
    const timestamp = Date.now();
    const valuesByEntity: Record<string, Record<string, string>> = {};

    for (const row of validated.data) {
      const entityKey = String(row[validated.entityKeyField]);
      if (!entityKey) continue;

      if (!valuesByEntity[entityKey]) {
        valuesByEntity[entityKey] = {};
      }

      for (const feature of featureSet.features as any[]) {
        const value = row[feature.name];
        if (value !== undefined && value !== null) {
          valuesByEntity[entityKey]![feature.name] = JSON.stringify(value);
        }
      }
    }

    const pipeline = redis.pipeline();

    for (const [entityKey, values] of Object.entries(valuesByEntity)) {
      const key = RedisKeys.featureValue(validated.featureSetId, versionId, entityKey);

      if (validated.mode === 'overwrite') {
        pipeline.del(key);
      }

      pipeline.hset(key, values);
      pipeline.expire(key, ttl);
    }

    await pipeline.exec();

    const offlinePath = `${validated.featureSetId}/${versionId}/${timestamp}.parquet`;
    if (featureSet.mode === 'offline' || featureSet.mode === 'both') {
      await featureStorage
        .putObject(offlinePath, JSON.stringify(validated.data), 'application/json')
        .catch((err) => {
          logger.warn({ error: err, path: offlinePath }, 'Failed to write offline feature data');
        });
    }

    this.updateStatistics(validated.featureSetId, validated.data).catch(() => {});

    logger.info(
      { featureSetId: validated.featureSetId, count: validated.data.length },
      'Features ingested'
    );

    return {
      ingestedCount: validated.data.length,
      timestamp,
    };
  }

  private async updateStatistics(featureSetId: string, data: Record<string, unknown>[]): Promise<void> {
    const featureSet = await prisma.featureSet.findUnique({ where: { id: featureSetId } });
    if (!featureSet) return;

    for (const feature of featureSet.features as any[]) {
      const values = data
        .map((d) => d[feature.name])
        .filter((v) => v !== null && v !== undefined && typeof v === 'number') as number[];

      if (values.length === 0) continue;

      const sorted = [...values].sort((a, b) => a - b);
      const stats = {
        min: Math.min(...values),
        max: Math.max(...values),
        mean: values.reduce((a, b) => a + b, 0) / values.length,
        std: Math.sqrt(
          values.reduce((acc, v) => acc + Math.pow(v - (values.reduce((a, b) => a + b, 0) / values.length), 2), 0) /
            values.length
        ),
        median: sorted[Math.floor(sorted.length / 2)],
      };

      const nullCount = data.filter((d) => d[feature.name] === null || d[feature.name] === undefined).length;
      const uniqueCount = new Set(values).size;

      const numBins = 10;
      const binWidth = (stats.max - stats.min) / numBins;
      const bins = Array.from({ length: numBins + 1 }, (_, i) => stats.min + i * binWidth);
      const counts = new Array(numBins).fill(0);

      for (const v of values) {
        const binIdx = Math.min(Math.floor((v - stats.min) / binWidth), numBins - 1);
        if (binIdx >= 0) counts[binIdx]++;
      }

      await prisma.featureStatistics.upsert({
        where: {
          featureSetId_featureName: {
            featureSetId,
            featureName: feature.name,
          },
        },
        update: {
          min: stats.min,
          max: stats.max,
          mean: stats.mean,
          std: stats.std,
          median: stats.median,
          nullCount: BigInt(nullCount),
          uniqueCount: BigInt(uniqueCount),
          histogramBins: bins,
          histogramCounts: counts.map((c) => BigInt(c)),
          lastUpdated: new Date(),
        },
        create: {
          id: uuidv4(),
          featureSetId,
          featureName: feature.name,
          min: stats.min,
          max: stats.max,
          mean: stats.mean,
          std: stats.std,
          median: stats.median,
          nullCount: BigInt(nullCount),
          uniqueCount: BigInt(uniqueCount),
          histogramBins: bins,
          histogramCounts: counts.map((c) => BigInt(c)),
        },
      });
    }
  }

  async getFeatureDistribution(
    featureSetId: string,
    featureName: string
  ): Promise<FeatureDistributionResponse> {
    const stats = await prisma.featureStatistics.findUnique({
      where: {
        featureSetId_featureName: {
          featureSetId,
          featureName,
        },
      },
    });

    if (!stats) {
      throw new Error(`No statistics found for feature: ${featureName}`);
    }

    return {
      featureName,
      statistics: {
        min: stats.min ?? undefined,
        max: stats.max ?? undefined,
        mean: stats.mean ?? undefined,
        std: stats.std ?? undefined,
        median: stats.median ?? undefined,
        nullCount: stats.nullCount ? Number(stats.nullCount) : undefined,
        uniqueCount: stats.uniqueCount ? Number(stats.uniqueCount) : undefined,
        lastUpdated: stats.lastUpdated.getTime(),
      },
      distribution:
        stats.histogramBins && stats.histogramCounts
          ? {
              bins: stats.histogramBins,
              counts: stats.histogramCounts.map((c) => Number(c)),
            }
          : undefined,
    };
  }

  private transformFeatureSet(prismaFs: any): FeatureSet {
    const versions = (prismaFs.versions || []).map((v: any) => this.transformVersion(v));
    return {
      id: prismaFs.id,
      name: prismaFs.name,
      description: prismaFs.description ?? undefined,
      projectId: prismaFs.projectId,
      ownerId: prismaFs.ownerId,
      team: prismaFs.team,
      mode: prismaFs.mode as FeatureSet['mode'],
      entities: prismaFs.entities as FeatureSet['entities'],
      features: prismaFs.features as FeatureSet['features'],
      tags: prismaFs.tags || [],
      status: prismaFs.status as FeatureSet['status'],
      ttlSeconds: prismaFs.ttlSeconds ?? undefined,
      onlineStorage: (prismaFs.onlineStorage as FeatureSet['onlineStorage']) ?? undefined,
      offlineStorage: (prismaFs.offlineStorage as FeatureSet['offlineStorage']) ?? undefined,
      createdAt: prismaFs.createdAt.getTime(),
      updatedAt: prismaFs.updatedAt.getTime(),
      latestVersion: versions[0],
      versions,
    };
  }

  private transformVersion(prismaV: any): FeatureSetVersion {
    return {
      id: prismaV.id,
      featureSetId: prismaV.featureSetId,
      version: prismaV.version,
      featureSchema: prismaV.featureSchema as FeatureSetVersion['featureSchema'],
      sourceUri: prismaV.sourceUri ?? undefined,
      rowCount: prismaV.rowCount ? Number(prismaV.rowCount) : undefined,
      sizeBytes: prismaV.sizeBytes ? Number(prismaV.sizeBytes) : undefined,
      createdAt: prismaV.createdAt.getTime(),
      status: prismaV.status as FeatureSetVersion['status'],
    };
  }
}

export const featureStoreService = new FeatureStoreService();

export async function registerFeatureStoreRoutes(fastify: any): Promise<void> {
  const service = featureStoreService;

  fastify.post('/api/v1/feature-sets', async (request: FastifyRequest, reply: FastifyReply) => {
    const result = await service.createFeatureSet(request.body as FeatureSetCreateRequest);
    return reply.status(201).send(result);
  });

  fastify.get('/api/v1/feature-sets/:id', async (request: FastifyRequest<{ Params: { id: string } }>, reply: FastifyReply) => {
    const result = await service.getFeatureSet(request.params.id);
    if (!result) return reply.status(404).send({ error: 'Feature set not found' });
    return result;
  });

  fastify.get('/api/v1/feature-sets', async (request: FastifyRequest) => {
    return service.listFeatureSets(request.query as FeatureListRequest);
  });

  fastify.post('/api/v1/feature-sets/:id/versions', async (request: FastifyRequest<{ Params: { id: string } }>, reply: FastifyReply) => {
    const result = await service.createVersion({
      ...(request.body as any),
      featureSetId: request.params.id,
    });
    return reply.status(201).send(result);
  });

  fastify.post('/api/v1/features/get', async (request: FastifyRequest) => {
    return service.getOnlineFeatures(request.body as FeatureGetRequest);
  });

  fastify.post('/api/v1/features/ingest', async (request: FastifyRequest) => {
    return service.ingestFeatures(request.body as FeatureIngestRequest);
  });

  fastify.get('/api/v1/feature-sets/:id/features/:name/distribution', async (
    request: FastifyRequest<{ Params: { id: string; name: string } }>,
    reply: FastifyReply
  ) => {
    try {
      return service.getFeatureDistribution(request.params.id, request.params.name);
    } catch (error) {
      return reply.status(404).send({ error: error instanceof Error ? error.message : 'Not found' });
    }
  });
}
