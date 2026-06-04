import { z } from 'zod';
import { TRPCError } from '@trpc/server';
import { router, protectedProcedure } from '../trpc';
import { DocumentType } from '../../lib/nlp/types';
import { getTaggingService } from '../services/TaggingService';

export const tagRouter = router({
  create: protectedProcedure
    .input(
      z.object({
        spaceId: z.string().cuid(),
        name: z.string().min(1, '标签名称不能为空').max(50, '标签名称最多50个字符'),
        color: z.string().regex(/^#[0-9A-Fa-f]{6}$/, '颜色格式不正确').optional(),
      })
    )
    .mutation(async ({ ctx, input }) => {
      const membership = await ctx.prisma.spaceMember.findFirst({
        where: {
          spaceId: input.spaceId,
          userId: ctx.user.id,
          role: { in: ['OWNER', 'ADMIN', 'EDITOR'] },
        },
      });

      if (!membership) {
        throw new TRPCError({
          code: 'FORBIDDEN',
          message: '无权限创建标签',
        });
      }

      const existingTag = await ctx.prisma.tag.findFirst({
        where: {
          spaceId: input.spaceId,
          name: {
            equals: input.name,
            mode: 'insensitive',
          },
        },
      });

      if (existingTag) {
        throw new TRPCError({
          code: 'CONFLICT',
          message: '标签已存在',
        });
      }

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
      const randomColor = colors[Math.floor(Math.random() * colors.length)];

      const tag = await ctx.prisma.tag.create({
        data: {
          spaceId: input.spaceId,
          name: input.name,
          color: input.color || randomColor,
          createdById: ctx.user.id,
        },
        include: {
          createdBy: {
            select: {
              id: true,
              name: true,
              email: true,
              avatar: true,
            },
          },
          _count: {
            select: {
              documents: true,
            },
          },
        },
      });

      return tag;
    }),

  list: protectedProcedure
    .input(
      z.object({
        spaceId: z.string().cuid(),
        page: z.number().min(1).default(1),
        pageSize: z.number().min(1).max(100).default(50),
        search: z.string().optional(),
      })
    )
    .query(async ({ ctx, input }) => {
      const membership = await ctx.prisma.spaceMember.findFirst({
        where: {
          spaceId: input.spaceId,
          userId: ctx.user.id,
        },
      });

      if (!membership) {
        throw new TRPCError({
          code: 'FORBIDDEN',
          message: '无权限访问此空间的标签',
        });
      }

      const skip = (input.page - 1) * input.pageSize;

      const where = {
        spaceId: input.spaceId,
        ...(input.search
          ? {
              name: {
                contains: input.search,
                mode: 'insensitive',
              },
            }
          : {}),
      };

      const [tags, total] = await Promise.all([
        ctx.prisma.tag.findMany({
          where,
          skip,
          take: input.pageSize,
          include: {
            createdBy: {
              select: {
                id: true,
                name: true,
                email: true,
                avatar: true,
              },
            },
            _count: {
              select: {
                documents: true,
              },
            },
          },
          orderBy: {
            name: 'asc',
          },
        }),
        ctx.prisma.tag.count({ where }),
      ]);

      return {
        items: tags,
        total,
        page: input.page,
        pageSize: input.pageSize,
        totalPages: Math.ceil(total / input.pageSize),
      };
    }),

  update: protectedProcedure
    .input(
      z.object({
        id: z.string().cuid(),
        name: z.string().min(1, '标签名称不能为空').max(50, '标签名称最多50个字符').optional(),
        color: z.string().regex(/^#[0-9A-Fa-f]{6}$/, '颜色格式不正确').optional(),
      })
    )
    .mutation(async ({ ctx, input }) => {
      const tag = await ctx.prisma.tag.findUnique({
        where: { id: input.id },
        select: {
          id: true,
          spaceId: true,
          name: true,
        },
      });

      if (!tag) {
        throw new TRPCError({
          code: 'NOT_FOUND',
          message: '标签不存在',
        });
      }

      const membership = await ctx.prisma.spaceMember.findFirst({
        where: {
          spaceId: tag.spaceId,
          userId: ctx.user.id,
          role: { in: ['OWNER', 'ADMIN', 'EDITOR'] },
        },
      });

      if (!membership) {
        throw new TRPCError({
          code: 'FORBIDDEN',
          message: '无权限编辑此标签',
        });
      }

      if (input.name && input.name !== tag.name) {
        const existingTag = await ctx.prisma.tag.findFirst({
          where: {
            spaceId: tag.spaceId,
            name: {
              equals: input.name,
              mode: 'insensitive',
            },
            id: { not: tag.id },
          },
        });

        if (existingTag) {
          throw new TRPCError({
            code: 'CONFLICT',
            message: '标签已存在',
          });
        }
      }

      const updatedTag = await ctx.prisma.tag.update({
        where: { id: tag.id },
        data: {
          ...(input.name !== undefined ? { name: input.name } : {}),
          ...(input.color !== undefined ? { color: input.color } : {}),
        },
        include: {
          createdBy: {
            select: {
              id: true,
              name: true,
              email: true,
              avatar: true,
            },
          },
          _count: {
            select: {
              documents: true,
            },
          },
        },
      });

      return updatedTag;
    }),

  delete: protectedProcedure
    .input(
      z.object({
        id: z.string().cuid(),
      })
    )
    .mutation(async ({ ctx, input }) => {
      const tag = await ctx.prisma.tag.findUnique({
        where: { id: input.id },
        select: {
          id: true,
          spaceId: true,
        },
      });

      if (!tag) {
        throw new TRPCError({
          code: 'NOT_FOUND',
          message: '标签不存在',
        });
      }

      const membership = await ctx.prisma.spaceMember.findFirst({
        where: {
          spaceId: tag.spaceId,
          userId: ctx.user.id,
          role: { in: ['OWNER', 'ADMIN', 'EDITOR'] },
        },
      });

      if (!membership) {
        throw new TRPCError({
          code: 'FORBIDDEN',
          message: '无权限删除此标签',
        });
      }

      await ctx.prisma.tag.delete({
        where: { id: tag.id },
      });

      return { success: true };
    }),

  assignToDocument: protectedProcedure
    .input(
      z.object({
        tagId: z.string().cuid(),
        documentId: z.string().cuid(),
      })
    )
    .mutation(async ({ ctx, input }) => {
      const [tag, document] = await Promise.all([
        ctx.prisma.tag.findUnique({
          where: { id: input.tagId },
          select: {
            id: true,
            spaceId: true,
          },
        }),
        ctx.prisma.document.findUnique({
          where: { id: input.documentId },
          select: {
            id: true,
            spaceId: true,
          },
        }),
      ]);

      if (!tag || !document) {
        throw new TRPCError({
          code: 'NOT_FOUND',
          message: '标签或文档不存在',
        });
      }

      if (tag.spaceId !== document.spaceId) {
        throw new TRPCError({
          code: 'BAD_REQUEST',
          message: '标签和文档不在同一空间',
        });
      }

      const membership = await ctx.prisma.spaceMember.findFirst({
        where: {
          spaceId: tag.spaceId,
          userId: ctx.user.id,
          role: { in: ['OWNER', 'ADMIN', 'EDITOR'] },
        },
      });

      if (!membership) {
        throw new TRPCError({
          code: 'FORBIDDEN',
          message: '无权限分配标签',
        });
      }

      await ctx.prisma.document.update({
        where: { id: document.id },
        data: {
          tags: {
            connect: { id: tag.id },
          },
        },
      });

      return { success: true };
    }),

  removeFromDocument: protectedProcedure
    .input(
      z.object({
        tagId: z.string().cuid(),
        documentId: z.string().cuid(),
      })
    )
    .mutation(async ({ ctx, input }) => {
      const [tag, document] = await Promise.all([
        ctx.prisma.tag.findUnique({
          where: { id: input.tagId },
          select: {
            id: true,
            spaceId: true,
          },
        }),
        ctx.prisma.document.findUnique({
          where: { id: input.documentId },
          select: {
            id: true,
            spaceId: true,
          },
        }),
      ]);

      if (!tag || !document) {
        throw new TRPCError({
          code: 'NOT_FOUND',
          message: '标签或文档不存在',
        });
      }

      const membership = await ctx.prisma.spaceMember.findFirst({
        where: {
          spaceId: tag.spaceId,
          userId: ctx.user.id,
          role: { in: ['OWNER', 'ADMIN', 'EDITOR'] },
        },
      });

      if (!membership) {
        throw new TRPCError({
          code: 'FORBIDDEN',
          message: '无权限移除标签',
        });
      }

      await ctx.prisma.document.update({
        where: { id: document.id },
        data: {
          tags: {
            disconnect: { id: tag.id },
          },
        },
      });

      return { success: true };
    }),

  autoSuggest: protectedProcedure
    .input(
      z.object({
        spaceId: z.string().cuid(),
        query: z.string().min(1, '查询关键词不能为空'),
        limit: z.number().min(1).max(20).default(10),
      })
    )
    .query(async ({ ctx, input }) => {
      const membership = await ctx.prisma.spaceMember.findFirst({
        where: {
          spaceId: input.spaceId,
          userId: ctx.user.id,
        },
      });

      if (!membership) {
        throw new TRPCError({
          code: 'FORBIDDEN',
          message: '无权限访问此空间的标签',
        });
      }

      const tags = await ctx.prisma.tag.findMany({
        where: {
          spaceId: input.spaceId,
          name: {
            contains: input.query,
            mode: 'insensitive',
          },
        },
        take: input.limit,
        select: {
          id: true,
          name: true,
          color: true,
        },
        orderBy: {
          name: 'asc',
        },
      });

      return tags;
    }),

  suggestTags: protectedProcedure
    .input(
      z.object({
        spaceId: z.string().cuid(),
        title: z.string(),
        content: z.string().optional().default(''),
        maxTags: z.number().min(1).max(50).default(10),
        minConfidence: z.number().min(0).max(1).default(0.3),
        includeClassificationTags: z.boolean().default(true),
      })
    )
    .query(async ({ ctx, input }) => {
      const membership = await ctx.prisma.spaceMember.findFirst({
        where: {
          spaceId: input.spaceId,
          userId: ctx.user.id,
        },
      });

      if (!membership) {
        throw new TRPCError({
          code: 'FORBIDDEN',
          message: '无权限访问此空间的标签',
        });
      }

      const taggingService = getTaggingService(ctx.prisma);
      const result = await taggingService.suggestTags(
        {
          title: input.title,
          content: input.content,
          spaceId: input.spaceId,
        },
        {
          maxTags: input.maxTags,
          minConfidence: input.minConfidence,
          includeClassificationTags: input.includeClassificationTags,
        }
      );

      return result;
    }),

  autoTagDocument: protectedProcedure
    .input(
      z.object({
        documentId: z.string().cuid(),
        spaceId: z.string().cuid(),
        maxTags: z.number().min(1).max(50).default(10),
        minConfidence: z.number().min(0).max(1).default(0.5),
      })
    )
    .mutation(async ({ ctx, input }) => {
      const membership = await ctx.prisma.spaceMember.findFirst({
        where: {
          spaceId: input.spaceId,
          userId: ctx.user.id,
          role: { in: ['OWNER', 'ADMIN', 'EDITOR'] },
        },
      });

      if (!membership) {
        throw new TRPCError({
          code: 'FORBIDDEN',
          message: '无权限编辑此文档的标签',
        });
      }

      const taggingService = getTaggingService(ctx.prisma);
      const result = await taggingService.autoTagDocument(
        input.documentId,
        input.spaceId,
        ctx.user.id,
        {
          maxTags: input.maxTags,
          minConfidence: input.minConfidence,
        }
      );

      return result;
    }),

  classifyAndTag: protectedProcedure
    .input(
      z.object({
        documentId: z.string().cuid(),
        spaceId: z.string().cuid(),
      })
    )
    .mutation(async ({ ctx, input }) => {
      const membership = await ctx.prisma.spaceMember.findFirst({
        where: {
          spaceId: input.spaceId,
          userId: ctx.user.id,
          role: { in: ['OWNER', 'ADMIN', 'EDITOR'] },
        },
      });

      if (!membership) {
        throw new TRPCError({
          code: 'FORBIDDEN',
          message: '无权限编辑此文档',
        });
      }

      const taggingService = getTaggingService(ctx.prisma);
      const result = await taggingService.classifyAndTag(
        input.documentId,
        input.spaceId,
        ctx.user.id
      );

      return result;
    }),

  classifyDocument: protectedProcedure
    .input(
      z.object({
        title: z.string(),
        content: z.string().optional().default(''),
      })
    )
    .query(async ({ ctx, input }) => {
      const { classifyDocument } = await import('../../lib/nlp/DocumentClassifier');
      const result = classifyDocument(input.title, input.content);
      return result;
    }),

  mergeTags: protectedProcedure
    .input(
      z.object({
        spaceId: z.string().cuid(),
        sourceTagIds: z.array(z.string().cuid()).min(1),
        targetTagId: z.string().cuid(),
      })
    )
    .mutation(async ({ ctx, input }) => {
      const membership = await ctx.prisma.spaceMember.findFirst({
        where: {
          spaceId: input.spaceId,
          userId: ctx.user.id,
          role: { in: ['OWNER', 'ADMIN'] },
        },
      });

      if (!membership) {
        throw new TRPCError({
          code: 'FORBIDDEN',
          message: '无权限管理此空间的标签',
        });
      }

      const taggingService = getTaggingService(ctx.prisma);
      const result = await taggingService.mergeTags(
        input.spaceId,
        input.sourceTagIds,
        input.targetTagId,
        ctx.user.id
      );

      return result;
    }),

  getUsageStats: protectedProcedure
    .input(
      z.object({
        spaceId: z.string().cuid(),
        days: z.number().min(1).max(365).default(30),
        limit: z.number().min(1).max(200).default(50),
      })
    )
    .query(async ({ ctx, input }) => {
      const membership = await ctx.prisma.spaceMember.findFirst({
        where: {
          spaceId: input.spaceId,
          userId: ctx.user.id,
        },
      });

      if (!membership) {
        throw new TRPCError({
          code: 'FORBIDDEN',
          message: '无权限访问此空间的标签',
        });
      }

      const taggingService = getTaggingService(ctx.prisma);
      const result = await taggingService.getTagUsageStats(input.spaceId, {
        days: input.days,
        limit: input.limit,
      });

      return result;
    }),

  getTrendingTags: protectedProcedure
    .input(
      z.object({
        spaceId: z.string().cuid(),
        days: z.number().min(1).max(365).default(7),
        limit: z.number().min(1).max(100).default(20),
      })
    )
    .query(async ({ ctx, input }) => {
      const membership = await ctx.prisma.spaceMember.findFirst({
        where: {
          spaceId: input.spaceId,
          userId: ctx.user.id,
        },
      });

      if (!membership) {
        throw new TRPCError({
          code: 'FORBIDDEN',
          message: '无权限访问此空间的标签',
        });
      }

      const taggingService = getTaggingService(ctx.prisma);
      const result = await taggingService.getTrendingTags(input.spaceId, {
        days: input.days,
        limit: input.limit,
      });

      return result;
    }),

  applySuggestedTags: protectedProcedure
    .input(
      z.object({
        documentId: z.string().cuid(),
        spaceId: z.string().cuid(),
        tagNames: z.array(z.string()).min(1),
      })
    )
    .mutation(async ({ ctx, input }) => {
      const membership = await ctx.prisma.spaceMember.findFirst({
        where: {
          spaceId: input.spaceId,
          userId: ctx.user.id,
          role: { in: ['OWNER', 'ADMIN', 'EDITOR'] },
        },
      });

      if (!membership) {
        throw new TRPCError({
          code: 'FORBIDDEN',
          message: '无权限编辑此文档的标签',
        });
      }

      const taggingService = getTaggingService(ctx.prisma);
      const results = [];

      for (const tagName of input.tagNames) {
        const tag = await taggingService.getOrCreateTag(
          input.spaceId,
          tagName,
          undefined,
          ctx.user.id,
          true
        );

        await ctx.prisma.documentTag.upsert({
          where: {
            documentId_tagId: {
              documentId: input.documentId,
              tagId: tag.id,
            },
          },
          create: {
            documentId: input.documentId,
            tagId: tag.id,
            assignedById: ctx.user.id,
          },
          update: {},
        });

        results.push({
          tagId: tag.id,
          tagName: tag.name,
          color: tag.color,
          isNew: tag.isNew,
        });
      }

      return { tags: results };
    }),

  listAll: protectedProcedure
    .input(
      z.object({
        page: z.number().min(1).default(1),
        pageSize: z.number().min(1).max(200).default(50),
        search: z.string().optional(),
      })
    )
    .query(async ({ ctx, input }) => {
      const skip = (input.page - 1) * input.pageSize;

      const where = {
        space: {
          members: {
            some: {
              userId: ctx.user.id,
            },
          },
        },
        ...(input.search
          ? {
              name: {
                contains: input.search,
                mode: 'insensitive',
              },
            }
          : {}),
      };

      const [tags, total] = await Promise.all([
        ctx.prisma.tag.findMany({
          where,
          skip,
          take: input.pageSize,
          include: {
            createdBy: {
              select: {
                id: true,
                name: true,
                email: true,
                avatar: true,
              },
            },
            _count: {
              select: {
                documents: true,
              },
            },
          },
          orderBy: {
            name: 'asc',
          },
        }),
        ctx.prisma.tag.count({ where }),
      ]);

      return {
        items: tags,
        total,
        page: input.page,
        pageSize: input.pageSize,
        totalPages: Math.ceil(total / input.pageSize),
      };
    }),
});
