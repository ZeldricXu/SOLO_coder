import fs from 'fs';
import path from 'path';
import crypto from 'crypto';
import { StorageProviderConfig } from './types';

export interface StorageProvider {
  upload(sourcePath: string, destinationPath: string): Promise<{ success: boolean; checksum?: string; size?: number }>;
  download(sourcePath: string, destinationPath: string): Promise<boolean>;
  delete(path: string): Promise<boolean>;
  list(prefix?: string): Promise<string[]>;
  exists(path: string): Promise<boolean>;
}

export class LocalStorageProvider implements StorageProvider {
  constructor(private config: StorageProviderConfig) {}

  private getBasePath(): string {
    return (this.config.options.basePath as string) || './data/backups';
  }

  async upload(sourcePath: string, destinationPath: string): Promise<{ success: boolean; checksum?: string; size?: number }> {
    try {
      const basePath = this.getBasePath();
      const fullDestPath = path.join(basePath, destinationPath);
      const destDir = path.dirname(fullDestPath);

      if (!fs.existsSync(destDir)) {
        fs.mkdirSync(destDir, { recursive: true });
      }

      const content = fs.readFileSync(sourcePath);
      fs.writeFileSync(fullDestPath, content);

      const checksum = this.calculateChecksum(content);
      const stats = fs.statSync(fullDestPath);

      return { success: true, checksum, size: stats.size };
    } catch (error) {
      console.error('[LocalStorage] Upload failed:', error);
      return { success: false };
    }
  }

  async download(sourcePath: string, destinationPath: string): Promise<boolean> {
    try {
      const basePath = this.getBasePath();
      const fullSourcePath = path.join(basePath, sourcePath);

      if (!fs.existsSync(fullSourcePath)) {
        return false;
      }

      const destDir = path.dirname(destinationPath);
      if (!fs.existsSync(destDir)) {
        fs.mkdirSync(destDir, { recursive: true });
      }

      fs.copyFileSync(fullSourcePath, destinationPath);
      return true;
    } catch (error) {
      console.error('[LocalStorage] Download failed:', error);
      return false;
    }
  }

  async delete(filePath: string): Promise<boolean> {
    try {
      const basePath = this.getBasePath();
      const fullPath = path.join(basePath, filePath);

      if (fs.existsSync(fullPath)) {
        fs.unlinkSync(fullPath);
        return true;
      }
      return false;
    } catch (error) {
      console.error('[LocalStorage] Delete failed:', error);
      return false;
    }
  }

  async list(prefix?: string): Promise<string[]> {
    try {
      const basePath = this.getBasePath();
      const searchPath = prefix ? path.join(basePath, prefix) : basePath;

      if (!fs.existsSync(searchPath)) {
        return [];
      }

      const files = fs.readdirSync(searchPath, { withFileTypes: true });
      return files
        .filter(f => f.isFile())
        .map(f => (prefix ? path.join(prefix, f.name) : f.name));
    } catch (error) {
      console.error('[LocalStorage] List failed:', error);
      return [];
    }
  }

  async exists(filePath: string): Promise<boolean> {
    const basePath = this.getBasePath();
    const fullPath = path.join(basePath, filePath);
    return fs.existsSync(fullPath);
  }

  private calculateChecksum(data: Buffer): string {
    return crypto.createHash('sha256').update(data).digest('hex');
  }
}

export class S3StorageProvider implements StorageProvider {
  constructor(private config: StorageProviderConfig) {}

  async upload(sourcePath: string, destinationPath: string): Promise<{ success: boolean; checksum?: string; size?: number }> {
    console.log(`[S3] Upload ${sourcePath} -> ${destinationPath}`);
    return { success: true, checksum: 's3-checksum', size: 0 };
  }

  async download(sourcePath: string, destinationPath: string): Promise<boolean> {
    console.log(`[S3] Download ${sourcePath} -> ${destinationPath}`);
    return true;
  }

  async delete(filePath: string): Promise<boolean> {
    console.log(`[S3] Delete ${filePath}`);
    return true;
  }

  async list(prefix?: string): Promise<string[]> {
    console.log(`[S3] List ${prefix || ''}`);
    return [];
  }

  async exists(filePath: string): Promise<boolean> {
    console.log(`[S3] Exists ${filePath}`);
    return false;
  }
}

export function createStorageProvider(config: StorageProviderConfig): StorageProvider {
  switch (config.type) {
    case 'local':
      return new LocalStorageProvider(config);
    case 's3':
      return new S3StorageProvider(config);
    default:
      throw new Error(`Unsupported storage provider type: ${config.type}`);
  }
}
