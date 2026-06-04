import type { PrismaClient, Role, Comment as PrismaComment } from '@prisma/client';
import { TRPCError } from '@trpc/server';
import type {
  CommentPosition,
  CommentWithRelations,
  CreateCommentInput,
  ListCommentsInput,
  UpdateCommentInput,
  CommentListResponse,
} from '@/lib/types/comment';

const COMMENT_INCLUDE = {
  author: {
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
  document: {
    select: {
      id: true,
      title: true,
      spaceId: true,
    },
  },
  version: {
    select: {
      id: true,
      version: true,
    },
  },
};

const THREAD_INCLUDE = {
  ...COMMENT_INCLUDE,
  children: {
    include: COMMENT_INCLUDE,
    orderBy: {
      createdAt: 'asc' as const,
    },
  },
};

export class CommentService {
  private prisma: PrismaClient;
  private userId: string;

  constructor(prisma: PrismaClient, userId: string) {
    this.prisma = prisma;
    this.userId = userId;
  }

  async validatePosition(
    documentId: string,
    position: CommentPosition
  ): Promise<boolean> {
    const document = await this.prisma.document.findUnique({
      where: { id: documentId },
      select: { content: true, contentHtml: true },
    });

    if (!document?.content) {
      return false;
    }

    const { start, end, text } = position;

    if (start < 0 || end > document.content.length || start >= end) {
      return false;
    }

    const actualText = document.content.slice(start, end);

    return actualText === text;
  }

  async createComment(input: CreateCommentInput): Promise<CommentWithRelations> {
    const { documentId, versionId, content, position, parentId, mentionedUserIds } = input;

    const membership = await this.prisma.spaceMember.findFirst({
      where: {
        space: {
          documents: {
            some: { id: documentId },
          },
        },
        userId: this.userId,
      },
    });

    if (!membership) {
      throw new TRPCError({
        code: 'FORBIDDEN',
        message: '无权限访问此文档',
      });
    }

    if (parentId) {
      const parentComment = await this.prisma.comment.findUnique({
        where: { id: parentId, documentId },
      });

      if (!parentComment) {
        throw new TRPCError({
          code: 'NOT_FOUND',
          message: '父评论不存在',
        });
      }

      if (parentComment.parentId !== null) {
        throw new TRPCError({
          code: 'BAD_REQUEST',
          message: '不支持嵌套回复',
        });
      }
    }

    if (position && !parentId) {
      const isValid = await this.validatePosition(documentId, position);
      if (!isValid) {
        throw new TRPCError({
          code: 'BAD_REQUEST',
          message: '评论位置无效',
        });
      }
    }

    const comment = await this.prisma.comment.create({
      data: {
        documentId,
        versionId,
        authorId: this.userId,
        content,
        position: position as unknown as Prisma.JsonObject,
        parentId,
      },
      include: THREAD_INCLUDE,
    });

    return comment as unknown as CommentWithRelations;
  }

  async listComments(input: ListCommentsInput): Promise<CommentListResponse> {
    const {
      documentId,
      versionId,
      page = 1,
      pageSize = 20,
      includeResolved = false,
      authorId,
    } = input;

    const membership = await this.prisma.spaceMember.findFirst({
      where: {
        space: {
          documents: {
            some: { id: documentId },
          },
        },
        userId: this.userId,
      },
    });

    if (!membership) {
      throw new TRPCError({
        code: 'FORBIDDEN',
        message: '无权限访问此文档',
      });
    }

    const skip = (page - 1) * pageSize;

    const where = {
      documentId,
      versionId,
      parentId: null,
      isResolved: includeResolved ? undefined : false,
      authorId,
    };

    const [comments, total] = await Promise.all([
      this.prisma.comment.findMany({
        where,
        skip,
        take: pageSize,
        include: THREAD_INCLUDE,
        orderBy: {
          createdAt: 'desc',
        },
      }),
      this.prisma.comment.count({ where }),
    ]);

    return {
      items: comments as unknown as CommentWithRelations[],
      total,
      page,
      pageSize,
      totalPages: Math.ceil(total / pageSize),
    };
  }

  async updateComment(input: UpdateCommentInput): Promise<CommentWithRelations> {
    const { id, content } = input;

    const comment = await this.prisma.comment.findUnique({
      where: { id },
      include: {
        document: {
          select: { spaceId: true },
        },
      },
    });

    if (!comment) {
      throw new TRPCError({
        code: 'NOT_FOUND',
        message: '评论不存在',
      });
    }

    if (comment.authorId !== this.userId) {
      throw new TRPCError({
        code: 'FORBIDDEN',
        message: '只能编辑自己的评论',
      });
    }

    const updatedComment = await this.prisma.comment.update({
      where: { id },
      data: { content },
      include: THREAD_INCLUDE,
    });

    return updatedComment as unknown as CommentWithRelations;
  }

  async resolveComment(id: string): Promise<CommentWithRelations> {
    const comment = await this.prisma.comment.findUnique({
      where: { id },
      include: {
        document: {
          select: { spaceId: true },
        },
      },
    });

    if (!comment) {
      throw new TRPCError({
        code: 'NOT_FOUND',
        message: '评论不存在',
      });
    }

    const membership = await this.prisma.spaceMember.findFirst({
      where: {
        spaceId: comment.document.spaceId,
        userId: this.userId,
        role: { in: ['ADMIN', 'EDITOR'] as Role[] },
      },
    });

    if (!membership) {
      throw new TRPCError({
        code: 'FORBIDDEN',
        message: '需要 EDITOR 以上权限才能解决评论',
      });
    }

    if (comment.isResolved) {
      throw new TRPCError({
        code: 'BAD_REQUEST',
        message: '评论已被解决',
      });
    }

    const resolvedComment = await this.prisma.$transaction(async (prisma) => {
      await prisma.comment.updateMany({
        where: { parentId: id },
        data: {
          isResolved: true,
          resolvedAt: new Date(),
          resolvedById: this.userId,
        },
      });

      return prisma.comment.update({
        where: { id },
        data: {
          isResolved: true,
          resolvedAt: new Date(),
          resolvedById: this.userId,
        },
        include: THREAD_INCLUDE,
      });
    });

    return resolvedComment as unknown as CommentWithRelations;
  }

  async unresolveComment(id: string): Promise<CommentWithRelations> {
    const comment = await this.prisma.comment.findUnique({
      where: { id },
      include: {
        document: {
          select: { spaceId: true },
        },
      },
    });

    if (!comment) {
      throw new TRPCError({
        code: 'NOT_FOUND',
        message: '评论不存在',
      });
    }

    const membership = await this.prisma.spaceMember.findFirst({
      where: {
        spaceId: comment.document.spaceId,
        userId: this.userId,
        role: { in: ['ADMIN', 'EDITOR'] as Role[] },
      },
    });

    if (!membership) {
      throw new TRPCError({
        code: 'FORBIDDEN',
        message: '需要 EDITOR 以上权限才能重新打开评论',
      });
    }

    if (!comment.isResolved) {
      throw new TRPCError({
        code: 'BAD_REQUEST',
        message: '评论未被解决',
      });
    }

    const unresolvedComment = await this.prisma.$transaction(async (prisma) => {
      await prisma.comment.updateMany({
        where: { parentId: id },
        data: {
          isResolved: false,
          resolvedAt: null,
          resolvedById: null,
        },
      });

      return prisma.comment.update({
        where: { id },
        data: {
          isResolved: false,
          resolvedAt: null,
          resolvedById: null,
        },
        include: THREAD_INCLUDE,
      });
    });

    return unresolvedComment as unknown as CommentWithRelations;
  }

  async deleteComment(id: string): Promise<{ success: boolean }> {
    const comment = await this.prisma.comment.findUnique({
      where: { id },
      include: {
        document: {
          select: { spaceId: true },
        },
      },
    });

    if (!comment) {
      throw new TRPCError({
        code: 'NOT_FOUND',
        message: '评论不存在',
      });
    }

    const isAdmin = await this.prisma.spaceMember.findFirst({
      where: {
        spaceId: comment.document.spaceId,
        userId: this.userId,
        role: { in: ['ADMIN'] as Role[] },
      },
    });

    if (comment.authorId !== this.userId && !isAdmin) {
      throw new TRPCError({
        code: 'FORBIDDEN',
        message: '只能删除自己的评论或拥有管理员权限',
      });
    }

    await this.prisma.$transaction(async (prisma) => {
      await prisma.comment.deleteMany({
        where: { parentId: id },
      });

      await prisma.comment.delete({
        where: { id },
      });
    });

    return { success: true };
  }

  async getCommentById(id: string): Promise<CommentWithRelations> {
    const comment = await this.prisma.comment.findUnique({
      where: { id },
      include: THREAD_INCLUDE,
    });

    if (!comment) {
      throw new TRPCError({
        code: 'NOT_FOUND',
        message: '评论不存在',
      });
    }

    const membership = await this.prisma.spaceMember.findFirst({
      where: {
        space: {
          documents: {
            some: { id: comment.documentId },
          },
        },
        userId: this.userId,
      },
    });

    if (!membership) {
      throw new TRPCError({
        code: 'FORBIDDEN',
        message: '无权限访问此评论',
      });
    }

    return comment as unknown as CommentWithRelations;
  }
}
