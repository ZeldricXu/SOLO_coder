import { z } from 'zod';

export const vectorSearchRequestSchema = z.object({
  featureSetId: z.string().uuid(),
  featureVersion: z.string().optional(),
  queryVector: z.array(z.number()).min(1),
  topK: z.number().int().min(1).max(1000).default(10),
  similarityMetric: z.enum(['cosine', 'euclidean', 'inner_product']).default('cosine'),
  filters: z.object({
    range: z.record(z.string(), z.object({
      min: z.number().optional(),
      max: z.number().optional(),
      gte: z.number().optional(),
      lte: z.number().optional(),
      gt: z.number().optional(),
      lt: z.number().optional(),
    })).optional(),
    exactMatch: z.record(z.string(), z.any()).optional(),
    include: z.array(z.string()).optional(),
    exclude: z.array(z.string()).optional(),
  }).optional(),
  threshold: z.number().min(0).max(1).optional(),
  includeVectors: z.boolean().default(false),
  includeMetadata: z.boolean().default(true),
});

export const rangeQueryRequestSchema = z.object({
  featureSetId: z.string().uuid(),
  featureVersion: z.string().optional(),
  featureName: z.string().min(1),
  min: z.number().optional(),
  max: z.number().optional(),
  gte: z.number().optional(),
  lte: z.number().optional(),
  gt: z.number().optional(),
  lt: z.number().optional(),
  filters: z.record(z.string(), z.any()).optional(),
  limit: z.number().int().min(1).max(10000).default(100),
  offset: z.number().int().min(0).default(0),
  sortOrder: z.enum(['asc', 'desc']).default('asc'),
});

export const vectorIndexBuildRequestSchema = z.object({
  featureSetId: z.string().uuid(),
  featureName: z.string().min(1),
  dimension: z.number().int().min(1).max(4096),
  similarityMetric: z.enum(['cosine', 'euclidean', 'inner_product']).default('cosine'),
  m: z.number().int().min(4).max(64).default(16),
  efConstruction: z.number().int().min(100).max(2000).default(200),
  entityKeys: z.array(z.string()).optional(),
  sampleSize: z.number().int().min(100).max(1000000).optional(),
  forceRebuild: z.boolean().default(false),
});

export const hybridSearchRequestSchema = z.object({
  featureSetId: z.string().uuid(),
  featureVersion: z.string().optional(),
  queryVector: z.array(z.number()).min(1).optional(),
  vectorWeight: z.number().min(0).max(1).default(0.7),
  keywordQuery: z.string().min(1).optional(),
  keywordFields: z.array(z.string()).optional(),
  keywordWeight: z.number().min(0).max(1).default(0.3),
  rangeFilters: z.record(z.string(), z.object({
    min: z.number().optional(),
    max: z.number().optional(),
  })).optional(),
  exactFilters: z.record(z.string(), z.any()).optional(),
  topK: z.number().int().min(1).max(1000).default(10),
  rerank: z.boolean().default(false),
  rerankModel: z.string().optional(),
});

export const vectorIndexConfigSchema = z.object({
  featureSetId: z.string().uuid(),
  featureName: z.string().min(1),
  dimension: z.number().int().min(1).max(4096),
  similarityMetric: z.enum(['cosine', 'euclidean', 'inner_product']).default('cosine'),
  m: z.number().int().min(4).max(64).default(16),
  efConstruction: z.number().int().min(100).max(2000).default(200),
  efSearch: z.number().int().min(10).max(2000).default(50),
  indexPath: z.string().optional(),
  autoRebuild: z.boolean().default(true),
  rebuildThreshold: z.number().min(0).max(1).default(0.2),
  ttlSeconds: z.number().int().min(3600).optional(),
});
