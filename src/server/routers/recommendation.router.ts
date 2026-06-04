import { z } from 'zod';
import { TRPCError } from '@trpc/server';
import { router, protectedProcedure } from '../trpc';
import { buildKnowledgeGraph } from '@/lib/knowledge-graph/graph-builder';
import {
  MultiFactorRecommender,
  DEFAULT_WEIGHTS,
} from '@/lib/knowledge-graph/multi-factor-recommender';
import { ViewLogService } from '../services/ViewLogService';

export const recommendationRouter = router({
  getRelatedDocuments: protectedProcedure
    .input(
      z.object({
        documentId: z.string().cuid(),
        limit: z.number().min(1).max(20).default(10),
      })
    )
    .query(async ({ ctx, input }) => {
      const document = await ctx.prisma.document.findUnique({
        where: {
          id: input.documentId,
          space: {
            members: {
              some: {
                userId: ctx.user.id,
              },
            },
          },
        },
        select: {
          id: true,
          spaceId: true,
        },
      });

      if (!document) {
        throw new TRPCError({
          code: 'NOT_FOUND',
          message: '文档不存在或无权限访问',
        });
      }

      const graph = await buildKnowledgeGraph(ctx.prisma, {
        spaceId: document.spaceId,
      });

      const recommender = new MultiFactorRecommender(ctx.prisma, graph, DEFAULT_WEIGHTS);
      const recommendations = await recommender.getRelatedDocuments(
        input.documentId,
        {
          limit: input.limit,
          excludeIds: [input.documentId],
          userId: ctx.user.id,
        }
      );

      const documentIds = recommendations.map((r) => r.documentId);
      const documents = await ctx.prisma.document.findMany({
        where: { id: { in: documentIds } },
        include: {
          createdBy: {
            select: {
              id: true,
              name: true,
              email: true,
              avatar: true,
            },
          },
          tags: {
            select: {
              id: true,
              name: true,
              color: true,
            },
          },
          space: {
            select: {
              id: true,
              name: true,
              icon: true,
            },
          },
          _count: {
            select: {
              comments: true,
              versions: true,
            },
          },
        },
      });

      const docMap = new Map(documents.map((d) => [d.id, d]));

      const items = recommendations
        .map((rec) => {
          const doc = docMap.get(rec.documentId);
          if (!doc) return null;
          return {
            ...doc,
            relevanceScore: rec.score,
            reasons: rec.reasons,
            factorScores: rec.factorScores,
          };
        })
        .filter(Boolean);

      return {
        items,
        total: items.length,
        weights: DEFAULT_WEIGHTS,
      };
    }),

  logDocumentView: protectedProcedure
    .input(
      z.object({
        documentId: z.string().cuid(),
        spaceId: z.string().cuid(),
        durationMs: z.number().optional(),
      })
    )
    .mutation(async ({ ctx, input }) => {
      const document = await ctx.prisma.document.findUnique({
        where: {
          id: input.documentId,
          spaceId: input.spaceId,
          space: {
            members: {
              some: {
                userId: ctx.user.id,
              },
            },
          },
        },
        select: { id: true },
      });

      if (!document) {
        throw new TRPCError({
          code: 'NOT_FOUND',
          message: '文档不存在或无权限访问',
        });
      }

      const viewLogService = new ViewLogService(ctx.prisma);
      await viewLogService.logView({
        userId: ctx.user.id,
        documentId: input.documentId,
        spaceId: input.spaceId,
        durationMs: input.durationMs,
      });

      return { success: true };
    }),

  getGraphNeighbors: protectedProcedure
    .input(
      z.object({
        documentId: z.string().cuid(),
        depth: z.number().min(1).max(3).default(2),
        limit: z.number().min(1).max(50).default(30),
      })
    )
    .query(async ({ ctx, input }) => {
      const document = await ctx.prisma.document.findUnique({
        where: {
          id: input.documentId,
          space: {
            members: {
              some: {
                userId: ctx.user.id,
              },
            },
          },
        },
        include: {
          tags: {
            select: {
              id: true,
              name: true,
              color: true,
            },
          },
          parent: {
            select: {
              id: true,
              title: true,
              path: true,
            },
          },
          children: {
            where: {
              status: { not: 'DELETED' },
            },
            select: {
              id: true,
              title: true,
              path: true,
            },
          },
        },
      });

      if (!document) {
        throw new TRPCError({
          code: 'NOT_FOUND',
          message: '文档不存在或无权限访问',
        });
      }

      const tagIds = document.tags.map((tag) => tag.id);

      const nodes: Array<{
        id: string;
        title: string;
        type: 'document' | 'tag';
        depth: number;
        data?: any;
      }> = [
        {
          id: document.id,
          title: document.title,
          type: 'document',
          depth: 0,
          data: {
            path: document.path,
            tags: document.tags,
          },
        },
      ];

      const edges: Array<{
        source: string;
        target: string;
        relationship: string;
      }> = [];

      document.tags.forEach((tag) => {
        if (!nodes.some((n) => n.id === `tag-${tag.id}`)) {
          nodes.push({
            id: `tag-${tag.id}`,
            title: tag.name,
            type: 'tag',
            depth: 1,
            data: {
              color: tag.color,
            },
          });
        }
        edges.push({
          source: document.id,
          target: `tag-${tag.id}`,
          relationship: 'tagged',
        });
      });

      if (document.parent) {
        if (!nodes.some((n) => n.id === document.parent!.id)) {
          nodes.push({
            id: document.parent.id,
            title: document.parent.title,
            type: 'document',
            depth: 1,
            data: {
              path: document.parent.path,
            },
          });
        }
        edges.push({
          source: document.parent.id,
          target: document.id,
          relationship: 'parent',
        });
      }

      document.children.forEach((child) => {
        if (!nodes.some((n) => n.id === child.id)) {
          nodes.push({
            id: child.id,
            title: child.title,
            type: 'document',
            depth: 1,
            data: {
              path: child.path,
            },
          });
        }
        edges.push({
          source: document.id,
          target: child.id,
          relationship: 'child',
        });
      });

      if (input.depth >= 2 && tagIds.length > 0) {
        const relatedDocs = await ctx.prisma.document.findMany({
          where: {
            id: { not: document.id },
            status: { not: 'DELETED' },
            spaceId: document.spaceId,
            tags: {
              some: {
                id: { in: tagIds },
              },
            },
          },
          take: Math.min(input.limit - nodes.length, 20),
          include: {
            tags: {
              select: {
                id: true,
                name: true,
                color: true,
              },
            },
          },
        });

        relatedDocs.forEach((doc) => {
          if (!nodes.some((n) => n.id === doc.id)) {
            const sharedTags = tagIds.filter((tid) =>
              doc.tags.some((t) => t.id === tid)
            );
            nodes.push({
              id: doc.id,
              title: doc.title,
              type: 'document',
              depth: 2,
              data: {
                tags: doc.tags,
                sharedTags,
              },
            });

            sharedTags.forEach((tid) => {
              if (nodes.some((n) => n.id === `tag-${tid}`)) {
                edges.push({
                  source: `tag-${tid}`,
                  target: doc.id,
                  relationship: 'tagged',
                });
              }
            });
          }
        });
      }

      return {
        rootId: document.id,
        nodes,
        edges,
      };
    }),

  getForUser: protectedProcedure
    .input(
      z.object({
        limit: z.number().min(1).max(20).default(10),
      })
    )
    .query(async ({ ctx, input }) => {
      const viewLogService = new ViewLogService(ctx.prisma);
      const viewedDocIds = await viewLogService.getUserViewedDocuments(
        ctx.user.id,
        10,
        90
      );

      if (viewedDocIds.length > 0) {
        const userSpaces = await ctx.prisma.spaceMember.findMany({
          where: { userId: ctx.user.id },
          select: { spaceId: true },
        });

        const spaceIds = userSpaces.map((m) => m.spaceId);
        const graph = await buildKnowledgeGraph(ctx.prisma, { spaceIds });

        const recommender = new MultiFactorRecommender(
          ctx.prisma,
          graph,
          DEFAULT_WEIGHTS
        );

        const recommendations = await recommender.getPersonalizedRecommendations(
          ctx.user.id,
          viewedDocIds,
          {
            limit: input.limit,
          }
        );

        const documentIds = recommendations.map((r) => r.documentId);
        const documents = await ctx.prisma.document.findMany({
          where: { id: { in: documentIds } },
          include: {
            createdBy: {
              select: {
                id: true,
                name: true,
                email: true,
                avatar: true,
              },
            },
            tags: {
              select: {
                id: true,
                name: true,
                color: true,
              },
            },
            space: {
              select: {
                id: true,
                name: true,
                icon: true,
              },
            },
            _count: {
              select: {
                comments: true,
                versions: true,
              },
            },
          },
        });

        const docMap = new Map(documents.map((d) => [d.id, d]));

        const items = recommendations
          .map((rec) => {
            const doc = docMap.get(rec.documentId);
            if (!doc) return null;
            return {
              ...doc,
              relevanceScore: rec.score,
              reasons: rec.reasons,
              factorScores: rec.factorScores,
            };
          })
          .filter(Boolean);

        return {
          items,
          total: items.length,
          weights: DEFAULT_WEIGHTS,
        };
      }

      const recentDocuments = await ctx.prisma.document.findMany({
        where: {
          status: { not: 'DELETED' },
          space: {
            members: {
              some: {
                userId: ctx.user.id,
              },
            },
          },
        },
        take: input.limit,
        orderBy: { updatedAt: 'desc' },
        include: {
          createdBy: {
            select: {
              id: true,
              name: true,
              email: true,
              avatar: true,
            },
          },
          tags: {
            select: { id: true, name: true, color: true },
          },
          space: {
            select: {
              id: true,
              name: true,
              icon: true,
            },
          },
          _count: {
            select: {
              comments: true,
              versions: true,
            },
          },
        },
      });

      const items = recentDocuments.map((doc) => ({
        ...doc,
        relevanceScore: 1.0,
        reasons: ['最近更新'],
        factorScores: {
          contentSimilarity: 0,
          sameAuthor: 0,
          sameSpace: 1,
          collaborativeFiltering: 0,
        },
      }));

      return {
        items,
        total: items.length,
        weights: DEFAULT_WEIGHTS,
      };
    }),
});
