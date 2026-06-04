import { z } from 'zod';
import { TRPCError } from '@trpc/server';
import { router, protectedProcedure } from '../trpc';

export const commentRouter = router({
  create: protectedProcedure
    .input(
      z.object({
        documentId: z.string().cuid(),
        parentId: z.string().cuid().optional(),
        content: z.string().min(1, '评论内容不能为空').max(5000, '评论内容最多5000个字符'),
        selection: z
          .object({
            text: z.string(),
            start: z.number(),
            end: z.number(),
          })
          .optional(),
      })
    )
    .mutation(async ({ ctx, input }) => {
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

      if (input.parentId) {
        const parentComment = await ctx.prisma.comment.findUnique({
          where: {
            id: input.parentId,
            documentId: document.id,
          },
        });

        if (!parentComment) {
          throw new TRPCError({
            code: 'NOT_FOUND',
            message: '父评论不存在',
          });
        }
      }

      const comment = await ctx.prisma.comment.create({
        data: {
          documentId: document.id,
          parentId: input.parentId,
          content: input.content,
          selectionText: input.selection?.text,
          selectionStart: input.selection?.start,
          selectionEnd: input.selection?.end,
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
          parent: {
            include: {
              createdBy: {
                select: {
                  id: true,
                  name: true,
                  avatar: true,
                },
              },
            },
          },
          replies: {
            include: {
              createdBy: {
                select: {
                  id: true,
                  name: true,
                  email: true,
                  avatar: true,
                },
              },
            },
            orderBy: {
              createdAt: 'asc',
            },
          },
        },
      });

      return comment;
    }),

  list: protectedProcedure
    .input(
      z.object({
        documentId: z.string().cuid(),
        page: z.number().min(1).default(1),
        pageSize: z.number().min(1).max(100).default(20),
        includeResolved: z.boolean().default(false),
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
        },
      });

      if (!document) {
        throw new TRPCError({
          code: 'NOT_FOUND',
          message: '文档不存在或无权限访问',
        });
      }

      const skip = (input.page - 1) * input.pageSize;

      const where = {
        documentId: document.id,
        parentId: null,
        ...(input.includeResolved ? {} : { resolvedAt: null }),
      };

      const [comments, total] = await Promise.all([
        ctx.prisma.comment.findMany({
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
            resolvedBy: {
              select: {
                id: true,
                name: true,
                email: true,
                avatar: true,
              },
            },
            replies: {
              where: input.includeResolved ? {} : { resolvedAt: null },
              include: {
                createdBy: {
                  select: {
                    id: true,
                    name: true,
                    email: true,
                    avatar: true,
                  },
                },
                resolvedBy: {
                  select: {
                    id: true,
                    name: true,
                    email: true,
                    avatar: true,
                  },
                },
              },
              orderBy: {
                createdAt: 'asc',
              },
            },
            _count: {
              select: {
                replies: true,
              },
            },
          },
          orderBy: {
            createdAt: 'desc',
          },
        }),
        ctx.prisma.comment.count({ where }),
      ]);

      return {
        items: comments,
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
        content: z.string().min(1, '评论内容不能为空').max(5000, '评论内容最多5000个字符'),
      })
    )
    .mutation(async ({ ctx, input }) => {
      const comment = await ctx.prisma.comment.findUnique({
        where: { id: input.id },
        include: {
          document: {
            select: {
              id: true,
              spaceId: true,
            },
          },
        },
      });

      if (!comment) {
        throw new TRPCError({
          code: 'NOT_FOUND',
          message: '评论不存在',
        });
      }

      const membership = await ctx.prisma.spaceMember.findFirst({
        where: {
          spaceId: comment.document.spaceId,
          userId: ctx.user.id,
        },
      });

      if (!membership) {
        throw new TRPCError({
          code: 'FORBIDDEN',
          message: '无权限访问此文档的评论',
        });
      }

      if (comment.createdById !== ctx.user.id) {
        throw new TRPCError({
          code: 'FORBIDDEN',
          message: '只能编辑自己的评论',
        });
      }

      const updatedComment = await ctx.prisma.comment.update({
        where: { id: comment.id },
        data: {
          content: input.content,
          updatedAt: new Date(),
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
          parent: {
            include: {
              createdBy: {
                select: {
                  id: true,
                  name: true,
                  avatar: true,
                },
              },
            },
          },
          replies: {
            include: {
              createdBy: {
                select: {
                  id: true,
                  name: true,
                  email: true,
                  avatar: true,
                },
              },
            },
            orderBy: {
              createdAt: 'asc',
            },
          },
        },
      });

      return updatedComment;
    }),

  resolve: protectedProcedure
    .input(
      z.object({
        id: z.string().cuid(),
      })
    )
    .mutation(async ({ ctx, input }) => {
      const comment = await ctx.prisma.comment.findUnique({
        where: { id: input.id },
        include: {
          document: {
            select: {
              id: true,
              spaceId: true,
            },
          },
        },
      });

      if (!comment) {
        throw new TRPCError({
          code: 'NOT_FOUND',
          message: '评论不存在',
        });
      }

      const membership = await ctx.prisma.spaceMember.findFirst({
        where: {
          spaceId: comment.document.spaceId,
          userId: ctx.user.id,
          role: { in: ['OWNER', 'ADMIN', 'EDITOR'] },
        },
      });

      if (!membership) {
        throw new TRPCError({
          code: 'FORBIDDEN',
          message: '无权限解决此评论',
        });
      }

      if (comment.resolvedAt) {
        throw new TRPCError({
          code: 'BAD_REQUEST',
          message: '评论已被解决',
        });
      }

      const resolvedComment = await ctx.prisma.comment.update({
        where: { id: comment.id },
        data: {
          resolvedAt: new Date(),
          resolvedById: ctx.user.id,
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
          resolvedBy: {
            select: {
              id: true,
              name: true,
              email: true,
              avatar: true,
            },
          },
          parent: {
            include: {
              createdBy: {
                select: {
                  id: true,
                  name: true,
                  avatar: true,
                },
              },
            },
          },
          replies: {
            include: {
              createdBy: {
                select: {
                  id: true,
                  name: true,
                  email: true,
                  avatar: true,
                },
              },
            },
            orderBy: {
              createdAt: 'asc',
            },
          },
        },
      });

      if (comment.parentId === null) {
        await ctx.prisma.comment.updateMany({
          where: {
            parentId: comment.id,
          },
          data: {
            resolvedAt: new Date(),
            resolvedById: ctx.user.id,
          },
        });
      }

      return resolvedComment;
    }),

  delete: protectedProcedure
    .input(
      z.object({
        id: z.string().cuid(),
      })
    )
    .mutation(async ({ ctx, input }) => {
      const comment = await ctx.prisma.comment.findUnique({
        where: { id: input.id },
        include: {
          document: {
            select: {
              id: true,
              spaceId: true,
            },
          },
        },
      });

      if (!comment) {
        throw new TRPCError({
          code: 'NOT_FOUND',
          message: '评论不存在',
        });
      }

      const membership = await ctx.prisma.spaceMember.findFirst({
        where: {
          spaceId: comment.document.spaceId,
          userId: ctx.user.id,
          role: { in: ['OWNER', 'ADMIN'] },
        },
      });

      if (comment.createdById !== ctx.user.id && !membership) {
        throw new TRPCError({
          code: 'FORBIDDEN',
          message: '只能删除自己的评论或拥有管理员权限',
        });
      }

      await ctx.prisma.comment.deleteMany({
        where: {
          parentId: comment.id,
        },
      });

      await ctx.prisma.comment.delete({
        where: { id: comment.id },
      });

      return { success: true };
    }),
});
