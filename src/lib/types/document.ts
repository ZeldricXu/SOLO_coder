import type { Document, DocumentVersion, Tag, User, Space } from '@prisma/client';

export type DocumentWithRelations = Document & {
  createdBy?: Pick<User, 'id' | 'name' | 'avatar'> | null;
  space?: Pick<Space, 'id' | 'name' | 'color' | 'icon'> | null;
  tags?: Tag[];
  parent?: Pick<Document, 'id' | 'title' | 'path'>;
  children?: Array<Pick<Document, 'id' | 'title' | 'path' | 'createdAt'>>;
  versions?: DocumentVersion[];
};

export type DocumentListItem = Pick<
  Document,
  | 'id'
  | 'title'
  | 'path'
  | 'wordCount'
  | 'externalSource'
  | 'isArchived'
  | 'createdAt'
  | 'updatedAt'
  | 'version'
> & {
  createdBy: Pick<User, 'id' | 'name' | 'avatar'>;
  tags: Array<Pick<Tag, 'id' | 'name' | 'color'>>;
};

export interface CreateDocumentInput {
  spaceId: string;
  title: string;
  content?: string;
  parentId?: string;
  path?: string;
  tags?: string[];
}

export interface UpdateDocumentInput {
  id: string;
  title?: string;
  content?: string;
  contentHtml?: string;
  parentId?: string | null;
  path?: string;
  isArchived?: boolean;
  createVersion?: boolean;
  changeSummary?: string;
}

export interface ListDocumentsInput {
  spaceId: string;
  page?: number;
  pageSize?: number;
  search?: string;
  tagIds?: string[];
  createdById?: string;
  isArchived?: boolean;
  sortBy?: 'createdAt' | 'updatedAt' | 'title' | 'wordCount';
  sortOrder?: 'asc' | 'desc';
  viewMode?: 'list' | 'tree' | 'grid';
  parentId?: string | null;
}

export interface PaginatedDocuments {
  items: DocumentListItem[];
  total: number;
  page: number;
  pageSize: number;
  totalPages: number;
}

export interface DocumentVersionWithRelations extends DocumentVersion {
  createdBy?: Pick<User, 'id' | 'name' | 'avatar'>;
  message?: string | null;
  changeSummary?: string | null;
}

export type DocumentType = 
  | 'TECH_PROPOSAL'
  | 'MEETING_NOTES'
  | 'WEEKLY_REPORT'
  | 'POST_MORTEM'
  | 'PRODUCT_REQUIREMENT'
  | 'OTHER';

export interface ClassificationResult {
  type: DocumentType;
  confidence: number;
  reasons: string[];
}

export interface DocumentTreeNode {
  id: string;
  title: string;
  path: string;
  children: DocumentTreeNode[];
  isArchived: boolean;
  createdAt: Date;
  updatedAt: Date;
}

export const DOCUMENT_TYPE_LABELS: Record<DocumentType, string> = {
  TECH_PROPOSAL: '技术方案',
  MEETING_NOTES: '会议纪要',
  WEEKLY_REPORT: '周报',
  POST_MORTEM: '项目复盘',
  PRODUCT_REQUIREMENT: '产品需求',
  OTHER: '其他',
};

export const DOCUMENT_TYPE_COLORS: Record<DocumentType, string> = {
  TECH_PROPOSAL: 'bg-blue-100 text-blue-700',
  MEETING_NOTES: 'bg-green-100 text-green-700',
  WEEKLY_REPORT: 'bg-purple-100 text-purple-700',
  POST_MORTEM: 'bg-red-100 text-red-700',
  PRODUCT_REQUIREMENT: 'bg-amber-100 text-amber-700',
  OTHER: 'bg-gray-100 text-gray-700',
};
