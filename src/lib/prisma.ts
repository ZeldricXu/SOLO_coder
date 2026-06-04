import { PrismaClient, Prisma } from '@prisma/client';
import {
  SearchQuery,
  SearchResult,
  IndexUpdateResult,
  BatchIndexResult,
} from './search/types';
import { executeSearch, buildSearchQuery } from './search/engine';
import {
  updateDocumentVector,
  updateDocumentVectorWithContent,
  rebuildAllIndexes,
  rebuildIndexesForSpace,
  checkZhparserAvailable,
} from './search/indexer';
import { highlightSearchResults } from './search/highlighter';

const globalForPrisma = globalThis as unknown as {
  prisma: PrismaClient | undefined;
};

export const prisma =
  globalForPrisma.prisma ??
  new PrismaClient({
    log:
      process.env.NODE_ENV === 'development'
        ? ['query', 'error', 'warn']
        : ['error'],
  });

if (process.env.NODE_ENV !== 'production') globalForPrisma.prisma = prisma;

export const searchExtension = Prisma.defineExtension({
  name: 'search',
  model: {
    document: {
      async search(query: SearchQuery): Promise<SearchResult> {
        const useZhparser = await checkZhparserAvailable(prisma);
        return executeSearch(prisma, query, useZhparser);
      },

      async buildSearchQuery(query: SearchQuery) {
        const useZhparser = await checkZhparserAvailable(prisma);
        return buildSearchQuery(query, useZhparser);
      },

      async updateSearchVector(
        documentId: string
      ): Promise<IndexUpdateResult> {
        const useZhparser = await checkZhparserAvailable(prisma);
        return updateDocumentVector(prisma, documentId, useZhparser);
      },

      async updateSearchVectorWithContent(
        documentId: string,
        title: string,
        content: string
      ): Promise<IndexUpdateResult> {
        const useZhparser = await checkZhparserAvailable(prisma);
        return updateDocumentVectorWithContent(
          prisma,
          documentId,
          title,
          content,
          useZhparser
        );
      },

      async rebuildAllSearchIndexes(
        batchSize: number = 100,
        userId?: string
      ): Promise<BatchIndexResult> {
        const useZhparser = await checkZhparserAvailable(prisma);
        return rebuildAllIndexes(prisma, batchSize, useZhparser, userId);
      },

      async rebuildSearchIndexesForSpace(
        spaceId: string,
        batchSize: number = 100
      ): Promise<BatchIndexResult> {
        const useZhparser = await checkZhparserAvailable(prisma);
        return rebuildIndexesForSpace(prisma, spaceId, batchSize, useZhparser);
      },

      async isZhparserAvailable(): Promise<boolean> {
        return checkZhparserAvailable(prisma);
      },
    },
  },
  query: {
    document: {
      async create({ args, query }) {
        const result = await query(args);
        try {
          const useZhparser = await checkZhparserAvailable(prisma);
          await updateDocumentVectorWithContent(
            prisma,
            result.id,
            result.title,
            result.content,
            useZhparser
          );
        } catch (e) {
          console.warn('Failed to update search vector after create:', e);
        }
        return result;
      },

      async update({ args, query }) {
        const result = await query(args);
        try {
          if (args.data.title || args.data.content) {
            const useZhparser = await checkZhparserAvailable(prisma);
            await updateDocumentVector(prisma, result.id, useZhparser);
          }
        } catch (e) {
          console.warn('Failed to update search vector after update:', e);
        }
        return result;
      },

      async createMany({ args, query }) {
        const result = await query(args);
        return result;
      },

      async updateMany({ args, query }) {
        const result = await query(args);
        return result;
      },
    },
  },
  result: {
    document: {
      highlightedTitle: {
        needs: { title: true },
        compute(doc) {
          return (query: string) => {
            const { highlightText } = require('./search/highlighter');
            return highlightText(doc.title, query);
          };
        },
      },
      highlightedContent: {
        needs: { content: true },
        compute(doc) {
          return (query: string) => {
            const { highlightText } = require('./search/highlighter');
            return highlightText(doc.content, query);
          };
        },
      },
    },
  },
});

export const extendedPrisma = prisma.$extends(searchExtension);

export type ExtendedPrismaClient = typeof extendedPrisma;

export default prisma;
