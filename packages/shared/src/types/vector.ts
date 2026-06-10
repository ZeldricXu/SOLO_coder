export type VectorDistanceMetric = 'cosine' | 'l2' | 'inner_product' | 'manhattan';

export interface VectorFeatureConfig {
  featureName: string;
  dimension: number;
  distanceMetric: VectorDistanceMetric;
  indexType: 'hnsw' | 'flat' | 'ivf';
  hnswConfig?: HNSWConfig;
  ivfConfig?: IVFConfig;
}

export interface HNSWConfig {
  m: number;
  efConstruction: number;
  efSearch: number;
  maxElements: number;
}

export interface IVFConfig {
  nlist: number;
  nprobe: number;
  quantizerType: 'flat' | 'pq';
  pqM?: number;
}

export interface VectorIndex {
  id: string;
  featureSetId: string;
  featureName: string;
  config: VectorFeatureConfig;
  status: 'building' | 'ready' | 'failed' | 'updating';
  size: number;
  dimension: number;
  memoryUsageBytes: number;
  buildProgress?: number;
  lastBuildTime?: number;
  createdAt: number;
  updatedAt: number;
}

export interface VectorSearchRequest {
  featureSetId: string;
  featureName: string;
  queryVector: number[];
  topK?: number;
  filter?: VectorSearchFilter;
  efSearch?: number;
  includeDistances?: boolean;
  includeFeatures?: boolean;
  featureNames?: string[];
  version?: string;
}

export interface VectorSearchFilter {
  rangeFilters?: RangeFilter[];
  exactMatchFilters?: ExactMatchFilter[];
  booleanOperator?: 'AND' | 'OR';
}

export interface RangeFilter {
  featureName: string;
  min?: number;
  max?: number;
  includeMin?: boolean;
  includeMax?: boolean;
}

export interface ExactMatchFilter {
  featureName: string;
  values: unknown[];
}

export interface VectorSearchResult {
  entityKey: string;
  distance: number;
  similarity: number;
  rank: number;
  features?: Record<string, unknown>;
}

export interface VectorSearchResponse {
  featureSetId: string;
  featureName: string;
  queryVector: number[];
  results: VectorSearchResult[];
  totalCount: number;
  searchTimeMs: number;
  distanceMetric: VectorDistanceMetric;
  indexSize: number;
}

export interface VectorIndexBuildRequest {
  featureSetId: string;
  featureName: string;
  dimension: number;
  distanceMetric?: VectorDistanceMetric;
  indexType?: 'hnsw' | 'flat' | 'ivf';
  hnswConfig?: HNSWConfig;
  ivfConfig?: IVFConfig;
  dataSource?: {
    entityKeyField: string;
    vectorField: string;
    additionalFields?: string[];
  };
}

export interface VectorIndexUpdateRequest {
  indexId: string;
  data: {
    entityKey: string;
    vector: number[];
    features?: Record<string, unknown>;
  }[];
  mode: 'add' | 'update' | 'delete';
}

export interface VectorIngestRequest {
  featureSetId: string;
  featureName: string;
  entityKeyField: string;
  vectorField: string;
  data: Record<string, unknown>[];
  additionalFields?: string[];
  mode: 'overwrite' | 'append' | 'upsert';
  version?: string;
}

export interface VectorIngestResponse {
  indexId: string;
  ingestedCount: number;
  failedCount: number;
  totalDurationMs: number;
  currentIndexSize: number;
}

export interface RangeQueryRequest {
  featureSetId: string;
  featureName: string;
  filters: VectorSearchFilter;
  topK?: number;
  sortBy?: {
    featureName: string;
    order: 'asc' | 'desc';
  };
  includeFeatures?: boolean;
  featureNames?: string[];
  version?: string;
}

export interface RangeQueryResult {
  entityKey: string;
  features: Record<string, unknown>;
  score?: number;
}

export interface RangeQueryResponse {
  featureSetId: string;
  featureName: string;
  results: RangeQueryResult[];
  totalCount: number;
  queryTimeMs: number;
  filters: VectorSearchFilter;
}

export interface VectorIndexStatus {
  indexId: string;
  status: 'building' | 'ready' | 'failed' | 'updating';
  buildProgress: number;
  size: number;
  dimension: number;
  memoryUsageBytes: number;
  buildDurationMs?: number;
  averageSearchTimeMs?: number;
  queriesPerSecond?: number;
  lastQueryTime?: number;
}

export interface VectorStats {
  indexCount: number;
  totalVectors: number;
  totalMemoryBytes: number;
  avgBuildTimeMs: number;
  avgSearchTimeMs: number;
  totalQueries: number;
  dimensionDistribution: Record<number, number>;
  metricDistribution: Record<VectorDistanceMetric, number>;
}
