import { getPrismaClient, withTransaction, tenantFilter } from '../../common/prisma';
import { getCacheClient, generateCacheKey, TTL } from '../../common/cache';
import { getEventBus, EventTypes } from '../../common/events';
import { NotFoundError, ValidationError } from '../../common/errors';
import { DocumentInput, ComparisonResult, DiffSegment, HighlightedTerm, PaginationParams, PaginatedResult, ProcessingContext } from '../../common/types';
import { verifyTenantAccess } from '../tenant';
import * as diff from 'diff';
import { createHash } from 'crypto';

const prisma = getPrismaClient();
const cache = getCacheClient();
const eventBus = getEventBus();

const CRITICAL_TERMS = [
  'confidential',
  'liability',
  'indemnification',
  'termination',
  'breach',
  'warranty',
  'disclaimer',
  'limitation of liability',
  'intellectual property',
  'non-compete',
  'non-disclosure',
  'governing law',
  'jurisdiction',
  'arbitration',
  'force majeure'
];

const IMPORTANT_TERMS = [
  'payment',
  'pricing',
  'fees',
  'deadline',
  'delivery',
  'acceptance',
  'approval',
  'amendment',
  'assignment',
  'notice'
];

export const createDocument = async (
  tenantId: string,
  data: DocumentInput,
  traceId?: string
) => {
  verifyTenantAccess(tenantId, tenantId, traceId);

  const contentHash = hashContent(data.content);

  return withTransaction(async (tx) => {
    const document = await tx.document.create({
      data: {
        name: data.name,
        contentType: data.contentType,
        contentHash,
        tenantId
      }
    });

    await tx.documentVersion.create({
      data: {
        documentId: document.id,
        version: 1,
        content: data.content,
        createdBy: data.createdBy
      }
    });

    await cache.del(generateCacheKey('documents', tenantId));
    return getDocumentById(tenantId, document.id, traceId);
  });
};

export const getDocumentById = async (
  tenantId: string,
  documentId: string,
  traceId?: string
) => {
  const cacheKey = generateCacheKey('document', documentId);
  const cached = await cache.get(cacheKey);
  if (cached) return cached;

  const document = await prisma.document.findUnique({
    where: { id: documentId },
    include: {
      versions: { orderBy: { version: 'desc' } },
      comparisons: { take: 10, orderBy: { createdAt: 'desc' } }
    }
  });

  if (!document) {
    throw new NotFoundError('Document not found', { documentId }, traceId);
  }

  verifyTenantAccess(document.tenantId, tenantId, traceId);

  await cache.set(cacheKey, document, TTL.MEDIUM);
  return document;
};

export const listDocuments = async (
  tenantId: string,
  params: PaginationParams
): Promise<PaginatedResult<unknown>> => {
  const cacheKey = generateCacheKey('documents', tenantId, String(params.page), String(params.pageSize));
  const cached = await cache.get(cacheKey);
  if (cached) return cached as PaginatedResult<unknown>;

  const [total, items] = await Promise.all([
    prisma.document.count({ where: tenantFilter(tenantId) }),
    prisma.document.findMany({
      where: tenantFilter(tenantId),
      skip: (params.page - 1) * params.pageSize,
      take: params.pageSize,
      orderBy: { createdAt: 'desc' },
      include: { _count: { select: { versions: true, comparisons: true } } }
    })
  ]);

  const result: PaginatedResult<unknown> = {
    items,
    total,
    page: params.page,
    pageSize: params.pageSize,
    totalPages: Math.ceil(total / params.pageSize)
  };

  await cache.set(cacheKey, result, TTL.SHORT);
  return result;
};

export const createDocumentVersion = async (
  tenantId: string,
  documentId: string,
  content: string,
  createdBy?: string,
  traceId?: string
) => {
  const document = await prisma.document.findUnique({
    where: { id: documentId },
    include: { versions: { orderBy: { version: 'desc' }, take: 1 } }
  });

  if (!document) {
    throw new NotFoundError('Document not found', { documentId }, traceId);
  }
  verifyTenantAccess(document.tenantId, tenantId, traceId);

  const latestVersion = document.versions[0];
  const newVersionNumber = latestVersion ? latestVersion.version + 1 : 1;
  const contentHash = hashContent(content);

  return withTransaction(async (tx) => {
    await tx.document.update({
      where: { id: documentId },
      data: { contentHash }
    });

    const version = await tx.documentVersion.create({
      data: {
        documentId,
        version: newVersionNumber,
        content,
        createdBy
      }
    });

    await cache.del(generateCacheKey('document', documentId));
    await cache.del(generateCacheKey('documents', tenantId));

    return version;
  });
};

export const getDocumentVersion = async (
  tenantId: string,
  documentId: string,
  version: number,
  traceId?: string
) => {
  const document = await prisma.document.findUnique({ where: { id: documentId } });
  if (!document) {
    throw new NotFoundError('Document not found', { documentId }, traceId);
  }
  verifyTenantAccess(document.tenantId, tenantId, traceId);

  const docVersion = await prisma.documentVersion.findUnique({
    where: { documentId_version: { documentId, version } }
  });

  if (!docVersion) {
    throw new NotFoundError('Version not found', { documentId, version }, traceId);
  }

  return docVersion;
};

export const compareDocuments = async (
  tenantId: string,
  documentId: string,
  fromVersion: number,
  toVersion: number,
  context?: Partial<ProcessingContext>
): Promise<ComparisonResult> => {
  const cacheKey = generateCacheKey('document', 'compare', documentId, String(fromVersion), String(toVersion));
  const cached = await cache.get(cacheKey);
  if (cached) return cached as ComparisonResult;

  const [fromDoc, toDoc] = await Promise.all([
    getDocumentVersion(tenantId, documentId, fromVersion, context?.traceId),
    getDocumentVersion(tenantId, documentId, toVersion, context?.traceId)
  ]);

  const segments = computeDiff(fromDoc.content, toDoc.content);
  const highlights = findCriticalChanges(fromDoc.content, toDoc.content, fromVersion, toVersion);
  const summary = generateSummary(segments, highlights, fromVersion, toVersion);

  const statistics = {
    additions: segments.filter(s => s.type === 'added').length,
    removals: segments.filter(s => s.type === 'removed').length,
    changes: segments.filter(s => s.type === 'modified').length,
    unchanged: segments.filter(s => s.type === 'unchanged').length
  };

  const result: ComparisonResult = {
    segments,
    statistics,
    highlights,
    summary
  };

  await withTransaction(async (tx) => {
    await tx.documentComparison.create({
      data: {
        documentId,
        fromVersion,
        toVersion,
        diffResult: segments as unknown as object,
        summary,
        highlights: highlights as unknown as object
      }
    });
  });

  eventBus.publish(EventTypes.DOCUMENT_COMPARED, {
    documentId,
    fromVersion,
    toVersion,
    statistics
  }, context);

  await cache.set(cacheKey, result, TTL.LONG);
  return result;
};

export const computeDiff = (oldText: string, newText: string): DiffSegment[] => {
  const changes = diff.diffLines(oldText, newText);
  const segments: DiffSegment[] = [];

  let lineNumber = 1;

  for (const part of changes) {
    if (part.added) {
      segments.push({
        type: 'added',
        value: part.value,
        lineNumber,
        confidence: 1
      });
    } else if (part.removed) {
      segments.push({
        type: 'removed',
        value: part.value,
        lineNumber,
        confidence: 1
      });
    } else {
      segments.push({
        type: 'unchanged',
        value: part.value,
        lineNumber,
        confidence: 1
      });
      lineNumber += part.count || 0;
    }
  }

  return segments;
};

export const findCriticalChanges = (
  oldText: string,
  newText: string,
  fromVersion: number,
  toVersion: number
): HighlightedTerm[] => {
  const highlights: HighlightedTerm[] = [];
  const lowerOld = oldText.toLowerCase();
  const lowerNew = newText.toLowerCase();

  const allTerms = [...CRITICAL_TERMS, ...IMPORTANT_TERMS];

  for (const term of allTerms) {
    const oldCount = countOccurrences(lowerOld, term);
    const newCount = countOccurrences(lowerNew, term);

    if (oldCount !== newCount) {
      const type = CRITICAL_TERMS.includes(term) ? 'critical' : 'important';

      const positions: Array<{ version: number; start: number; end: number }> = [];
      const searchText = type === 'critical' ? lowerOld : lowerNew;
      const version = type === 'critical' ? fromVersion : toVersion;

      let index = searchText.indexOf(term);
      while (index !== -1) {
        positions.push({
          version,
          start: index,
          end: index + term.length
        });
        index = searchText.indexOf(term, index + 1);
      }

      if (positions.length > 0) {
        highlights.push({
          term,
          type,
          description: generateTermDescription(term, oldCount, newCount, type),
          positions
        });
      }
    }
  }

  return highlights;
};

const countOccurrences = (text: string, term: string): number => {
  const regex = new RegExp(`\\b${escapeRegex(term)}\\b`, 'gi');
  return (text.match(regex) || []).length;
};

const escapeRegex = (str: string): string => {
  return str.replace(/[.*+?^${}()|[\]\\]/g, '\\$&');
};

const generateTermDescription = (term: string, oldCount: number, newCount: number, type: string): string => {
  const change = newCount - oldCount;
  if (change > 0) {
    return `${type.toUpperCase()}: "${term}" appears ${change} more time(s) in the new version`;
  } else if (change < 0) {
    return `${type.toUpperCase()}: "${term}" appears ${Math.abs(change)} fewer time(s) in the new version`;
  }
  return `${type.toUpperCase()}: "${term}" count changed between versions`;
};

export const generateSummary = (
  segments: DiffSegment[],
  highlights: HighlightedTerm[],
  fromVersion: number,
  toVersion: number
): string => {
  const additions = segments.filter(s => s.type === 'added').length;
  const removals = segments.filter(s => s.type === 'removed').length;
  const modified = segments.filter(s => s.type === 'modified').length;
  const critical = highlights.filter(h => h.type === 'critical').length;
  const important = highlights.filter(h => h.type === 'important').length;

  const parts: string[] = [];
  parts.push(`Comparing version ${fromVersion} to ${toVersion}:`);

  if (additions > 0) parts.push(`${additions} addition(s)`);
  if (removals > 0) parts.push(`${removals} removal(s)`);
  if (modified > 0) parts.push(`${modified} modification(s)`);

  if (critical > 0) {
    parts.push(`WARNING: ${critical} critical term change(s) detected!`);
  }
  if (important > 0) {
    parts.push(`${important} important term change(s) detected.`);
  }

  if (critical === 0 && important === 0 && additions === 0 && removals === 0 && modified === 0) {
    return `No differences found between version ${fromVersion} and ${toVersion}.`;
  }

  return parts.join(' ');
};

export const getDocumentComparisons = async (
  tenantId: string,
  documentId: string,
  params: PaginationParams,
  traceId?: string
): Promise<PaginatedResult<unknown>> => {
  const document = await prisma.document.findUnique({ where: { id: documentId } });
  if (!document) {
    throw new NotFoundError('Document not found', { documentId }, traceId);
  }
  verifyTenantAccess(document.tenantId, tenantId, traceId);

  const [total, items] = await Promise.all([
    prisma.documentComparison.count({ where: { documentId } }),
    prisma.documentComparison.findMany({
      where: { documentId },
      skip: (params.page - 1) * params.pageSize,
      take: params.pageSize,
      orderBy: { createdAt: 'desc' }
    })
  ]);

  return {
    items,
    total,
    page: params.page,
    pageSize: params.pageSize,
    totalPages: Math.ceil(total / params.pageSize)
  };
};

export const getComparisonById = async (
  tenantId: string,
  comparisonId: string,
  traceId?: string
) => {
  const comparison = await prisma.documentComparison.findUnique({
    where: { id: comparisonId },
    include: { document: true }
  });

  if (!comparison) {
    throw new NotFoundError('Comparison not found', { comparisonId }, traceId);
  }
  verifyTenantAccess(comparison.document.tenantId, tenantId, traceId);
  return comparison;
};

export const deleteDocument = async (
  tenantId: string,
  documentId: string,
  traceId?: string
) => {
  const document = await prisma.document.findUnique({ where: { id: documentId } });
  if (!document) {
    throw new NotFoundError('Document not found', { documentId }, traceId);
  }
  verifyTenantAccess(document.tenantId, tenantId, traceId);

  await withTransaction(async (tx) => {
    await tx.documentComparison.deleteMany({ where: { documentId } });
    await tx.documentVersion.deleteMany({ where: { documentId } });
    await tx.document.delete({ where: { id: documentId } });
  });

  await cache.del(generateCacheKey('document', documentId));
  await cache.del(generateCacheKey('documents', tenantId));
};

export const getDocumentStats = async (
  tenantId: string
) => {
  const [documents, versions, comparisons] = await Promise.all([
    prisma.document.count({ where: tenantFilter(tenantId) }),
    prisma.documentVersion.count({
      where: { document: { tenantId } }
    }),
    prisma.documentComparison.count({
      where: { document: { tenantId } }
    })
  ]);

  return {
    documents,
    versions,
    comparisons,
    avgVersionsPerDocument: documents > 0 ? versions / documents : 0
  };
};

export const batchCompare = async (
  tenantId: string,
  comparisons: Array<{ documentId: string; fromVersion: number; toVersion: number }>,
  context?: Partial<ProcessingContext>
) => {
  const results = [];

  for (const comparison of comparisons) {
    try {
      const result = await compareDocuments(
        tenantId,
        comparison.documentId,
        comparison.fromVersion,
        comparison.toVersion,
        context
      );
      results.push({ ...comparison, status: 'success', result });
    } catch (err) {
      results.push({
        ...comparison,
        status: 'failed',
        error: err instanceof Error ? err.message : String(err)
      });
    }
  }

  return results;
};

const hashContent = (content: string): string => {
  return createHash('sha256').update(content).digest('hex');
};

export const compareTextDirect = (
  oldText: string,
  newText: string
): ComparisonResult => {
  const segments = computeDiff(oldText, newText);
  const highlights = findCriticalChanges(oldText, newText, 1, 2);
  const summary = generateSummary(segments, highlights, 1, 2);
  const statistics = {
    additions: segments.filter(s => s.type === 'added').length,
    removals: segments.filter(s => s.type === 'removed').length,
    changes: segments.filter(s => s.type === 'modified').length,
    unchanged: segments.filter(s => s.type === 'unchanged').length
  };

  return { segments, statistics, highlights, summary };
};
