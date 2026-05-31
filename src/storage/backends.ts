import { StorageConfig, StoredFile, FileUploadOptions, FileListOptions, FileListResult, StorageStats } from './types';
import { generateId, currentDateTime, logger } from '../utils/common';
import * as fs from 'fs';
import * as path from 'path';

export abstract class StorageBackend {
  abstract config: StorageConfig;

  abstract upload(
    data: Buffer | string,
    filename: string,
    options?: FileUploadOptions
  ): Promise<StoredFile>;

  abstract download(fileId: string): Promise<Buffer | null>;

  abstract delete(fileId: string): Promise<boolean>;

  abstract get(fileId: string): StoredFile | undefined;

  abstract list(options?: FileListOptions): Promise<FileListResult>;

  abstract getStats(): Promise<StorageStats>;

  abstract exists(fileId: string): boolean;
}

export class LocalStorageBackend extends StorageBackend {
  config: StorageConfig;
  private files: Map<string, StoredFile> = new Map();
  private baseDir: string;

  constructor(config: Omit<StorageConfig, 'storageId' | 'type'> & { storageId?: string }) {
    super();
    this.config = {
      ...config,
      storageId: config.storageId || generateId('stor_'),
      type: 'local',
    } as StorageConfig;

    this.baseDir = path.resolve(this.config.basePath);
    this.ensureBaseDir();
    this.loadExistingFiles();
  }

  private ensureBaseDir(): void {
    if (!fs.existsSync(this.baseDir)) {
      fs.mkdirSync(this.baseDir, { recursive: true });
    }
  }

  private loadExistingFiles(): void {
    if (!fs.existsSync(this.baseDir)) return;

    const files = fs.readdirSync(this.baseDir, { withFileTypes: true });
    for (const file of files) {
      if (file.isFile()) {
        const filePath = path.join(this.baseDir, file.name);
        const stats = fs.statSync(filePath);
        const storedFile: StoredFile = {
          fileId: generateId('file_'),
          name: file.name,
          path: filePath,
          sizeBytes: stats.size,
          contentType: 'application/octet-stream',
          storageId: this.config.storageId,
          createdAt: stats.birthtime.toISOString(),
          updatedAt: stats.mtime.toISOString(),
          archived: false,
          version: 1,
          metadata: {},
          tags: [],
        };
        this.files.set(storedFile.fileId, storedFile);
      }
    }
  }

  async upload(
    data: Buffer | string,
    filename: string,
    options: FileUploadOptions = {}
  ): Promise<StoredFile> {
    const buffer = typeof data === 'string' ? Buffer.from(data) : data;
    const filePath = path.join(this.baseDir, filename);

    if (fs.existsSync(filePath) && !options.overwrite) {
      throw new Error(`File already exists: ${filename}`);
    }

    if (this.config.maxSizeBytes) {
      const currentTotal = Array.from(this.files.values()).reduce((sum, f) => sum + f.sizeBytes, 0);
      if (currentTotal + buffer.length > this.config.maxSizeBytes) {
        throw new Error('Storage capacity exceeded');
      }
    }

    fs.writeFileSync(filePath, buffer);

    const fileId = generateId('file_');
    const now = currentDateTime();

    const storedFile: StoredFile = {
      fileId,
      name: filename,
      path: filePath,
      sizeBytes: buffer.length,
      contentType: options.contentType || 'application/octet-stream',
      metadata: options.metadata || {},
      storageId: this.config.storageId,
      createdAt: now,
      updatedAt: now,
      expiresAt: options.ttlMs ? new Date(Date.now() + options.ttlMs).toISOString() : undefined,
      archived: false,
      version: 1,
      tags: options.tags || [],
    };

    this.files.set(fileId, storedFile);
    logger.debug(`File uploaded`, { fileId, name: filename, size: buffer.length });

    return storedFile;
  }

  async download(fileId: string): Promise<Buffer | null> {
    const file = this.files.get(fileId);
    if (!file) return null;

    if (file.expiresAt && new Date(file.expiresAt) < new Date()) {
      this.delete(fileId);
      return null;
    }

    if (!fs.existsSync(file.path)) {
      this.files.delete(fileId);
      return null;
    }

    return fs.readFileSync(file.path);
  }

  async delete(fileId: string): Promise<boolean> {
    const file = this.files.get(fileId);
    if (!file) return false;

    if (fs.existsSync(file.path)) {
      fs.unlinkSync(file.path);
    }

    this.files.delete(fileId);
    logger.debug(`File deleted`, { fileId });
    return true;
  }

  get(fileId: string): StoredFile | undefined {
    const file = this.files.get(fileId);
    if (file?.expiresAt && new Date(file.expiresAt) < new Date()) {
      this.delete(fileId);
      return undefined;
    }
    return file;
  }

  async list(options: FileListOptions = {}): Promise<FileListResult> {
    let files = Array.from(this.files.values());

    if (options.prefix) {
      files = files.filter(f => f.name.startsWith(options.prefix!));
    }

    if (options.tags?.length) {
      files = files.filter(f => options.tags!.some(tag => f.tags.includes(tag)));
    }

    if (!options.includeArchived) {
      files = files.filter(f => !f.archived);
    }

    const total = files.length;
    const limit = options.limit || 100;
    const offset = options.offset || 0;

    const paginated = files.slice(offset, offset + limit);

    return {
      files: paginated,
      total,
      hasMore: offset + limit < total,
    };
  }

  async getStats(): Promise<StorageStats> {
    const files = Array.from(this.files.values());
    const totalSize = files.reduce((sum, f) => sum + f.sizeBytes, 0);
    const archived = files.filter(f => f.archived);
    const archivedSize = archived.reduce((sum, f) => sum + f.sizeBytes, 0);

    return {
      totalFiles: files.length,
      totalSizeBytes: totalSize,
      archivedFiles: archived.length,
      archivedSizeBytes: archivedSize,
      storageUsedPercentage: this.config.maxSizeBytes
        ? (totalSize / this.config.maxSizeBytes) * 100
        : 0,
    };
  }

  exists(fileId: string): boolean {
    const file = this.files.get(fileId);
    return !!file && (!file.expiresAt || new Date(file.expiresAt) >= new Date());
  }
}

export class MemoryStorageBackend extends StorageBackend {
  config: StorageConfig;
  private files: Map<string, { data: Buffer; metadata: StoredFile }> = new Map();

  constructor(config: Omit<StorageConfig, 'storageId' | 'type'> & { storageId?: string }) {
    super();
    this.config = {
      ...config,
      storageId: config.storageId || generateId('stor_'),
      type: 'memory',
    } as StorageConfig;
  }

  async upload(
    data: Buffer | string,
    filename: string,
    options: FileUploadOptions = {}
  ): Promise<StoredFile> {
    const buffer = typeof data === 'string' ? Buffer.from(data) : data;

    const fileId = generateId('file_');
    const now = currentDateTime();

    const metadata: StoredFile = {
      fileId,
      name: filename,
      path: `memory://${filename}`,
      sizeBytes: buffer.length,
      contentType: options.contentType || 'application/octet-stream',
      metadata: options.metadata || {},
      storageId: this.config.storageId,
      createdAt: now,
      updatedAt: now,
      expiresAt: options.ttlMs ? new Date(Date.now() + options.ttlMs).toISOString() : undefined,
      archived: false,
      version: 1,
      tags: options.tags || [],
    };

    this.files.set(fileId, { data: buffer, metadata });
    logger.debug(`File uploaded to memory`, { fileId, name: filename, size: buffer.length });

    return metadata;
  }

  async download(fileId: string): Promise<Buffer | null> {
    const file = this.files.get(fileId);
    if (!file) return null;

    if (file.metadata.expiresAt && new Date(file.metadata.expiresAt) < new Date()) {
      this.delete(fileId);
      return null;
    }

    return file.data;
  }

  async delete(fileId: string): Promise<boolean> {
    const deleted = this.files.delete(fileId);
    if (deleted) {
      logger.debug(`File deleted from memory`, { fileId });
    }
    return deleted;
  }

  get(fileId: string): StoredFile | undefined {
    const file = this.files.get(fileId);
    if (file?.metadata.expiresAt && new Date(file.metadata.expiresAt) < new Date()) {
      this.delete(fileId);
      return undefined;
    }
    return file?.metadata;
  }

  async list(options: FileListOptions = {}): Promise<FileListResult> {
    let files = Array.from(this.files.values()).map(f => f.metadata);

    if (options.prefix) {
      files = files.filter(f => f.name.startsWith(options.prefix!));
    }

    if (options.tags?.length) {
      files = files.filter(f => options.tags!.some(tag => f.tags.includes(tag)));
    }

    if (!options.includeArchived) {
      files = files.filter(f => !f.archived);
    }

    const total = files.length;
    const limit = options.limit || 100;
    const offset = options.offset || 0;

    return {
      files: files.slice(offset, offset + limit),
      total,
      hasMore: offset + limit < total,
    };
  }

  async getStats(): Promise<StorageStats> {
    const files = Array.from(this.files.values()).map(f => f.metadata);
    const totalSize = files.reduce((sum, f) => sum + f.sizeBytes, 0);
    const archived = files.filter(f => f.archived);
    const archivedSize = archived.reduce((sum, f) => sum + f.sizeBytes, 0);

    return {
      totalFiles: files.length,
      totalSizeBytes: totalSize,
      archivedFiles: archived.length,
      archivedSizeBytes: archivedSize,
      storageUsedPercentage: this.config.maxSizeBytes
        ? (totalSize / this.config.maxSizeBytes) * 100
        : 0,
    };
  }

  exists(fileId: string): boolean {
    const file = this.files.get(fileId);
    return !!file && (!file.metadata.expiresAt || new Date(file.metadata.expiresAt) >= new Date());
  }
}
