import pLimit from 'p-limit';
import { KnowledgeSource, KnowledgeDocument, SyncConfig, SyncResult, SyncError, DEFAULT_SYNC_CONFIG } from '../types';
import { SyncSource, SyncPipelineHooks, PipelineContext, PipelineStats, PipelineState, PipelineStatus, SyncCursor, FullValidationResult, ExternalDocumentInfo, LocalDocumentInfo } from './types';
import { PersistenceAdapter } from './types';

export class SyncPipeline {
  private persistence: PersistenceAdapter;
  private defaultConfig: SyncConfig;
  private state: PipelineState;
  private activeSources: Map<string, SyncSource> = new Map();

  constructor(
    persistence: PersistenceAdapter,
    defaultConfig?: Partial<SyncConfig>
  ) {
    this.persistence = persistence;
    this.defaultConfig = { ...DEFAULT_SYNC_CONFIG, ...defaultConfig };
    this.state = {
      status: 'idle',
      stats: this.createEmptyStats(),
      currentSourceId: null,
      progress: 0,
      total: 0,
    };
  }

  private createEmptyStats(): PipelineStats {
    return {
      fetched: 0,
      normalized: 0,
      saved: 0,
      created: 0,
      updated: 0,
      skipped: 0,
      failed: 0,
      errors: [],
      currentDocument: null,
      stage: 'idle',
    };
  }

  registerSource(sourceId: string, source: SyncSource): void {
    this.activeSources.set(sourceId, source);
  }

  unregisterSource(sourceId: string): void {
    const source = this.activeSources.get(sourceId);
    if (source?.cleanup) {
      source.cleanup();
    }
    this.activeSources.delete(sourceId);
  }

  getSource(sourceId: string): SyncSource | undefined {
    return this.activeSources.get(sourceId);
  }

  async execute(
    knowledgeSource: KnowledgeSource,
    syncSource: SyncSource,
    config: Partial<SyncConfig> = {},
    hooks: SyncPipelineHooks = {}
  ): Promise<SyncResult> {
    const syncConfig: SyncConfig = { ...this.defaultConfig, ...config };
    const startedAt = new Date();
    const cursor = await syncSource.getCursor(knowledgeSource.id);

    const context: PipelineContext = {
      source: knowledgeSource,
      config: syncConfig,
      hooks,
      stats: this.createEmptyStats(),
      startedAt,
      cursor,
      metadata: {},
    };

    this.state = {
      status: 'running',
      stats: context.stats,
      currentSourceId: knowledgeSource.id,
      progress: 0,
      total: 0,
      startedAt,
    };

    try {
      const result = await this.runPipeline(syncSource, context, hooks);
      await this.finalizeSync(syncSource, knowledgeSource.id, context);
      
      if (hooks.onSyncComplete) {
        await hooks.onSyncComplete(result);
      }

      this.state.status = 'completed';
      this.state.endedAt = result.endedAt;
      return result;
    } catch (error) {
      const errorResult = this.createErrorResult(knowledgeSource, syncConfig, startedAt, error);
      this.state.status = 'failed';
      this.state.endedAt = errorResult.endedAt;
      return errorResult;
    }
  }

  private async runPipeline(
    syncSource: SyncSource,
    context: PipelineContext,
    hooks: SyncPipelineHooks
  ): Promise<SyncResult> {
    let hasMore = true;
    let total = 0;
    const limit = pLimit(context.config.concurrency);

    while (hasMore) {
      context.stats.stage = 'fetching';
      
      let fetchResult;
      if (context.config.mode === 'incremental' && context.cursor?.lastModifiedAt) {
        fetchResult = await syncSource.fetchIncremental(
          context.cursor.lastModifiedAt,
          { limit: 100 }
        );
      } else {
        fetchResult = await syncSource.fetch({ limit: 100 });
      }

      hasMore = fetchResult.hasMore;
      total = fetchResult.total || total + fetchResult.data.length;
      this.state.total = total;
      context.stats.fetched += fetchResult.data.length;

      if (fetchResult.data.length === 0) {
        break;
      }

      for (const doc of fetchResult.data) {
        if (hooks.onDocumentFetched) {
          await hooks.onDocumentFetched(doc);
        }
      }

      context.stats.stage = 'normalizing';
      const normalizedDocs: KnowledgeDocument[] = [];
      
      for (const doc of fetchResult.data) {
        try {
          const normalized = await syncSource.normalize(doc);
          normalizedDocs.push(normalized);
          context.stats.normalized++;
          
          if (hooks.onDocumentNormalized) {
            await hooks.onDocumentNormalized(normalized);
          }
        } catch (error) {
          this.handleDocumentError(doc, error, context, hooks);
        }
      }

      context.stats.stage = 'saving';
      const batchResults = await this.persistence.saveDocuments(
        normalizedDocs,
        context.source.id
      );

      const documentIds: string[] = [];
      for (const result of batchResults) {
        context.stats.saved++;
        documentIds.push(result.documentId);
        
        if (result.action === 'created') {
          context.stats.created++;
        } else if (result.action === 'updated') {
          context.stats.updated++;
        } else {
          context.stats.skipped++;
        }

        if (hooks.onDocumentSaved) {
          await hooks.onDocumentSaved(
            result.doc,
            result.action as 'created' | 'updated' | 'skipped'
          );
        }
      }

      if (hooks.onBatchComplete) {
        await hooks.onBatchComplete(normalizedDocs, batchResults);
      }

      if (documentIds.length > 0) {
        if (hooks.onSearchIndexUpdate) {
          await hooks.onSearchIndexUpdate(documentIds);
        }
        if (hooks.onKnowledgeGraphUpdate) {
          await hooks.onKnowledgeGraphUpdate(documentIds);
        }
      }

      this.state.progress = context.stats.saved;

      if (!hasMore) break;
    }

    const endedAt = new Date();
    const status = this.calculateStatus(context.stats);

    return {
      sourceId: context.source.id,
      sourceType: context.source.type,
      status,
      mode: context.config.mode,
      total,
      created: context.stats.created,
      updated: context.stats.updated,
      failed: context.stats.failed,
      skipped: context.stats.skipped,
      errors: context.stats.errors,
      startedAt: context.startedAt,
      endedAt,
      durationMs: endedAt.getTime() - context.startedAt.getTime(),
    };
  }

  private handleDocumentError(
    doc: KnowledgeDocument,
    error: unknown,
    context: PipelineContext,
    hooks: SyncPipelineHooks
  ): void {
    const syncError: SyncError = {
      externalId: doc.externalId,
      message: error instanceof Error ? error.message : 'Unknown error',
      code: 'SYNC_ERROR',
      details: { error: String(error) },
      timestamp: new Date(),
    };

    context.stats.failed++;
    context.stats.errors.push(syncError);

    if (hooks.onError) {
      hooks.onError(syncError, doc);
    }

    if (hooks.onDocumentSaved) {
      hooks.onDocumentSaved(doc, 'failed');
    }
  }

  private calculateStatus(stats: PipelineStats): SyncResult['status'] {
    if (stats.failed > 0 && (stats.created + stats.updated) > 0) {
      return 'partial';
    }
    if (stats.failed > 0 && stats.created + stats.updated === 0) {
      return 'failed';
    }
    return 'success';
  }

  private async finalizeSync(
    syncSource: SyncSource,
    sourceId: string,
    context: PipelineContext
  ): Promise<void> {
    const cursor: SyncCursor = {
      lastSyncAt: new Date(),
      lastModifiedAt: context.cursor?.lastModifiedAt,
      metadata: context.metadata,
    };
    await syncSource.setCursor(sourceId, cursor);
    await this.persistence.setLastSync(sourceId, cursor.lastSyncAt);
  }

  private createErrorResult(
    knowledgeSource: KnowledgeSource,
    config: SyncConfig,
    startedAt: Date,
    error: unknown
  ): SyncResult {
    const endedAt = new Date();
    return {
      sourceId: knowledgeSource.id,
      sourceType: knowledgeSource.type,
      status: 'failed',
      mode: config.mode,
      total: 0,
      created: 0,
      updated: 0,
      failed: 0,
      skipped: 0,
      errors: [
        {
          message: error instanceof Error ? error.message : 'Unknown error',
          code: 'SYNC_FAILED',
          details: { error: String(error) },
          timestamp: endedAt,
        },
      ],
      startedAt,
      endedAt,
      durationMs: endedAt.getTime() - startedAt.getTime(),
    };
  }

  async runFullValidation(
    knowledgeSource: KnowledgeSource,
    syncSource: SyncSource,
    hooks: SyncPipelineHooks = {}
  ): Promise<FullValidationResult> {
    const startedAt = new Date();
    const result: FullValidationResult = {
      sourceId: knowledgeSource.id,
      startedAt,
      endedAt: new Date(),
      totalExternal: 0,
      totalLocal: 0,
      toArchive: [],
      toFetch: [],
      archived: 0,
      fetched: 0,
      failed: 0,
      errors: [],
    };

    try {
      const [externalDocs, localDocs] = await Promise.all([
        this.fetchAllExternalDocuments(syncSource),
        this.persistence.getLocalDocuments(knowledgeSource.id),
      ]);

      result.totalExternal = externalDocs.length;
      result.totalLocal = localDocs.length;

      const externalIdSet = new Set(externalDocs.map(d => d.externalId));
      const localIdMap = new Map(localDocs.map(d => [d.externalId, d]));

      for (const localDoc of localDocs) {
        if (!externalIdSet.has(localDoc.externalId) && !localDoc.isArchived) {
          result.toArchive.push(localDoc.externalId);
        }
      }

      for (const externalDoc of externalDocs) {
        if (externalDoc.isDeleted) {
          const localDoc = localIdMap.get(externalDoc.externalId);
          if (localDoc && !localDoc.isArchived) {
            result.toArchive.push(externalDoc.externalId);
          }
        } else {
          const localDoc = localIdMap.get(externalDoc.externalId);
          if (!localDoc) {
            result.toFetch.push(externalDoc.externalId);
          }
        }
      }

      const limit = pLimit(3);

      const archivePromises = result.toArchive.map(externalId =>
        limit(async () => {
          try {
            const localDoc = localIdMap.get(externalId);
            if (localDoc) {
              const success = await this.persistence.archiveDocument(localDoc.documentId);
              if (success) {
                result.archived++;
                if (hooks.onDocumentSaved) {
                  const doc: KnowledgeDocument = {
                    externalId,
                    sourceId: knowledgeSource.id,
                    title: '',
                    content: '',
                    sourceType: knowledgeSource.type,
                    metadata: {},
                    tags: [],
                    internalLinks: [],
                    headings: [],
                    lastModifiedAt: new Date(),
                  };
                  await hooks.onDocumentSaved(doc, 'updated');
                }
              } else {
                result.failed++;
                result.errors.push(`Failed to archive document: ${externalId}`);
              }
            }
          } catch (error) {
            result.failed++;
            result.errors.push(`Error archiving ${externalId}: ${error}`);
          }
        })
      );

      const fetchPromises = result.toFetch.map(externalId =>
        limit(async () => {
          try {
            await this.persistence.markForReSync(externalId, knowledgeSource.id);
            result.fetched++;
          } catch (error) {
            result.failed++;
            result.errors.push(`Error marking for re-sync ${externalId}: ${error}`);
          }
        })
      );

      await Promise.all([...archivePromises, ...fetchPromises]);

      const cursor = await syncSource.getCursor(knowledgeSource.id);
      await syncSource.setCursor(knowledgeSource.id, {
        ...cursor,
        lastFullValidationAt: new Date(),
        lastSyncAt: cursor?.lastSyncAt || new Date(),
      });

      if (result.toFetch.length > 0 && hooks.onSearchIndexUpdate) {
        const localDocsToReindex = result.toFetch
          .map(id => localIdMap.get(id))
          .filter(Boolean) as LocalDocumentInfo[];
        if (localDocsToReindex.length > 0) {
          await hooks.onSearchIndexUpdate(localDocsToReindex.map(d => d.documentId));
        }
      }
    } catch (error) {
      result.errors.push(`Validation failed: ${error}`);
    }

    result.endedAt = new Date();
    return result;
  }

  private async fetchAllExternalDocuments(syncSource: SyncSource): Promise<ExternalDocumentInfo[]> {
    const allDocs: ExternalDocumentInfo[] = [];
    let hasMore = true;
    let offset = 0;

    while (hasMore) {
      const result = await syncSource.fetchDocumentList({ limit: 200, offset });
      allDocs.push(...result.data);
      hasMore = result.hasMore;
      offset += 200;

      if (allDocs.length > 10000) {
        break;
      }
    }

    return allDocs;
  }

  async shouldRunFullValidation(sourceId: string, syncSource: SyncSource): Promise<boolean> {
    const cursor = await syncSource.getCursor(sourceId);
    if (!cursor?.lastFullValidationAt) {
      return true;
    }

    const now = new Date();
    const lastValidation = cursor.lastFullValidationAt;
    const diffHours = (now.getTime() - lastValidation.getTime()) / (1000 * 60 * 60);

    return diffHours >= 24;
  }

  getState(): PipelineState {
    return { ...this.state };
  }

  dispose(): void {
    for (const [sourceId, source] of this.activeSources) {
      if (source.cleanup) {
        source.cleanup();
      }
    }
    this.activeSources.clear();
  }
}
