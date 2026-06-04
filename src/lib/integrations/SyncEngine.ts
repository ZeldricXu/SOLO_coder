import { PrismaClient, ExternalSource, SyncStatus as PrismaSyncStatus } from '@prisma/client';
import pLimit from 'p-limit';
import {
  KnowledgeSource,
  KnowledgeDocument,
  SyncResult,
  SyncConfig,
  SyncError,
  DEFAULT_SYNC_CONFIG,
  SourceType,
  SyncMode,
} from './types';
import { BaseSource } from './BaseSource';
import { FeishuSource } from './FeishuSource';
import { NotionSource } from './NotionSource';
import { ConfluenceSource } from './ConfluenceSource';
import { GithubWikiSource } from './GithubWikiSource';

interface SyncEngineOptions {
  prisma?: PrismaClient;
  defaultConfig?: Partial<SyncConfig>;
  onDocumentSync?: (doc: KnowledgeDocument, action: 'created' | 'updated' | 'skipped' | 'failed') => void;
  onSyncComplete?: (result: SyncResult) => void;
  onSearchIndexUpdate?: (documentIds: string[]) => Promise<void>;
  onKnowledgeGraphUpdate?: (documentIds: string[]) => Promise<void>;
  onOcrProcessing?: (documentId: string, content: string, contentHtml?: string) => Promise<void>;
}

interface SyncStats {
  created: number;
  updated: number;
  failed: number;
  skipped: number;
  errors: SyncError[];
}

const sourceTypeMap: Record<SourceType, ExternalSource> = {
  feishu: 'FEISHU',
  notion: 'NOTION',
  confluence: 'CONFLUENCE',
  github_wiki: 'GITHUB_WIKI',
};

export class SyncEngine {
  private readonly prisma: PrismaClient;
  private readonly defaultConfig: SyncConfig;
  private readonly options: SyncEngineOptions;
  private readonly sources: Map<string, BaseSource> = new Map();

  constructor(options: SyncEngineOptions = {}) {
    this.prisma = options.prisma || new PrismaClient();
    this.defaultConfig = { ...DEFAULT_SYNC_CONFIG, ...options.defaultConfig };
    this.options = options;
  }

  private createSource(knowledgeSource: KnowledgeSource): BaseSource {
    const existing = this.sources.get(knowledgeSource.id);
    if (existing) {
      return existing;
    }

    let source: BaseSource;
    switch (knowledgeSource.type) {
      case 'feishu':
        source = new FeishuSource(knowledgeSource);
        break;
      case 'notion':
        source = new NotionSource(knowledgeSource);
        break;
      case 'confluence':
        source = new ConfluenceSource(knowledgeSource);
        break;
      case 'github_wiki':
        source = new GithubWikiSource(knowledgeSource);
        break;
      default:
        throw new Error(`Unsupported source type: ${knowledgeSource.type}`);
    }

    this.sources.set(knowledgeSource.id, source);
    return source;
  }

  async syncSource(
    knowledgeSource: KnowledgeSource,
    config: Partial<SyncConfig> = {}
  ): Promise<SyncResult> {
    const syncConfig: SyncConfig = { ...this.defaultConfig, ...config };
    const startedAt = new Date();
    const source = this.createSource(knowledgeSource);

    const syncConfigId = await this.ensureSyncConfigRecord(knowledgeSource);
    const syncLogId = await this.createSyncLog(syncConfigId);

    let result: SyncResult | null = null;

    try {
      result = await this.performSync(knowledgeSource, source, syncConfig);
      await this.updateSyncLog(syncLogId, result);
      await this.updateSyncConfigLastSync(knowledgeSource.id, result);

      if (this.options.onSyncComplete) {
        this.options.onSyncComplete(result);
      }

      const updatedDocIds = await this.getSyncedDocumentIds(knowledgeSource.id);
      if (updatedDocIds.length > 0) {
        if (this.options.onSearchIndexUpdate) {
          await this.options.onSearchIndexUpdate(updatedDocIds);
        }
        if (this.options.onKnowledgeGraphUpdate) {
          await this.options.onKnowledgeGraphUpdate(updatedDocIds);
        }
      }

      return result;
    } catch (error) {
      const errorResult = this.createErrorResult(knowledgeSource, syncConfig, startedAt, error);
      await this.updateSyncLog(syncLogId, errorResult);
      return errorResult;
    }
  }

  async syncMultipleSources(
    knowledgeSources: KnowledgeSource[],
    config: Partial<SyncConfig> = {}
  ): Promise<SyncResult[]> {
    const results: SyncResult[] = [];
    const concurrency = config.concurrency || this.defaultConfig.concurrency;
    const limit = pLimit(concurrency);

    const promises = knowledgeSources.map((source) =>
      limit(() => this.syncSource(source, config))
    );

    const settledResults = await Promise.allSettled(promises);

    for (const settled of settledResults) {
      if (settled.status === 'fulfilled') {
        results.push(settled.value);
      } else {
        const failedSource = knowledgeSources[results.length];
        const errorResult = this.createErrorResult(
          failedSource,
          { ...this.defaultConfig, ...config },
          new Date(),
          settled.reason
        );
        results.push(errorResult);
      }
    }

    return results;
  }

  async syncSingleDocument(
    knowledgeSource: KnowledgeSource,
    externalId: string,
    config: Partial<SyncConfig> = {}
  ): Promise<KnowledgeDocument | null> {
    const source = this.createSource(knowledgeSource);
    const syncConfig: SyncConfig = { ...this.defaultConfig, ...config };

    try {
      const doc = await this.withRetry(
        () => source.fetchSingleDocument(externalId),
        syncConfig.maxRetries,
        syncConfig.retryDelayMs
      );

      if (!doc) {
        return null;
      }

      const savedDoc = await this.upsertDocument(knowledgeSource, doc);

      if (this.options.onDocumentSync) {
        this.options.onDocumentSync(
          doc,
          savedDoc.action === 'create' ? 'created' : 'updated'
        );
      }

      return doc;
    } catch (error) {
      console.error(`Failed to sync document ${externalId}:`, error);
      return null;
    }
  }

  private async performSync(
    knowledgeSource: KnowledgeSource,
    source: BaseSource,
    config: SyncConfig
  ): Promise<SyncResult> {
    const startedAt = new Date();
    const stats: SyncStats = {
      created: 0,
      updated: 0,
      failed: 0,
      skipped: 0,
      errors: [],
    };

    let hasMore = true;
    let cursor: string | undefined;
    let total = 0;
    const limit = pLimit(config.concurrency);

    while (hasMore) {
      let fetchResult;

      if (config.mode === 'incremental' && config.since) {
        fetchResult = await source.fetchIncremental(config.since, {
          limit: 100,
        });
      } else {
        fetchResult = await source.fetchDocuments({
          limit: 100,
        });
      }

      hasMore = fetchResult.hasMore;
      cursor = fetchResult.nextCursor;
      total = fetchResult.total || total + fetchResult.data.length;

      const processPromises = fetchResult.data.map((doc) =>
        limit(async () => {
          try {
            const result = await this.processDocument(knowledgeSource, doc);

            if (result.action === 'create') {
              stats.created++;
            } else if (result.action === 'update') {
              stats.updated++;
            } else {
              stats.skipped++;
            }

            if (this.options.onDocumentSync) {
              this.options.onDocumentSync(
                doc,
                result.action === 'create'
                  ? 'created'
                  : result.action === 'update'
                  ? 'updated'
                  : 'skipped'
              );
            }
          } catch (error) {
            stats.failed++;
            stats.errors.push({
              externalId: doc.externalId,
              message: error instanceof Error ? error.message : 'Unknown error',
              code: 'SYNC_ERROR',
              details: { error: String(error) },
              timestamp: new Date(),
            });

            if (this.options.onDocumentSync) {
              this.options.onDocumentSync(doc, 'failed');
            }
          }
        })
      );

      await Promise.all(processPromises);

      if (!hasMore) break;
    }

    const endedAt = new Date();
    const status = this.calculateSyncStatus(stats, total);

    return {
      sourceId: knowledgeSource.id,
      sourceType: knowledgeSource.type,
      status,
      mode: config.mode,
      total,
      created: stats.created,
      updated: stats.updated,
      failed: stats.failed,
      skipped: stats.skipped,
      errors: stats.errors,
      startedAt,
      endedAt,
      durationMs: endedAt.getTime() - startedAt.getTime(),
    };
  }

  private async processDocument(
    knowledgeSource: KnowledgeSource,
    doc: KnowledgeDocument
  ): Promise<{ action: 'create' | 'update' | 'skip' }> {
    const existingDoc = await this.prisma.document.findFirst({
      where: {
        externalId: doc.externalId,
        externalSource: sourceTypeMap[knowledgeSource.type],
      },
    });

    if (!existingDoc) {
      await this.createDocument(knowledgeSource, doc);
      return { action: 'create' };
    }

    const existingModified = existingDoc.updatedAt;
    const newModified = doc.lastModifiedAt;

    if (newModified <= existingModified) {
      await this.prisma.document.update({
        where: { id: existingDoc.id },
        data: { lastSyncedAt: new Date() },
      });
      return { action: 'skip' };
    }

    await this.updateDocument(knowledgeSource, doc, existingDoc.id);
    return { action: 'update' };
  }

  private async upsertDocument(
    knowledgeSource: KnowledgeSource,
    doc: KnowledgeDocument
  ): Promise<{ action: 'create' | 'update'; id: string }> {
    const existingDoc = await this.prisma.document.findFirst({
      where: {
        externalId: doc.externalId,
        externalSource: sourceTypeMap[knowledgeSource.type],
      },
    });

    if (!existingDoc) {
      const created = await this.createDocument(knowledgeSource, doc);
      return { action: 'create', id: created.id };
    }

    const updated = await this.updateDocument(knowledgeSource, doc, existingDoc.id);
    return { action: 'update', id: updated.id };
  }

  private async createDocument(
    knowledgeSource: KnowledgeSource,
    doc: KnowledgeDocument
  ) {
    const systemUserId = await this.getSystemUserId();

    const document = await this.prisma.document.create({
      data: {
        spaceId: knowledgeSource.config.spaceId as string || this.getDefaultSpaceId(),
        title: doc.title,
        content: doc.normalizedContent || doc.content,
        contentHtml: doc.contentHtml,
        externalId: doc.externalId,
        externalSource: sourceTypeMap[knowledgeSource.type],
        lastSyncedAt: new Date(),
        createdById: systemUserId,
        path: this.generatePath(doc),
        metadata: {
          ...doc.metadata,
          originalContent: doc.content,
          headings: doc.headings,
          internalLinks: doc.internalLinks,
        },
      },
    });

    if (this.options.onOcrProcessing && (doc.normalizedContent || doc.content)) {
      this.options
        .onOcrProcessing(document.id, doc.normalizedContent || doc.content, doc.contentHtml)
        .catch((err) => console.error(`OCR processing failed for doc ${document.id}:`, err));
    }

    return document;
  }

  private async updateDocument(
    knowledgeSource: KnowledgeSource,
    doc: KnowledgeDocument,
    documentId: string
  ) {
    const document = await this.prisma.document.update({
      where: { id: documentId },
      data: {
        title: doc.title,
        content: doc.normalizedContent || doc.content,
        contentHtml: doc.contentHtml,
        lastSyncedAt: new Date(),
        path: this.generatePath(doc),
        metadata: {
          ...doc.metadata,
          originalContent: doc.content,
          headings: doc.headings,
          internalLinks: doc.internalLinks,
        },
      },
    });

    if (this.options.onOcrProcessing && (doc.normalizedContent || doc.content)) {
      this.options
        .onOcrProcessing(documentId, doc.normalizedContent || doc.content, doc.contentHtml)
        .catch((err) => console.error(`OCR processing failed for doc ${documentId}:`, err));
    }

    return document;
  }

  private async ensureSyncConfigRecord(
    knowledgeSource: KnowledgeSource
  ): Promise<string> {
    const existing = await this.prisma.syncConfig.findFirst({
      where: {
        spaceId: knowledgeSource.config.spaceId as string || this.getDefaultSpaceId(),
        sourceType: sourceTypeMap[knowledgeSource.type],
      },
    });

    if (existing) {
      return existing.id;
    }

    const created = await this.prisma.syncConfig.create({
      data: {
        spaceId: knowledgeSource.config.spaceId as string || this.getDefaultSpaceId(),
        sourceType: sourceTypeMap[knowledgeSource.type],
        config: knowledgeSource.config,
        isEnabled: knowledgeSource.enabled,
      },
    });

    return created.id;
  }

  private async createSyncLog(syncConfigId: string): Promise<string> {
    const log = await this.prisma.syncLog.create({
      data: {
        syncConfigId,
        status: 'SUCCESS',
        startedAt: new Date(),
      },
    });
    return log.id;
  }

  private async updateSyncLog(syncLogId: string, result: SyncResult): Promise<void> {
    const prismaStatus = this.mapSyncStatusToPrisma(result.status);

    await this.prisma.syncLog.update({
      where: { id: syncLogId },
      data: {
        status: prismaStatus,
        recordsSynced: result.created + result.updated,
        recordsFailed: result.failed,
        errorMessage: result.errors.length > 0
          ? result.errors.map((e) => `${e.code}: ${e.message}`).join('; ')
          : null,
        completedAt: result.endedAt,
      },
    });
  }

  private async updateSyncConfigLastSync(
    sourceId: string,
    result: SyncResult
  ): Promise<void> {
    await this.prisma.syncConfig.updateMany({
      where: {
        sourceType: sourceTypeMap[result.sourceType],
      },
      data: {
        lastSyncAt: result.endedAt,
        nextSyncAt: this.calculateNextSync(),
      },
    });
  }

  private async getSyncedDocumentIds(sourceId: string): Promise<string[]> {
    const docs = await this.prisma.document.findMany({
      where: {
        externalId: { not: null },
        lastSyncedAt: {
          gte: new Date(Date.now() - 24 * 60 * 60 * 1000),
        },
      },
      select: { id: true },
    });
    return docs.map((d) => d.id);
  }

  private mapSyncStatusToPrisma(status: string): PrismaSyncStatus {
    switch (status) {
      case 'success':
        return 'SUCCESS';
      case 'failed':
        return 'FAILED';
      case 'partial':
        return 'PARTIAL';
      default:
        return 'FAILED';
    }
  }

  private calculateSyncStatus(stats: SyncStats, total: number): SyncResult['status'] {
    if (stats.failed > 0 && (stats.created + stats.updated) > 0) {
      return 'partial';
    }
    if (stats.failed > 0 && stats.created + stats.updated === 0) {
      return 'failed';
    }
    if (total === 0 && stats.errors.length === 0) {
      return 'success';
    }
    return 'success';
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

  private async withRetry<T>(
    operation: () => Promise<T>,
    maxRetries: number,
    delayMs: number
  ): Promise<T> {
    let lastError: Error | null = null;

    for (let attempt = 1; attempt <= maxRetries; attempt++) {
      try {
        return await operation();
      } catch (error) {
        lastError = error as Error;
        if (attempt < maxRetries) {
          await this.delay(delayMs * Math.pow(2, attempt - 1));
        }
      }
    }

    throw lastError || new Error('Operation failed after retries');
  }

  private delay(ms: number): Promise<void> {
    return new Promise((resolve) => setTimeout(resolve, ms));
  }

  private generatePath(doc: KnowledgeDocument): string {
    const slug = doc.title
      .toLowerCase()
      .replace(/[^\w\u4e00-\u9fa5-]/g, '-')
      .replace(/-+/g, '-')
      .replace(/^-|-$/g, '');
    return `/${doc.sourceType}/${slug}`;
  }

  private getDefaultSpaceId(): string {
    return process.env.DEFAULT_SPACE_ID || 'default-space';
  }

  private async getSystemUserId(): Promise<string> {
    const systemUser = await this.prisma.user.findFirst({
      where: { email: 'system@knowledge-hub.local' },
      select: { id: true },
    });

    if (systemUser) {
      return systemUser.id;
    }

    const created = await this.prisma.user.create({
      data: {
        name: 'System',
        email: 'system@knowledge-hub.local',
        passwordHash: 'system',
      },
      select: { id: true },
    });

    return created.id;
  }

  private calculateNextSync(): Date {
    const now = new Date();
    now.setHours(now.getHours() + 1);
    return now;
  }

  async testConnection(knowledgeSource: KnowledgeSource): Promise<{ success: boolean; error?: string }> {
    try {
      const source = this.createSource(knowledgeSource);
      return await source.testConnection();
    } catch (error) {
      return {
        success: false,
        error: error instanceof Error ? error.message : 'Unknown error',
      };
    }
  }

  dispose(): void {
    for (const source of this.sources.values()) {
      if (source instanceof GithubWikiSource) {
        source.cleanup();
      }
    }
    this.sources.clear();
  }
}
