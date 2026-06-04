import { PrismaClient, DocumentViewLog } from '@prisma/client';

interface LogViewOptions {
  userId: string;
  documentId: string;
  spaceId: string;
  durationMs?: number;
  ipAddress?: string;
  userAgent?: string;
}

interface CooccurrenceResult {
  documentId: string;
  cooccurrenceCount: number;
  confidence: number;
}

export class ViewLogService {
  private prisma: PrismaClient;

  constructor(prisma: PrismaClient) {
    this.prisma = prisma;
  }

  async logView(options: LogViewOptions): Promise<DocumentViewLog> {
    return this.prisma.documentViewLog.create({
      data: {
        userId: options.userId,
        documentId: options.documentId,
        spaceId: options.spaceId,
        durationMs: options.durationMs,
        ipAddress: options.ipAddress,
        userAgent: options.userAgent,
      },
    });
  }

  async getViewCount(documentId: string, days: number = 30): Promise<number> {
    const since = new Date();
    since.setDate(since.getDate() - days);

    return this.prisma.documentViewLog.count({
      where: {
        documentId,
        viewedAt: { gte: since },
      },
    });
  }

  async getUserViewedDocuments(
    userId: string,
    limit: number = 50,
    days: number = 90
  ): Promise<string[]> {
    const since = new Date();
    since.setDate(since.getDate() - days);

    const logs = await this.prisma.documentViewLog.findMany({
      where: {
        userId,
        viewedAt: { gte: since },
      },
      select: { documentId: true },
      distinct: ['documentId'],
      orderBy: { viewedAt: 'desc' },
      take: limit,
    });

    return logs.map((log) => log.documentId);
  }

  async getCooccurringDocuments(
    sourceDocumentId: string,
    limit: number = 10,
    days: number = 90,
    excludeDocumentIds: string[] = []
  ): Promise<CooccurrenceResult[]> {
    const since = new Date();
    since.setDate(since.getDate() - days);

    const sourceViewers = await this.prisma.documentViewLog.findMany({
      where: {
        documentId: sourceDocumentId,
        viewedAt: { gte: since },
      },
      select: { userId: true },
      distinct: ['userId'],
    });

    if (sourceViewers.length === 0) {
      return [];
    }

    const viewerIds = sourceViewers.map((v) => v.userId);

    const otherViews = await this.prisma.documentViewLog.groupBy({
      by: ['documentId'],
      where: {
        userId: { in: viewerIds },
        documentId: { notIn: [sourceDocumentId, ...excludeDocumentIds] },
        viewedAt: { gte: since },
      },
      _count: {
        userId: true,
      },
      orderBy: {
        _count: { userId: 'desc' },
      },
      take: limit,
    });

    const sourceViewerCount = sourceViewers.length;

    return otherViews.map((item) => ({
      documentId: item.documentId,
      cooccurrenceCount: item._count.userId,
      confidence: item._count.userId / sourceViewerCount,
    }));
  }

  async getCollaborativeFilteringRecommendations(
    userId: string,
    limit: number = 10,
    days: number = 90
  ): Promise<CooccurrenceResult[]> {
    const userViewedDocs = await this.getUserViewedDocuments(userId, 20, days);

    if (userViewedDocs.length === 0) {
      return [];
    }

    const allCooccurrences = new Map<string, { count: number; maxConfidence: number }>();

    for (const docId of userViewedDocs) {
      const cooccurrences = await this.getCooccurringDocuments(
        docId,
        limit * 2,
        days,
        userViewedDocs
      );

      for (const co of cooccurrences) {
        const existing = allCooccurrences.get(co.documentId) || {
          count: 0,
          maxConfidence: 0,
        };
        existing.count += co.cooccurrenceCount;
        existing.maxConfidence = Math.max(existing.maxConfidence, co.confidence);
        allCooccurrences.set(co.documentId, existing);
      }
    }

    return Array.from(allCooccurrences.entries())
      .map(([documentId, data]) => ({
        documentId,
        cooccurrenceCount: data.count,
        confidence: data.maxConfidence,
      }))
      .sort((a, b) => b.confidence - a.confidence || b.cooccurrenceCount - a.cooccurrenceCount)
      .slice(0, limit);
  }

  async getDocumentViewers(
    documentId: string,
    days: number = 30
  ): Promise<string[]> {
    const since = new Date();
    since.setDate(since.getDate() - days);

    const viewers = await this.prisma.documentViewLog.findMany({
      where: {
        documentId,
        viewedAt: { gte: since },
      },
      select: { userId: true },
      distinct: ['userId'],
    });

    return viewers.map((v) => v.userId);
  }

  async getPopularDocuments(
    spaceId?: string,
    limit: number = 10,
    days: number = 30
  ): Promise<Array<{ documentId: string; viewCount: number }>> {
    const since = new Date();
    since.setDate(since.getDate() - days);

    const results = await this.prisma.documentViewLog.groupBy({
      by: ['documentId'],
      where: {
        spaceId: spaceId || undefined,
        viewedAt: { gte: since },
      },
      _count: {
        id: true,
      },
      orderBy: {
        _count: { id: 'desc' },
      },
      take: limit,
    });

    return results.map((r) => ({
      documentId: r.documentId,
      viewCount: r._count.id,
    }));
  }

  async cleanupOldLogs(days: number = 180): Promise<number> {
    const cutoff = new Date();
    cutoff.setDate(cutoff.getDate() - days);

    const result = await this.prisma.documentViewLog.deleteMany({
      where: {
        viewedAt: { lt: cutoff },
      },
    });

    return result.count;
  }
}
