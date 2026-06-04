import { PrismaClient } from '@prisma/client';
import { IndexUpdateResult, BatchIndexResult } from './types';

export function buildDocumentVectorSql(useZhparser: boolean = false): string {
  const configName = useZhparser ? 'zhparser' : 'simple';

  return `
    setweight(to_tsvector('${configName}', coalesce($1, '')), 'A') ||
    setweight(to_tsvector('${configName}', coalesce($2, '')), 'B') ||
    setweight(to_tsvector('${configName}', coalesce($3, '')), 'D')
  `;
}

export async function updateDocumentVector(
  prisma: PrismaClient,
  documentId: string,
  useZhparser: boolean = false
): Promise<IndexUpdateResult> {
  try {
    const document = await prisma.document.findUnique({
      where: { id: documentId },
      select: { id: true, title: true, content: true, ocrText: true },
    });

    if (!document) {
      return {
        documentId,
        success: false,
        error: 'Document not found',
      };
    }

    const vectorSql = buildDocumentVectorSql(useZhparser);

    await prisma.$executeRawUnsafe(
      `
        UPDATE "Document"
        SET "searchVector" = ${vectorSql},
            "updatedAt" = CURRENT_TIMESTAMP
        WHERE id = $4
      `,
      document.title,
      document.content,
      document.ocrText,
      documentId
    );

    return {
      documentId,
      success: true,
      updatedAt: new Date(),
    };
  } catch (error) {
    const errorMessage = error instanceof Error ? error.message : 'Unknown error';
    return {
      documentId,
      success: false,
      error: errorMessage,
    };
  }
}

export async function updateDocumentVectorWithContent(
  prisma: PrismaClient,
  documentId: string,
  title: string,
  content: string,
  ocrText?: string | null,
  useZhparser: boolean = false
): Promise<IndexUpdateResult> {
  try {
    const vectorSql = buildDocumentVectorSql(useZhparser);

    await prisma.$executeRawUnsafe(
      `
        UPDATE "Document"
        SET "searchVector" = ${vectorSql},
            "updatedAt" = CURRENT_TIMESTAMP
        WHERE id = $4
      `,
      title,
      content,
      ocrText || '',
      documentId
    );

    return {
      documentId,
      success: true,
      updatedAt: new Date(),
    };
  } catch (error) {
    const errorMessage = error instanceof Error ? error.message : 'Unknown error';
    return {
      documentId,
      success: false,
      error: errorMessage,
    };
  }
}

export async function rebuildAllIndexes(
  prisma: PrismaClient,
  batchSize: number = 100,
  useZhparser: boolean = false,
  userId?: string
): Promise<BatchIndexResult> {
  const startTime = Date.now();

  try {
    const whereClause = userId ? { createdById: userId } : {};
    const totalCount = await prisma.document.count({ where: whereClause });

    if (totalCount === 0) {
      return {
        total: 0,
        succeeded: 0,
        failed: 0,
        results: [],
        success: true,
      };
    }

    let successCount = 0;
    let failedCount = 0;
    const results: IndexUpdateResult[] = [];

    let cursor: string | undefined;
    let processedCount = 0;

    while (processedCount < totalCount) {
      const documents = await prisma.document.findMany({
        where: whereClause,
        select: { id: true, title: true, content: true, ocrText: true },
        orderBy: { id: 'asc' },
        take: batchSize,
        ...(cursor ? { cursor: { id: cursor }, skip: 1 } : {}),
      });

      if (documents.length === 0) break;

      const vectorSql = buildDocumentVectorSql(useZhparser);

      const updatePromises = documents.map(async (doc) => {
        try {
          await prisma.$executeRawUnsafe(
            `
              UPDATE "Document"
              SET "searchVector" = ${vectorSql},
                  "updatedAt" = CURRENT_TIMESTAMP
              WHERE id = $4
            `,
            doc.title,
            doc.content,
            (doc as any).ocrText || '',
            doc.id
          );
          return { documentId: doc.id, success: true, updatedAt: new Date() };
        } catch (error) {
          const errorMessage = error instanceof Error ? error.message : 'Unknown error';
          return { documentId: doc.id, success: false, error: errorMessage };
        }
      });

      const batchResults = await Promise.all(updatePromises);
      results.push(...batchResults);

      for (const result of batchResults) {
        if (result.success) {
          successCount++;
        } else {
          failedCount++;
        }
      }

      processedCount += documents.length;
      cursor = documents[documents.length - 1].id;
    }

    return {
      total: totalCount,
      succeeded: successCount,
      failed: failedCount,
      results,
      success: failedCount === 0,
    };
  } catch (error) {
    const errorMessage = error instanceof Error ? error.message : 'Unknown error';
    return {
      total: 0,
      succeeded: 0,
      failed: 0,
      results: [{ documentId: 'all', success: false, error: errorMessage }],
      success: false,
    };
  }
}

export async function rebuildIndexesForSpace(
  prisma: PrismaClient,
  spaceId: string,
  batchSize: number = 100,
  useZhparser: boolean = false
): Promise<BatchIndexResult> {
  const startTime = Date.now();

  try {
    const totalCount = await prisma.document.count({ where: { spaceId } });

    if (totalCount === 0) {
      return {
        total: 0,
        succeeded: 0,
        failed: 0,
        results: [],
        success: true,
      };
    }

    let successCount = 0;
    let failedCount = 0;
    const results: IndexUpdateResult[] = [];

    let cursor: string | undefined;
    let processedCount = 0;

    while (processedCount < totalCount) {
      const documents = await prisma.document.findMany({
        where: { spaceId },
        select: { id: true, title: true, content: true, ocrText: true },
        orderBy: { id: 'asc' },
        take: batchSize,
        ...(cursor ? { cursor: { id: cursor }, skip: 1 } : {}),
      });

      if (documents.length === 0) break;

      const vectorSql = buildDocumentVectorSql(useZhparser);

      const updatePromises = documents.map(async (doc) => {
        try {
          await prisma.$executeRawUnsafe(
            `
              UPDATE "Document"
              SET "searchVector" = ${vectorSql},
                  "updatedAt" = CURRENT_TIMESTAMP
              WHERE id = $4
            `,
            doc.title,
            doc.content,
            (doc as any).ocrText || '',
            doc.id
          );
          return { documentId: doc.id, success: true, updatedAt: new Date() };
        } catch (error) {
          const errorMessage = error instanceof Error ? error.message : 'Unknown error';
          return { documentId: doc.id, success: false, error: errorMessage };
        }
      });

      const batchResults = await Promise.all(updatePromises);
      results.push(...batchResults);

      for (const result of batchResults) {
        if (result.success) {
          successCount++;
        } else {
          failedCount++;
        }
      }

      processedCount += documents.length;
      cursor = documents[documents.length - 1].id;
    }

    return {
      total: totalCount,
      succeeded: successCount,
      failed: failedCount,
      results,
      success: failedCount === 0,
    };
  } catch (error) {
    const errorMessage = error instanceof Error ? error.message : 'Unknown error';
    return {
      total: 0,
      succeeded: 0,
      failed: 0,
      results: [{ documentId: 'all', success: false, error: errorMessage }],
      success: false,
    };
  }
}

export async function checkZhparserAvailable(prisma: PrismaClient): Promise<boolean> {
  try {
    const result = await prisma.$queryRawUnsafe<Array<{ extname: string }>>(
      "SELECT extname FROM pg_extension WHERE extname = 'zhparser'"
    );
    return result.length > 0;
  } catch {
    return false;
  }
}
