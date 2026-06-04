export type NodeType = 'document' | 'tag' | 'keyword';

export interface KnowledgeNode {
  id: string;
  type: NodeType;
  title: string;
  content?: string;
  metadata?: Record<string, unknown>;
  createdAt: Date;
  updatedAt: Date;
  isDeleted?: boolean;
  isArchived?: boolean;
  viewCount?: number;
  referenceCount?: number;
}

export interface DocumentVector {
  documentId: string;
  vector: SparseVector;
  terms: string[];
  termFrequencies: Map<string, number>;
  wordCount: number;
}

export interface SparseVector {
  indices: number[];
  values: number[];
  dimension: number;
}

export interface SimilarityResult {
  documentId: string;
  similarity: number;
  rank: number;
}

export type EdgeType = 'similarity' | 'reference' | 'citation';

export interface GraphEdge {
  id: string;
  sourceId: string;
  targetId: string;
  type: EdgeType;
  weight: number;
  metadata?: Record<string, unknown>;
}

export interface KnowledgeGraph {
  nodes: Map<string, KnowledgeNode>;
  edges: GraphEdge[];
  vectors: Map<string, DocumentVector>;
  idfMap: Map<string, number>;
}

export interface TokenizedResult {
  tokens: string[];
  keywords: string[];
  termFreq: Map<string, number>;
  filteredTokens: string[];
}

export interface ReferenceInfo {
  sourceDocumentId: string;
  targetDocumentId: string;
  linkText: string;
  isInternal: boolean;
  isWikiLink: boolean;
  rawPath: string;
}

export interface RecommendResult {
  documentId: string;
  title: string;
  score: number;
  similarityScore: number;
  referenceScore: number;
  popularityScore: number;
  reasons: string[];
}

export interface GraphBuildOptions {
  similarityThreshold?: number;
  maxEdgesPerNode?: number;
  includeContent?: boolean;
}

export interface RecommendOptions {
  limit?: number;
  similarityWeight?: number;
  referenceWeight?: number;
  popularityWeight?: number;
  excludeIds?: string[];
}
