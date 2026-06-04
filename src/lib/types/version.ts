import type { Change } from 'diff';

export type DiffType = 'added' | 'removed' | 'unchanged' | 'modified';

export interface DiffLine {
  type: DiffType;
  content: string;
  lineNumberOld: number | null;
  lineNumberNew: number | null;
  charChanges?: Change[];
}

export interface DiffChunk {
  oldStart: number;
  oldLines: number;
  newStart: number;
  newLines: number;
  lines: DiffLine[];
}

export interface DiffStats {
  added: number;
  removed: number;
  modified: number;
  unchanged: number;
  total: number;
}

export interface VersionDiff {
  versionFrom: {
    id: string;
    version: number;
    title: string;
    createdAt: Date;
    createdById: string;
    createdBy?: {
      id: string;
      name: string;
      email: string;
      avatar?: string | null;
    };
  };
  versionTo: {
    id: string;
    version: number;
    title: string;
    createdAt: Date;
    createdById: string;
    createdBy?: {
      id: string;
      name: string;
      email: string;
      avatar?: string | null;
    };
  };
  contentDiff: DiffChunk[];
  titleDiff: Change[];
  stats: DiffStats;
}

export interface VersionSummary {
  version: number;
  title: string;
  message?: string | null;
  createdAt: Date;
  createdBy: {
    id: string;
    name: string;
    avatar?: string | null;
  };
  stats: DiffStats;
}

export interface Version {
  id: string;
  documentId: string;
  title: string;
  content: string;
  contentHtml?: string | null;
  version: number;
  message?: string | null;
  createdById: string;
  createdAt: Date;
  createdBy?: {
    id: string;
    name: string;
    email: string;
    avatar?: string | null;
  };
}

export interface VersionListResult {
  items: Version[];
  total: number;
  page: number;
  pageSize: number;
  totalPages: number;
}

export type DiffViewMode = 'split' | 'unified' | 'inline';

export interface VersionServiceCreateInput {
  documentId: string;
  title: string;
  content: string;
  contentHtml?: string;
  message?: string;
  createdById: string;
}

export interface VersionServiceListInput {
  documentId: string;
  page?: number;
  pageSize?: number;
  sortBy?: 'version' | 'createdAt';
  sortOrder?: 'asc' | 'desc';
}

export interface VersionServiceCompareInput {
  documentId: string;
  versionFrom: number;
  versionTo: number;
  ignoreWhitespace?: boolean;
}

export interface VersionServiceRollbackInput {
  documentId: string;
  targetVersion: number;
  createdById: string;
  message?: string;
}

export interface VersionServiceCleanupInput {
  documentId: string;
  keepCount: number;
}
