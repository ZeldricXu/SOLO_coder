import { logger } from '../logging';
import { v4 as uuidv4 } from 'uuid';
import * as fs from 'fs-extra';
import * as path from 'path';
import * as mime from 'mime-types';

export type StorageType = 'local' | 's3' | 'gcs' | 'azure';
export type LifecycleRuleAction = 'archive' | 'delete' | 'transition_to_cold';

export interface StorageConfig {
  type: StorageType;
  basePath?: string;
  bucket?: string;
  region?: string;
  accessKey?: string;
  secretKey?: string;
}

export interface LifecycleRule {
  id: string;
  name: string;
  prefix: string;
  daysAfterUpload: number;
  action: LifecycleRuleAction;
  enabled: boolean;
}

export interface StoredFile {
  id: string;
  name: string;
  path: string;
  size: number;
  mimeType: string;
  storageType: StorageType;
  createdAt: string;
  lastAccessedAt: string;
  metadata: Record<string, string>;
}

export interface FileUploadOptions {
  metadata?: Record<string, string>;
  contentType?: string;
  acl?: 'private' | 'public-read';
}

export class StorageManager {
  private config: StorageConfig;
  private files: Map<string, StoredFile> = new Map();
  private lifecycleRules: LifecycleRule[] = [];

  constructor(config: StorageConfig) {
    this.config = config;
    if (config.basePath) {
      fs.ensureDirSync(config.basePath);
    }
    logger.info('Storage manager initialized', { type: config.type });
  }

  async uploadFile(filePath: string, destPath: string, options: FileUploadOptions = {}): Promise<StoredFile> {
    const stats = await fs.stat(filePath);
    const fileId = `file_${uuidv4()}`;
    const contentType = options.contentType || mime.lookup(filePath) || 'application/octet-stream';
    
    const storedFile: StoredFile = {
      id: fileId,
      name: path.basename(filePath),
      path: destPath,
      size: stats.size,
      mimeType: contentType,
      storageType: this.config.type,
      createdAt: new Date().toISOString(),
      lastAccessedAt: new Date().toISOString(),
      metadata: options.metadata || {}
    };

    if (this.config.basePath) {
      const fullPath = path.join(this.config.basePath, destPath);
      await fs.ensureDir(path.dirname(fullPath));
      await fs.copy(filePath, fullPath);
    }

    this.files.set(fileId, storedFile);
    logger.info('File uploaded', { fileId, name: storedFile.name, size: storedFile.size });
    return storedFile;
  }

  async downloadFile(fileId: string, destPath: string): Promise<StoredFile> {
    const storedFile = this.files.get(fileId);
    if (!storedFile) {
      throw new Error(`File not found: ${fileId}`);
    }

    storedFile.lastAccessedAt = new Date().toISOString();
    
    if (this.config.basePath) {
      const sourcePath = path.join(this.config.basePath, storedFile.path);
      await fs.ensureDir(path.dirname(destPath));
      await fs.copy(sourcePath, destPath);
    }

    logger.info('File downloaded', { fileId, name: storedFile.name });
    return storedFile;
  }

  async deleteFile(fileId: string): Promise<void> {
    const storedFile = this.files.get(fileId);
    if (!storedFile) {
      throw new Error(`File not found: ${fileId}`);
    }

    if (this.config.basePath) {
      const fullPath = path.join(this.config.basePath, storedFile.path);
      await fs.remove(fullPath);
    }

    this.files.delete(fileId);
    logger.info('File deleted', { fileId, name: storedFile.name });
  }

  getFile(fileId: string): StoredFile | undefined {
    return this.files.get(fileId);
  }

  listFiles(prefix?: string): StoredFile[] {
    let files = Array.from(this.files.values());
    if (prefix) {
      files = files.filter(f => f.path.startsWith(prefix));
    }
    return files;
  }

  addLifecycleRule(rule: Omit<LifecycleRule, 'id'>): LifecycleRule {
    const newRule: LifecycleRule = { ...rule, id: `rule_${uuidv4()}` };
    this.lifecycleRules.push(newRule);
    logger.info('Lifecycle rule added', { ruleId: newRule.id, name: newRule.name });
    return newRule;
  }

  removeLifecycleRule(ruleId: string): void {
    this.lifecycleRules = this.lifecycleRules.filter(r => r.id !== ruleId);
  }

  async applyLifecycleRules(): Promise<{ archived: number; deleted: number }> {
    const now = new Date();
    let archived = 0;
    let deleted = 0;

    for (const file of this.files.values()) {
      const fileAge = (now.getTime() - new Date(file.createdAt).getTime()) / (1000 * 60 * 60 * 24);
      
      for (const rule of this.lifecycleRules.filter(r => r.enabled)) {
        if (file.path.startsWith(rule.prefix) && fileAge >= rule.daysAfterUpload) {
          switch (rule.action) {
            case 'archive':
              archived++;
              logger.info('File archived', { fileId: file.id, rule: rule.name });
              break;
            case 'delete':
              await this.deleteFile(file.id);
              deleted++;
              break;
            case 'transition_to_cold':
              file.storageType = this.config.type;
              archived++;
              break;
          }
        }
      }
    }

    logger.info('Lifecycle rules applied', { archived, deleted });
    return { archived, deleted };
  }

  getStats(): { totalFiles: number; totalSize: number } {
    const files = Array.from(this.files.values());
    return {
      totalFiles: files.length,
      totalSize: files.reduce((sum, f) => sum + f.size, 0)
    };
  }
}

export const createStorageManager = (config: StorageConfig): StorageManager => {
  return new StorageManager(config);
};
