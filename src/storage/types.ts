import { z } from 'zod';

export const StorageConfigSchema = z.object({
  storageId: z.string(),
  type: z.enum(['local', 's3', 'gcs', 'azure', 'memory']),
  name: z.string(),
  basePath: z.string().default('./storage'),
  maxSizeBytes: z.number().optional(),
  lifecycle: z.object({
    autoDeleteDays: z.number().optional(),
    archiveAfterDays: z.number().optional(),
    deleteAfterDays: z.number().optional(),
    versioning: z.boolean().default(false),
  }).optional(),
  encryption: z.object({
    enabled: z.boolean().default(false),
    algorithm: z.string().default('aes-256-gcm'),
  }).optional(),
  accessControl: z.object({
    read: z.array(z.string()).default([]),
    write: z.array(z.string()).default([]),
    delete: z.array(z.string()).default([]),
  }).optional(),
});

export type StorageConfig = z.infer<typeof StorageConfigSchema>;

export const StoredFileSchema = z.object({
  fileId: z.string(),
  name: z.string(),
  path: z.string(),
  sizeBytes: z.number(),
  contentType: z.string().default('application/octet-stream'),
  metadata: z.record(z.string()).default({}),
  storageId: z.string(),
  createdAt: z.string().datetime(),
  updatedAt: z.string().datetime(),
  expiresAt: z.string().datetime().optional(),
  archived: z.boolean().default(false),
  version: z.number().default(1),
  tags: z.array(z.string()).default([]),
});

export type StoredFile = z.infer<typeof StoredFileSchema>;

export interface FileUploadOptions {
  contentType?: string;
  metadata?: Record<string, string>;
  tags?: string[];
  ttlMs?: number;
  overwrite?: boolean;
}

export interface FileListOptions {
  prefix?: string;
  tags?: string[];
  includeArchived?: boolean;
  limit?: number;
  offset?: number;
}

export interface FileListResult {
  files: StoredFile[];
  total: number;
  hasMore: boolean;
}

export interface LifecycleRule {
  id: string;
  storageId: string;
  condition: {
    ageDays?: number;
    prefix?: string;
    tags?: string[];
    sizeGreaterThan?: number;
  };
  action: {
    type: 'archive' | 'delete' | 'change_storage_class';
    targetStorageClass?: string;
  };
  enabled: boolean;
}

export interface StorageStats {
  totalFiles: number;
  totalSizeBytes: number;
  archivedFiles: number;
  archivedSizeBytes: number;
  storageUsedPercentage: number;
}
