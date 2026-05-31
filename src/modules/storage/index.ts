import { IStorageAdapter, IMetadataIndex } from '@ports/index';
import { StoredObject } from '@apptypes/index';
import { rootLogger } from '@modules/logging';
import { generateId, nowISO } from '@utils/index';
import { config } from '@config/index';

export class MemoryStorageAdapter implements IStorageAdapter {
  private logger = rootLogger.child({ module: 'MemoryStorageAdapter' });
  private storage: Map<string, { data: Buffer; metadata: StoredObject }> = new Map();

  private getKey(bucket: string, key: string): string {
    return `${bucket}/${key}`;
  }

  async put(
    bucket: string,
    key: string,
    data: Buffer,
    metadata?: Record<string, string>
  ): Promise<StoredObject> {
    const fullKey = this.getKey(bucket, key);
    const objectId = generateId('obj_');

    const storedObject: StoredObject = {
      object_id: objectId,
      bucket,
      key,
      size: data.length,
      content_type: metadata?.['content-type'] || 'application/octet-stream',
      metadata: metadata || {},
      created_at: nowISO(),
    };

    this.storage.set(fullKey, { data, metadata: storedObject });
    this.logger.info('Object stored', { bucket, key, size: data.length });

    return storedObject;
  }

  async get(bucket: string, key: string): Promise<Buffer | null> {
    const fullKey = this.getKey(bucket, key);
    const stored = this.storage.get(fullKey);
    return stored ? stored.data : null;
  }

  async delete(bucket: string, key: string): Promise<boolean> {
    const fullKey = this.getKey(bucket, key);
    const existed = this.storage.has(fullKey);
    this.storage.delete(fullKey);
    if (existed) {
      this.logger.info('Object deleted', { bucket, key });
    }
    return existed;
  }

  async list(bucket: string, prefix?: string): Promise<StoredObject[]> {
    const results: StoredObject[] = [];
    const bucketPrefix = `${bucket}/`;

    for (const [key, stored] of this.storage) {
      if (key.startsWith(bucketPrefix)) {
        const objectKey = key.slice(bucketPrefix.length);
        if (!prefix || objectKey.startsWith(prefix)) {
          results.push(stored.metadata);
        }
      }
    }

    return results.sort((a, b) => a.key.localeCompare(b.key));
  }

  async getMetadata(bucket: string, key: string): Promise<StoredObject | null> {
    const fullKey = this.getKey(bucket, key);
    const stored = this.storage.get(fullKey);
    return stored ? stored.metadata : null;
  }

  clear(): void {
    this.storage.clear();
    this.logger.info('Memory storage cleared');
  }

  size(): number {
    return this.storage.size;
  }
}

export class MemoryMetadataIndex implements IMetadataIndex {
  private logger = rootLogger.child({ module: 'MemoryMetadataIndex' });
  private objectIndex: Map<string, StoredObject> = new Map();
  private tagIndex: Map<string, Set<string>> = new Map();

  async index(object: StoredObject): Promise<void> {
    this.objectIndex.set(object.object_id, object);

    Object.entries(object.metadata).forEach(([key, value]) => {
      const tagKey = `${key}:${value}`;
      if (!this.tagIndex.has(tagKey)) {
        this.tagIndex.set(tagKey, new Set());
      }
      this.tagIndex.get(tagKey)!.add(object.object_id);
    });

    this.logger.info('Object indexed', { object_id: object.object_id });
  }

  async search(query: Record<string, string>): Promise<StoredObject[]> {
    if (Object.keys(query).length === 0) {
      return Array.from(this.objectIndex.values());
    }

    let resultIds: Set<string> | null = null;

    Object.entries(query).forEach(([key, value]) => {
      const tagKey = `${key}:${value}`;
      const matchingIds = this.tagIndex.get(tagKey) || new Set<string>();

      if (resultIds === null) {
        resultIds = new Set(matchingIds);
      } else {
        resultIds = new Set([...resultIds].filter((id) => matchingIds.has(id)));
      }
    });

    const results: StoredObject[] = [];
    if (resultIds) {
      (resultIds as Set<string>).forEach((id: string) => {
        const obj = this.objectIndex.get(id);
        if (obj) results.push(obj);
      });
    }

    return results;
  }

  async update(objectId: string, metadata: Record<string, string>): Promise<boolean> {
    const object = this.objectIndex.get(objectId);
    if (!object) return false;

    Object.keys(object.metadata).forEach((key) => {
      const tagKey = `${key}:${object.metadata[key]}`;
      this.tagIndex.get(tagKey)?.delete(objectId);
    });

    object.metadata = { ...object.metadata, ...metadata };

    Object.entries(object.metadata).forEach(([key, value]) => {
      const tagKey = `${key}:${value}`;
      if (!this.tagIndex.has(tagKey)) {
        this.tagIndex.set(tagKey, new Set());
      }
      this.tagIndex.get(tagKey)!.add(objectId);
    });

    this.logger.info('Object metadata updated', { object_id: objectId });
    return true;
  }

  async delete(objectId: string): Promise<boolean> {
    const object = this.objectIndex.get(objectId);
    if (!object) return false;

    Object.entries(object.metadata).forEach(([key, value]) => {
      const tagKey = `${key}:${value}`;
      this.tagIndex.get(tagKey)?.delete(objectId);
    });

    this.objectIndex.delete(objectId);
    this.logger.info('Object removed from index', { object_id: objectId });
    return true;
  }

  clear(): void {
    this.objectIndex.clear();
    this.tagIndex.clear();
    this.logger.info('Metadata index cleared');
  }
}

export class StorageManager {
  private logger = rootLogger.child({ module: 'StorageManager' });
  private storageAdapter: IStorageAdapter;
  private metadataIndex: IMetadataIndex;

  constructor(
    storageAdapter?: IStorageAdapter,
    metadataIndex?: IMetadataIndex
  ) {
    this.storageAdapter = storageAdapter || new MemoryStorageAdapter();
    this.metadataIndex = metadataIndex || new MemoryMetadataIndex();
  }

  async storeObject(
    bucket: string,
    key: string,
    data: Buffer,
    metadata?: Record<string, string>
  ): Promise<StoredObject> {
    const stored = await this.storageAdapter.put(bucket, key, data, metadata);
    await this.metadataIndex.index(stored);
    return stored;
  }

  async getObject(bucket: string, key: string): Promise<Buffer | null> {
    return this.storageAdapter.get(bucket, key);
  }

  async getObjectMetadata(bucket: string, key: string): Promise<StoredObject | null> {
    return this.storageAdapter.getMetadata(bucket, key);
  }

  async deleteObject(bucket: string, key: string): Promise<boolean> {
    const metadata = await this.storageAdapter.getMetadata(bucket, key);
    const deleted = await this.storageAdapter.delete(bucket, key);
    if (deleted && metadata) {
      await this.metadataIndex.delete(metadata.object_id);
    }
    return deleted;
  }

  async listObjects(bucket: string, prefix?: string): Promise<StoredObject[]> {
    return this.storageAdapter.list(bucket, prefix);
  }

  async searchObjects(query: Record<string, string>): Promise<StoredObject[]> {
    return this.metadataIndex.search(query);
  }

  async updateObjectMetadata(
    objectId: string,
    metadata: Record<string, string>
  ): Promise<boolean> {
    return this.metadataIndex.update(objectId, metadata);
  }

  getStorageAdapter(): IStorageAdapter {
    return this.storageAdapter;
  }

  getMetadataIndex(): IMetadataIndex {
    return this.metadataIndex;
  }
}

export const storageManager = new StorageManager();
