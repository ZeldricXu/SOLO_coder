import { KnowledgeDocument, KnowledgeSource, SyncConfig, SyncResult, FetchOptions, FetchResult, SyncError } from '../types';

export interface SyncCursor {
  lastModifiedAt?: Date;
  lastSyncAt: Date;
  externalIds?: string[];
  lastFullValidationAt?: Date;
  metadata?: Record<string, unknown>;
}

export interface FullValidationResult {
  sourceId: string;
  startedAt: Date;
  endedAt: Date;
  totalExternal: number;
  totalLocal: number;
  toArchive: string[];
  toFetch: string[];
  archived: number;
  fetched: number;
  failed: number;
  errors: string[];
}

export interface LocalDocumentInfo {
  externalId: string;
  documentId: string;
  lastSyncedAt: Date | null;
  isArchived: boolean;
}

export interface ExternalDocumentInfo {
  externalId: string;
  title: string;
  lastModifiedAt: Date;
  isDeleted?: boolean;
}

export interface SyncSource {
  readonly sourceType: string;

  fetch(options?: FetchOptions): Promise<FetchResult<KnowledgeDocument>>;

  fetchIncremental(
    since: Date,
    options?: FetchOptions
  ): Promise<FetchResult<KnowledgeDocument>>;

  fetchSingle(externalId: string): Promise<KnowledgeDocument | null>;

  fetchDocumentList(options?: FetchOptions): Promise<FetchResult<ExternalDocumentInfo>>;

  normalize(document: KnowledgeDocument): Promise<KnowledgeDocument>;

  getCursor(sourceId: string): Promise<SyncCursor | null>;

  setCursor(sourceId: string, cursor: SyncCursor): Promise<void>;

  validateConfig(): Promise<boolean>;

  testConnection(): Promise<{ success: boolean; error?: string }>;

  cleanup?(): void;
}

export interface SyncPipelineHooks {
  onDocumentFetched?: (doc: KnowledgeDocument) => void | Promise<void>;
  onDocumentNormalized?: (doc: KnowledgeDocument) => void | Promise<void>;
  onDocumentSaved?: (
    doc: KnowledgeDocument,
    action: 'created' | 'updated' | 'skipped' | 'failed'
  ) => void | Promise<void>;
  onBatchComplete?: (
    batch: KnowledgeDocument[],
    results: Array<{ doc: KnowledgeDocument; action: string }>
  ) => void | Promise<void>;
  onSearchIndexUpdate?: (documentIds: string[]) => void | Promise<void>;
  onKnowledgeGraphUpdate?: (documentIds: string[]) => void | Promise<void>;
  onSyncComplete?: (result: SyncResult) => void | Promise<void>;
  onError?: (error: SyncError, doc?: KnowledgeDocument) => void;
}

export interface PipelineStage {
  name: string;
  execute: (
    input: unknown,
    context: PipelineContext
  ) => Promise<unknown>;
}

export interface PipelineContext {
  source: KnowledgeSource;
  config: SyncConfig;
  hooks: SyncPipelineHooks;
  stats: PipelineStats;
  startedAt: Date;
  cursor: SyncCursor | null;
  metadata: Record<string, unknown>;
}

export interface PipelineStats {
  fetched: number;
  normalized: number;
  saved: number;
  created: number;
  updated: number;
  skipped: number;
  failed: number;
  errors: SyncError[];
  currentDocument: string | null;
  stage: string;
}

export type PipelineStatus = 'idle' | 'running' | 'paused' | 'completed' | 'failed';

export interface PipelineState {
  status: PipelineStatus;
  stats: PipelineStats;
  currentSourceId: string | null;
  progress: number;
  total: number;
  startedAt?: Date;
  endedAt?: Date;
}

export interface PersistenceAdapter {
  saveDocument(doc: KnowledgeDocument, sourceId: string): Promise<{
    action: 'created' | 'updated' | 'skipped';
    documentId: string;
  }>;

  saveDocuments(
    docs: KnowledgeDocument[],
    sourceId: string
  ): Promise<
    Array<{ doc: KnowledgeDocument; action: 'created' | 'updated' | 'skipped'; documentId: string }>
  >;

  getLastSync(sourceId: string): Promise<Date | null>;

  setLastSync(sourceId: string, date: Date): Promise<void>;

  getLocalDocuments(sourceId: string): Promise<LocalDocumentInfo[]>;

  archiveDocument(documentId: string): Promise<boolean>;

  markForReSync(externalId: string, sourceId: string): Promise<void>;
}
