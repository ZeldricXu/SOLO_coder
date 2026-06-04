import { PrismaClient, ExternalSource } from '@prisma/client';
import { KnowledgeDocument, SourceType } from '../types';
import { PersistenceAdapter, LocalDocumentInfo } from './types';

const sourceTypeMap: Record<SourceType, ExternalSource> = {
  feishu: 'FEISHU',
  notion: 'NOTION',
  confluence: 'CONFLUENCE',
  github_wiki: 'GITHUB_WIKI',
};

export class PrismaPersistenceAdapter implements PersistenceAdapter {
  private prisma: PrismaClient;
  private systemUserId: string | null = null;

  constructor(prisma: PrismaClient) {
    this.prisma = prisma;
  }

  private async getSystemUserId(): Promise<string> {
    if (this.systemUserId) {
      return this.systemUserId;
    }

    const systemUser = await this.prisma.user.findFirst({
      where: { email: 'system@knowledge-hub.local' },
      select: { id: true },
    });

    if (systemUser) {
      this.systemUserId = systemUser.id;
      return this.systemUserId;
    }

    const created = await this.prisma.user.create({
      data: {
        name: 'System',
        email: 'system@knowledge-hub.local',
        password: 'system',
      },
      select: { id: true },
    });

    this.systemUserId = created.id;
    return created.id;
  }

  private getExternalSource(sourceType: string): ExternalSource {
    if (sourceType in sourceTypeMap) {
      return sourceTypeMap[sourceType as SourceType];
    }
    throw new Error(`Unknown source type: ${sourceType}`);
  }

  private generatePath(doc: KnowledgeDocument): string {
    const slug = doc.title
      .toLowerCase()
      .replace(/[^\w\u4e00-\u9fa5-]/g, '-')
      .replace(/-+/g, '-')
      .replace(/^-|-$/g, '');
    return `/${doc.sourceType}/${slug}`;
  }

  private getSpaceId(doc: KnowledgeDocument, sourceId: string): string {
    return process.env.DEFAULT_SPACE_ID || 'default-space';
  }

  async saveDocument(
    doc: KnowledgeDocument,
    sourceId: string
  ): Promise<{ action: 'created' | 'updated' | 'skipped'; documentId: string }> {
    const externalSource = this.getExternalSource(doc.sourceType);
    const systemUserId = await this.getSystemUserId();
    const spaceId = this.getSpaceId(doc, sourceId);

    const existingDoc = await this.prisma.document.findFirst({
      where: {
        externalId: doc.externalId,
        externalSource,
      },
      select: { id: true, updatedAt: true },
    });

    if (!existingDoc) {
      const created = await this.prisma.document.create({
        data: {
          spaceId,
          title: doc.title,
          content: doc.normalizedContent || doc.content,
          contentHtml: doc.metadata?.contentHtml as string | undefined,
          externalId: doc.externalId,
          externalSource,
          lastSyncedAt: new Date(),
          createdById: systemUserId,
          path: this.generatePath(doc),
          metadata: {
            ...doc.metadata,
            originalContent: doc.content,
            headings: doc.headings,
            internalLinks: doc.internalLinks,
          } as any,
        },
        select: { id: true },
      });

      return { action: 'created', documentId: created.id };
    }

    const existingModified = existingDoc.updatedAt;
    const newModified = doc.lastModifiedAt;

    if (newModified <= existingModified) {
      await this.prisma.document.update({
        where: { id: existingDoc.id },
        data: { lastSyncedAt: new Date() },
      });
      return { action: 'skipped', documentId: existingDoc.id };
    }

    await this.prisma.document.update({
      where: { id: existingDoc.id },
      data: {
        title: doc.title,
        content: doc.normalizedContent || doc.content,
        contentHtml: doc.metadata?.contentHtml as string | undefined,
        lastSyncedAt: new Date(),
        path: this.generatePath(doc),
        metadata: {
          ...doc.metadata,
          originalContent: doc.content,
          headings: doc.headings,
          internalLinks: doc.internalLinks,
        } as any,
      },
    });

    return { action: 'updated', documentId: existingDoc.id };
  }

  async saveDocuments(
    docs: KnowledgeDocument[],
    sourceId: string
  ): Promise<
    Array<{
      doc: KnowledgeDocument;
      action: 'created' | 'updated' | 'skipped';
      documentId: string;
    }>
  > {
    const results: Array<{
      doc: KnowledgeDocument;
      action: 'created' | 'updated' | 'skipped';
      documentId: string;
    }> = [];

    for (const doc of docs) {
      try {
        const result = await this.saveDocument(doc, sourceId);
        results.push({ doc, ...result });
      } catch (error) {
        console.error(`Failed to save document ${doc.externalId}:`, error);
      }
    }

    return results;
  }

  async getLastSync(sourceId: string): Promise<Date | null> {
    const syncConfig = await this.prisma.syncConfig.findFirst({
      where: { id: sourceId },
      select: { lastSyncAt: true },
    });
    return syncConfig?.lastSyncAt || null;
  }

  async setLastSync(sourceId: string, date: Date): Promise<void> {
    await this.prisma.syncConfig.updateMany({
      where: { id: sourceId },
      data: {
        lastSyncAt: date,
        nextSyncAt: new Date(date.getTime() + 60 * 60 * 1000),
      },
    });
  }

  async getLocalDocuments(sourceId: string): Promise<LocalDocumentInfo[]> {
    const syncConfig = await this.prisma.syncConfig.findUnique({
      where: { id: sourceId },
      select: { sourceType: true },
    });

    if (!syncConfig) {
      return [];
    }

    const externalSource = this.getExternalSource(syncConfig.sourceType);

    const documents = await this.prisma.document.findMany({
      where: {
        externalSource,
        externalId: { not: null },
        isArchived: false,
      },
      select: {
        id: true,
        externalId: true,
        lastSyncedAt: true,
        isArchived: true,
      },
    });

    return documents.map(doc => ({
      documentId: doc.id,
      externalId: doc.externalId!,
      lastSyncedAt: doc.lastSyncedAt,
      isArchived: doc.isArchived,
    }));
  }

  async archiveDocument(documentId: string): Promise<boolean> {
    try {
      await this.prisma.document.update({
        where: { id: documentId },
        data: {
          isArchived: true,
          updatedAt: new Date(),
        },
      });
      return true;
    } catch (error) {
      console.error(`Failed to archive document ${documentId}:`, error);
      return false;
    }
  }

  async markForReSync(externalId: string, sourceId: string): Promise<void> {
    const syncConfig = await this.prisma.syncConfig.findUnique({
      where: { id: sourceId },
      select: { sourceType: true },
    });

    if (!syncConfig) return;

    const externalSource = this.getExternalSource(syncConfig.sourceType);

    await this.prisma.document.updateMany({
      where: {
        externalId,
        externalSource,
      },
      data: {
        lastSyncedAt: new Date(0),
        updatedAt: new Date(),
      },
    });
  }
}
