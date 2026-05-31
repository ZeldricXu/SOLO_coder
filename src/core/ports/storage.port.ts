import type { StorageContent, PinStatus } from '@core/domain/blockchain';

export interface DecentralizedStoragePort {
  upload(content: Uint8Array, contentType?: string): Promise<StorageContent>;
  uploadJSON<T = unknown>(data: T): Promise<StorageContent>;
  download(cid: string): Promise<Uint8Array>;
  downloadJSON<T = unknown>(cid: string): Promise<T>;
  pin(cid: string): Promise<PinStatus>;
  unpin(cid: string): Promise<boolean>;
  getPinStatus(cid: string): Promise<PinStatus | null>;
  listPins(): Promise<PinStatus[]>;
  getGatewayUrl(cid: string): string;
}

export interface StorageProvider {
  getProvider(name: string): DecentralizedStoragePort;
  addProvider(name: string, provider: DecentralizedStoragePort): void;
  removeProvider(name: string): void;
}

export interface StorageConfig {
  providers: {
    name: string;
    type: 'ipfs' | 'arweave';
    endpoint?: string;
    gateway?: string;
    apiKey?: string;
  }[];
  defaultProvider: string;
}
