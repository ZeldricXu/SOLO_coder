import { PrismaClient } from '@prisma/client';
import { KnowledgeDocument, FetchOptions, FetchResult, HeadingNode } from '../types';
import { BaseSource } from '../BaseSource';
import { SyncSource, SyncCursor, ExternalDocumentInfo } from './types';

function convertHeadings(
  headings: Array<{ level: number; text: string; id?: string; children: unknown[] }>
): HeadingNode[] {
  return headings.map((h) => ({
    level: h.level,
    text: h.text,
    id: h.id,
    children: convertHeadings(h.children as Array<{ level: number; text: string; id?: string; children: unknown[] }>),
  }));
}

export class BaseSourceAdapter implements SyncSource {
  private source: BaseSource;
  private prisma: PrismaClient;
  private cursorCache: Map<string, SyncCursor> = new Map();

  constructor(source: BaseSource, prisma: PrismaClient) {
    this.source = source;
    this.prisma = prisma;
  }

  get sourceType(): string {
    return this.source.sourceType;
  }

  async fetch(options?: FetchOptions): Promise<FetchResult<KnowledgeDocument>> {
    return this.source.fetchDocuments(options);
  }

  async fetchIncremental(
    since: Date,
    options?: FetchOptions
  ): Promise<FetchResult<KnowledgeDocument>> {
    return this.source.fetchIncremental(since, options);
  }

  async fetchSingle(externalId: string): Promise<KnowledgeDocument | null> {
    return this.source.fetchSingleDocument(externalId);
  }

  async fetchDocumentList(options?: FetchOptions): Promise<FetchResult<ExternalDocumentInfo>> {
    const result = await this.source.fetchDocuments(options);
    
    const documents: ExternalDocumentInfo[] = result.data.map((doc) => ({
      externalId: doc.externalId,
      title: doc.title,
      lastModifiedAt: doc.lastModifiedAt || new Date(),
      isDeleted: doc.metadata?.isDeleted as boolean | undefined,
    }));

    return {
      data: documents,
      total: result.total,
      hasMore: result.hasMore,
      nextCursor: result.nextCursor,
    };
  }

  async normalize(document: KnowledgeDocument): Promise<KnowledgeDocument> {
    try {
      const normalized = await this.source.normalizeContent(
        document.content,
        document.metadata
      );
      return {
        ...document,
        normalizedContent: normalized.markdown,
        headings: convertHeadings(normalized.headings),
        internalLinks: normalized.internalLinks,
      };
    } catch (error) {
      console.warn(`Normalization failed for ${document.externalId}:`, error);
      return document;
    }
  }

  async getCursor(sourceId: string): Promise<SyncCursor | null> {
    if (this.cursorCache.has(sourceId)) {
      return this.cursorCache.get(sourceId)!;
    }

    const syncConfig = await this.prisma.syncConfig.findFirst({
      where: { id: sourceId },
      select: {
        lastSyncAt: true,
        cursor: true,
      },
    });

    if (!syncConfig) {
      return null;
    }

    const cursor: SyncCursor = {
      lastSyncAt: syncConfig.lastSyncAt || new Date(0),
      metadata: syncConfig.cursor as Record<string, unknown> | undefined,
    };

    this.cursorCache.set(sourceId, cursor);
    return cursor;
  }

  async setCursor(sourceId: string, cursor: SyncCursor): Promise<void> {
    this.cursorCache.set(sourceId, cursor);

    await this.prisma.syncConfig.updateMany({
      where: { id: sourceId },
      data: {
        lastSyncAt: cursor.lastSyncAt,
        cursor: (cursor.metadata as any) || null,
      },
    });
  }

  async validateConfig(): Promise<boolean> {
    return this.source.validateConfig();
  }

  async testConnection(): Promise<{ success: boolean; error?: string }> {
    return this.source.testConnection();
  }

  cleanup(): void {
    this.cursorCache.clear();
    if ('cleanup' in this.source && typeof (this.source as any).cleanup === 'function') {
      (this.source as any).cleanup();
    }
  }
}
