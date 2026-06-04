import { PrismaClient } from '@prisma/client';
import {
  SearchService,
  SearchQuery,
  SearchResult,
  SearchResultItem,
  HighlightQuery,
  HighlightResult,
  SuggestionQuery,
  SuggestionResult,
  SearchIndexOptions,
} from './types';
import {
  ChineseTokenizerService,
  tokenizeDocumentForSearch,
  tokenizeQueryForSearch,
} from './ChineseTokenizerService';

export class PostgresSearchService implements SearchService {
  private prisma: PrismaClient;
  private tokenizer: ChineseTokenizerService;
  private enableTrigramFallback: boolean;

  constructor(prisma: PrismaClient, options?: { enableTrigramFallback?: boolean }) {
    this.prisma = prisma;
    this.tokenizer = ChineseTokenizerService.getInstance();
    this.enableTrigramFallback = options?.enableTrigramFallback ?? true;
  }

  async search(query: SearchQuery): Promise<SearchResult> {
    const startTime = Date.now();
    const skip = (query.page - 1) * query.pageSize;

    const filterWhere = this.buildFilterWhere(query);
    const { sql, params, countSql, countParams } = this.buildSearchSql(
      query,
      filterWhere,
      skip
    );

    const [documents, totalResult] = await Promise.all([
      this.prisma.$queryRawUnsafe(sql, ...params) as Promise<Array<{
        id: string;
        title: string;
        content: string | null;
        summary: string | null;
        spaceId: string;
        createdById: string;
        updatedById: string;
        createdAt: Date;
        updatedAt: Date;
        path: string | null;
        rank: number;
        match_method: string;
      }>>,
      this.prisma.$queryRawUnsafe(countSql, ...countParams) as Promise<Array<{ count: bigint }>>,
    ]);

    const total = Number(totalResult[0]?.count ?? 0);

    const documentIds = documents.map(d => d.id);
    const relatedData = await this.fetchRelatedData(documentIds);

    const items: SearchResultItem[] = documents.map((doc) => ({
      id: doc.id,
      title: doc.title,
      content: doc.content || '',
      summary: null,
      spaceId: doc.spaceId,
      space: relatedData.spaces.get(doc.spaceId) || null,
      createdBy: relatedData.users.get(doc.createdById) || null,
      updatedBy: null,
      tags: relatedData.tags.get(doc.id) || [],
      createdAt: doc.createdAt,
      updatedAt: doc.updatedAt,
      path: doc.path || undefined,
      _count: relatedData.counts.get(doc.id) || { comments: 0, versions: 0 },
      score: doc.rank,
      matchMethod: doc.match_method,
    }));

    const queryTimeMs = Date.now() - startTime;

    return {
      items,
      total,
      page: query.page,
      pageSize: query.pageSize,
      totalPages: Math.ceil(total / query.pageSize),
      query: query.query,
      queryTimeMs,
      hasMore: skip + query.pageSize < total,
    };
  }

  private buildSearchSql(
    query: SearchQuery,
    filterWhere: string,
    skip: number
  ): {
    sql: string;
    params: unknown[];
    countSql: string;
    countParams: unknown[];
  } {
    const queryText = query.query.trim();
    const { tokens, tsQuery, hasChinese } = this.tokenizer.tokenizeQuery(queryText);

    const params: unknown[] = [];
    const countParams: unknown[] = [];
    let paramIndex = 1;

    const addParam = (value: unknown, forCount = true): number => {
      params.push(value);
      if (forCount) countParams.push(value);
      return paramIndex++;
    };

    const tsQueryParam = addParam(tsQuery);
    const patternParam = addParam(`%${queryText}%`, false);

    let rankExpression = '';
    let matchMethod = '';

    if (hasChinese && tokens.length > 0) {
      rankExpression = `
        CASE
          WHEN "tokenVector" @@ to_tsquery('simple', $${tsQueryParam}) THEN
            ts_rank_cd("tokenVector", to_tsquery('simple', $${tsQueryParam}), 32) * 2
          WHEN "tokenVector" IS NOT NULL AND "tokens" ILIKE $${patternParam} THEN
            similarity("tokens", $${patternParam}) * 0.8
          ELSE
            (similarity(title, $${patternParam}) * 0.6 + similarity(coalesce(content, ''), $${patternParam}) * 0.4) * 0.5
        END
      `;
      matchMethod = `
        CASE
          WHEN "tokenVector" @@ to_tsquery('simple', $${tsQueryParam}) THEN 'token_tsvector'
          WHEN "tokenVector" IS NOT NULL AND "tokens" ILIKE $${patternParam} THEN 'token_trigram'
          ELSE 'fallback_trigram'
        END
      `;
    } else {
      rankExpression = `
        CASE
          WHEN "tokenVector" @@ to_tsquery('simple', $${tsQueryParam}) THEN
            ts_rank_cd("tokenVector", to_tsquery('simple', $${tsQueryParam}), 32) * 2
          ELSE
            (similarity(title, $${patternParam}) * 0.6 + similarity(coalesce(content, ''), $${patternParam}) * 0.4) * 0.8
        END
      `;
      matchMethod = `
        CASE
          WHEN "tokenVector" @@ to_tsquery('simple', $${tsQueryParam}) THEN 'token_tsvector'
          ELSE 'fallback_trigram'
        END
      `;
    }

    const searchConditions = [];
    if (this.enableTrigramFallback) {
      searchConditions.push(`"tokenVector" @@ to_tsquery('simple', $${tsQueryParam})`);
      searchConditions.push(`"tokens" ILIKE $${patternParam}`);
      searchConditions.push(`title ILIKE $${patternParam}`);
      searchConditions.push(`content ILIKE $${patternParam}`);
      if (query.includeOcr) {
        searchConditions.push(`"ocrText" ILIKE $${patternParam}`);
      }
    } else {
      searchConditions.push(`"tokenVector" @@ to_tsquery('simple', $${tsQueryParam})`);
    }

    const whereClause = [
      `"status" != 'DELETED'`,
      `(${searchConditions.join(' OR ')})`,
      filterWhere,
    ].filter(Boolean).join(' AND ');

    const orderBy = this.buildOrderBySql(query, rankExpression);

    const selectSql = `
      SELECT
        id,
        title,
        content,
        summary,
        "spaceId",
        "createdById",
        "updatedById",
        "createdAt",
        "updatedAt",
        path,
        ${rankExpression} as rank,
        ${matchMethod} as match_method
      FROM "Document"
      WHERE ${whereClause}
      ${orderBy}
      LIMIT $${addParam(query.pageSize, false)} OFFSET $${addParam(skip, false)}
    `;

    const countSql = `
      SELECT COUNT(*) as count
      FROM "Document"
      WHERE ${whereClause}
    `;

    return {
      sql: selectSql,
      params,
      countSql,
      countParams,
    };
  }

  private buildOrderBySql(query: SearchQuery, rankExpression: string): string {
    const sortBy = query.sortBy || 'relevance';
    const sortOrder = (query.sortOrder || 'desc').toUpperCase();

    switch (sortBy) {
      case 'relevance':
        return `ORDER BY ${rankExpression} DESC, "updatedAt" DESC`;
      case 'title':
        return `ORDER BY title ${sortOrder}`;
      case 'createdAt':
      case 'date':
        return `ORDER BY "createdAt" ${sortOrder}`;
      case 'updatedAt':
      default:
        return `ORDER BY "updatedAt" ${sortOrder}`;
    }
  }

  private buildFilterWhere(query: SearchQuery): string {
    const conditions: string[] = [];
    let paramIndex = 1;

    if (query.spaceId) {
      conditions.push(`"spaceId" = $${paramIndex++}`);
    }

    if (query.tagIds && query.tagIds.length > 0) {
      conditions.push(`id IN (SELECT "documentId" FROM "_DocumentToTag" WHERE "B" IN (${query.tagIds.map(() => `$${paramIndex++}`).join(', ')}))`);
    }

    if (query.dateFrom) {
      conditions.push(`"createdAt" >= $${paramIndex++}`);
    }

    if (query.dateTo) {
      conditions.push(`"createdAt" <= $${paramIndex++}`);
    }

    if (query.source) {
      conditions.push(`source = $${paramIndex++}`);
    }

    conditions.push(`"spaceId" IN (SELECT "spaceId" FROM "SpaceMember" WHERE "userId" = $${paramIndex++})`);

    if (query.filter) {
      if (query.filter.sourceType) {
        conditions.push(`"externalSource" = $${paramIndex++}`);
      }
      if (query.filter.isArchived !== undefined) {
        conditions.push(`"isArchived" = $${paramIndex++}`);
      }
    }

    return conditions.length > 0 ? conditions.join(' AND ') : '';
  }

  private async fetchRelatedData(documentIds: string[]) {
    const [documents, documentTags, counts] = await Promise.all([
      this.prisma.document.findMany({
        where: { id: { in: documentIds } },
        include: {
          space: { select: { id: true, name: true, icon: true } },
          createdBy: { select: { id: true, name: true, email: true, avatar: true } },
        },
      }),
      this.prisma.documentTag.findMany({
        where: { documentId: { in: documentIds } },
        include: {
          tag: { select: { id: true, name: true, color: true } },
        },
      }),
      this.prisma.document.findMany({
        where: { id: { in: documentIds } },
        select: {
          id: true,
          _count: {
            select: { comments: true, versions: true },
          },
        },
      }),
    ]);

    const tagMap = new Map<string, Array<{ id: string; name: string; color: string | null }>>();
    documentTags.forEach(dt => {
      if (!tagMap.has(dt.documentId)) {
        tagMap.set(dt.documentId, []);
      }
      tagMap.get(dt.documentId)!.push(dt.tag);
    });

    const spaceMap = new Map<string, typeof documents[0]['space']>();
    const userMap = new Map<string, typeof documents[0]['createdBy']>();

    documents.forEach(d => {
      if (d.space) spaceMap.set(d.space.id, d.space);
      if (d.createdBy) userMap.set(d.createdBy.id, d.createdBy);
    });

    const countMap = new Map<string, typeof counts[0]['_count']>();
    counts.forEach(c => countMap.set(c.id, c._count));

    return { tags: tagMap, spaces: spaceMap, users: userMap, counts: countMap };
  }

  async highlight(query: HighlightQuery): Promise<HighlightResult | null> {
    const document = await this.prisma.document.findUnique({
      where: {
        id: query.documentId,
        space: {
          members: {
            some: {
              userId: query.userId,
            },
          },
        },
      },
      select: {
        id: true,
        title: true,
        content: true,
      },
    });

    if (!document) {
      return null;
    }

    const { tokens } = tokenizeQueryForSearch(query.query);

    const escapeRegex = (str: string) =>
      str.replace(/[.*+?^${}()|[\]\\]/g, '\\$&');
    
    const allTerms = [query.query, ...tokens].map(escapeRegex);
    const regex = new RegExp(`(${allTerms.join('|')})`, 'gi');

    const highlightText = (text: string) =>
      text.replace(regex, '<mark>$1</mark>');

    return {
      documentId: document.id,
      title: document.title,
      highlightedTitle: highlightText(document.title),
      highlightedContent: highlightText(document.content || ''),
      highlightedSummary: null,
    };
  }

  async suggest(query: SuggestionQuery): Promise<SuggestionResult> {
    const where = {
      isArchived: false,
      ...(query.spaceId ? { spaceId: query.spaceId } : {}),
      space: {
        members: {
          some: {
            userId: query.userId,
          },
        },
      },
    } as const;

    const [documents, tagSuggestions] = await Promise.all([
      this.prisma.$queryRawUnsafe(`
        SELECT id, title, path, "spaceId"
        FROM "Document"
        WHERE "isArchived" = false
          AND "spaceId" IN (SELECT "spaceId" FROM "SpaceMember" WHERE "userId" = $1)
          ${query.spaceId ? `AND "spaceId" = $2` : ''}
          AND (title % $${query.spaceId ? 3 : 2} OR tokens % $${query.spaceId ? 3 : 2})
        ORDER BY similarity(title, $${query.spaceId ? 3 : 2}) DESC
        LIMIT $${query.spaceId ? 4 : 3}
      `, query.spaceId ? [query.userId, query.spaceId, query.query, query.limit] : [query.userId, query.query, query.limit]) as Promise<Array<{
        id: string;
        title: string;
        path: string | null;
        spaceId: string;
      }>>,
      this.prisma.tag.findMany({
        where: {
          name: {
            startsWith: query.query,
            mode: 'insensitive',
          },
        },
        take: Math.floor(query.limit / 2),
        select: {
          id: true,
          name: true,
          color: true,
        },
      }),
    ]);

    const spaceIds = documents.map(d => d.spaceId);
    const spaces = await this.prisma.space.findMany({
      where: { id: { in: spaceIds } },
      select: { id: true, name: true, icon: true },
    });
    const spaceMap = new Map(spaces.map(s => [s.id, s]));

    const formattedDocs = documents.map(d => ({
      id: d.id,
      title: d.title,
      path: d.path || '',
      space: spaceMap.get(d.spaceId) || { id: '', name: '', icon: null },
    }));

    return {
      documents: formattedDocs,
      tags: tagSuggestions,
    };
  }

  async indexDocument(documentId: string): Promise<void> {
    const doc = await this.prisma.document.findUnique({
      where: { id: documentId },
      select: { id: true, title: true, content: true, ocrText: true },
    });

    if (!doc) return;

    const tokenizeResult = tokenizeDocumentForSearch(doc.title, doc.content || '', doc.ocrText);

    await this.prisma.$executeRawUnsafe(`
      UPDATE "Document"
      SET
        tokens = $1,
        "tokenVector" = ${tokenizeResult.tokenVectorSql},
        "searchVector" = 
          setweight(to_tsvector('simple', coalesce(title, '')), 'A') ||
          setweight(to_tsvector('simple', coalesce(content, '')), 'B') ||
          setweight(to_tsvector('simple', coalesce("ocrText", '')), 'D')
      WHERE id = $${tokenizeResult.tokenVectorParams.length + 2}
    `, tokenizeResult.tokens, ...tokenizeResult.tokenVectorParams, documentId);
  }

  async indexDocuments(documentIds: string[]): Promise<void> {
    if (documentIds.length === 0) return;

    await Promise.all(
      documentIds.map((id) => this.indexDocument(id))
    );
  }

  async removeFromIndex(documentId: string): Promise<void> {
    await this.prisma.$executeRaw`
      UPDATE "Document"
      SET "searchVector" = NULL, "tokenVector" = NULL, tokens = NULL
      WHERE id = ${documentId}
    `;
  }

  async rebuildIndex(options: SearchIndexOptions = { batchSize: 100 }): Promise<void> {
    const total = await this.prisma.document.count({
      where: { isArchived: false },
    });

    for (let i = 0; i < total; i += options.batchSize) {
      const documents = await this.prisma.document.findMany({
        where: { isArchived: false },
        skip: i,
        take: options.batchSize,
        select: { id: true, title: true, content: true, ocrText: true },
      });

      await Promise.all(
        documents.map((doc) => {
          const tokenizeResult = tokenizeDocumentForSearch(doc.title, doc.content || '', doc.ocrText);
          return this.prisma.$executeRawUnsafe(`
            UPDATE "Document"
            SET
              tokens = $1,
              "tokenVector" = ${tokenizeResult.tokenVectorSql},
              "searchVector" = 
                setweight(to_tsvector('simple', coalesce(title, '')), 'A') ||
                setweight(to_tsvector('simple', coalesce(content, '')), 'B') ||
                setweight(to_tsvector('simple', coalesce("ocrText", '')), 'D')
            WHERE id = $${tokenizeResult.tokenVectorParams.length + 2}
          `, tokenizeResult.tokens, ...tokenizeResult.tokenVectorParams, doc.id);
        })
      );
    }
  }
}
