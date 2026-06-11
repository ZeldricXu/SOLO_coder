export interface VectorSearchRequest {
  featureSetId: string;
  featureVersion?: string;
  queryVector: number[];
  topK?: number;
  similarityMetric?: 'cosine' | 'euclidean' | 'inner_product';
  filters?: {
    range?: Record<string, {
      min?: number;
      max?: number;
      gte?: number;
      lte?: number;
      gt?: number;
      lt?: number;
    }>;
    exactMatch?: Record<string, unknown>;
    include?: string[];
    exclude?: string[];
  };
  threshold?: number;
  includeVectors?: boolean;
  includeMetadata?: boolean;
}

export interface VectorSearchResult {
  entityKey: string;
  score: number;
  similarity: number;
  distance?: number;
  rank: number;
  features?: Record<string, unknown>;
  vector?: number[];
  metadata?: Record<string, unknown>;
}

export interface VectorSearchResponse {
  featureSetId: string;
  featureVersion: string;
  queryVector: number[];
  results: VectorSearchResult[];
  totalFound: number;
  searchTimeMs: number;
  indexStats?: {
    indexSize: number;
    dimension: number;
    similarityMetric: string;
    buildTimeMs?: number;
  };
}

export interface RangeQueryRequest {
  featureSetId: string;
  featureVersion?: string;
  featureName: string;
  min?: number;
  max?: number;
  gte?: number;
  lte?: number;
  gt?: number;
  lt?: number;
  filters?: Record<string, unknown>;
  limit?: number;
  offset?: number;
  sortOrder?: 'asc' | 'desc';
}

export interface RangeQueryResult {
  entityKey: string;
  featureValue: number;
  features: Record<string, unknown>;
  rank?: number;
}

export interface RangeQueryResponse {
  featureSetId: string;
  featureVersion: string;
  featureName: string;
  results: RangeQueryResult[];
  totalFound: number;
  queryTimeMs: number;
}

export interface VectorIndexConfig {
  featureSetId: string;
  featureName: string;
  dimension: number;
  similarityMetric: 'cosine' | 'euclidean' | 'inner_product';
  m?: number;
  efConstruction?: number;
  efSearch?: number;
  indexPath?: string;
  autoRebuild?: boolean;
  rebuildThreshold?: number;
  ttlSeconds?: number;
}

export interface VectorIndexStats {
  featureSetId: string;
  featureName: string;
  dimension: number;
  similarityMetric: string;
  indexSize: number;
  memoryUsageBytes: number;
  buildTimeMs: number;
  lastRebuiltAt: number;
  queryCount: number;
  avgQueryTimeMs: number;
  m: number;
  efConstruction: number;
  efSearch: number;
}

export interface VectorIndexBuildRequest {
  featureSetId: string;
  featureName: string;
  dimension: number;
  similarityMetric?: 'cosine' | 'euclidean' | 'inner_product';
  m?: number;
  efConstruction?: number;
  entityKeys?: string[];
  sampleSize?: number;
  forceRebuild?: boolean;
}

export interface HybridSearchRequest {
  featureSetId: string;
  featureVersion?: string;
  queryVector?: number[];
  vectorWeight?: number;
  keywordQuery?: string;
  keywordFields?: string[];
  keywordWeight?: number;
  rangeFilters?: Record<string, {
    min?: number;
    max?: number;
  }>;
  exactFilters?: Record<string, unknown>;
  topK?: number;
  rerank?: boolean;
  rerankModel?: string;
}

export interface HybridSearchResponse {
  results: VectorSearchResult[];
  totalFound: number;
  searchTimeMs: number;
  vectorSearchTimeMs?: number;
  keywordSearchTimeMs?: number;
  rerankTimeMs?: number;
}
