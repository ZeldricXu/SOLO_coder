export type SourceType = 'feishu' | 'notion' | 'confluence' | 'github_wiki';

export type SyncMode = 'full' | 'incremental';

export type SyncStatus = 'pending' | 'running' | 'success' | 'failed' | 'partial';

export interface KnowledgeSource {
  id: string;
  type: SourceType;
  name: string;
  config: Record<string, unknown>;
  enabled: boolean;
  lastSyncedAt?: Date;
  lastSyncStatus?: SyncStatus;
  createdAt: Date;
  updatedAt: Date;
}

export interface HeadingNode {
  level: number;
  text: string;
  id?: string;
  children: HeadingNode[];
}

export interface KnowledgeDocument {
  id?: string;
  externalId: string;
  sourceId: string;
  sourceType: SourceType;
  title: string;
  content: string;
  normalizedContent?: string;
  summary?: string;
  url?: string;
  metadata: Record<string, unknown>;
  tags: string[];
  internalLinks: string[];
  headings: HeadingNode[];
  lastModifiedAt: Date;
  lastSyncedAt?: Date;
  createdAt?: Date;
  updatedAt?: Date;
}

export interface SyncResult {
  sourceId: string;
  sourceType: SourceType;
  status: SyncStatus;
  mode: SyncMode;
  total: number;
  created: number;
  updated: number;
  failed: number;
  skipped: number;
  errors: SyncError[];
  startedAt: Date;
  endedAt: Date;
  durationMs: number;
}

export interface SyncError {
  externalId?: string;
  message: string;
  code: string;
  details?: Record<string, unknown>;
  timestamp: Date;
}

export interface SyncConfig {
  mode: SyncMode;
  concurrency: number;
  maxRetries: number;
  retryDelayMs: number;
  timeoutMs: number;
  since?: Date;
  includeMetadata?: boolean;
}

export interface FetchOptions {
  since?: Date;
  limit?: number;
  offset?: number;
  signal?: AbortSignal;
}

export interface FetchResult<T> {
  data: T[];
  hasMore: boolean;
  nextCursor?: string;
  total?: number;
}

export interface NormalizedContent {
  markdown: string;
  headings: HeadingNode[];
  internalLinks: string[];
  externalLinks: string[];
  tags: string[];
  codeBlocks: CodeBlock[];
}

export interface CodeBlock {
  language?: string;
  code: string;
  position: {
    start: number;
    end: number;
  };
}

export interface RetryConfig {
  maxRetries: number;
  delayMs: number;
  backoffFactor: number;
  timeoutMs: number;
}

export const DEFAULT_RETRY_CONFIG: RetryConfig = {
  maxRetries: 3,
  delayMs: 1000,
  backoffFactor: 2,
  timeoutMs: 30000,
};

export const DEFAULT_SYNC_CONFIG: SyncConfig = {
  mode: 'full',
  concurrency: 5,
  maxRetries: 3,
  retryDelayMs: 1000,
  timeoutMs: 30000,
  includeMetadata: true,
};
