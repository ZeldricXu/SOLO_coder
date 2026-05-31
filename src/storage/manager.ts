import { StorageConfig, StoredFile, FileUploadOptions, FileListOptions, FileListResult, StorageStats } from './types';
import { StorageBackend, LocalStorageBackend, MemoryStorageBackend } from './backends';
import { LifecycleManager, lifecycleManager } from './lifecycleManager';
import { generateId, logger } from '../utils/common';

export class StorageManager {
  private backends: Map<string, StorageBackend> = new Map();
  private lifecycleManager: LifecycleManager;

  constructor() {
    this.lifecycleManager = lifecycleManager;
  }

  createLocalStorage(
    name: string,
    basePath: string = './storage',
    config: Partial<StorageConfig> = {}
  ): StorageBackend {
    const storageId = generateId('stor_');
    const backend = new LocalStorageBackend({
      ...config,
      storageId,
      name,
      basePath,
      type: 'local',
    } as any);

    this.backends.set(storageId, backend);
    this.lifecycleManager.registerBackend(storageId, backend);

    logger.info(`Local storage created`, { storageId, name, basePath });
    return backend;
  }

  createMemoryStorage(
    name: string,
    config: Partial<StorageConfig> = {}
  ): StorageBackend {
    const storageId = generateId('stor_');
    const backend = new MemoryStorageBackend({
      ...config,
      storageId,
      name,
      basePath: 'memory://',
      type: 'memory',
    } as any);

    this.backends.set(storageId, backend);
    this.lifecycleManager.registerBackend(storageId, backend);

    logger.info(`Memory storage created`, { storageId, name });
    return backend;
  }

  getStorage(storageId: string): StorageBackend | undefined {
    return this.backends.get(storageId);
  }

  getStorageByName(name: string): StorageBackend | undefined {
    return Array.from(this.backends.values()).find(b => b.config.name === name);
  }

  removeStorage(storageId: string): boolean {
    return this.backends.delete(storageId);
  }

  listStorages(): StorageBackend[] {
    return Array.from(this.backends.values());
  }

  async upload(
    storageId: string,
    data: Buffer | string,
    filename: string,
    options?: FileUploadOptions
  ): Promise<StoredFile> {
    const backend = this.backends.get(storageId);
    if (!backend) {
      throw new Error(`Storage not found: ${storageId}`);
    }
    return backend.upload(data, filename, options);
  }

  async download(storageId: string, fileId: string): Promise<Buffer | null> {
    const backend = this.backends.get(storageId);
    if (!backend) {
      throw new Error(`Storage not found: ${storageId}`);
    }
    return backend.download(fileId);
  }

  async delete(storageId: string, fileId: string): Promise<boolean> {
    const backend = this.backends.get(storageId);
    if (!backend) {
      throw new Error(`Storage not found: ${storageId}`);
    }
    return backend.delete(fileId);
  }

  getFile(storageId: string, fileId: string): StoredFile | undefined {
    const backend = this.backends.get(storageId);
    if (!backend) {
      throw new Error(`Storage not found: ${storageId}`);
    }
    return backend.get(fileId);
  }

  async listFiles(storageId: string, options?: FileListOptions): Promise<FileListResult> {
    const backend = this.backends.get(storageId);
    if (!backend) {
      throw new Error(`Storage not found: ${storageId}`);
    }
    return backend.list(options);
  }

  async getStorageStats(storageId: string): Promise<StorageStats> {
    const backend = this.backends.get(storageId);
    if (!backend) {
      throw new Error(`Storage not found: ${storageId}`);
    }
    return backend.getStats();
  }

  async runLifecycle(): Promise<{ rulesEvaluated: number; filesProcessed: number }> {
    return this.lifecycleManager.evaluateRules();
  }

  addLifecycleRule(rule: any) {
    return this.lifecycleManager.addRule(rule);
  }

  getLifecycleManager(): LifecycleManager {
    return this.lifecycleManager;
  }
}

export const storageManager = new StorageManager();
