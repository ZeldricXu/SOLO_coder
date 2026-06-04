import type { PrismaClient, User } from '@prisma/client';
import type { ReviewDecision } from '@/lib/types/review';
import type { CommentWithRelations } from '@/lib/types/comment';

export interface NotificationPayload {
  type: string;
  userId: string;
  title: string;
  message: string;
  metadata: Record<string, unknown>;
}

export class NotificationService {
  private prisma: PrismaClient;

  constructor(prisma: PrismaClient) {
    this.prisma = prisma;
  }

  private async sendNotification(
    userId: string,
    payload: Omit<NotificationPayload, 'userId'>
  ): Promise<void> {
    try {
      const user = await this.prisma.user.findUnique({
        where: { id: userId },
        select: { email: true, name: true },
      });

      if (!user) {
        console.warn(`User not found for notification: ${userId}`);
        return;
      }

      await this.prisma.notification.upsert({
        where: {
          id: `${payload.type}-${JSON.stringify(payload.metadata)}`,
        },
        create: {
          userId,
          type: payload.type,
          title: payload.title,
          message: payload.message,
          metadata: payload.metadata as unknown as Prisma.JsonObject,
          read: false,
        },
        update: {
          createdAt: new Date(),
          read: false,
        },
      });

      console.log(`[Notification] Sent to ${user.email}: ${payload.title}`);
    } catch (error) {
      console.error('Failed to send notification:', error);
    }
  }

  async sendReviewAssignedNotification(
    userId: string,
    reviewId: string,
    documentTitle: string
  ): Promise<void> {
    await this.sendNotification(userId, {
      type: 'REVIEW_ASSIGNED',
      title: '您被指定为审阅人',
      message: `您被指定审阅文档：${documentTitle}`,
      metadata: {
        reviewId,
        documentTitle,
      },
    });
  }

  async sendCommentMentionNotification(
    comment: CommentWithRelations,
    mentionedUserIds: string[]
  ): Promise<void> {
    const promises = mentionedUserIds.map(async (userId) => {
      await this.sendNotification(userId, {
        type: 'COMMENT_MENTION',
        title: '您在评论中被提及',
        message: `${comment.author.name} 在评论中提及了您：${comment.content.slice(0, 50)}${comment.content.length > 50 ? '...' : ''}`,
        metadata: {
          commentId: comment.id,
          documentId: comment.documentId,
          authorName: comment.author.name,
        },
      });
    });

    await Promise.all(promises);
  }

  async sendReviewSubmittedNotification(
    userId: string,
    reviewId: string,
    documentTitle: string,
    decision: ReviewDecision
  ): Promise<void> {
    const decisionText = decision === 'APPROVED' ? '已批准' : '需要修改';

    await this.sendNotification(userId, {
      type: 'REVIEW_SUBMITTED',
      title: `审阅意见已提交 - ${decisionText}`,
      message: `文档 "${documentTitle}" 的审阅意见已提交：${decisionText}`,
      metadata: {
        reviewId,
        documentTitle,
        decision,
      },
    });
  }

  async sendCommentResolvedNotification(
    comment: CommentWithRelations,
    resolverId: string
  ): Promise<void> {
    if (comment.authorId === resolverId) {
      return;
    }

    const resolver = await this.prisma.user.findUnique({
      where: { id: resolverId },
      select: { name: true },
    });

    await this.sendNotification(comment.authorId, {
      type: 'COMMENT_RESOLVED',
      title: '您的评论已被解决',
      message: `${resolver?.name || '有人'} 解决了您在 "${comment.document.title}" 中的评论`,
      metadata: {
        commentId: comment.id,
        documentId: comment.documentId,
        documentTitle: comment.document.title,
        resolverName: resolver?.name,
      },
    });
  }

  async sendReviewCompletedNotification(
    reviewId: string,
    authorId: string,
    documentTitle: string,
    status: string
  ): Promise<void> {
    const statusText =
      status === 'APPROVED' ? '已通过' : status === 'CHANGES_REQUESTED' ? '需要修改' : '已完成';

    await this.sendNotification(authorId, {
      type: 'REVIEW_COMPLETED',
      title: `审阅已完成 - ${statusText}`,
      message: `文档 "${documentTitle}" 的审阅已完成：${statusText}`,
      metadata: {
        reviewId,
        documentTitle,
        status,
      },
    });
  }
}
