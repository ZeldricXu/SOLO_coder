import { z } from 'zod';
import type {
  VectorSearchRequest,
  RangeQueryRequest,
  VectorIndexBuildRequest,
  VectorIndexUpdateRequest,
  VectorIngestRequest,
} from '../types/vector';

export const hnswConfigSchema = z.object({
  m: z.number().int().positive().min(4).max(64).default(16),
  efConstruction: z.number().int().positive().min(100).max(2000).default(200),
  efSearch: z.number().int().positive().min(10).max(2000).default(50),
  maxElements: z.number().int().positive().default(1000000),
});

export const ivfConfigSchema = z.object({
  nlist: z.number().int().positive().min(10).max(65536).default(1024),
  nprobe: z.number().int().positive().min(1).max(65536).default(64),
  quantizerType: z.enum(['flat', 'pq']).default('flat'),
  pqM: z.number().int().positive().min(1).max(64).optional(),
});

export const vectorFeatureConfigSchema = z.object({
  featureName: z.string().min(1),
  dimension: z.number().int().positive().min(1).max(4096),
  distanceMetric: z.enum(['cosine', 'l2', 'inner_product', 'manhattan']).default('cosine'),
  indexType: z.enum(['hnsw', 'flat', 'ivf']).default('hnsw'),
  hnswConfig: hnswConfigSchema.optional(),
  ivfConfig: ivfConfigSchema.optional(),
});

export const rangeFilterSchema = z.object({
  featureName: z.string().min(1),
  min: z.number().optional(),
  max: z.number().optional(),
  includeMin: z.boolean().default(true),
  includeMax: z.boolean().default(true),
});

export const exactMatchFilterSchema = z.object({
  featureName: z.string().min(1),
  values: z.array(z.unknown()).min(1),
});

export const vectorSearchFilterSchema = z.object({
  rangeFilters: z.array(rangeFilterSchema).default([]),
  exactMatchFilters: z.array(exactMatchFilterSchema).default([]),
  booleanOperator: z.enum(['AND', 'OR']).default('AND'),
});

export const vectorSearchRequestSchema = z.object({
  featureSetId: z.string().min(1),
  featureName: z.string().min(1),
  queryVector: z.array(z.number()).min(1),
  topK: z.number().int().positive().max(1000).default(10),
  filter: vectorSearchFilterSchema.optional(),
  efSearch: z.number().int().positive().optional(),
  includeDistances: z.boolean().default(true),
  includeFeatures: z.boolean().default(true),
  featureNames: z.array(z.string()).optional(),
  version: z.string().optional(),
}).refine(data => {
  if (data.queryVector.length < 1) return false;
  if (data.efSearch && (data.efSearch < 10 || data.efSearch > 2000)) return false;
  return true;
}, {
  message: 'Invalid vector search parameters',
});

export const rangeQueryRequestSchema = z.object({
  featureSetId: z.string().min(1),
  featureName: z.string().min(1),
  filters: vectorSearchFilterSchema,
  topK: z.number().int().positive().max(1000).default(100),
  sortBy: z.object({
    featureName: z.string().min(1),
    order: z.enum(['asc', 'desc']).default('asc'),
  }).optional(),
  includeFeatures: z.boolean().default(true),
  featureNames: z.array(z.string()).optional(),
  version: z.string().optional(),
});

export const vectorIndexBuildRequestSchema = z.object({
  featureSetId: z.string().min(1),
  featureName: z.string().min(1),
  dimension: z.number().int().positive().min(1).max(4096),
  distanceMetric: z.enum(['cosine', 'l2', 'inner_product', 'manhattan']).default('cosine'),
  indexType: z.enum(['hnsw', 'flat', 'ivf']).default('hnsw'),
  hnswConfig: hnswConfigSchema.optional(),
  ivfConfig: ivfConfigSchema.optional(),
  dataSource: z.object({
    entityKeyField: z.string().min(1),
    vectorField: z.string().min(1),
    additionalFields: z.array(z.string()).default([]),
  }).optional(),
});

export const vectorIndexUpdateRequestSchema = z.object({
  indexId: z.string().min(1),
  data: z.array(z.object({
    entityKey: z.string().min(1),
    vector: z.array(z.number()).min(1),
    features: z.record(z.unknown()).optional(),
  })).min(1),
  mode: z.enum(['add', 'update', 'delete']),
});

export const vectorIngestRequestSchema = z.object({
  featureSetId: z.string().min(1),
  featureName: z.string().min(1),
  entityKeyField: z.string().min(1),
  vectorField: z.string().min(1),
  data: z.array(z.record(z.unknown())).min(1),
  additionalFields: z.array(z.string()).default([]),
  mode: z.enum(['overwrite', 'append', 'upsert']).default('upsert'),
  version: z.string().optional(),
});

export type VectorSearchRequestSchema = z.infer<typeof vectorSearchRequestSchema>;
export type RangeQueryRequestSchema = z.infer<typeof rangeQueryRequestSchema>;
export type VectorIndexBuildRequestSchema = z.infer<typeof vectorIndexBuildRequestSchema>;
export type VectorIndexUpdateRequestSchema = z.infer<typeof vectorIndexUpdateRequestSchema>;
export type VectorIngestRequestSchema = z.infer<typeof vectorIngestRequestSchema>;
