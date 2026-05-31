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
export declare class LocalStorageAdapter implements StorageAdapter {
    name: string;
    type: "local";
    private storagePath;
    private objects;
    constructor(storagePath?: string);
    upload(key: string, data: Buffer, contentType?: string, metadata?: Record<string, string>): Promise<StorageObject>;
    download(key: string): Promise<Buffer>;
    delete(key: string): Promise<boolean>;
    exists(key: string): Promise<boolean>;
    list(prefix?: string): Promise<string[]>;
    getObjectInfo(key: string): Promise<StorageObject | null>;
    copy(sourceKey: string, destinationKey: string): Promise<StorageObject>;
    private generateEtag;
}
export declare class MetadataIndex {
    private metadataStore;
    private objectIdToMetadata;
    index(metadata: Omit<ObjectMetadata, 'metadataId' | 'createdAt'>): Promise<ObjectMetadata>;
    query(query: MetadataIndexQuery): Promise<ObjectMetadata[]>;
    getByObjectId(objectId: string): Promise<ObjectMetadata[]>;
    getLatest(objectId: string): Promise<ObjectMetadata | null>;
    delete(metadataId: string): Promise<boolean>;
    deleteByObjectId(objectId: string): Promise<number>;
}
export declare class StorageManager {
    private adapters;
    private defaultAdapter;
    private metadataIndex;
    private config;
    constructor(config?: Partial<StorageConfig>);
    registerAdapter(name: string, adapter: StorageAdapter): void;
    setDefaultAdapter(name: string): void;
    upload(key: string, data: Buffer, options?: UploadOptions, adapterName?: string): Promise<{
        object: StorageObject;
        metadata: ObjectMetadata;
    }>;
    download(key: string, adapterName?: string): Promise<Buffer>;
    delete(key: string, adapterName?: string): Promise<boolean>;
    exists(key: string, adapterName?: string): Promise<boolean>;
    list(prefix?: string, adapterName?: string): Promise<string[]>;
    getObjectInfo(key: string, adapterName?: string): Promise<StorageObject | null>;
    queryMetadata(query: MetadataIndexQuery): Promise<ObjectMetadata[]>;
    getObjectMetadata(objectId: string): Promise<ObjectMetadata | null>;
    private getAdapter;
    listAdapters(): Array<{
        name: string;
        type: string;
    }>;
}
//# sourceMappingURL=index.d.ts.map