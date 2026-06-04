import type { User, Document, DocumentVersion, ReviewStatus } from '@prisma/client';
import type { CommentWithRelations } from './comment';

export type ReviewDecision = 'APPROVED' | 'CHANGES_REQUESTED';

export interface Reviewer {
  id: string;
  reviewId: string;
  userId: string;
  status: ReviewStatus;
  comment: string | null;
  reviewedAt: Date | null;
  createdAt: Date;
}

export interface ReviewerWithRelations extends Reviewer {
  user: Pick<User, 'id' | 'name' | 'email' | 'avatar'>;
}

export interface Review {
  id: string;
  documentId: string;
  versionId: string;
  createdById: string;
  status: ReviewStatus;
  comment: string | null;
  createdAt: Date;
  updatedAt: Date;
}

export interface ReviewWithRelations extends Review {
  createdBy: Pick<User, 'id' | 'name' | 'email' | 'avatar'>;
  document: Pick<Document, 'id' | 'title' | 'path' | 'content' | 'spaceId'>;
  reviewers: ReviewerWithRelations[];
  comments: CommentWithRelations[];
  _count: {
    reviewers: number;
    comments: number;
  };
}

export interface CreateReviewInput {
  documentId: string;
  versionId: string;
  comment?: string;
  reviewerIds: string[];
}

export interface ListReviewsInput {
  documentId?: string;
  spaceId?: string;
  status?: ReviewStatus;
  asReviewer?: boolean;
  asAuthor?: boolean;
  page?: number;
  pageSize?: number;
}

export interface ReviewListResponse {
  items: ReviewWithRelations[];
  total: number;
  page: number;
  pageSize: number;
  totalPages: number;
}

export interface SubmitReviewInput {
  reviewId: string;
  decision: ReviewDecision;
  comment: string;
}

export interface AddReviewerInput {
  reviewId: string;
  userId: string;
}

export interface ReviewProgress {
  total: number;
  approved: number;
  changesRequested: number;
  pending: number;
}
