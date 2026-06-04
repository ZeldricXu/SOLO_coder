export interface SearchQuery {
  query: string;
  spaceId?: string;
  tagIds?: string[];
  dateFrom?: Date;
  dateTo?: Date;
  source?: 'MANUAL' | 'IMPORTED' | 'SYNCED' | 'API';
  userId: string;
  page: number;
  pageSize: number;
  includeOcr?: boolean;
  sortBy?: 'relevance' | 'date' | 'createdAt' | 'updatedAt' | 'title';
  sortOrder?: 'asc' | 'desc';
  filter?: SearchFilter;
  highlight?: boolean;
  highlightConfig?: HighlightConfig;
}

export interface SearchFilter {
  spaceId?: string;
  tagIds?: string[];
  dateFrom?: Date;
  dateTo?: Date;
  source?: 'MANUAL' | 'IMPORTED' | 'SYNCED' | 'API';
  sourceType?: string;
  isArchived?: boolean;
  userId?: string;
}

export interface SearchResultItem {
  id: string;
  title: string;
  content: string;
  summary?: string | null;
  spaceId: string;
  userId?: string;
  sourceType?: string;
  sourceUrl?: string;
  isArchived?: boolean;
  space?: {
    id: string;
    name: string;
    icon?: string | null;
  } | null;
  createdBy?: {
    id: string;
    name: string;
    email: string;
    avatar?: string | null;
  } | null;
  updatedBy?: {
    id: string;
    name: string;
    email: string;
    avatar?: string | null;
  } | null;
  tags?: Array<{
    id: string;
    name: string;
    color?: string | null;
  }>;
  createdAt: Date;
  updatedAt: Date;
  path?: string | null;
  score?: number;
  rank?: number;
  relevanceScore?: number;
  matchMethod?: 'token_tsvector' | 'token_trigram' | 'fallback_trigram' | string;
  highlightedTitle?: string;
  highlightedContent?: string;
  highlightedSummary?: string | null;
  matchedTerms?: string[];
  _count?: {
    comments?: number;
    versions?: number;
  };
}

export interface SearchResult {
  items: SearchResultItem[];
  total: number;
  page: number;
  pageSize: number;
  totalPages: number;
  query: string;
  queryTimeMs?: number;
  executionTimeMs?: number;
  hasMore: boolean;
  matchedTerms?: string[];
}

export interface SuggestionQuery {
  query: string;
  spaceId?: string;
  userId: string;
  limit: number;
}

export interface SuggestionResult {
  documents: Array<{
    id: string;
    title: string;
    path: string;
    space: {
      id: string;
      name: string;
      icon?: string | null;
    };
  }>;
  tags: Array<{
    id: string;
    name: string;
    color?: string | null;
  }>;
}

export interface HighlightConfig {
  preTag?: string;
  postTag?: string;
  fragmentSize?: number;
  maxFragments?: number;
  highlightAll?: boolean;
}

export const DEFAULT_HIGHLIGHT_CONFIG: Required<HighlightConfig> = {
  preTag: '<mark>',
  postTag: '</mark>',
  fragmentSize: 150,
  maxFragments: 3,
  highlightAll: false,
};

export interface SearchConfig {
  pageSize: number;
  maxPageSize: number;
  fulltextWeight: number;
  titleWeight: number;
  contentWeight: number;
  fuzzyWeight: number;
}

export const DEFAULT_SEARCH_CONFIG: Required<SearchConfig> = {
  pageSize: 10,
  maxPageSize: 100,
  fulltextWeight: 2.0,
  titleWeight: 1.5,
  contentWeight: 1.0,
  fuzzyWeight: 0.5,
};

export interface SearchQueryBuildResult {
  sql: string;
  params: unknown[];
  paramIndex: number;
}

export interface HighlightQuery {
  documentId: string;
  query: string;
  userId: string;
}

export interface HighlightResult {
  documentId: string;
  title: string;
  highlightedTitle: string;
  highlightedContent: string;
  highlightedSummary?: string | null;
}

export interface SearchIndexOptions {
  batchSize: number;
  rebuild?: boolean;
}

export interface HighlightFragment {
  text: string;
  isHighlighted: boolean;
}

export interface IndexUpdateResult {
  documentId: string;
  success: boolean;
  error?: string;
  updatedAt?: Date;
}

export interface BatchIndexResult {
  total: number;
  succeeded: number;
  failed: number;
  results: IndexUpdateResult[];
  success?: boolean;
}

export interface SearchService {
  search(query: SearchQuery): Promise<SearchResult>;
  highlight(query: HighlightQuery): Promise<HighlightResult | null>;
  suggest(query: SuggestionQuery): Promise<SuggestionResult>;
  indexDocument(documentId: string): Promise<void>;
  indexDocuments(documentIds: string[]): Promise<void>;
  removeFromIndex(documentId: string): Promise<void>;
  rebuildIndex(options?: SearchIndexOptions): Promise<void>;
}
