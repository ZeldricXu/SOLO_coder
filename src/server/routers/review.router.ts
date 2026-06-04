import { z } from 'zod';
import { TRPCError } from '@trpc/server';
import { router, protectedProcedure } from '../trpc';

const ReviewStatusSchema = z.enum(['PENDING', 'IN_REVIEW', 'APPROVED', 'REJECTED', 'CHANGES_REQUESTED']);
const ReviewDecisionSchema = z.enum(['APPROVED', 'REJECTED', 'CHANGES_REQUESTED']);

export const reviewRouter = router({
  create: protectedProcedure
    .input(
      z.object({
        documentId: z.string().cuid(),
        title: z.string().min(1, '审阅标题不能为空').max(200, '审阅标题最多200个字符'),
        description: z.string().max(2000, '描述最多2000个字符').optional(),
        deadline: z.date().optional(),
        reviewerIds: z.array(z.string().cuid()).min(1, '至少需要指定一位审阅人'),
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
                role: { in: ['OWNER', 'ADMIN', 'EDITOR'] },
              },
            },
          },
        },
        select: {
          id: true,
          spaceId: true,
          title: true,
        },
      });

      if (!document) {
        throw new TRPCError({
          code: 'NOT_FOUND',
          message: '文档不存在或无权限创建审阅',
        });
      }

      const validReviewers = await ctx.prisma.spaceMember.findMany({
        where: {
          spaceId: document.spaceId,
          userId: { in: input.reviewerIds },
        },
        select: {
          userId: true,
        },
      });

      if (validReviewers.length !== input.reviewerIds.length) {
        throw new TRPCError({
          code: 'BAD_REQUEST',
          message: '部分审阅人不是空间成员',
        });
      }

      const review = await ctx.prisma.$transaction(async (prisma) => {
        const r = await prisma.review.create({
          data: {
            documentId: document.id,
            title: input.title,
            description: input.description,
            deadline: input.deadline,
            status: 'PENDING',
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
            document: {
              select: {
                id: true,
                title: true,
                path: true,
              },
            },
          },
        });

        await prisma.reviewReviewer.createMany({
          data: input.reviewerIds.map((userId) => ({
            reviewId: r.id,
            userId,
            status: 'PENDING',
          })),
        });

        return r;
      });

      return review;
    }),

  list: protectedProcedure
    .input(
      z.object({
        documentId: z.string().cuid().optional(),
        spaceId: z.string().cuid().optional(),
        status: ReviewStatusSchema.optional(),
        asReviewer: z.boolean().default(false),
        asCreator: z.boolean().default(false),
        page: z.number().min(1).default(1),
        pageSize: z.number().min(1).max(100).default(20),
      })
    )
    .query(async ({ ctx, input }) => {
      const where = {
        ...(input.documentId ? { documentId: input.documentId } : {}),
        ...(input.spaceId
          ? {
              document: {
                spaceId: input.spaceId,
              },
            }
          : {}),
        ...(input.status ? { status: input.status } : {}),
        ...(input.asReviewer
          ? {
              reviewers: {
                some: {
                  userId: ctx.user.id,
                },
              },
            }
          : {}),
        ...(input.asCreator ? { createdById: ctx.user.id } : {}),
        document: {
          space: {
            members: {
              some: {
                userId: ctx.user.id,
              },
            },
          },
        },
      };

      const skip = (input.page - 1) * input.pageSize;

      const [reviews, total] = await Promise.all([
        ctx.prisma.review.findMany({
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
            document: {
              select: {
                id: true,
                title: true,
                path: true,
              },
            },
            reviewers: {
              include: {
                user: {
                  select: {
                    id: true,
                    name: true,
                    email: true,
                    avatar: true,
                  },
                },
              },
            },
            _count: {
              select: {
                reviewers: true,
                comments: true,
              },
            },
          },
          orderBy: {
            createdAt: 'desc',
          },
        }),
        ctx.prisma.review.count({ where }),
      ]);

      return {
        items: reviews,
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
      const review = await ctx.prisma.review.findUnique({
        where: {
          id: input.id,
          document: {
            space: {
              members: {
                some: {
                  userId: ctx.user.id,
                },
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
          document: {
            select: {
              id: true,
              title: true,
              path: true,
              content: true,
              spaceId: true,
            },
          },
          reviewers: {
            include: {
              user: {
                select: {
                  id: true,
                  name: true,
                  email: true,
                  avatar: true,
                },
              },
            },
          },
          comments: {
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
          _count: {
            select: {
              reviewers: true,
              comments: true,
            },
          },
        },
      });

      if (!review) {
        throw new TRPCError({
          code: 'NOT_FOUND',
          message: '审阅不存在或无权限访问',
        });
      }

      return review;
    }),

  addReviewer: protectedProcedure
    .input(
      z.object({
        reviewId: z.string().cuid(),
        userId: z.string().cuid(),
      })
    )
    .mutation(async ({ ctx, input }) => {
      const review = await ctx.prisma.review.findUnique({
        where: {
          id: input.reviewId,
          createdById: ctx.user.id,
        },
        include: {
          document: {
            select: {
              id: true,
              spaceId: true,
            },
          },
        },
      });

      if (!review) {
        throw new TRPCError({
          code: 'FORBIDDEN',
          message: '无权限添加审阅人',
        });
      }

      const spaceMember = await ctx.prisma.spaceMember.findFirst({
        where: {
          spaceId: review.document.spaceId,
          userId: input.userId,
        },
      });

      if (!spaceMember) {
        throw new TRPCError({
          code: 'BAD_REQUEST',
          message: '该用户不是空间成员',
        });
      }

      const existingReviewer = await ctx.prisma.reviewReviewer.findFirst({
        where: {
          reviewId: input.reviewId,
          userId: input.userId,
        },
      });

      if (existingReviewer) {
        throw new TRPCError({
          code: 'CONFLICT',
          message: '该用户已是审阅人',
        });
      }

      const reviewer = await ctx.prisma.reviewReviewer.create({
        data: {
          reviewId: input.reviewId,
          userId: input.userId,
          status: 'PENDING',
        },
        include: {
          user: {
            select: {
              id: true,
              name: true,
              email: true,
              avatar: true,
            },
          },
        },
      });

      return reviewer;
    }),

  submitReview: protectedProcedure
    .input(
      z.object({
        reviewId: z.string().cuid(),
        decision: ReviewDecisionSchema,
        comment: z.string().min(1, '审阅意见不能为空').max(5000, '审阅意见最多5000个字符'),
      })
    )
    .mutation(async ({ ctx, input }) => {
      const reviewer = await ctx.prisma.reviewReviewer.findFirst({
        where: {
          reviewId: input.reviewId,
          userId: ctx.user.id,
        },
        include: {
          review: {
            include: {
              document: {
                select: {
                  id: true,
                  spaceId: true,
                },
              },
            },
          },
        },
      });

      if (!reviewer) {
        throw new TRPCError({
          code: 'FORBIDDEN',
          message: '您不是此审阅的审阅人',
        });
      }

      if (reviewer.status !== 'PENDING') {
        throw new TRPCError({
          code: 'BAD_REQUEST',
          message: '您已提交过审阅意见',
        });
      }

      const updatedReview = await ctx.prisma.$transaction(async (prisma) => {
        await prisma.reviewReviewer.update({
          where: { id: reviewer.id },
          data: {
            status: input.decision,
            comment: input.comment,
            submittedAt: new Date(),
          },
        });

        const allReviewers = await prisma.reviewReviewer.findMany({
          where: { reviewId: input.reviewId },
        });

        const submittedCount = allReviewers.filter(
          (r) => r.status !== 'PENDING'
        ).length;
        const allSubmitted = submittedCount === allReviewers.length;
        const hasRejection = allReviewers.some((r) => r.status === 'REJECTED');
        const hasChangesRequested = allReviewers.some(
          (r) => r.status === 'CHANGES_REQUESTED'
        );
        const allApproved = allReviewers.every((r) => r.status === 'APPROVED');

        let newStatus = 'IN_REVIEW';
        if (allSubmitted) {
          if (hasRejection) {
            newStatus = 'REJECTED';
          } else if (hasChangesRequested) {
            newStatus = 'CHANGES_REQUESTED';
          } else if (allApproved) {
            newStatus = 'APPROVED';
          }
        }

        return prisma.review.update({
          where: { id: input.reviewId },
          data: {
            status: newStatus,
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
            document: {
              select: {
                id: true,
                title: true,
                path: true,
              },
            },
            reviewers: {
              include: {
                user: {
                  select: {
                    id: true,
                    name: true,
                    email: true,
                    avatar: true,
                  },
                },
              },
            },
            _count: {
              select: {
                reviewers: true,
                comments: true,
              },
            },
          },
        });
      });

      return updatedReview;
    }),

  updateStatus: protectedProcedure
    .input(
      z.object({
        reviewId: z.string().cuid(),
        status: ReviewStatusSchema,
      })
    )
    .mutation(async ({ ctx, input }) => {
      const review = await ctx.prisma.review.findUnique({
        where: {
          id: input.reviewId,
          createdById: ctx.user.id,
        },
        include: {
          document: {
            select: {
              id: true,
              spaceId: true,
            },
          },
        },
      });

      if (!review) {
        throw new TRPCError({
          code: 'FORBIDDEN',
          message: '无权限更新审阅状态',
        });
      }

      const updatedReview = await ctx.prisma.review.update({
        where: { id: input.reviewId },
        data: {
          status: input.status,
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
          document: {
            select: {
              id: true,
              title: true,
              path: true,
            },
          },
          reviewers: {
            include: {
              user: {
                select: {
                  id: true,
                  name: true,
                  email: true,
                  avatar: true,
                },
              },
            },
          },
          _count: {
            select: {
              reviewers: true,
              comments: true,
            },
          },
        },
      });

      return updatedReview;
    }),
});
