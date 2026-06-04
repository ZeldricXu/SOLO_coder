import { z } from 'zod';
import { router, protectedProcedure } from '../trpc';
import { PostgresSearchService } from '../../lib/search/PostgresSearchService';
import { SearchQuery, HighlightQuery, SuggestionQuery } from '../../lib/search/types';

export const searchRouter = router({
  search: protectedProcedure
    .input(
      z.object({
        query: z.string().min(1, '搜索关键词不能为空'),
        spaceId: z.string().cuid().optional(),
        tagIds: z.array(z.string().cuid()).optional(),
        dateFrom: z.date().optional(),
        dateTo: z.date().optional(),
        source: z.enum(['MANUAL', 'IMPORTED', 'SYNCED', 'API']).optional(),
        page: z.number().min(1).default(1),
        pageSize: z.number().min(1).max(100).default(20),
        includeOcr: z.boolean().default(true),
        sortBy: z.enum(['relevance', 'updatedAt', 'createdAt', 'title']).default('updatedAt'),
        sortOrder: z.enum(['asc', 'desc']).default('desc'),
      })
    )
    .query(async ({ ctx, input }) => {
      const searchService = new PostgresSearchService(ctx.prisma);

      const query: SearchQuery = {
        query: input.query,
        spaceId: input.spaceId,
        tagIds: input.tagIds,
        dateFrom: input.dateFrom,
        dateTo: input.dateTo,
        source: input.source,
        userId: ctx.user.id,
        page: input.page,
        pageSize: input.pageSize,
        includeOcr: input.includeOcr,
        sortBy: input.sortBy,
        sortOrder: input.sortOrder,
      };

      return searchService.search(query);
    }),

  highlight: protectedProcedure
    .input(
      z.object({
        documentId: z.string().cuid(),
        query: z.string().min(1, '搜索关键词不能为空'),
      })
    )
    .query(async ({ ctx, input }) => {
      const searchService = new PostgresSearchService(ctx.prisma);

      const query: HighlightQuery = {
        documentId: input.documentId,
        query: input.query,
        userId: ctx.user.id,
      };

      return searchService.highlight(query);
    }),

  suggest: protectedProcedure
    .input(
      z.object({
        query: z.string().min(1, '搜索关键词不能为空'),
        spaceId: z.string().cuid().optional(),
        limit: z.number().min(1).max(20).default(8),
      })
    )
    .query(async ({ ctx, input }) => {
      const searchService = new PostgresSearchService(ctx.prisma);

      const query: SuggestionQuery = {
        query: input.query,
        spaceId: input.spaceId,
        userId: ctx.user.id,
        limit: input.limit,
      };

      return searchService.suggest(query);
    }),
});
