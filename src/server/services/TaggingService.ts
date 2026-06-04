import { PrismaClient } from '@prisma/client';
import {
  TagSuggestion,
  ClassificationResult,
  TagUsageStats,
  TrendingTag,
  DocumentForAnalysis,
} from '../../lib/nlp/types';
import { generateTagSuggestions } from '../../lib/nlp/KeywordExtractor';
import { classifyDocument } from '../../lib/nlp/DocumentClassifier';

export class TaggingService {
  private prisma: PrismaClient;

  constructor(prisma: PrismaClient) {
    this.prisma = prisma;
  }

  public async suggestTags(
    document: DocumentForAnalysis,
    options: {
      maxTags?: number;
      minConfidence?: number;
      includeClassificationTags?: boolean;
    } = {}
  ): Promise<{
      suggestions: (TagSuggestion & {
        isExisting?: boolean;
        tagId?: string;
      })[];
      classification: ClassificationResult | null;
    }> {
    const { maxTags = 10, minConfidence = 0.3, includeClassificationTags = true } = options;

    const classification = classifyDocument(document.title, document.content);

    const suggestions = generateTagSuggestions(
      document.title,
      document.content,
      includeClassificationTags ? classification.type : undefined,
      {
        maxTags,
        minConfidence,
        includeClassificationTags,
      }
    );

    const existingTags = await this.prisma.tag.findMany({
      where: {
        spaceId: document.spaceId,
      },
      select: {
        id: true,
        name: true,
        color: true,
        name: true,
      },
    });

    const existingTagNames = new Set(existingTags.map((t) => t.name.toLowerCase()));

    const suggestionsWithColor = suggestions.map((suggestion) => {
      const existingTag = existingTags.find(
        (t) => t.name.toLowerCase() === suggestion.name.toLowerCase());
      return {
        ...suggestion,
        color: suggestion.color || existingTag?.color || this.generateRandomColor(),
        isExisting: !!existingTag,
        tagId: existingTag?.id,
      };
    });

    return {
      suggestions: suggestionsWithColor,
      classification,
    };
  }

  public async autoTagDocument(
    documentId: string,
    spaceId: string,
    userId: string,
    options: {
      maxTags?: number;
      minConfidence?: number;
    } = {}
  ): Promise<{
    tags: Array<{
      tagId: string;
      tagName: string;
      color: string | null;
      isNew: boolean;
    }>;
    classification: ClassificationResult;
  }> {
    const document = await this.prisma.document.findUnique({
      where: { id: documentId },
      select: {
        id: true,
        title: true,
        content: true,
        spaceId: true,
      },
    });

    if (!document) {
      throw new Error('文档不存在');
    }

    const { suggestions, classification } = await this.suggestTags(
      {
        id: document.id,
        title: document.title,
        content: document.content || '',
        spaceId: document.spaceId,
      },
      options
    );

    const highConfidenceSuggestions = suggestions.filter((s) => s.confidence >= (options.minConfidence || 0.5));

    const result: Array<{
      tagId: string;
      tagName: string;
      color: string | null;
      isNew: boolean;
    }> = [];

    for (const suggestion of highConfidenceSuggestions) {
      const tag = await this.getOrCreateTag(
        spaceId,
        suggestion.name,
        suggestion.color,
        userId,
        true
      );

      await this.prisma.documentTag.upsert({
        where: {
          documentId_tagId: {
            documentId,
            tagId: tag.id,
          },
        },
        create: {
          documentId,
          tagId: tag.id,
          assignedById: userId,
        },
        update: {},
      });

      result.push({
        tagId: tag.id,
        tagName: tag.name,
        color: tag.color,
        isNew: tag.isNew,
      });
    }

    return {
      tags: result,
      classification: classification!,
    };
  }

  public async classifyAndTag(
    documentId: string,
    spaceId: string,
    userId: string
  ): Promise<{
    classification: ClassificationResult;
    tags: Array<{
      tagId: string;
      tagName: string;
      color: string | null;
    }>;
  }> {
    const { tags, classification } = await this.autoTagDocument(documentId, spaceId, userId);

    return {
      classification,
      tags,
    };
  }

  public async mergeTags(
    spaceId: string,
    sourceTagIds: string[],
    targetTagId: string,
    userId: string
  ): Promise<{
    success: boolean;
    mergedCount: number;
  }> {
    if (sourceTagIds.includes(targetTagId)) {
      throw new Error('目标标签不能在源标签列表中');
    }

    const targetTag = await this.prisma.tag.findUnique({
      where: { id: targetTagId, spaceId },
    });

    if (!targetTag) {
      throw new Error('目标标签不存在');
    }

    const sourceTags = await this.prisma.tag.findMany({
      where: {
        id: { in: sourceTagIds },
        spaceId,
      },
    });

    if (sourceTags.length !== sourceTagIds.length) {
      throw new Error('部分源标签不存在');
    }

    let mergedCount = 0;

    for (const sourceTag of sourceTags) {
      const documentTags = await this.prisma.documentTag.findMany({
        where: { tagId: sourceTag.id },
      });

      for (const dt of documentTags) {
        await this.prisma.documentTag.upsert({
          where: {
            documentId_tagId: {
              documentId: dt.documentId,
              tagId: targetTagId,
            },
          },
          create: {
            documentId: dt.documentId,
            tagId: targetTagId,
            assignedById: userId,
          },
          update: {},
        });
        mergedCount++;
      }

      await this.prisma.documentTag.deleteMany({
        where: { tagId: sourceTag.id },
      });

      await this.prisma.tag.delete({
        where: { id: sourceTag.id },
      });
    }

    return {
      success: true,
      mergedCount,
    };
  }

  public async getTagUsageStats(
    spaceId: string,
    options: {
      days?: number;
      limit?: number;
    } = {}
  ): Promise<TagUsageStats[]> {
    const { days = 30, limit = 50 } = options;

    const startDate = new Date();
    startDate.setDate(startDate.getDate() - days);

    const tags = await this.prisma.tag.findMany({
      where: { spaceId },
      include: {
        _count: {
          select: {
            documentTags: {
              where: {
                assignedAt: {
                  gte: startDate,
                },
              },
            },
          },
        },
        documentTags: {
          orderBy: {
            assignedAt: 'desc',
          },
          take: 1,
          select: {
            assignedAt: true,
          },
        },
      },
      orderBy: {
        documentTags: {
          _count: 'desc',
        },
      },
      take: limit,
    });

    const previousStartDate = new Date(startDate);
    previousStartDate.setDate(previousStartDate.getDate() - days);

    const previousStats = await this.prisma.documentTag.groupBy({
      by: ['tagId'],
      where: {
        tag: { spaceId },
        assignedAt: {
          gte: previousStartDate,
          lt: startDate,
        },
      },
      _count: {
        tagId: true,
      },
    });

    const previousCounts = new Map(previousStats.map((s) => [s.tagId, s._count.tagId]));

    return tags.map((tag) => {
      const currentCount = tag._count.documentTags;
      const previousCount = previousCounts.get(tag.id) || 0;
      const trend = currentCount > previousCount ? 'up' : currentCount < previousCount ? 'down' : 'stable';

      return {
        tagId: tag.id,
        tagName: tag.name,
        color: tag.color,
        usageCount: currentCount,
        lastUsedAt: tag.documentTags[0]?.assignedAt || null,
        trend,
      };
    });
  }

  public async getTrendingTags(
    spaceId: string,
    options: {
      days?: number;
      limit?: number;
    } = {}
  ): Promise<TrendingTag[]> {
    const { days = 7, limit = 20 } = options;

    const currentStart = new Date();
    currentStart.setDate(currentStart.getDate() - days);

    const previousStart = new Date(currentStart);
    previousStart.setDate(previousStart.getDate() - days);

    const [current, previous] = await Promise.all([
      this.prisma.documentTag.groupBy({
        by: ['tagId'],
        where: {
          tag: { spaceId },
          assignedAt: { gte: currentStart },
        },
        _count: { tagId: true },
      }),
      this.prisma.documentTag.groupBy({
        by: ['tagId'],
        where: {
          tag: { spaceId },
          assignedAt: {
            gte: previousStart,
            lt: currentStart,
          },
        },
        _count: { tagId: true },
      }),
    ]);

    const currentMap = new Map(current.map((c) => [c.tagId, c._count.tagId]));
    const previousMap = new Map(previous.map((p) => [p.tagId, p._count.tagId]));

    const tagIds = Array.from(new Set([...currentMap.keys(), ...previousMap.keys()]));

    const tags = await this.prisma.tag.findMany({
      where: {
        id: { in: tagIds },
        spaceId,
      },
      select: {
        id: true,
        name: true,
        color: true,
      },
    });

    const trendingTags: TrendingTag[] = tags
      .map((tag) => {
        const currentCount = currentMap.get(tag.id) || 0;
        const previousCount = previousMap.get(tag.id) || 0;
        const growthRate = previousCount > 0
          ? (currentCount - previousCount) / previousCount
          : currentCount > 0 ? 1 : 0;

        return {
          tagId: tag.id,
          tagName: tag.name,
          color: tag.color,
          usageCount: currentCount,
          growthRate,
        };
      })
      .filter((t) => t.usageCount > 0 || t.growthRate > 0)
      .sort((a, b) => b.growthRate - a.growthRate || b.usageCount - a.usageCount)
      .slice(0, limit);

    return trendingTags;
  }

  public async getOrCreateTag(
    spaceId: string,
    name: string,
    color: string | undefined,
    userId: string,
    isAutoGenerated: boolean = false
  ): Promise<{
    id: string;
    name: string;
    color: string | null;
    isNew: boolean;
  }> {
    let tag = await this.prisma.tag.findFirst({
      where: {
        spaceId,
        name: {
          equals: name,
          mode: 'insensitive',
        },
      },
    });

    if (tag) {
      return {
        id: tag.id,
        name: tag.name,
        color: tag.color,
        isNew: false,
      };
    }

    tag = await this.prisma.tag.create({
      data: {
        spaceId,
        name,
        color: color || this.generateRandomColor(),
        isAutoGenerated,
        createdById: userId,
      },
    });

    return {
      id: tag.id,
      name: tag.name,
      color: tag.color,
      isNew: true,
    };
  }

  private generateRandomColor(): string {
    const colors = [
      '#ef4444',
      '#f97316',
      '#f59e0b',
      '#eab308',
      '#84cc16',
      '#22c55e',
      '#10b981',
      '#14b8a6',
      '#06b6d4',
      '#0ea5e9',
      '#3b82f6',
      '#6366f1',
      '#8b5cf6',
      '#a855f7',
      '#d946ef',
      '#ec4899',
      '#f43f5e',
    ];
    return colors[Math.floor(Math.random() * colors.length)];
  }
}

let taggingServiceInstance: TaggingService | null = null;

export function getTaggingService(prisma: PrismaClient): TaggingService {
  if (!taggingServiceInstance) {
    taggingServiceInstance = new TaggingService(prisma);
  }
  return taggingServiceInstance;
}
