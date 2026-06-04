import { PrismaClient } from '@prisma/client';
import {
  SearchQuery,
  SearchResult,
  SearchResultItem,
  SearchFilter,
  SearchQueryBuildResult,
  DEFAULT_SEARCH_CONFIG,
  DEFAULT_HIGHLIGHT_CONFIG,
  HighlightConfig,
} from './types';
import { highlightSearchResults, extractMatchedTerms } from './highlighter';

export function escapeSqlString(str: string): string {
  return str.replace(/'/g, "''").replace(/\\/g, '\\\\');
}

export function buildSearchQuery(
  searchQuery: SearchQuery,
  useZhparser: boolean = false
): SearchQueryBuildResult {
  const params: unknown[] = [];
  let paramIndex = 1;
  const { query, filter, page = 1, pageSize = DEFAULT_SEARCH_CONFIG.pageSize, sortBy = 'relevance', sortOrder = 'desc', highlight = false, highlightConfig } = searchQuery;

  const configName = useZhparser ? 'zhparser' : 'simple';
  const normalizedQuery = query.trim();
  const safeQuery = escapeSqlString(normalizedQuery);

  const tsQuery = `plainto_tsquery('${configName}', $${paramIndex})`;
  params.push(normalizedQuery);
  paramIndex++;

  const selectFields = [
    'd.id',
    'd.title',
    'd.content',
    'd."sourceType"',
    'd."sourceUrl"',
    'd."isArchived"',
    'd."spaceId"',
    'd."userId"',
    'd."createdAt"',
    'd."updatedAt"',
  ];

  const fulltextScore = `
    CASE
      WHEN d."searchVector" @@ ${tsQuery} THEN
        ts_rank_cd(d."searchVector", ${tsQuery}, 32) * ${DEFAULT_SEARCH_CONFIG.fulltextWeight}
      ELSE 0
    END
  `;

  const fuzzyTitleScore = `
    CASE
      WHEN d.title % $${paramIndex} THEN
        similarity(d.title, $${paramIndex + 1}) * ${DEFAULT_SEARCH_CONFIG.titleWeight} * ${DEFAULT_SEARCH_CONFIG.fuzzyWeight}
      ELSE 0
    END
  `;
  params.push(normalizedQuery, normalizedQuery);
  paramIndex += 2;

  const fuzzyContentScore = `
    CASE
      WHEN d.content % $${paramIndex} THEN
        similarity(d.content, $${paramIndex + 1}) * ${DEFAULT_SEARCH_CONFIG.contentWeight} * ${DEFAULT_SEARCH_CONFIG.fuzzyWeight}
      ELSE 0
    END
  `;
  params.push(normalizedQuery, normalizedQuery);
  paramIndex += 2;

  const totalScore = `(${fulltextScore} + ${fuzzyTitleScore} + ${fuzzyContentScore}) as score`;

  if (highlight) {
    const hlConfig = { ...DEFAULT_HIGHLIGHT_CONFIG, ...highlightConfig };
    const safePreTag = escapeSqlString(hlConfig.preTag);
    const safePostTag = escapeSqlString(hlConfig.postTag);

    selectFields.push(`
      ts_headline('${configName}', d.title, ${tsQuery}, 
        'StartSel=${safePreTag}, StopSel=${safePostTag}, MaxWords=${hlConfig.fragmentSize}, MinWords=10, HighlightAll=${hlConfig.highlightAll}') as "highlightedTitle"
    `);
    selectFields.push(`
      ts_headline('${configName}', d.content, ${tsQuery}, 
        'StartSel=${safePreTag}, StopSel=${safePostTag}, MaxWords=${hlConfig.fragmentSize}, MinWords=10, MaxFragments=${hlConfig.maxFragments}, HighlightAll=${hlConfig.highlightAll}') as "highlightedContent"
    `);
  }

  selectFields.push(totalScore);

  const whereClauses: string[] = ['d."userId" = $' + paramIndex];
  params.push(filter?.userId || searchQuery.userId);
  paramIndex++;

  if (filter?.spaceId) {
    whereClauses.push('d."spaceId" = $' + paramIndex);
    params.push(filter.spaceId);
    paramIndex++;
  }

  if (filter?.tagIds && filter.tagIds.length > 0) {
    whereClauses.push(`
      d.id IN (
        SELECT dt."documentId" FROM "DocumentTag" dt
        WHERE dt."tagId" = ANY($${paramIndex}::text[])
      )
    `);
    params.push(filter.tagIds);
    paramIndex++;
  }

  if (filter?.dateFrom) {
    whereClauses.push('d."createdAt" >= $' + paramIndex);
    params.push(filter.dateFrom);
    paramIndex++;
  }

  if (filter?.dateTo) {
    whereClauses.push('d."createdAt" <= $' + paramIndex);
    params.push(filter.dateTo);
    paramIndex++;
  }

  if (filter?.sourceType) {
    whereClauses.push('d."sourceType" = $' + paramIndex);
    params.push(filter.sourceType);
    paramIndex++;
  }

  if (filter?.isArchived !== undefined) {
    whereClauses.push('d."isArchived" = $' + paramIndex);
    params.push(filter.isArchived);
    paramIndex++;
  }

  if (normalizedQuery.length > 0) {
    whereClauses.push(`
      (d."searchVector" @@ ${tsQuery} 
       OR d.title % $${paramIndex} 
       OR d.content % $${paramIndex + 1})
    `);
    params.push(normalizedQuery, normalizedQuery);
    paramIndex += 2;
  }

  const whereSql = whereClauses.join(' AND ');

  let orderBySql = '';
  if (sortBy === 'relevance') {
    orderBySql = `score ${sortOrder.toUpperCase()}, d."updatedAt" DESC`;
  } else if (sortBy === 'date') {
    orderBySql = `d."createdAt" ${sortOrder.toUpperCase()}`;
  } else if (sortBy === 'title') {
    orderBySql = `d.title ${sortOrder.toUpperCase()}`;
  }

  const offset = (page - 1) * pageSize;
  const limit = Math.min(pageSize, DEFAULT_SEARCH_CONFIG.maxPageSize);

  const selectSql = `
    SELECT ${selectFields.join(', ')},
           ROW_NUMBER() OVER (ORDER BY ${orderBySql}) as rank
    FROM "Document" d
    WHERE ${whereSql}
    ORDER BY ${orderBySql}
    LIMIT ${limit} OFFSET ${offset}
  `;

  const countSql = `
    SELECT COUNT(*) as total
    FROM "Document" d
    WHERE ${whereSql}
  `;

  const sql = `
    WITH search_results AS (${selectSql}),
         total_count AS (${countSql})
    SELECT sr.*, tc.total
    FROM search_results sr, total_count tc
  `;

  return { sql, params, paramIndex };
}

export async function executeSearch(
  prisma: PrismaClient,
  searchQuery: SearchQuery,
  useZhparser: boolean = false
): Promise<SearchResult> {
  const startTime = Date.now();
  const { query, page = 1, pageSize = DEFAULT_SEARCH_CONFIG.pageSize, highlight = false, highlightConfig } = searchQuery;

  const { sql, params } = buildSearchQuery(searchQuery, useZhparser);

  const results = await prisma.$queryRawUnsafe<Array<SearchResultItem & { total: bigint; rank: number; score: number; highlightedTitle?: string; highlightedContent?: string }>>(
    sql,
    ...params
  );

  const total = results.length > 0 ? Number(results[0].total) : 0;
  const totalPages = Math.ceil(total / Math.min(pageSize, DEFAULT_SEARCH_CONFIG.maxPageSize));

  const matchedTerms = extractMatchedTerms(query);

  let items: SearchResultItem[] = results.map((row, index) => ({
    id: row.id,
    title: row.title,
    content: row.content,
    sourceType: row.sourceType,
    sourceUrl: row.sourceUrl,
    isArchived: row.isArchived,
    spaceId: row.spaceId,
    userId: row.userId,
    createdAt: row.createdAt,
    updatedAt: row.updatedAt,
    score: row.score,
    rank: row.rank,
    highlightedTitle: row.highlightedTitle,
    highlightedContent: row.highlightedContent,
  }));

  if (highlight) {
    const hlConfig: Required<HighlightConfig> = { ...DEFAULT_HIGHLIGHT_CONFIG, ...highlightConfig };
    items = highlightSearchResults(items, query, hlConfig);
  }

  const documentIds = items.map((item) => item.id);
  if (documentIds.length > 0) {
    const documentsWithRelations = await prisma.document.findMany({
      where: { id: { in: documentIds } },
      include: {
        documentTags: { include: { tag: { select: { id: true, name: true, color: true } } } },
        space: { select: { id: true, name: true } },
      },
    });

    const relationMap = new Map(documentsWithRelations.map((doc) => [doc.id, doc]));
    items = items.map((item) => {
      const doc = relationMap.get(item.id) as any;
      return {
        ...item,
        tags: doc?.documentTags?.map((dt: any) => dt.tag),
        space: doc?.space,
      };
    });
  }

  const executionTimeMs = Date.now() - startTime;

  return {
    items,
    total,
    page,
    pageSize: Math.min(pageSize, DEFAULT_SEARCH_CONFIG.maxPageSize),
    totalPages,
    query,
    queryTimeMs: executionTimeMs,
    executionTimeMs,
    hasMore: page < totalPages,
    matchedTerms,
  };
}

export function buildFilterQuery(filter: SearchFilter, paramStartIndex: number = 1): { sql: string; params: unknown[]; nextParamIndex: number } {
  const params: unknown[] = [];
  let paramIndex = paramStartIndex;
  const clauses: string[] = [];

  clauses.push(`"userId" = $${paramIndex}`);
  params.push(filter.userId);
  paramIndex++;

  if (filter.spaceId) {
    clauses.push(`"spaceId" = $${paramIndex}`);
    params.push(filter.spaceId);
    paramIndex++;
  }

  if (filter.tagIds && filter.tagIds.length > 0) {
    clauses.push(`id IN (SELECT "documentId" FROM "DocumentTag" WHERE "tagId" = ANY($${paramIndex}::text[]))`);
    params.push(filter.tagIds);
    paramIndex++;
  }

  if (filter.dateFrom) {
    clauses.push(`"createdAt" >= $${paramIndex}`);
    params.push(filter.dateFrom);
    paramIndex++;
  }

  if (filter.dateTo) {
    clauses.push(`"createdAt" <= $${paramIndex}`);
    params.push(filter.dateTo);
    paramIndex++;
  }

  if (filter.sourceType) {
    clauses.push(`"sourceType" = $${paramIndex}`);
    params.push(filter.sourceType);
    paramIndex++;
  }

  if (filter.isArchived !== undefined) {
    clauses.push(`"isArchived" = $${paramIndex}`);
    params.push(filter.isArchived);
    paramIndex++;
  }

  return {
    sql: clauses.join(' AND '),
    params,
    nextParamIndex: paramIndex,
  };
}
