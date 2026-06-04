import { z } from 'zod';
import { TRPCError } from '@trpc/server';
import { nanoid } from 'nanoid';
import * as diff from 'diff';
import { router, protectedProcedure } from '../trpc';

const DocumentStatusSchema = z.enum(['DRAFT', 'PUBLISHED', 'ARCHIVED', 'DELETED']);
const DocumentSourceSchema = z.enum(['MANUAL', 'IMPORTED', 'SYNCED', 'API']);

export const documentRouter = router({
  create: protectedProcedure
    .input(
      z.object({
        spaceId: z.string().cuid(),
        parentId: z.string().cuid().optional(),
        title: z.string().min(1, '标题不能为空').max(200, '标题最多200个字符'),
        content: z.string().default(''),
        summary: z.string().max(500, '摘要最多500个字符').optional(),
        path: z.string().optional(),
        tags: z.array(z.string().cuid()).optional(),
        source: DocumentSourceSchema.default('MANUAL'),
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
          message: '无权限创建文档',
        });
      }

      const path = input.path || `/${nanoid(8)}`;

      const existingPath = await ctx.prisma.document.findFirst({
        where: {
          spaceId: input.spaceId,
          path,
          status: { not: 'DELETED' },
        },
      });

      if (existingPath) {
        throw new TRPCError({
          code: 'CONFLICT',
          message: '路径已存在',
        });
      }

      const document = await ctx.prisma.$transaction(async (prisma) => {
        const doc = await prisma.document.create({
          data: {
            spaceId: input.spaceId,
            parentId: input.parentId,
            title: input.title,
            content: input.content,
            summary: input.summary,
            path,
            source: input.source,
            status: 'DRAFT',
            createdById: ctx.user.id,
            updatedById: ctx.user.id,
            tags: input.tags
              ? {
                  connect: input.tags.map((id) => ({ id })),
                }
              : undefined,
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
            updatedBy: {
              select: {
                id: true,
                name: true,
                email: true,
                avatar: true,
              },
            },
            tags: true,
            _count: {
              select: {
                comments: true,
                versions: true,
              },
            },
          },
        });

        await prisma.documentVersion.create({
          data: {
            documentId: doc.id,
            title: doc.title,
            content: doc.content,
            summary: doc.summary,
            version: 1,
            createdById: ctx.user.id,
          },
        });

        return doc;
      });

      return document;
    }),

  list: protectedProcedure
    .input(
      z.object({
        spaceId: z.string().cuid().optional(),
        parentId: z.string().cuid().optional(),
        page: z.number().min(1).default(1),
        pageSize: z.number().min(1).max(100).default(20),
        search: z.string().optional(),
        status: DocumentStatusSchema.optional(),
        tagIds: z.array(z.string().cuid()).optional(),
        includeDeleted: z.boolean().default(false),
        sortBy: z.enum(['createdAt', 'updatedAt', 'title']).default('updatedAt'),
        sortOrder: z.enum(['asc', 'desc']).default('desc'),
      })
    )
    .query(async ({ ctx, input }) => {
      const skip = (input.page - 1) * input.pageSize;

      const where = {
        ...(input.spaceId ? { spaceId: input.spaceId } : {}),
        ...(input.parentId !== undefined ? { parentId: input.parentId } : {}),
        ...(input.status ? { status: input.status } : {}),
        ...(input.includeDeleted ? {} : { status: { not: 'DELETED' } }),
        ...(input.search
          ? {
              OR: [
                { title: { contains: input.search, mode: 'insensitive' } },
                { content: { contains: input.search, mode: 'insensitive' } },
                { summary: { contains: input.search, mode: 'insensitive' } },
              ],
            }
          : {}),
        ...(input.tagIds && input.tagIds.length > 0
          ? {
              tags: {
                some: {
                  id: { in: input.tagIds },
                },
              },
            }
          : {}),
        space: {
          members: {
            some: {
              userId: ctx.user.id,
            },
          },
        },
      };

      const [documents, total] = await Promise.all([
        ctx.prisma.document.findMany({
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
            updatedBy: {
              select: {
                id: true,
                name: true,
                email: true,
                avatar: true,
              },
            },
            tags: true,
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
          orderBy: {
            [input.sortBy]: input.sortOrder,
          },
        }),
        ctx.prisma.document.count({ where }),
      ]);

      return {
        items: documents,
        total,
        page: input.page,
        pageSize: input.pageSize,
        totalPages: Math.ceil(total / input.pageSize),
      };
    }),

  getById: protectedProcedure
    .input(
      z.object({
        id: z.string().cuid(),
      })
    )
    .query(async ({ ctx, input }) => {
      const document = await ctx.prisma.document.findUnique({
        where: {
          id: input.id,
          space: {
            members: {
              some: {
                userId: ctx.user.id,
              },
            },
          },
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
          updatedBy: {
            select: {
              id: true,
              name: true,
              email: true,
              avatar: true,
            },
          },
          tags: true,
          space: {
            select: {
              id: true,
              name: true,
              icon: true,
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
            orderBy: { title: 'asc' },
          },
          _count: {
            select: {
              comments: true,
              versions: true,
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

      return document;
    }),

  getByPath: protectedProcedure
    .input(
      z.object({
        spaceId: z.string().cuid(),
        path: z.string(),
      })
    )
    .query(async ({ ctx, input }) => {
      const document = await ctx.prisma.document.findFirst({
        where: {
          spaceId: input.spaceId,
          path: input.path,
          status: { not: 'DELETED' },
          space: {
            members: {
              some: {
                userId: ctx.user.id,
              },
            },
          },
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
          updatedBy: {
            select: {
              id: true,
              name: true,
              email: true,
              avatar: true,
            },
          },
          tags: true,
          space: {
            select: {
              id: true,
              name: true,
              icon: true,
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
            orderBy: { title: 'asc' },
          },
          _count: {
            select: {
              comments: true,
              versions: true,
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

      return document;
    }),

  update: protectedProcedure
    .input(
      z.object({
        id: z.string().cuid(),
        title: z.string().min(1, '标题不能为空').max(200, '标题最多200个字符').optional(),
        content: z.string().optional(),
        summary: z.string().max(500, '摘要最多500个字符').optional(),
        path: z.string().optional(),
        status: DocumentStatusSchema.optional(),
        tags: z.array(z.string().cuid()).optional(),
        createVersion: z.boolean().default(true),
        versionMessage: z.string().max(200, '版本说明最多200个字符').optional(),
      })
    )
    .mutation(async ({ ctx, input }) => {
      const document = await ctx.prisma.document.findUnique({
        where: {
          id: input.id,
        },
        select: {
          id: true,
          title: true,
          content: true,
          summary: true,
          path: true,
          spaceId: true,
          _count: {
            select: {
              versions: true,
            },
          },
        },
      });

      if (!document) {
        throw new TRPCError({
          code: 'NOT_FOUND',
          message: '文档不存在',
        });
      }

      const membership = await ctx.prisma.spaceMember.findFirst({
        where: {
          spaceId: document.spaceId,
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

      if (input.path && input.path !== document.path) {
        const existingPath = await ctx.prisma.document.findFirst({
          where: {
            spaceId: document.spaceId,
            path: input.path,
            id: { not: document.id },
            status: { not: 'DELETED' },
          },
        });

        if (existingPath) {
          throw new TRPCError({
            code: 'CONFLICT',
            message: '路径已存在',
          });
        }
      }

      const updatedDocument = await ctx.prisma.$transaction(async (prisma) => {
        const doc = await prisma.document.update({
          where: { id: document.id },
          data: {
            ...(input.title !== undefined ? { title: input.title } : {}),
            ...(input.content !== undefined ? { content: input.content } : {}),
            ...(input.summary !== undefined ? { summary: input.summary } : {}),
            ...(input.path !== undefined ? { path: input.path } : {}),
            ...(input.status !== undefined ? { status: input.status } : {}),
            updatedById: ctx.user.id,
            tags: input.tags
              ? {
                  set: input.tags.map((id) => ({ id })),
                }
              : undefined,
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
            updatedBy: {
              select: {
                id: true,
                name: true,
                email: true,
                avatar: true,
              },
            },
            tags: true,
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

        if (input.createVersion && input.content !== undefined) {
          await prisma.documentVersion.create({
            data: {
              documentId: doc.id,
              title: doc.title,
              content: doc.content,
              summary: doc.summary,
              version: document._count.versions + 1,
              message: input.versionMessage,
              createdById: ctx.user.id,
            },
          });
        }

        return doc;
      });

      return updatedDocument;
    }),

  delete: protectedProcedure
    .input(
      z.object({
        id: z.string().cuid(),
        permanent: z.boolean().default(false),
      })
    )
    .mutation(async ({ ctx, input }) => {
      const document = await ctx.prisma.document.findUnique({
        where: { id: input.id },
        select: {
          id: true,
          spaceId: true,
          status: true,
        },
      });

      if (!document) {
        throw new TRPCError({
          code: 'NOT_FOUND',
          message: '文档不存在',
        });
      }

      const membership = await ctx.prisma.spaceMember.findFirst({
        where: {
          spaceId: document.spaceId,
          userId: ctx.user.id,
          role: { in: ['OWNER', 'ADMIN', 'EDITOR'] },
        },
      });

      if (!membership) {
        throw new TRPCError({
          code: 'FORBIDDEN',
          message: '无权限删除此文档',
        });
      }

      if (input.permanent) {
        await ctx.prisma.document.delete({
          where: { id: document.id },
        });
      } else {
        await ctx.prisma.document.update({
          where: { id: document.id },
          data: {
            status: 'DELETED',
            deletedAt: new Date(),
            updatedById: ctx.user.id,
          },
        });
      }

      return { success: true };
    }),

  move: protectedProcedure
    .input(
      z.object({
        id: z.string().cuid(),
        parentId: z.string().cuid().nullable(),
        newPath: z.string().optional(),
      })
    )
    .mutation(async ({ ctx, input }) => {
      const document = await ctx.prisma.document.findUnique({
        where: { id: input.id },
        select: {
          id: true,
          spaceId: true,
          parentId: true,
          path: true,
        },
      });

      if (!document) {
        throw new TRPCError({
          code: 'NOT_FOUND',
          message: '文档不存在',
        });
      }

      const membership = await ctx.prisma.spaceMember.findFirst({
        where: {
          spaceId: document.spaceId,
          userId: ctx.user.id,
          role: { in: ['OWNER', 'ADMIN', 'EDITOR'] },
        },
      });

      if (!membership) {
        throw new TRPCError({
          code: 'FORBIDDEN',
          message: '无权限移动此文档',
        });
      }

      if (input.parentId === document.id) {
        throw new TRPCError({
          code: 'BAD_REQUEST',
          message: '不能将文档移动到自身',
        });
      }

      if (input.parentId) {
        const parentDoc = await ctx.prisma.document.findUnique({
          where: { id: input.parentId },
          select: {
            id: true,
            spaceId: true,
          },
        });

        if (!parentDoc || parentDoc.spaceId !== document.spaceId) {
          throw new TRPCError({
            code: 'BAD_REQUEST',
            message: '父文档不存在或不在同一空间',
          });
        }
      }

      let path = input.newPath;
      if (!path && input.parentId) {
        const parentDoc = await ctx.prisma.document.findUnique({
          where: { id: input.parentId },
          select: { path: true },
        });
        if (parentDoc) {
          path = `${parentDoc.path}/${nanoid(8)}`;
        }
      }

      if (path && path !== document.path) {
        const existingPath = await ctx.prisma.document.findFirst({
          where: {
            spaceId: document.spaceId,
            path,
            id: { not: document.id },
            status: { not: 'DELETED' },
          },
        });

        if (existingPath) {
          throw new TRPCError({
            code: 'CONFLICT',
            message: '路径已存在',
          });
        }
      }

      const updatedDocument = await ctx.prisma.document.update({
        where: { id: document.id },
        data: {
          parentId: input.parentId,
          ...(path ? { path } : {}),
          updatedById: ctx.user.id,
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
          updatedBy: {
            select: {
              id: true,
              name: true,
              email: true,
              avatar: true,
            },
          },
          tags: true,
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

      return updatedDocument;
    }),

  restore: protectedProcedure
    .input(
      z.object({
        id: z.string().cuid(),
      })
    )
    .mutation(async ({ ctx, input }) => {
      const document = await ctx.prisma.document.findUnique({
        where: { id: input.id },
        select: {
          id: true,
          spaceId: true,
          status: true,
        },
      });

      if (!document) {
        throw new TRPCError({
          code: 'NOT_FOUND',
          message: '文档不存在',
        });
      }

      if (document.status !== 'DELETED') {
        throw new TRPCError({
          code: 'BAD_REQUEST',
          message: '文档未被删除',
        });
      }

      const membership = await ctx.prisma.spaceMember.findFirst({
        where: {
          spaceId: document.spaceId,
          userId: ctx.user.id,
          role: { in: ['OWNER', 'ADMIN', 'EDITOR'] },
        },
      });

      if (!membership) {
        throw new TRPCError({
          code: 'FORBIDDEN',
          message: '无权限恢复此文档',
        });
      }

      const restoredDocument = await ctx.prisma.document.update({
        where: { id: document.id },
        data: {
          status: 'DRAFT',
          deletedAt: null,
          updatedById: ctx.user.id,
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
          updatedBy: {
            select: {
              id: true,
              name: true,
              email: true,
              avatar: true,
            },
          },
          tags: true,
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

      return restoredDocument;
    }),

  listVersions: protectedProcedure
    .input(
      z.object({
        documentId: z.string().cuid(),
        page: z.number().min(1).default(1),
        pageSize: z.number().min(1).max(100).default(20),
      })
    )
    .query(async ({ ctx, input }) => {
      const document = await ctx.prisma.document.findUnique({
        where: { id: input.documentId },
        select: {
          id: true,
          spaceId: true,
        },
      });

      if (!document) {
        throw new TRPCError({
          code: 'NOT_FOUND',
          message: '文档不存在',
        });
      }

      const membership = await ctx.prisma.spaceMember.findFirst({
        where: {
          spaceId: document.spaceId,
          userId: ctx.user.id,
        },
      });

      if (!membership) {
        throw new TRPCError({
          code: 'FORBIDDEN',
          message: '无权限访问此文档的版本历史',
        });
      }

      const skip = (input.page - 1) * input.pageSize;

      const [versions, total] = await Promise.all([
        ctx.prisma.documentVersion.findMany({
          where: { documentId: input.documentId },
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
          },
          orderBy: { version: 'desc' },
        }),
        ctx.prisma.documentVersion.count({
          where: { documentId: input.documentId },
        }),
      ]);

      return {
        items: versions,
        total,
        page: input.page,
        pageSize: input.pageSize,
        totalPages: Math.ceil(total / input.pageSize),
      };
    }),

  getVersion: protectedProcedure
    .input(
      z.object({
        documentId: z.string().cuid(),
        version: z.number().min(1),
      })
    )
    .query(async ({ ctx, input }) => {
      const document = await ctx.prisma.document.findUnique({
        where: { id: input.documentId },
        select: {
          id: true,
          spaceId: true,
        },
      });

      if (!document) {
        throw new TRPCError({
          code: 'NOT_FOUND',
          message: '文档不存在',
        });
      }

      const membership = await ctx.prisma.spaceMember.findFirst({
        where: {
          spaceId: document.spaceId,
          userId: ctx.user.id,
        },
      });

      if (!membership) {
        throw new TRPCError({
          code: 'FORBIDDEN',
          message: '无权限访问此文档的版本',
        });
      }

      const version = await ctx.prisma.documentVersion.findFirst({
        where: {
          documentId: input.documentId,
          version: input.version,
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
        },
      });

      if (!version) {
        throw new TRPCError({
          code: 'NOT_FOUND',
          message: '版本不存在',
        });
      }

      return version;
    }),

  rollbackToVersion: protectedProcedure
    .input(
      z.object({
        documentId: z.string().cuid(),
        version: z.number().min(1),
      })
    )
    .mutation(async ({ ctx, input }) => {
      const document = await ctx.prisma.document.findUnique({
        where: { id: input.documentId },
        select: {
          id: true,
          spaceId: true,
          _count: {
            select: {
              versions: true,
            },
          },
        },
      });

      if (!document) {
        throw new TRPCError({
          code: 'NOT_FOUND',
          message: '文档不存在',
        });
      }

      const membership = await ctx.prisma.spaceMember.findFirst({
        where: {
          spaceId: document.spaceId,
          userId: ctx.user.id,
          role: { in: ['OWNER', 'ADMIN', 'EDITOR'] },
        },
      });

      if (!membership) {
        throw new TRPCError({
          code: 'FORBIDDEN',
          message: '无权限回滚此文档',
        });
      }

      const targetVersion = await ctx.prisma.documentVersion.findFirst({
        where: {
          documentId: input.documentId,
          version: input.version,
        },
      });

      if (!targetVersion) {
        throw new TRPCError({
          code: 'NOT_FOUND',
          message: '目标版本不存在',
        });
      }

      const updatedDocument = await ctx.prisma.$transaction(async (prisma) => {
        const doc = await prisma.document.update({
          where: { id: document.id },
          data: {
            title: targetVersion.title,
            content: targetVersion.content,
            summary: targetVersion.summary,
            updatedById: ctx.user.id,
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
            updatedBy: {
              select: {
                id: true,
                name: true,
                email: true,
                avatar: true,
              },
            },
            tags: true,
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

        await prisma.documentVersion.create({
          data: {
            documentId: doc.id,
            title: doc.title,
            content: doc.content,
            summary: doc.summary,
            version: document._count.versions + 1,
            message: `回滚到版本 ${input.version}`,
            createdById: ctx.user.id,
          },
        });

        return doc;
      });

      return updatedDocument;
    }),

  compareVersions: protectedProcedure
    .input(
      z.object({
        documentId: z.string().cuid(),
        versionFrom: z.number().min(1),
        versionTo: z.number().min(1),
      })
    )
    .query(async ({ ctx, input }) => {
      const document = await ctx.prisma.document.findUnique({
        where: { id: input.documentId },
        select: {
          id: true,
          spaceId: true,
        },
      });

      if (!document) {
        throw new TRPCError({
          code: 'NOT_FOUND',
          message: '文档不存在',
        });
      }

      const membership = await ctx.prisma.spaceMember.findFirst({
        where: {
          spaceId: document.spaceId,
          userId: ctx.user.id,
        },
      });

      if (!membership) {
        throw new TRPCError({
          code: 'FORBIDDEN',
          message: '无权限比较此文档的版本',
        });
      }

      const [versionFrom, versionTo] = await Promise.all([
        ctx.prisma.documentVersion.findFirst({
          where: {
            documentId: input.documentId,
            version: input.versionFrom,
          },
        }),
        ctx.prisma.documentVersion.findFirst({
          where: {
            documentId: input.documentId,
            version: input.versionTo,
          },
        }),
      ]);

      if (!versionFrom || !versionTo) {
        throw new TRPCError({
          code: 'NOT_FOUND',
          message: '版本不存在',
        });
      }

      const contentDiff = diff.diffLines(versionFrom.content, versionTo.content);
      const titleDiff = diff.diffWords(versionFrom.title, versionTo.title);

      return {
        versionFrom: {
          id: versionFrom.id,
          version: versionFrom.version,
          title: versionFrom.title,
          createdAt: versionFrom.createdAt,
          createdById: versionFrom.createdById,
        },
        versionTo: {
          id: versionTo.id,
          version: versionTo.version,
          title: versionTo.title,
          createdAt: versionTo.createdAt,
          createdById: versionTo.createdById,
        },
        contentDiff,
        titleDiff,
      };
    }),
});
