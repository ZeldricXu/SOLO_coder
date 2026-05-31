"use strict";
var __importDefault = (this && this.__importDefault) || function (mod) {
    return (mod && mod.__esModule) ? mod : { "default": mod };
};
Object.defineProperty(exports, "__esModule", { value: true });
exports.StorageManager = exports.MetadataIndex = exports.LocalStorageAdapter = void 0;
const uuid_1 = require("uuid");
const logger_1 = __importDefault(require("../common/logger"));
const errors_1 = require("../common/errors");
class LocalStorageAdapter {
    constructor(storagePath = './data') {
        this.name = 'local';
        this.type = 'local';
        this.objects = new Map();
        this.storagePath = storagePath;
    }
    async upload(key, data, contentType = 'application/octet-stream', metadata = {}) {
        const objectId = (0, uuid_1.v4)();
        const now = new Date().toISOString();
        const storageObject = {
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
        logger_1.default.debug({ key, size: data.length }, '对象已存储到本地');
        return storageObject;
    }
    async download(key) {
        const obj = this.objects.get(key);
        if (!obj) {
            throw new errors_1.NotFoundError(`对象不存在: ${key}`);
        }
        return obj.data;
    }
    async delete(key) {
        return this.objects.delete(key);
    }
    async exists(key) {
        return this.objects.has(key);
    }
    async list(prefix = '') {
        return Array.from(this.objects.keys()).filter(key => key.startsWith(prefix));
    }
    async getObjectInfo(key) {
        const obj = this.objects.get(key);
        return obj ? obj.info : null;
    }
    async copy(sourceKey, destinationKey) {
        const source = this.objects.get(sourceKey);
        if (!source) {
            throw new errors_1.NotFoundError(`源对象不存在: ${sourceKey}`);
        }
        const newInfo = {
            ...source.info,
            objectId: (0, uuid_1.v4)(),
            key: destinationKey,
            createdAt: new Date().toISOString(),
            updatedAt: new Date().toISOString()
        };
        this.objects.set(destinationKey, { data: source.data, info: newInfo });
        return newInfo;
    }
    generateEtag(data) {
        return `${data.length}-${data.toString('base64').slice(0, 16)}`;
    }
}
exports.LocalStorageAdapter = LocalStorageAdapter;
class MetadataIndex {
    constructor() {
        this.metadataStore = new Map();
        this.objectIdToMetadata = new Map();
    }
    async index(metadata) {
        const fullMetadata = {
            ...metadata,
            metadataId: (0, uuid_1.v4)(),
            createdAt: new Date().toISOString()
        };
        this.metadataStore.set(fullMetadata.metadataId, fullMetadata);
        if (!this.objectIdToMetadata.has(fullMetadata.objectId)) {
            this.objectIdToMetadata.set(fullMetadata.objectId, []);
        }
        this.objectIdToMetadata.get(fullMetadata.objectId).push(fullMetadata.metadataId);
        logger_1.default.debug({ objectId: fullMetadata.objectId, key: fullMetadata.key }, '元数据已索引');
        return fullMetadata;
    }
    async query(query) {
        let results = Array.from(this.metadataStore.values());
        if (query.bucket) {
            results = results.filter(m => m.bucket === query.bucket);
        }
        if (query.tags) {
            results = results.filter(m => {
                for (const [key, value] of Object.entries(query.tags)) {
                    if (m.tags[key] !== value)
                        return false;
                }
                return true;
            });
        }
        if (query.createdAtStart) {
            results = results.filter(m => m.createdAt >= query.createdAtStart);
        }
        if (query.createdAtEnd) {
            results = results.filter(m => m.createdAt <= query.createdAtEnd);
        }
        if (query.offset) {
            results = results.slice(query.offset);
        }
        if (query.limit) {
            results = results.slice(0, query.limit);
        }
        return results;
    }
    async getByObjectId(objectId) {
        const ids = this.objectIdToMetadata.get(objectId) || [];
        return ids.map(id => this.metadataStore.get(id)).filter(Boolean);
    }
    async getLatest(objectId) {
        const all = await this.getByObjectId(objectId);
        return all.find(m => m.isLatest) || all[0] || null;
    }
    async delete(metadataId) {
        const metadata = this.metadataStore.get(metadataId);
        if (!metadata)
            return false;
        this.metadataStore.delete(metadataId);
        const ids = this.objectIdToMetadata.get(metadata.objectId);
        if (ids) {
            const index = ids.indexOf(metadataId);
            if (index > -1)
                ids.splice(index, 1);
        }
        return true;
    }
    async deleteByObjectId(objectId) {
        const ids = this.objectIdToMetadata.get(objectId) || [];
        for (const id of ids) {
            this.metadataStore.delete(id);
        }
        this.objectIdToMetadata.delete(objectId);
        return ids.length;
    }
}
exports.MetadataIndex = MetadataIndex;
class StorageManager {
    constructor(config = {}) {
        this.adapters = new Map();
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
    registerAdapter(name, adapter) {
        this.adapters.set(name, adapter);
        logger_1.default.info({ name, type: adapter.type }, '注册存储适配器');
    }
    setDefaultAdapter(name) {
        const adapter = this.adapters.get(name);
        if (!adapter) {
            throw new Error(`适配器不存在: ${name}`);
        }
        this.defaultAdapter = adapter;
        logger_1.default.info({ name }, '设置默认存储适配器');
    }
    async upload(key, data, options = {}, adapterName) {
        if (data.length > this.config.maxObjectSize) {
            throw new Error(`对象大小超过限制: ${data.length} > ${this.config.maxObjectSize}`);
        }
        const adapter = adapterName ? this.getAdapter(adapterName) : this.defaultAdapter;
        const bucket = options.bucket ?? this.config.defaultBucket;
        const storageObject = await adapter.upload(key, data, options.contentType, options.metadata);
        const metadata = await this.metadataIndex.index({
            objectId: storageObject.objectId,
            key,
            bucket,
            tags: options.tags || {},
            customMetadata: options.metadata || {},
            version: 1,
            isLatest: true
        });
        logger_1.default.info({ key, objectId: storageObject.objectId, size: data.length }, '对象上传成功');
        return { object: storageObject, metadata };
    }
    async download(key, adapterName) {
        const adapter = adapterName ? this.getAdapter(adapterName) : this.defaultAdapter;
        return adapter.download(key);
    }
    async delete(key, adapterName) {
        const adapter = adapterName ? this.getAdapter(adapterName) : this.defaultAdapter;
        const info = await adapter.getObjectInfo(key);
        const deleted = await adapter.delete(key);
        if (deleted && info) {
            await this.metadataIndex.deleteByObjectId(info.objectId);
        }
        return deleted;
    }
    async exists(key, adapterName) {
        const adapter = adapterName ? this.getAdapter(adapterName) : this.defaultAdapter;
        return adapter.exists(key);
    }
    async list(prefix, adapterName) {
        const adapter = adapterName ? this.getAdapter(adapterName) : this.defaultAdapter;
        return adapter.list(prefix);
    }
    async getObjectInfo(key, adapterName) {
        const adapter = adapterName ? this.getAdapter(adapterName) : this.defaultAdapter;
        return adapter.getObjectInfo(key);
    }
    async queryMetadata(query) {
        return this.metadataIndex.query(query);
    }
    async getObjectMetadata(objectId) {
        return this.metadataIndex.getLatest(objectId);
    }
    getAdapter(name) {
        const adapter = this.adapters.get(name);
        if (!adapter) {
            throw new Error(`存储适配器不存在: ${name}`);
        }
        return adapter;
    }
    listAdapters() {
        return Array.from(this.adapters.entries()).map(([name, adapter]) => ({
            name,
            type: adapter.type
        }));
    }
}
exports.StorageManager = StorageManager;
//# sourceMappingURL=index.js.map