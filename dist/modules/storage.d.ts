import { StorageContent } from '../types';
export interface PinStatus {
    cid: string;
    status: 'pinned' | 'pinning' | 'failed' | 'unpinned';
    size: number;
    network: 'ipfs' | 'arweave';
    createdAt: string;
    updatedAt: string;
}
export interface UploadOptions {
    pin?: boolean;
    contentType?: string;
    metadata?: Record<string, string>;
}
export interface StorageAdapter {
    upload(content: Uint8Array | string, options?: UploadOptions): Promise<StorageContent>;
    download(cid: string): Promise<Uint8Array>;
    pin(cid: string): Promise<PinStatus>;
    unpin(cid: string): Promise<boolean>;
    getPinStatus(cid: string): Promise<PinStatus | undefined>;
}
export declare class DecentralizedStorage {
    private adapters;
    private contents;
    private logger;
    constructor();
    upload(params: {
        content: Uint8Array | string;
        contentType?: string;
        network?: 'ipfs' | 'arweave';
        pin?: boolean;
        metadata?: Record<string, string>;
    }): Promise<StorageContent>;
    download(cid: string, network?: 'ipfs' | 'arweave'): Promise<Uint8Array>;
    downloadAsText(cid: string, network?: 'ipfs' | 'arweave'): Promise<string>;
    downloadAsJSON<T = unknown>(cid: string, network?: 'ipfs' | 'arweave'): Promise<T>;
    pin(cid: string, network?: 'ipfs' | 'arweave'): Promise<PinStatus>;
    unpin(cid: string, network?: 'ipfs' | 'arweave'): Promise<boolean>;
    getPinStatus(cid: string, network?: 'ipfs' | 'arweave'): Promise<PinStatus | undefined>;
    getContent(cid: string): StorageContent | undefined;
    listContents(network?: 'ipfs' | 'arweave'): StorageContent[];
    getGatewayUrl(cid: string, network?: 'ipfs' | 'arweave'): string;
    uploadJSON<T = unknown>(params: {
        data: T;
        network?: 'ipfs' | 'arweave';
        pin?: boolean;
        metadata?: Record<string, string>;
    }): Promise<StorageContent>;
    batchUpload(params: Array<{
        content: Uint8Array | string;
        contentType?: string;
        network?: 'ipfs' | 'arweave';
        pin?: boolean;
        metadata?: Record<string, string>;
    }>): Promise<StorageContent[]>;
    verifyContent(cid: string, content: Uint8Array | string): boolean;
    getStats(): {
        totalContents: number;
        totalSize: number;
        pinnedCount: number;
        ipfsCount: number;
        arweaveCount: number;
    };
}
export declare const decentralizedStorage: DecentralizedStorage;
//# sourceMappingURL=storage.d.ts.map