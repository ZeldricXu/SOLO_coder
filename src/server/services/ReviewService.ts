import type { PrismaClient, Role, ReviewStatus } from '@prisma/client';
import { TRPCError } from '@trpc/server';
import type {
  ReviewWithRelations,
  ReviewerWithRelations,
  CreateReviewInput,
  ListReviewsInput,
  SubmitReviewInput,
  AddReviewerInput,
  ReviewListResponse,
  ReviewProgress,
} from '@/lib/types/review';
import { NotificationService } from './NotificationService';

const REVIEW_INCLUDE = {
  author: {
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
      spaceId: true,
    },
  },
  version: {
    select: {
      id: true,
      version: true,
      title: true,
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
    where: { parentId: null },
    include: {
      author: {
        select: {
          id: true,
          name: true,
          email: true,
          avatar: true,
        },
      },
      children: {
        include: {
          author: {
            select: {
              id: true,
              name: true,
              email: true,
              avatar: true,
            },
          },
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
};

export class ReviewService {
  private prisma: PrismaClient;
  private userId: string;
  private notificationService: NotificationService;

  constructor(prisma: PrismaClient, userId: string) {
    this.prisma = prisma;
    this.userId = userId;
    this.notificationService = new NotificationService(prisma);
  }

  async checkAllReviewersApproved(reviewId: string): Promise<boolean> {
    const reviewers = await this.prisma.reviewer.findMany({
      where: { reviewId },
      select: { status: true },
    });

    if (reviewers.length === 0) return false;

    return reviewers.every((r) => r.status === 'APPROVED');
  }

  async getReviewProgress(reviewId: string): Promise<ReviewProgress> {
    const reviewers = await this.prisma.reviewer.findMany({
      where: { reviewId },
      select: { status: true },
    });

    const progress: ReviewProgress = {
      total: reviewers.length,
      approved: 0,
      changesRequested: 0,
      pending: 0,
    };

    reviewers.forEach((r) => {
      if (r.status === 'APPROVED') {
        progress.approved++;
      } else if (r.status === 'CHANGES_REQUESTED') {
        progress.changesRequested++;
      } else {
        progress.pending++;
      }
    });

    return progress;
  }

  async updateReviewStatus(reviewId: string): Promise<void> {
    const progress = await this.getReviewProgress(reviewId);

    let newStatus: ReviewStatus = 'PENDING';

    if (progress.pending === 0) {
      if (progress.changesRequested > 0) {
        newStatus = 'CHANGES_REQUESTED';
      } else if (progress.approved === progress.total) {
        newStatus = 'APPROVED';
      }
    } else if (progress.approved > 0 || progress.changesRequested > 0) {
      newStatus = 'PENDING';
    }

    await this.prisma.review.update({
      where: { id: reviewId },
      data: { status: newStatus },
    });
  }

  async createReview(input: CreateReviewInput): Promise<ReviewWithRelations> {
    const { documentId, versionId, comment, reviewerIds } = input;

    const document = await this.prisma.document.findUnique({
      where: { id: documentId },
      select: {
        spaceId: true,
        title: true,
      },
    });

    if (!document) {
      throw new TRPCError({
        code: 'NOT_FOUND',
        message: '文档不存在',
      });
    }

    const membership = await this.prisma.spaceMember.findFirst({
      where: {
        spaceId: document.spaceId,
        userId: this.userId,
        role: { in: ['ADMIN', 'EDITOR'] as Role[] },
      },
    });

    if (!membership) {
      throw new TRPCError({
        code: 'FORBIDDEN',
        message: '需要 EDITOR 以上权限才能创建审阅',
      });
    }

    const version = await this.prisma.documentVersion.findUnique({
      where: { id: versionId, documentId },
    });

    if (!version) {
      throw new TRPCError({
        code: 'NOT_FOUND',
        message: '文档版本不存在',
      });
    }

    const validReviewers = await this.prisma.spaceMember.findMany({
      where: {
        spaceId: document.spaceId,
        userId: { in: reviewerIds },
      },
      select: { userId: true },
    });

    if (validReviewers.length !== reviewerIds.length) {
      throw new TRPCError({
        code: 'BAD_REQUEST',
        message: '部分审阅人不是空间成员',
      });
    }

    if (reviewerIds.includes(this.userId)) {
      throw new TRPCError({
        code: 'BAD_REQUEST',
        message: '不能指定自己为审阅人',
      });
    }

    const review = await this.prisma.$transaction(async (prisma) => {
      const r = await prisma.review.create({
        data: {
          documentId,
          versionId,
          authorId: this.userId,
          comment,
          status: 'PENDING',
        },
        include: REVIEW_INCLUDE,
      });

      await prisma.reviewer.createMany({
        data: reviewerIds.map((userId) => ({
          reviewId: r.id,
          userId,
          status: 'PENDING',
        })),
      });

      return r;
    });

    for (const reviewerId of reviewerIds) {
      await this.notificationService.sendReviewAssignedNotification(
        reviewerId,
        review.id,
        document.title
      );
    }

    const updatedReview = await this.prisma.review.findUnique({
      where: { id: review.id },
      include: REVIEW_INCLUDE,
    });

    return updatedReview as unknown as ReviewWithRelations;
  }

  async listReviews(input: ListReviewsInput): Promise<ReviewListResponse> {
    const {
      documentId,
      spaceId,
      status,
      asReviewer = false,
      asAuthor = false,
      page = 1,
      pageSize = 20,
    } = input;

    const where = {
      ...(documentId ? { documentId } : {}),
      ...(spaceId ? { document: { spaceId } } : {}),
      ...(status ? { status } : {}),
      ...(asReviewer ? { reviewers: { some: { userId: this.userId } } } : {}),
      ...(asAuthor ? { authorId: this.userId } : {}),
      document: {
        space: {
          members: {
            some: { userId: this.userId },
          },
        },
      },
    };

    const skip = (page - 1) * pageSize;

    const [reviews, total] = await Promise.all([
      this.prisma.review.findMany({
        where,
        skip,
        take: pageSize,
        include: REVIEW_INCLUDE,
        orderBy: {
          createdAt: 'desc',
        },
      }),
      this.prisma.review.count({ where }),
    ]);

    return {
      items: reviews as unknown as ReviewWithRelations[],
      total,
      page,
      pageSize,
      totalPages: Math.ceil(total / pageSize),
    };
  }

  async getReviewById(id: string): Promise<ReviewWithRelations> {
    const review = await this.prisma.review.findUnique({
      where: {
        id,
        document: {
          space: {
            members: {
              some: { userId: this.userId },
            },
          },
        },
      },
      include: REVIEW_INCLUDE,
    });

    if (!review) {
      throw new TRPCError({
        code: 'NOT_FOUND',
        message: '审阅不存在或无权限访问',
      });
    }

    return review as unknown as ReviewWithRelations;
  }

  async addReviewer(input: AddReviewerInput): Promise<ReviewerWithRelations> {
    const { reviewId, userId } = input;

    const review = await this.prisma.review.findUnique({
      where: {
        id: reviewId,
        authorId: this.userId,
      },
      include: {
        document: {
          select: { spaceId: true, title: true },
        },
      },
    });

    if (!review) {
      throw new TRPCError({
        code: 'FORBIDDEN',
        message: '只有审阅创建者才能添加审阅人',
      });
    }

    if (userId === this.userId) {
      throw new TRPCError({
        code: 'BAD_REQUEST',
        message: '不能添加自己为审阅人',
      });
    }

    const spaceMember = await this.prisma.spaceMember.findFirst({
      where: {
        spaceId: review.document.spaceId,
        userId,
      },
    });

    if (!spaceMember) {
      throw new TRPCError({
        code: 'BAD_REQUEST',
        message: '该用户不是空间成员',
      });
    }

    const existingReviewer = await this.prisma.reviewer.findFirst({
      where: { reviewId, userId },
    });

    if (existingReviewer) {
      throw new TRPCError({
        code: 'CONFLICT',
        message: '该用户已是审阅人',
      });
    }

    const reviewer = await this.prisma.reviewer.create({
      data: {
        reviewId,
        userId,
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

    await this.notificationService.sendReviewAssignedNotification(
      userId,
      reviewId,
      review.document.title
    );

    return reviewer as unknown as ReviewerWithRelations;
  }

  async submitReview(input: SubmitReviewInput): Promise<ReviewWithRelations> {
    const { reviewId, decision, comment } = input;

    const reviewer = await this.prisma.reviewer.findFirst({
      where: {
        reviewId,
        userId: this.userId,
      },
      include: {
        review: {
          include: {
            document: {
              select: {
                title: true,
                spaceId: true,
              },
            },
            author: {
              select: {
                id: true,
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

    await this.prisma.$transaction(async (prisma) => {
      await prisma.reviewer.update({
        where: { id: reviewer.id },
        data: {
          status: decision,
          comment,
          reviewedAt: new Date(),
        },
      });
    });

    await this.updateReviewStatus(reviewId);

    await this.notificationService.sendReviewSubmittedNotification(
      reviewer.review.author.id,
      reviewId,
      reviewer.review.document.title,
      decision
    );

    const updatedReview = await this.prisma.review.findUnique({
      where: { id: reviewId },
      include: REVIEW_INCLUDE,
    });

    return updatedReview as unknown as ReviewWithRelations;
  }
}
