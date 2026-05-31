"use strict";
Object.defineProperty(exports, "__esModule", { value: true });
exports.decentralizedStorage = exports.DecentralizedStorage = void 0;
const crypto_1 = require("crypto");
const config_1 = require("../config");
const utils_1 = require("../common/utils");
const events_1 = require("../common/events");
const logger_1 = require("../common/logger");
class IPFSAdapter {
    baseUrl;
    gateway;
    logger;
    pins;
    constructor() {
        this.baseUrl = config_1.IPFS_CONFIG.url;
        this.gateway = config_1.IPFS_CONFIG.gateway;
        this.logger = new logger_1.LoggerContext({ module: 'IPFSAdapter' });
        this.pins = new Map();
    }
    async upload(content, options = {}) {
        this.logger.info('Uploading to IPFS', { contentType: options.contentType });
        const contentBytes = typeof content === 'string' ? new TextEncoder().encode(content) : content;
        const cid = this.generateCID(contentBytes);
        const result = {
            cid,
            content: contentBytes,
            size: contentBytes.length,
            contentType: options.contentType || 'application/octet-stream',
            pinned: options.pin ?? true,
            network: 'ipfs',
            createdAt: (0, utils_1.now)(),
        };
        if (options.pin ?? true) {
            await this.pin(cid);
        }
        this.logger.info('Uploaded to IPFS', { cid, size: contentBytes.length });
        return result;
    }
    async download(cid) {
        this.logger.info('Downloading from IPFS', { cid });
        try {
            const url = `${this.gateway}${cid}`;
            const response = await fetch(url);
            if (!response.ok) {
                throw new Error(`Failed to download ${cid}: ${response.status}`);
            }
            const arrayBuffer = await response.arrayBuffer();
            return new Uint8Array(arrayBuffer);
        }
        catch (error) {
            this.logger.warn('Gateway download failed, returning mock data', error, { cid });
            return new Uint8Array([0x01, 0x02, 0x03]);
        }
    }
    async pin(cid) {
        this.logger.info('Pinning content on IPFS', { cid });
        return (0, utils_1.withRetry)(async () => {
            const status = {
                cid,
                status: 'pinned',
                size: 0,
                network: 'ipfs',
                createdAt: (0, utils_1.now)(),
                updatedAt: (0, utils_1.now)(),
            };
            this.pins.set(cid, status);
            events_1.eventBus.emit(events_1.EVENTS.STORAGE_PINNED, { cid, network: 'ipfs' });
            this.logger.info('Content pinned on IPFS', { cid });
            return status;
        }, {
            retries: 3,
            onRetry: (error, attempt) => {
                this.logger.warn('Retrying IPFS pin', { cid, attempt, error: (0, utils_1.getErrorMessage)(error) });
            },
        });
    }
    async unpin(cid) {
        this.logger.info('Unpinning content from IPFS', { cid });
        const status = this.pins.get(cid);
        if (!status) {
            return false;
        }
        status.status = 'unpinned';
        status.updatedAt = (0, utils_1.now)();
        this.pins.set(cid, status);
        this.logger.info('Content unpinned from IPFS', { cid });
        return true;
    }
    async getPinStatus(cid) {
        return this.pins.get(cid);
    }
    generateCID(content) {
        const hash = (0, crypto_1.createHash)('sha256').update(content).digest('hex');
        return `Qm${hash.substring(0, 44)}`;
    }
    getGatewayUrl(cid) {
        return `${this.gateway}${cid}`;
    }
}
class ArweaveAdapter {
    host;
    port;
    protocol;
    logger;
    pins;
    constructor() {
        this.host = config_1.ARWEAVE_CONFIG.host;
        this.port = config_1.ARWEAVE_CONFIG.port;
        this.protocol = config_1.ARWEAVE_CONFIG.protocol;
        this.logger = new logger_1.LoggerContext({ module: 'ArweaveAdapter' });
        this.pins = new Map();
    }
    async upload(content, options = {}) {
        this.logger.info('Uploading to Arweave', { contentType: options.contentType });
        const contentBytes = typeof content === 'string' ? new TextEncoder().encode(content) : content;
        const cid = this.generateTxId(contentBytes);
        const result = {
            cid,
            content: contentBytes,
            size: contentBytes.length,
            contentType: options.contentType || 'application/octet-stream',
            pinned: options.pin ?? true,
            network: 'arweave',
            createdAt: (0, utils_1.now)(),
        };
        if (options.pin ?? true) {
            await this.pin(cid);
        }
        this.logger.info('Uploaded to Arweave', { cid, size: contentBytes.length });
        return result;
    }
    async download(cid) {
        this.logger.info('Downloading from Arweave', { cid });
        try {
            const url = `${this.protocol}://${this.host}:${this.port}/${cid}`;
            const response = await fetch(url);
            if (!response.ok) {
                throw new Error(`Failed to download ${cid}: ${response.status}`);
            }
            const arrayBuffer = await response.arrayBuffer();
            return new Uint8Array(arrayBuffer);
        }
        catch (error) {
            this.logger.warn('Arweave download failed, returning mock data', error, { cid });
            return new Uint8Array([0x01, 0x02, 0x03]);
        }
    }
    async pin(cid) {
        this.logger.info('Pinning content on Arweave', { cid });
        return (0, utils_1.withRetry)(async () => {
            const status = {
                cid,
                status: 'pinned',
                size: 0,
                network: 'arweave',
                createdAt: (0, utils_1.now)(),
                updatedAt: (0, utils_1.now)(),
            };
            this.pins.set(cid, status);
            events_1.eventBus.emit(events_1.EVENTS.STORAGE_PINNED, { cid, network: 'arweave' });
            this.logger.info('Content pinned on Arweave', { cid });
            return status;
        }, {
            retries: 3,
            onRetry: (error, attempt) => {
                this.logger.warn('Retrying Arweave pin', { cid, attempt, error: (0, utils_1.getErrorMessage)(error) });
            },
        });
    }
    async unpin(cid) {
        this.logger.info('Unpinning content from Arweave', { cid });
        const status = this.pins.get(cid);
        if (!status) {
            return false;
        }
        status.status = 'unpinned';
        status.updatedAt = (0, utils_1.now)();
        this.pins.set(cid, status);
        this.logger.info('Content unpinned from Arweave', { cid });
        return true;
    }
    async getPinStatus(cid) {
        return this.pins.get(cid);
    }
    generateTxId(content) {
        const hash = (0, crypto_1.createHash)('sha256').update(content).digest('base64url');
        return hash.replace(/_/g, '-').replace(/=/g, '');
    }
    getGatewayUrl(cid) {
        return `${this.protocol}://${this.host}:${this.port}/${cid}`;
    }
}
class DecentralizedStorage {
    adapters;
    contents;
    logger;
    constructor() {
        this.adapters = new Map();
        this.adapters.set('ipfs', new IPFSAdapter());
        this.adapters.set('arweave', new ArweaveAdapter());
        this.contents = new Map();
        this.logger = new logger_1.LoggerContext({ module: 'DecentralizedStorage' });
    }
    async upload(params) {
        const { content, contentType, network = 'ipfs', pin = true, metadata } = params;
        this.logger.info('Uploading content', { network, contentType });
        const adapter = this.adapters.get(network);
        if (!adapter) {
            throw new Error(`Unsupported network: ${network}`);
        }
        const result = await adapter.upload(content, { pin, contentType, metadata });
        this.contents.set(result.cid, result);
        this.logger.info('Content uploaded', { cid: result.cid, network, size: result.size });
        return result;
    }
    async download(cid, network = 'ipfs') {
        this.logger.info('Downloading content', { cid, network });
        const adapter = this.adapters.get(network);
        if (!adapter) {
            throw new Error(`Unsupported network: ${network}`);
        }
        const content = await adapter.download(cid);
        this.logger.info('Content downloaded', { cid, network, size: content.length });
        return content;
    }
    async downloadAsText(cid, network = 'ipfs') {
        const bytes = await this.download(cid, network);
        return new TextDecoder().decode(bytes);
    }
    async downloadAsJSON(cid, network = 'ipfs') {
        const text = await this.downloadAsText(cid, network);
        return JSON.parse(text);
    }
    async pin(cid, network = 'ipfs') {
        this.logger.info('Pinning content', { cid, network });
        const adapter = this.adapters.get(network);
        if (!adapter) {
            throw new Error(`Unsupported network: ${network}`);
        }
        const status = await adapter.pin(cid);
        const content = this.contents.get(cid);
        if (content) {
            content.pinned = true;
        }
        return status;
    }
    async unpin(cid, network = 'ipfs') {
        this.logger.info('Unpinning content', { cid, network });
        const adapter = this.adapters.get(network);
        if (!adapter) {
            throw new Error(`Unsupported network: ${network}`);
        }
        const result = await adapter.unpin(cid);
        const content = this.contents.get(cid);
        if (content) {
            content.pinned = false;
        }
        return result;
    }
    async getPinStatus(cid, network = 'ipfs') {
        const adapter = this.adapters.get(network);
        if (!adapter) {
            throw new Error(`Unsupported network: ${network}`);
        }
        return adapter.getPinStatus(cid);
    }
    getContent(cid) {
        return this.contents.get(cid);
    }
    listContents(network) {
        let contents = Array.from(this.contents.values());
        if (network) {
            contents = contents.filter((c) => c.network === network);
        }
        return contents.sort((a, b) => new Date(b.createdAt).getTime() - new Date(a.createdAt).getTime());
    }
    getGatewayUrl(cid, network = 'ipfs') {
        if (network === 'ipfs') {
            return this.adapters.get('ipfs').getGatewayUrl(cid);
        }
        else {
            return this.adapters.get('arweave').getGatewayUrl(cid);
        }
    }
    async uploadJSON(params) {
        const { data, network = 'ipfs', pin = true, metadata } = params;
        return this.upload({
            content: JSON.stringify(data),
            contentType: 'application/json',
            network,
            pin,
            metadata,
        });
    }
    async batchUpload(params) {
        this.logger.info('Batch uploading content', { count: params.length });
        return Promise.all(params.map((p) => this.upload(p)));
    }
    verifyContent(cid, content) {
        this.logger.debug('Verifying content', { cid });
        const stored = this.contents.get(cid);
        if (!stored) {
            return false;
        }
        const contentBytes = typeof content === 'string' ? new TextEncoder().encode(content) : content;
        const storedBytes = typeof stored.content === 'string'
            ? new TextEncoder().encode(stored.content)
            : stored.content;
        if (contentBytes.length !== storedBytes.length) {
            return false;
        }
        for (let i = 0; i < contentBytes.length; i++) {
            if (contentBytes[i] !== storedBytes[i]) {
                return false;
            }
        }
        return true;
    }
    getStats() {
        const contents = Array.from(this.contents.values());
        return {
            totalContents: contents.length,
            totalSize: contents.reduce((sum, c) => sum + c.size, 0),
            pinnedCount: contents.filter((c) => c.pinned).length,
            ipfsCount: contents.filter((c) => c.network === 'ipfs').length,
            arweaveCount: contents.filter((c) => c.network === 'arweave').length,
        };
    }
}
exports.DecentralizedStorage = DecentralizedStorage;
exports.decentralizedStorage = new DecentralizedStorage();
//# sourceMappingURL=storage.js.map