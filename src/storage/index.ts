import { v4 as uuidv4 } from 'uuid';
import { StorageObject, LifecyclePolicy } from '../types';
import { ProcessingPipeline } from '../core';
import * as fs from 'fs';
import * as path from 'path';

export interface StorageBackend {
  put(key: string, data: Buffer, metadata?: Record<string, string>): Promise<StorageObject>;
  get(key: string): Promise<{ data: Buffer; object: StorageObject } | null>;
  delete(key: string): Promise<boolean>;
  list(prefix?: string): Promise<StorageObject[]>;
  exists(key: string): Promise<boolean>;
}

export class FileSystemBackend implements StorageBackend {
  private baseDir: string;
  private metadataDir: string;

  constructor(baseDir: string) {
    this.baseDir = baseDir;
    this.metadataDir = path.join(baseDir, '.metadata');
    this.ensureDirs();
  }

  private ensureDirs(): void {
    if (!fs.existsSync(this.baseDir)) {
      fs.mkdirSync(this.baseDir, { recursive: true });
    }
    if (!fs.existsSync(this.metadataDir)) {
      fs.mkdirSync(this.metadataDir, { recursive: true });
    }
  }

  private getFilePath(key: string): string {
    return path.join(this.baseDir, key);
  }

  private getMetadataPath(key: string): string {
    return path.join(this.metadataDir, `${key}.json`);
  }

  private saveMetadata(obj: StorageObject): void {
    const metadataPath = this.getMetadataPath(obj.key);
    const metadataDir = path.dirname(metadataPath);
    if (!fs.existsSync(metadataDir)) {
      fs.mkdirSync(metadataDir, { recursive: true });
    }
    fs.writeFileSync(metadataPath, JSON.stringify(obj, null, 2));
  }

  private loadMetadata(key: string): StorageObject | null {
    const metadataPath = this.getMetadataPath(key);
    if (!fs.existsSync(metadataPath)) {
      return null;
    }
    try {
      return JSON.parse(fs.readFileSync(metadataPath, 'utf8'));
    } catch {
      return null;
    }
  }

  async put(key: string, data: Buffer, metadata: Record<string, string> = {}): Promise<StorageObject> {
    const filePath = this.getFilePath(key);
    const fileDir = path.dirname(filePath);

    if (!fs.existsSync(fileDir)) {
      fs.mkdirSync(fileDir, { recursive: true });
    }

    fs.writeFileSync(filePath, data);

    const obj: StorageObject = {
      id: uuidv4(),
      key,
      size: data.length,
      contentType: metadata['content-type'] || 'application/octet-stream',
      metadata,
      createdAt: new Date().toISOString(),
      updatedAt: new Date().toISOString(),
      lifecycleState: 'active',
    };

    this.saveMetadata(obj);
    return obj;
  }

  async get(key: string): Promise<{ data: Buffer; object: StorageObject } | null> {
    const filePath = this.getFilePath(key);
    if (!fs.existsSync(filePath)) {
      return null;
    }

    const data = fs.readFileSync(filePath);
    const object = this.loadMetadata(key);

    if (!object) {
      return null;
    }

    return { data, object };
  }

  async delete(key: string): Promise<boolean> {
    const filePath = this.getFilePath(key);
    const metadataPath = this.getMetadataPath(key);

    if (fs.existsSync(filePath)) {
      fs.unlinkSync(filePath);
    }
    if (fs.existsSync(metadataPath)) {
      fs.unlinkSync(metadataPath);
    }

    return true;
  }

  async list(prefix: string = ''): Promise<StorageObject[]> {
    const results: StorageObject[] = [];
    const prefixDir = path.join(this.metadataDir, prefix);

    if (!fs.existsSync(this.metadataDir)) {
      return [];
    }

    const readDir = (dir: string): void => {
      const entries = fs.readdirSync(dir, { withFileTypes: true });
      for (const entry of entries) {
        const fullPath = path.join(dir, entry.name);
        if (entry.isDirectory()) {
          readDir(fullPath);
        } else if (entry.name.endsWith('.json')) {
          const relativePath = path.relative(this.metadataDir, fullPath);
          const key = relativePath.slice(0, -5);
          const obj = this.loadMetadata(key);
          if (obj && obj.key.startsWith(prefix)) {
            results.push(obj);
          }
        }
      }
    };

    if (prefix && fs.existsSync(prefixDir)) {
      readDir(prefixDir);
    } else {
      readDir(this.metadataDir);
    }

    return results;
  }

  async exists(key: string): Promise<boolean> {
    return fs.existsSync(this.getFilePath(key));
  }
}

export class LifecycleManager {
  private policies: Map<string, LifecyclePolicy> = new Map();
  private backend: StorageBackend;
  private checkInterval: number = 3600000;
  private checkTimer: NodeJS.Timeout | null = null;

  constructor(backend: StorageBackend) {
    this.backend = backend;
  }

  addPolicy(policy: LifecyclePolicy): void {
    this.policies.set(policy.id, policy);
  }

  removePolicy(id: string): boolean {
    return this.policies.delete(id);
  }

  getPolicies(): LifecyclePolicy[] {
    return Array.from(this.policies.values());
  }

  startAutoCheck(): void {
    if (this.checkTimer) return;
    this.checkTimer = setInterval(() => this.checkLifecycle(), this.checkInterval);
  }

  stopAutoCheck(): void {
    if (this.checkTimer) {
      clearInterval(this.checkTimer);
      this.checkTimer = null;
    }
  }

  async checkLifecycle(): Promise<void> {
    const now = Date.now();
    const allObjects = await this.backend.list();

    for (const obj of allObjects) {
      const policy = this.findMatchingPolicy(obj.key);
      if (!policy || !policy.enabled) continue;

      const createdDate = new Date(obj.createdAt).getTime();
      const ageDays = (now - createdDate) / (1000 * 60 * 60 * 24);

      for (const transition of policy.transitions) {
        if (ageDays >= transition.days && obj.lifecycleState === 'active') {
          obj.lifecycleState = transition.storageClass === 'archive' ? 'archived' : 'active';
          obj.updatedAt = new Date().toISOString();
        }
      }

      if (policy.expirationDays && ageDays >= policy.expirationDays) {
        obj.lifecycleState = 'deleted';
        await this.backend.delete(obj.key);
      }
    }
  }

  private findMatchingPolicy(key: string): LifecyclePolicy | null {
    for (const policy of this.policies.values()) {
      if (key.startsWith(policy.prefix)) {
        return policy;
      }
    }
    return null;
  }
}

export class StorageManager {
  private backend: StorageBackend;
  private lifecycleManager: LifecycleManager;
  private pipeline: ProcessingPipeline<{ key: string; data: Buffer; metadata?: Record<string, string> }, StorageObject>;

  constructor(backend: StorageBackend, lifecycleManager: LifecycleManager) {
    this.backend = backend;
    this.lifecycleManager = lifecycleManager;
    this.pipeline = this.buildPipeline();
  }

  private buildPipeline(): ProcessingPipeline<{ key: string; data: Buffer; metadata?: Record<string, string> }, StorageObject> {
    return new ProcessingPipeline<{ key: string; data: Buffer; metadata?: Record<string, string> }, StorageObject>()
      .addStage({
        name: 'validation',
        process: async (input) => {
          if (!input.key || input.key.length === 0) {
            throw new Error('Storage key is required');
          }
          if (!input.data || input.data.length === 0) {
            throw new Error('Data is required');
          }
          return input;
        },
      })
      .addStage({
        name: 'storage',
        process: async (input) => this.backend.put(input.key, input.data, input.metadata),
      });
  }

  async put(key: string, data: Buffer, metadata?: Record<string, string>): Promise<StorageObject> {
    const result = await this.pipeline.execute({ key, data, metadata });
    if (!result.success || !result.data) {
      throw new Error(result.error || 'Failed to store object');
    }
    return result.data;
  }

  async get(key: string): Promise<{ data: Buffer; object: StorageObject } | null> {
    return this.backend.get(key);
  }

  async delete(key: string): Promise<boolean> {
    return this.backend.delete(key);
  }

  async list(prefix?: string): Promise<StorageObject[]> {
    return this.backend.list(prefix);
  }

  async exists(key: string): Promise<boolean> {
    return this.backend.exists(key);
  }

  async getObjectInfo(key: string): Promise<StorageObject | null> {
    const result = await this.backend.get(key);
    return result ? result.object : null;
  }

  async copy(sourceKey: string, destKey: string): Promise<StorageObject | null> {
    const source = await this.backend.get(sourceKey);
    if (!source) return null;
    return this.backend.put(destKey, source.data, source.object.metadata);
  }

  async move(sourceKey: string, destKey: string): Promise<StorageObject | null> {
    const copied = await this.copy(sourceKey, destKey);
    if (copied) {
      await this.backend.delete(sourceKey);
    }
    return copied;
  }

  getLifecycleManager(): LifecycleManager {
    return this.lifecycleManager;
  }
}

export class StorageQuotaManager {
  private backend: StorageBackend;
  private maxSize: number;
  private currentSize: number = 0;
  private maxObjects: number;

  constructor(backend: StorageBackend, maxSize: number = 1073741824, maxObjects: number = 100000) {
    this.backend = backend;
    this.maxSize = maxSize;
    this.maxObjects = maxObjects;
  }

  async recalculateUsage(): Promise<void> {
    const objects = await this.backend.list();
    this.currentSize = objects.reduce((sum, obj) => sum + obj.size, 0);
  }

  getCurrentSize(): number {
    return this.currentSize;
  }

  getUsagePercent(): number {
    return (this.currentSize / this.maxSize) * 100;
  }

  canStore(size: number): boolean {
    return this.currentSize + size <= this.maxSize;
  }

  async enforceQuota(): Promise<void> {
    if (this.getUsagePercent() > 90) {
      const objects = await this.backend.list();
      const sorted = objects.sort((a, b) =>
        new Date(a.createdAt).getTime() - new Date(b.createdAt).getTime()
      );

      while (this.getUsagePercent() > 80 && sorted.length > 0) {
        const oldest = sorted.shift();
        if (oldest) {
          await this.backend.delete(oldest.key);
          this.currentSize -= oldest.size;
        }
      }
    }
  }
}

export function createStorageModule(baseDir: string): {
  backend: StorageBackend;
  lifecycleManager: LifecycleManager;
  storageManager: StorageManager;
  quotaManager: StorageQuotaManager;
} {
  const backend = new FileSystemBackend(baseDir);
  const lifecycleManager = new LifecycleManager(backend);
  const storageManager = new StorageManager(backend, lifecycleManager);
  const quotaManager = new StorageQuotaManager(backend);

  return {
    backend,
    lifecycleManager,
    storageManager,
    quotaManager,
  };
}
