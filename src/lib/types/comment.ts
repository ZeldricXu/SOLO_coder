import type { User, Document, DocumentVersion } from '@prisma/client';

export interface CommentPosition {
  start: number;
  end: number;
  text: string;
  path?: string;
}

export interface Comment {
  id: string;
  documentId: string;
  versionId: string | null;
  authorId: string;
  content: string;
  position: CommentPosition | null;
  isResolved: boolean;
  resolvedAt: Date | null;
  resolvedById: string | null;
  parentId: string | null;
  createdAt: Date;
  updatedAt: Date;
}

export interface CommentWithRelations extends Comment {
  author: Pick<User, 'id' | 'name' | 'email' | 'avatar'>;
  resolvedBy: Pick<User, 'id' | 'name' | 'email' | 'avatar'> | null;
  parent: CommentWithRelations | null;
  children: CommentWithRelations[];
  document: Pick<Document, 'id' | 'title' | 'spaceId'>;
  version: Pick<DocumentVersion, 'id' | 'version'> | null;
}

export interface CommentThread {
  id: string;
  comments: CommentWithRelations[];
  isResolved: boolean;
  position: CommentPosition | null;
  documentId: string;
  versionId: string | null;
}

export interface CreateCommentInput {
  documentId: string;
  versionId?: string;
  content: string;
  position?: CommentPosition;
  parentId?: string;
  mentionedUserIds?: string[];
}

export interface UpdateCommentInput {
  id: string;
  content: string;
}

export interface ListCommentsInput {
  documentId: string;
  versionId?: string;
  page?: number;
  pageSize?: number;
  includeResolved?: boolean;
  authorId?: string;
}

export interface CommentListResponse {
  items: CommentWithRelations[];
  total: number;
  page: number;
  pageSize: number;
  totalPages: number;
}

export type CommentFilterType = 'all' | 'unresolved' | 'resolved';
