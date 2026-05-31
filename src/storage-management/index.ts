import { v4 as uuidv4 } from 'uuid';
import logger from '../common/logger';
import { NotFoundError } from '../common/errors';

export interface StorageObject {
  objectId: string;
  key: string;
  bucket: string;
  size: number;
  contentType: string;
  metadata: Record<string, string>;
  etag: string;
  createdAt: string;
  updatedAt: string;
}

export interface ObjectMetadata {
  metadataId: string;
  objectId: string;
  key: string;
  bucket: string;
  tags: Record<string, string>;
  customMetadata: Record<string, unknown>;
  version: number;
  isLatest: boolean;
  createdAt: string;
}

export interface StorageAdapter {
  name: string;
  type: 's3' | 'local' | 'azure' | 'gcs' | 'minio';
  upload(key: string, data: Buffer, contentType?: string, metadata?: Record<string, string>): Promise<StorageObject>;
  download(key: string): Promise<Buffer>;
  delete(key: string): Promise<boolean>;
  exists(key: string): Promise<boolean>;
  list(prefix?: string): Promise<string[]>;
  getObjectInfo(key: string): Promise<StorageObject | null>;
  copy(sourceKey: string, destinationKey: string): Promise<StorageObject>;
}

export interface StorageConfig {
  defaultBucket: string;
  enableVersioning: boolean;
  maxObjectSize: number;
  allowedContentTypes: string[];
}

export interface UploadOptions {
  bucket?: string;
  contentType?: string;
  metadata?: Record<string, string>;
  tags?: Record<string, string>;
}

export interface MetadataIndexQuery {
  bucket?: string;
  tags?: Record<string, string>;
  createdAtStart?: string;
  createdAtEnd?: string;
  limit?: number;
  offset?: number;
}

export class LocalStorageAdapter implements StorageAdapter {
  name = 'local';
  type = 'local' as const;
  private storagePath: string;
  private objects: Map<string, { data: Buffer; info: StorageObject }> = new Map();

  constructor(storagePath: string = './data') {
    this.storagePath = storagePath;
  }

  async upload(key: string, data: Buffer, contentType: string = 'application/octet-stream', metadata: Record<string, string> = {}): Promise<StorageObject> {
    const objectId = uuidv4();
    const now = new Date().toISOString();

    const storageObject: StorageObject = {
      objectId,
      key,
      bucket: 'default',
      size: data.length,
      contentType,
      metadata,
      etag: this.generateEtag(data),
      createdAt: now,
      updatedAt: now
    };

    this.objects.set(key, { data, info: storageObject });
    logger.debug({ key, size: data.length }, '对象已存储到本地');
    return storageObject;
  }

  async download(key: string): Promise<Buffer> {
    const obj = this.objects.get(key);
    if (!obj) {
      throw new NotFoundError(`对象不存在: ${key}`);
    }
    return obj.data;
  }

  async delete(key: string): Promise<boolean> {
    return this.objects.delete(key);
  }

  async exists(key: string): Promise<boolean> {
    return this.objects.has(key);
  }

  async list(prefix: string = ''): Promise<string[]> {
    return Array.from(this.objects.keys()).filter(key => key.startsWith(prefix));
  }

  async getObjectInfo(key: string): Promise<StorageObject | null> {
    const obj = this.objects.get(key);
    return obj ? obj.info : null;
  }

  async copy(sourceKey: string, destinationKey: string): Promise<StorageObject> {
    const source = this.objects.get(sourceKey);
    if (!source) {
      throw new NotFoundError(`源对象不存在: ${sourceKey}`);
    }

    const newInfo: StorageObject = {
      ...source.info,
      objectId: uuidv4(),
      key: destinationKey,
      createdAt: new Date().toISOString(),
      updatedAt: new Date().toISOString()
    };

    this.objects.set(destinationKey, { data: source.data, info: newInfo });
    return newInfo;
  }

  private generateEtag(data: Buffer): string {
    return `${data.length}-${data.toString('base64').slice(0, 16)}`;
  }
}

export class MetadataIndex {
  private metadataStore: Map<string, ObjectMetadata> = new Map();
  private objectIdToMetadata: Map<string, string[]> = new Map();

  async index(metadata: Omit<ObjectMetadata, 'metadataId' | 'createdAt'>): Promise<ObjectMetadata> {
    const fullMetadata: ObjectMetadata = {
      ...metadata,
      metadataId: uuidv4(),
      createdAt: new Date().toISOString()
    };

    this.metadataStore.set(fullMetadata.metadataId, fullMetadata);

    if (!this.objectIdToMetadata.has(fullMetadata.objectId)) {
      this.objectIdToMetadata.set(fullMetadata.objectId, []);
    }
    this.objectIdToMetadata.get(fullMetadata.objectId)!.push(fullMetadata.metadataId);

    logger.debug({ objectId: fullMetadata.objectId, key: fullMetadata.key }, '元数据已索引');
    return fullMetadata;
  }

  async query(query: MetadataIndexQuery): Promise<ObjectMetadata[]> {
    let results = Array.from(this.metadataStore.values());

    if (query.bucket) {
      results = results.filter(m => m.bucket === query.bucket);
    }

    if (query.tags) {
      results = results.filter(m => {
        for (const [key, value] of Object.entries(query.tags!)) {
          if (m.tags[key] !== value) return false;
        }
        return true;
      });
    }

    if (query.createdAtStart) {
      results = results.filter(m => m.createdAt >= query.createdAtStart!);
    }

    if (query.createdAtEnd) {
      results = results.filter(m => m.createdAt <= query.createdAtEnd!);
    }

    if (query.offset) {
      results = results.slice(query.offset);
    }

    if (query.limit) {
      results = results.slice(0, query.limit);
    }

    return results;
  }

  async getByObjectId(objectId: string): Promise<ObjectMetadata[]> {
    const ids = this.objectIdToMetadata.get(objectId) || [];
    return ids.map(id => this.metadataStore.get(id)!).filter(Boolean);
  }

  async getLatest(objectId: string): Promise<ObjectMetadata | null> {
    const all = await this.getByObjectId(objectId);
    return all.find(m => m.isLatest) || all[0] || null;
  }

  async delete(metadataId: string): Promise<boolean> {
    const metadata = this.metadataStore.get(metadataId);
    if (!metadata) return false;

    this.metadataStore.delete(metadataId);
    const ids = this.objectIdToMetadata.get(metadata.objectId);
    if (ids) {
      const index = ids.indexOf(metadataId);
      if (index > -1) ids.splice(index, 1);
    }
    return true;
  }

  async deleteByObjectId(objectId: string): Promise<number> {
    const ids = this.objectIdToMetadata.get(objectId) || [];
    for (const id of ids) {
      this.metadataStore.delete(id);
    }
    this.objectIdToMetadata.delete(objectId);
    return ids.length;
  }
}

export class StorageManager {
  private adapters: Map<string, StorageAdapter> = new Map();
  private defaultAdapter: StorageAdapter;
  private metadataIndex: MetadataIndex;
  private config: StorageConfig;

  constructor(config: Partial<StorageConfig> = {}) {
    this.config = {
      defaultBucket: config.defaultBucket ?? 'default',
      enableVersioning: config.enableVersioning ?? true,
      maxObjectSize: config.maxObjectSize ?? 100 * 1024 * 1024,
      allowedContentTypes: config.allowedContentTypes ?? ['*/*']
    };

    this.defaultAdapter = new LocalStorageAdapter();
    this.adapters.set('local', this.defaultAdapter);
    this.metadataIndex = new MetadataIndex();
  }

  registerAdapter(name: string, adapter: StorageAdapter): void {
    this.adapters.set(name, adapter);
    logger.info({ name, type: adapter.type }, '注册存储适配器');
  }

  setDefaultAdapter(name: string): void {
    const adapter = this.adapters.get(name);
    if (!adapter) {
      throw new Error(`适配器不存在: ${name}`);
    }
    this.defaultAdapter = adapter;
    logger.info({ name }, '设置默认存储适配器');
  }

  async upload(key: string, data: Buffer, options: UploadOptions = {}, adapterName?: string): Promise<{ object: StorageObject; metadata: ObjectMetadata }> {
    if (data.length > this.config.maxObjectSize) {
      throw new Error(`对象大小超过限制: ${data.length} > ${this.config.maxObjectSize}`);
    }

    const adapter = adapterName ? this.getAdapter(adapterName) : this.defaultAdapter;
    const bucket = options.bucket ?? this.config.defaultBucket;

    const storageObject = await adapter.upload(
      key,
      data,
      options.contentType,
      options.metadata
    );

    const metadata = await this.metadataIndex.index({
      objectId: storageObject.objectId,
      key,
      bucket,
      tags: options.tags || {},
      customMetadata: options.metadata || {},
      version: 1,
      isLatest: true
    });

    logger.info({ key, objectId: storageObject.objectId, size: data.length }, '对象上传成功');
    return { object: storageObject, metadata };
  }

  async download(key: string, adapterName?: string): Promise<Buffer> {
    const adapter = adapterName ? this.getAdapter(adapterName) : this.defaultAdapter;
    return adapter.download(key);
  }

  async delete(key: string, adapterName?: string): Promise<boolean> {
    const adapter = adapterName ? this.getAdapter(adapterName) : this.defaultAdapter;
    const info = await adapter.getObjectInfo(key);
    const deleted = await adapter.delete(key);

    if (deleted && info) {
      await this.metadataIndex.deleteByObjectId(info.objectId);
    }

    return deleted;
  }

  async exists(key: string, adapterName?: string): Promise<boolean> {
    const adapter = adapterName ? this.getAdapter(adapterName) : this.defaultAdapter;
    return adapter.exists(key);
  }

  async list(prefix?: string, adapterName?: string): Promise<string[]> {
    const adapter = adapterName ? this.getAdapter(adapterName) : this.defaultAdapter;
    return adapter.list(prefix);
  }

  async getObjectInfo(key: string, adapterName?: string): Promise<StorageObject | null> {
    const adapter = adapterName ? this.getAdapter(adapterName) : this.defaultAdapter;
    return adapter.getObjectInfo(key);
  }

  async queryMetadata(query: MetadataIndexQuery): Promise<ObjectMetadata[]> {
    return this.metadataIndex.query(query);
  }

  async getObjectMetadata(objectId: string): Promise<ObjectMetadata | null> {
    return this.metadataIndex.getLatest(objectId);
  }

  private getAdapter(name: string): StorageAdapter {
    const adapter = this.adapters.get(name);
    if (!adapter) {
      throw new Error(`存储适配器不存在: ${name}`);
    }
    return adapter;
  }

  listAdapters(): Array<{ name: string; type: string }> {
    return Array.from(this.adapters.entries()).map(([name, adapter]) => ({
      name,
      type: adapter.type
    }));
  }
}
