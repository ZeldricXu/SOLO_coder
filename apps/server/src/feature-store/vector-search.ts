import type { PrismaClient } from '@prisma/client';
import type { FastifyRequest, FastifyReply } from 'fastify';
import pino from 'pino';
import { v4 as uuidv4 } from 'uuid';
import { prisma } from '../config/database';
import {
  type VectorIndex,
  type VectorSearchRequest,
  type VectorSearchResponse,
  type VectorSearchResult,
  type VectorIndexBuildRequest,
  type VectorIndexUpdateRequest,
  type VectorIngestRequest,
  type VectorIngestResponse,
  type RangeQueryRequest,
  type RangeQueryResponse,
  type RangeQueryResult,
  type VectorSearchFilter,
  type RangeFilter,
  type HNSWConfig,
  type VectorDistanceMetric,
  type VectorStats,
} from '@mlops/shared';

const logger = pino({ name: 'vector-search' });

interface HnswIndex {
  id: string;
  featureSetId: string;
  featureName: string;
  dimension: number;
  metric: VectorDistanceMetric;
  index: any;
  entityKeys: string[];
  featuresMap: Map<number, Record<string, unknown>>;
  size: number;
  efSearch: number;
}

export class VectorSearchService {
  private indexes: Map<string, HnswIndex> = new Map();
  private loadPromises: Map<string, Promise<HnswIndex>> = new Map();

  constructor(private prisma: PrismaClient) {}

  async createIndex(request: VectorIndexBuildRequest): Promise<VectorIndex> {
    const existing = await this.prisma.vectorIndex.findUnique({
      where: {
        featureSetId_featureName: {
          featureSetId: request.featureSetId,
          featureName: request.featureName,
        },
      },
    });

    if (existing) {
      throw new Error(`Vector index already exists for ${request.featureSetId}/${request.featureName}`);
    }

    const featureSet = await this.prisma.featureSet.findUnique({
      where: { id: request.featureSetId },
    });

    if (!featureSet) {
      throw new Error(`Feature set not found: ${request.featureSetId}`);
    }

    const defaultHnswConfig: HNSWConfig = {
      m: 16,
      efConstruction: 200,
      efSearch: 50,
      maxElements: 100000,
    };

    const hnswConfig = request.hnswConfig || defaultHnswConfig;
    const metric = request.distanceMetric || 'cosine';

    const dbIndex = await this.prisma.vectorIndex.create({
      data: {
        id: uuidv4(),
        featureSetId: request.featureSetId,
        featureName: request.featureName,
        config: {
          indexType: request.indexType || 'hnsw',
          distanceMetric: metric,
          dimension: request.dimension,
          hnswConfig,
        },
        dimension: request.dimension,
        status: 'building',
      },
    });

    setImmediate(() => {
      this.buildIndexInBackground(dbIndex.id, request).catch((err) => {
        logger.error({ err, indexId: dbIndex.id }, 'Failed to build vector index');
        this.prisma.vectorIndex
          .update({
            where: { id: dbIndex.id },
            data: { status: 'failed' },
          })
          .catch(() => {});
      });
    });

    return this.transformIndex(dbIndex);
  }

  private async buildIndexInBackground(indexId: string, request: VectorIndexBuildRequest): Promise<void> {
    let hnswlib: any;
    try {
      hnswlib = await import('hnswlib-node');
    } catch {
      logger.warn('hnswlib-node not available, using brute force fallback');
    }

    const dbIndex = await this.prisma.vectorIndex.findUnique({
      where: { id: indexId },
    });

    if (!dbIndex) return;

    const config = dbIndex.config as any;
    const hnswConfig = config.hnswConfig as HNSWConfig;
    const dimension = dbIndex.dimension;

    let index: any = null;
    if (hnswlib) {
      const spaceType = this.getSpaceType(config.distanceMetric);
      index = new hnswlib.HierarchicalNSW(spaceType, dimension);
      index.initIndex(
        hnswConfig.maxElements,
        hnswConfig.m,
        hnswConfig.efConstruction,
        Math.random() * 1000
      );
      index.setEfSearch(hnswConfig.efSearch);
    }

    const entityKeys: string[] = [];
    const featuresMap = new Map<number, Record<string, unknown>>();

    const records = await this.prisma.vectorRecord.findMany({
      where: { indexId: dbIndex.id },
    });

    for (let i = 0; i < records.length; i++) {
      const record = records[i]!;
      entityKeys.push(record.entityKey);
      featuresMap.set(i, (record.features as Record<string, unknown>) || {});

      if (index && record.vector) {
        const vec = this.extractVector(record.vector);
        if (vec.length === dimension) {
          index.addPoint(vec, i);
        }
      }
    }

    const memoryUsage = this.estimateMemoryUsage(dimension, entityKeys.length, hnswConfig?.m || 16);

    const hnswIndex: HnswIndex = {
      id: dbIndex.id,
      featureSetId: dbIndex.featureSetId,
      featureName: dbIndex.featureName,
      dimension,
      metric: config.distanceMetric,
      index,
      entityKeys,
      featuresMap,
      size: entityKeys.length,
      efSearch: hnswConfig.efSearch,
    };

    this.indexes.set(dbIndex.id, hnswIndex);

    await this.prisma.vectorIndex.update({
      where: { id: dbIndex.id },
      data: {
        status: 'ready',
        size: entityKeys.length,
        memoryUsageBytes: BigInt(memoryUsage),
        lastBuildTime: new Date(),
      },
    });

    logger.info({ indexId: dbIndex.id, size: entityKeys.length }, 'Vector index built successfully');
  }

  private getSpaceType(metric: VectorDistanceMetric): string {
    switch (metric) {
      case 'cosine':
        return 'cosine';
      case 'l2':
        return 'l2';
      case 'inner_product':
        return 'ip';
      default:
        return 'cosine';
    }
  }

  private extractVector(vectorData: unknown): number[] {
    if (Array.isArray(vectorData)) {
      return vectorData as number[];
    }
    if (typeof vectorData === 'string') {
      try {
        return JSON.parse(vectorData);
      } catch {
        return [];
      }
    }
    return [];
  }

  private estimateMemoryUsage(dimension: number, numElements: number, m: number): number {
    const dataSize = dimension * numElements * 4;
    const linkSize = m * 2 * numElements * 4;
    const overhead = numElements * 32;
    return dataSize + linkSize + overhead;
  }

  private async getOrLoadIndex(indexId: string): Promise<HnswIndex> {
    if (this.indexes.has(indexId)) {
      return this.indexes.get(indexId)!;
    }

    if (this.loadPromises.has(indexId)) {
      return this.loadPromises.get(indexId)!;
    }

    const loadPromise = this.loadIndex(indexId);
    this.loadPromises.set(indexId, loadPromise);

    try {
      const result = await loadPromise;
      return result;
    } finally {
      this.loadPromises.delete(indexId);
    }
  }

  private async loadIndex(indexId: string): Promise<HnswIndex> {
    const dbIndex = await this.prisma.vectorIndex.findUnique({
      where: { id: indexId },
    });

    if (!dbIndex) {
      throw new Error(`Vector index not found: ${indexId}`);
    }

    if (dbIndex.status !== 'ready' && dbIndex.status !== 'updating') {
      throw new Error(`Vector index is not ready: ${dbIndex.status}`);
    }

    const config = dbIndex.config as any;
    const hnswConfig = config.hnswConfig as HNSWConfig;
    const dimension = dbIndex.dimension;

    let hnswlib: any;
    try {
      hnswlib = await import('hnswlib-node');
    } catch {
      logger.warn('hnswlib-node not available, using brute force fallback');
    }

    let index: any = null;
    if (hnswlib) {
      const spaceType = this.getSpaceType(config.distanceMetric);
      index = new hnswlib.HierarchicalNSW(spaceType, dimension);
      index.initIndex(
        Math.max(hnswConfig.maxElements, dbIndex.size + 1000),
        hnswConfig.m,
        hnswConfig.efConstruction,
        Math.random() * 1000
      );
      index.setEfSearch(hnswConfig.efSearch);
    }

    const entityKeys: string[] = [];
    const featuresMap = new Map<number, Record<string, unknown>>();

    const records = await this.prisma.vectorRecord.findMany({
      where: { indexId },
    });

    for (let i = 0; i < records.length; i++) {
      const record = records[i]!;
      entityKeys.push(record.entityKey);
      featuresMap.set(i, (record.features as Record<string, unknown>) || {});

      if (index && record.vector) {
        const vec = this.extractVector(record.vector);
        if (vec.length === dimension) {
          index.addPoint(vec, i);
        }
      }
    }

    const hnswIndex: HnswIndex = {
      id: dbIndex.id,
      featureSetId: dbIndex.featureSetId,
      featureName: dbIndex.featureName,
      dimension,
      metric: config.distanceMetric,
      index,
      entityKeys,
      featuresMap,
      size: entityKeys.length,
      efSearch: hnswConfig.efSearch,
    };

    this.indexes.set(indexId, hnswIndex);
    logger.debug({ indexId, size: entityKeys.length }, 'Vector index loaded into memory');

    return hnswIndex;
  }

  async search(request: VectorSearchRequest): Promise<VectorSearchResponse> {
    const startTime = Date.now();

    const dbIndex = await this.prisma.vectorIndex.findUnique({
      where: {
        featureSetId_featureName: {
          featureSetId: request.featureSetId,
          featureName: request.featureName,
        },
      },
    });

    if (!dbIndex) {
      throw new Error(`Vector index not found for ${request.featureSetId}/${request.featureName}`);
    }

    if (dbIndex.status !== 'ready') {
      throw new Error(`Vector index is not ready: ${dbIndex.status}`);
    }

    const hnswIndex = await this.getOrLoadIndex(dbIndex.id);

    const topK = request.topK || 10;
    const efSearch = request.efSearch || hnswIndex.efSearch;

    if (hnswIndex.index) {
      hnswIndex.index.setEfSearch(efSearch);
    }

    let results: VectorSearchResult[] = [];

    if (hnswIndex.index && hnswIndex.size > 0) {
      const result = hnswIndex.index.searchKnn(request.queryVector, topK);
      const labels = result.neighbors || [];
      const distances = result.distances || [];

      for (let i = 0; i < labels.length; i++) {
        const idx = labels[i];
        const distance = distances[i];
        const entityKey = hnswIndex.entityKeys[idx];
        const features = request.includeFeatures ? hnswIndex.featuresMap.get(idx) : undefined;

        const similarity = this.distanceToSimilarity(distance, hnswIndex.metric);

        results.push({
          entityKey,
          distance,
          similarity,
          rank: i + 1,
          features: request.includeFeatures ? features : undefined,
        });
      }
    } else {
      results = this.bruteForceSearch(hnswIndex, request.queryVector, topK, request.includeFeatures);
    }

    if (request.filter && (request.filter.rangeFilters?.length || request.filter.exactMatchFilters?.length)) {
      results = await this.applyFilter(results, request.filter, request.featureSetId);
    }

    const searchTimeMs = Date.now() - startTime;

    return {
      featureSetId: request.featureSetId,
      featureName: request.featureName,
      queryVector: request.queryVector,
      results,
      totalCount: results.length,
      searchTimeMs,
      distanceMetric: hnswIndex.metric,
      indexSize: hnswIndex.size,
    };
  }

  private bruteForceSearch(
    hnswIndex: HnswIndex,
    queryVector: number[],
    topK: number,
    includeFeatures: boolean,
  ): VectorSearchResult[] {
    const distances: { idx: number; distance: number }[] = [];

    for (let i = 0; i < hnswIndex.entityKeys.length; i++) {
      const features = hnswIndex.featuresMap.get(i);
      const vec = features?.[hnswIndex.featureName];
      if (!Array.isArray(vec)) continue;

      const distance = this.calculateDistance(queryVector, vec, hnswIndex.metric);
      distances.push({ idx: i, distance });
    }

    distances.sort((a, b) => a.distance - b.distance);

    return distances.slice(0, topK).map((d, i) => ({
      entityKey: hnswIndex.entityKeys[d.idx]!,
      distance: d.distance,
      similarity: this.distanceToSimilarity(d.distance, hnswIndex.metric),
      rank: i + 1,
      features: includeFeatures ? hnswIndex.featuresMap.get(d.idx) : undefined,
    }));
  }

  private calculateDistance(a: number[], b: number[], metric: VectorDistanceMetric): number {
    if (a.length !== b.length) return Infinity;

    switch (metric) {
      case 'l2':
        return Math.sqrt(a.reduce((sum, v, i) => sum + (v - b[i]!) ** 2, 0));
      case 'cosine': {
        let dot = 0;
        let normA = 0;
        let normB = 0;
        for (let i = 0; i < a.length; i++) {
          dot += a[i]! * b[i]!;
          normA += a[i]! ** 2;
          normB += b[i]! ** 2;
        }
        return 1 - dot / (Math.sqrt(normA) * Math.sqrt(normB));
      }
      case 'inner_product':
        return -a.reduce((sum, v, i) => sum + v * b[i]!, 0);
      default:
        return Infinity;
    }
  }

  private distanceToSimilarity(distance: number, metric: VectorDistanceMetric): number {
    switch (metric) {
      case 'cosine':
        return 1 - distance;
      case 'l2':
        return 1 / (1 + distance);
      case 'inner_product':
        return -distance;
      default:
        return 1 / (1 + distance);
    }
  }

  private async applyFilter(
    results: VectorSearchResult[],
    filter: VectorSearchFilter,
    featureSetId: string,
  ): Promise<VectorSearchResult[]> {
    const entityKeys = results.map((r) => r.entityKey);

    const featuresData: Record<string, Record<string, unknown>> = {};
    const allFilterFields = new Set<string>();

    for (const rf of filter.rangeFilters || []) {
      allFilterFields.add(rf.featureName);
    }
    for (const ef of filter.exactMatchFilters || []) {
      allFilterFields.add(ef.featureName);
    }

    for (const result of results) {
      if (result.features) {
        featuresData[result.entityKey] = result.features;
      }
    }

    const operator = filter.booleanOperator || 'AND';

    return results.filter((result) => {
      const features = featuresData[result.entityKey] || result.features || {};
      const conditions: boolean[] = [];

      for (const rf of filter.rangeFilters || []) {
        const value = features[rf.featureName];
        if (typeof value !== 'number') {
          conditions.push(false);
          continue;
        }

        let pass = true;
        if (rf.min !== undefined) {
          pass = pass && (rf.includeMin ? value >= rf.min : value > rf.min);
        }
        if (rf.max !== undefined) {
          pass = pass && (rf.includeMax ? value <= rf.max : value < rf.max);
        }
        conditions.push(pass);
      }

      for (const ef of filter.exactMatchFilters || []) {
        const value = features[ef.featureName];
        conditions.push(ef.values.includes(value));
      }

      if (operator === 'AND') {
        return conditions.every((c) => c);
      } else {
        return conditions.some((c) => c);
      }
    });
  }

  async rangeQuery(request: RangeQueryRequest): Promise<RangeQueryResponse> {
    const startTime = Date.now();

    const featureSet = await this.prisma.featureSet.findUnique({
      where: { id: request.featureSetId },
    });

    if (!featureSet) {
      throw new Error(`Feature set not found: ${request.featureSetId}`);
    }

    const dbIndex = await this.prisma.vectorIndex.findUnique({
      where: {
        featureSetId_featureName: {
          featureSetId: request.featureSetId,
          featureName: request.featureName,
        },
      },
    });

    if (!dbIndex) {
      throw new Error(`Vector index not found`);
    }

    const hnswIndex = await this.getOrLoadIndex(dbIndex.id);

    let results: RangeQueryResult[] = [];

    for (let i = 0; i < hnswIndex.entityKeys.length; i++) {
      const entityKey = hnswIndex.entityKeys[i]!;
      const features = hnswIndex.featuresMap.get(i) || {};

      results.push({
        entityKey,
        features: request.includeFeatures ? features : {},
      });
    }

    if (request.filters) {
      results = results.filter((result) => {
        const features = result.features;
        const operator = request.filters!.booleanOperator || 'AND';
        const conditions: boolean[] = [];

        for (const rf of request.filters!.rangeFilters || []) {
          const value = features[rf.featureName];
          if (typeof value !== 'number') {
            conditions.push(false);
            continue;
          }

          let pass = true;
          if (rf.min !== undefined) {
            pass = pass && (rf.includeMin ? value >= rf.min : value > rf.min);
          }
          if (rf.max !== undefined) {
            pass = pass && (rf.includeMax ? value <= rf.max : value < rf.max);
          }
          conditions.push(pass);
        }

        for (const ef of request.filters!.exactMatchFilters || []) {
          const value = features[ef.featureName];
          conditions.push(ef.values.includes(value));
        }

        return operator === 'AND' ? conditions.every((c) => c) : conditions.some((c) => c);
      });
    }

    if (request.sortBy) {
      results.sort((a, b) => {
        const aVal = a.features[request.sortBy!.featureName];
        const bVal = b.features[request.sortBy!.featureName];
        if (typeof aVal !== 'number' || typeof bVal !== 'number') return 0;
        return request.sortBy!.order === 'asc' ? aVal - bVal : bVal - aVal;
      });
    }

    const totalCount = results.length;
    const topK = request.topK || 100;
    results = results.slice(0, topK);

    return {
      featureSetId: request.featureSetId,
      featureName: request.featureName,
      results,
      totalCount,
      queryTimeMs: Date.now() - startTime,
      filters: request.filters || { rangeFilters: [], exactMatchFilters: [] },
    };
  }

  async ingestVectors(request: VectorIngestRequest): Promise<VectorIngestResponse> {
    const startTime = Date.now();

    const dbIndex = await this.prisma.vectorIndex.findUnique({
      where: {
        featureSetId_featureName: {
          featureSetId: request.featureSetId,
          featureName: request.featureName,
        },
      },
    });

    if (!dbIndex) {
      throw new Error(`Vector index not found for ${request.featureSetId}/${request.featureName}`);
    }

    let ingestedCount = 0;
    let failedCount = 0;

    await this.prisma.$transaction(async (tx) => {
      if (request.mode === 'overwrite') {
        await tx.vectorRecord.deleteMany({ where: { indexId: dbIndex.id } });
      }

      for (const row of request.data) {
        try {
          const entityKey = String(row[request.entityKeyField]);
          const vectorValue = row[request.vectorField];

          if (!entityKey || !vectorValue) {
            failedCount++;
            continue;
          }

          const vector = Array.isArray(vectorValue) ? vectorValue : JSON.parse(String(vectorValue));
          if (!Array.isArray(vector) || vector.length !== dbIndex.dimension) {
            failedCount++;
            continue;
          }

          const additionalFeatures: Record<string, unknown> = {};
          for (const field of request.additionalFields || []) {
            additionalFeatures[field] = row[field];
          }
          additionalFeatures[request.vectorField] = vector;

          if (request.mode === 'upsert') {
            const existing = await tx.vectorRecord.findUnique({
              where: {
                indexId_entityKey: { indexId: dbIndex.id, entityKey },
              },
            });

            if (existing) {
              await tx.vectorRecord.update({
                where: { id: existing.id },
                data: {
                  vector: vector as any,
                  features: { ...(existing.features as object), ...additionalFeatures },
                },
              });
            } else {
              await tx.vectorRecord.create({
                data: {
                  id: uuidv4(),
                  indexId: dbIndex.id,
                  entityKey,
                  vector: vector as any,
                  features: additionalFeatures,
                },
              });
            }
          } else {
            await tx.vectorRecord.create({
              data: {
                id: uuidv4(),
                indexId: dbIndex.id,
                entityKey,
                vector: vector as any,
                features: additionalFeatures,
              },
            });
          }

          ingestedCount++;
        } catch {
          failedCount++;
        }
      }
    });

    const newSize = await this.prisma.vectorRecord.count({ where: { indexId: dbIndex.id } });

    await this.prisma.vectorIndex.update({
      where: { id: dbIndex.id },
      data: {
        size: newSize,
        status: 'updating',
      },
    });

    this.indexes.delete(dbIndex.id);

    setImmediate(() => {
      this.rebuildIndex(dbIndex.id).catch((err) => {
        logger.error({ err, indexId: dbIndex.id }, 'Failed to rebuild vector index after ingest');
      });
    });

    return {
      indexId: dbIndex.id,
      ingestedCount,
      failedCount,
      totalDurationMs: Date.now() - startTime,
      currentIndexSize: newSize,
    };
  }

  private async rebuildIndex(indexId: string): Promise<void> {
    const dbIndex = await this.prisma.vectorIndex.findUnique({
      where: { id: indexId },
    });

    if (!dbIndex) return;

    const config = dbIndex.config as any;
    const request: VectorIndexBuildRequest = {
      featureSetId: dbIndex.featureSetId,
      featureName: dbIndex.featureName,
      dimension: dbIndex.dimension,
      distanceMetric: config.distanceMetric,
      indexType: config.indexType,
      hnswConfig: config.hnswConfig,
    };

    this.indexes.delete(indexId);

    await this.buildIndexInBackground(indexId, request);
  }

  async getIndex(indexId: string): Promise<VectorIndex | null> {
    const dbIndex = await this.prisma.vectorIndex.findUnique({
      where: { id: indexId },
    });

    return dbIndex ? this.transformIndex(dbIndex) : null;
  }

  async listIndexes(featureSetId: string): Promise<VectorIndex[]> {
    const indexes = await this.prisma.vectorIndex.findMany({
      where: { featureSetId },
      orderBy: { createdAt: 'desc' },
    });

    return indexes.map((i) => this.transformIndex(i));
  }

  async deleteIndex(indexId: string): Promise<void> {
    await this.prisma.vectorIndex.delete({ where: { id: indexId } });
    this.indexes.delete(indexId);
  }

  async getStats(): Promise<VectorStats> {
    const indexes = await this.prisma.vectorIndex.findMany();

    let totalVectors = 0;
    let totalMemoryBytes = 0;
    let avgBuildTimeMs = 0;
    let avgSearchTimeMs = 0;
    let totalQueries = 0;

    const dimensionDistribution: Record<number, number> = {};
    const metricDistribution: Record<VectorDistanceMetric, number> = {
      cosine: 0,
      l2: 0,
      inner_product: 0,
      manhattan: 0,
    };

    for (const idx of indexes) {
      totalVectors += idx.size;
      totalMemoryBytes += Number(idx.memoryUsageBytes);

      const config = idx.config as any;
      const metric = config.distanceMetric as VectorDistanceMetric;
      if (metricDistribution[metric] !== undefined) {
        metricDistribution[metric]++;
      }

      dimensionDistribution[idx.dimension] = (dimensionDistribution[idx.dimension] || 0) + 1;
    }

    return {
      indexCount: indexes.length,
      totalVectors,
      totalMemoryBytes,
      avgBuildTimeMs,
      avgSearchTimeMs,
      totalQueries,
      dimensionDistribution,
      metricDistribution,
    };
  }

  private transformIndex(dbIndex: any): VectorIndex {
    const config = dbIndex.config as any;
    return {
      id: dbIndex.id,
      featureSetId: dbIndex.featureSetId,
      featureName: dbIndex.featureName,
      config: {
        featureName: dbIndex.featureName,
        dimension: dbIndex.dimension,
        distanceMetric: config.distanceMetric,
        indexType: config.indexType || 'hnsw',
        hnswConfig: config.hnswConfig,
        ivfConfig: config.ivfConfig,
      },
      status: dbIndex.status as VectorIndex['status'],
      size: dbIndex.size,
      dimension: dbIndex.dimension,
      memoryUsageBytes: Number(dbIndex.memoryUsageBytes),
      buildProgress: dbIndex.buildProgress ?? undefined,
      lastBuildTime: dbIndex.lastBuildTime?.getTime(),
      createdAt: dbIndex.createdAt.getTime(),
      updatedAt: dbIndex.updatedAt.getTime(),
    };
  }
}

export const vectorSearchService = new VectorSearchService(prisma);

export async function registerVectorSearchRoutes(fastify: any): Promise<void> {
  const service = vectorSearchService;

  fastify.post('/api/v1/vector-indexes', async (request: FastifyRequest, reply: FastifyReply) => {
    const result = await service.createIndex(request.body as VectorIndexBuildRequest);
    return reply.status(201).send(result);
  });

  fastify.get('/api/v1/vector-indexes/:id', async (request: FastifyRequest<{ Params: { id: string } }>, reply: FastifyReply) => {
    const result = await service.getIndex(request.params.id);
    if (!result) return reply.status(404).send({ error: 'Vector index not found' });
    return result;
  });

  fastify.get('/api/v1/feature-sets/:id/vector-indexes', async (request: FastifyRequest<{ Params: { id: string } }>) => {
    return service.listIndexes(request.params.id);
  });

  fastify.delete('/api/v1/vector-indexes/:id', async (request: FastifyRequest<{ Params: { id: string } }>, reply: FastifyReply) => {
    await service.deleteIndex(request.params.id);
    return reply.status(204).send();
  });

  fastify.post('/api/v1/vector-search', async (request: FastifyRequest) => {
    return service.search(request.body as VectorSearchRequest);
  });

  fastify.post('/api/v1/vector-search/range', async (request: FastifyRequest) => {
    return service.rangeQuery(request.body as RangeQueryRequest);
  });

  fastify.post('/api/v1/vector-indexes/ingest', async (request: FastifyRequest) => {
    return service.ingestVectors(request.body as VectorIngestRequest);
  });

  fastify.get('/api/v1/vector-stats', async () => {
    return service.getStats();
  });
}
