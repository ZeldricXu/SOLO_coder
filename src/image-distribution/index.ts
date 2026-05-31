import { ContainerImage, ImageLayer } from '../types';
import { logger } from '../logging';
import { v4 as uuidv4 } from 'uuid';
import {
  BatchImageOperations,
  BatchPullRequest,
  BatchPullResult,
  BatchSyncRequest,
  BatchSyncResult,
  BatchDeleteRequest,
  BatchDeleteResult,
  BatchOperationOptions
} from './batch-operations';

export interface RegistryConfig {
  url: string;
  username?: string;
  password?: string;
  insecure?: boolean;
}

export interface LayerDownloadProgress {
  digest: string;
  downloaded: number;
  total: number;
  speed: number;
}

export interface ImagePullOptions {
  parallelLayers?: number;
  verifyDigest?: boolean;
  useP2P?: boolean;
}

export interface P2PPeer {
  id: string;
  address: string;
  availableLayers: string[];
  bandwidth: number;
}

export class ImageDistributionModule {
  private registries: Map<string, RegistryConfig> = new Map();
  private p2pPeers: P2PPeer[] = [];
  private localCache: Map<string, ContainerImage> = new Map();
  private downloadProgress: Map<string, LayerDownloadProgress> = new Map();
  private batchOperations: BatchImageOperations;

  constructor(batchOptions?: BatchOperationOptions) {
    this.batchOperations = new BatchImageOperations(batchOptions);
  }

  addRegistry(name: string, config: RegistryConfig): void {
    this.registries.set(name, config);
    logger.info('Registry added', { name, url: config.url });
  }

  removeRegistry(name: string): void {
    this.registries.delete(name);
  }

  addP2PPeer(peer: Omit<P2PPeer, 'id'>): P2PPeer {
    const newPeer: P2PPeer = { ...peer, id: `peer_${uuidv4()}` };
    this.p2pPeers.push(newPeer);
    logger.info('P2P peer added', { peerId: newPeer.id });
    return newPeer;
  }

  async pullImage(imageName: string, tag: string, options: ImagePullOptions = {}): Promise<ContainerImage> {
    const opts = { parallelLayers: 3, verifyDigest: true, useP2P: false, ...options };
    const cacheKey = `${imageName}:${tag}`;
    
    if (this.localCache.has(cacheKey)) {
      logger.info('Image found in local cache', { image: cacheKey });
      return this.localCache.get(cacheKey)!;
    }

    logger.info('Pulling image', { image: imageName, tag: tag });

    const layers: ImageLayer[] = [];
    const layerCount = 3;
    
    for (let i = 0; i < layerCount; i++) {
      const layer: ImageLayer = {
        digest: `sha256:${uuidv4().replace(/-/g, '')}`,
        size: Math.floor(Math.random() * 10000000) + 1000000,
        url: `https://registry.example.com/layers/${i}`,
        mediaType: 'application/vnd.oci.image.layer.v1.tar+gzip'
      };
      layers.push(layer);
      await this.downloadLayer(layer, opts);
    }

    const image: ContainerImage = {
      name: imageName,
      tag: tag,
      digest: `sha256:${uuidv4().replace(/-/g, '')}`,
      layers: layers,
      size: layers.reduce((sum, l) => sum + l.size, 0),
      createdAt: new Date().toISOString()
    };

    this.localCache.set(cacheKey, image);
    logger.info('Image pull completed', { image: imageName, layers: layers.length });
    return image;
  }

  private async downloadLayer(layer: ImageLayer, options: ImagePullOptions): Promise<void> {
    this.downloadProgress.set(layer.digest, { digest: layer.digest, downloaded: 0, total: layer.size, speed: 0 });
    
    const totalSteps = 10;
    for (let i = 0; i <= totalSteps; i++) {
      await new Promise(resolve => setTimeout(resolve, 50));
      const downloaded = Math.floor((i / totalSteps) * layer.size);
      this.downloadProgress.set(layer.digest, {
        digest: layer.digest,
        downloaded,
        total: layer.size,
        speed: downloaded / (i * 0.05 + 0.01)
      });
    }
  }

  async syncImage(imageName: string, tag: string, sourceRegistry: string, targetRegistry: string): Promise<void> {
    const source = this.registries.get(sourceRegistry);
    const target = this.registries.get(targetRegistry);
    
    if (!source || !target) {
      throw new Error('Registry not found');
    }

    logger.info('Syncing image between registries', { image: imageName, source: sourceRegistry, target: targetRegistry });
    
    const image = await this.pullImage(imageName, tag);
    
    logger.info('Image synced successfully', { image: imageName });
  }

  getDownloadProgress(layerDigest?: string): LayerDownloadProgress[] {
    if (layerDigest) {
      const progress = this.downloadProgress.get(layerDigest);
      return progress ? [progress] : [];
    }
    return Array.from(this.downloadProgress.values());
  }

  getLocalImages(): ContainerImage[] {
    return Array.from(this.localCache.values());
  }

  getP2PPeers(): P2PPeer[] {
    return [...this.p2pPeers];
  }

  getRegistries(): string[] {
    return Array.from(this.registries.keys());
  }

  async batchPull(
    requests: BatchPullRequest[],
    options?: BatchOperationOptions & ImagePullOptions
  ): Promise<BatchPullResult> {
    return this.batchOperations.batchPullImages(
      requests,
      async (imageName, tag) => this.pullImage(imageName, tag, options),
      options
    );
  }

  async batchSync(
    requests: BatchSyncRequest[],
    options?: BatchOperationOptions
  ): Promise<BatchSyncResult> {
    return this.batchOperations.batchSyncImages(
      requests,
      async (imageName, tag, source, target) => this.syncImage(imageName, tag, source, target),
      options
    );
  }

  async batchDelete(
    requests: BatchDeleteRequest[],
    options?: BatchOperationOptions
  ): Promise<BatchDeleteResult> {
    return this.batchOperations.batchDeleteImages(
      requests,
      async (imageName, tag, registry) => {
        const cacheKey = registry ? `${registry}/${imageName}:${tag}` : `${imageName}:${tag}`;
        const image = this.localCache.get(cacheKey);
        const freedSize = image?.size || 0;
        this.localCache.delete(cacheKey);
        return freedSize;
      },
      options
    );
  }

  queuePullRequest(request: BatchPullRequest): Promise<ContainerImage> {
    return this.batchOperations.queuePullRequest(request);
  }

  getQueueSize(): number {
    return this.batchOperations.getQueueSize();
  }

  clearBatchQueue(): void {
    this.batchOperations.clearQueue();
  }

  clearLocalCache(): number {
    const count = this.localCache.size;
    this.localCache.clear();
    logger.info('Local cache cleared', { images: count });
    return count;
  }

  getBatchStats(): { queueSize: number; registries: number; cachedImages: number; peers: number } {
    return {
      queueSize: this.getQueueSize(),
      registries: this.registries.size,
      cachedImages: this.localCache.size,
      peers: this.p2pPeers.length
    };
  }
}

export const createImageDistributionModule = (batchOptions?: BatchOperationOptions): ImageDistributionModule => {
  return new ImageDistributionModule(batchOptions);
};

export {
  BatchImageOperations,
  BatchPullRequest,
  BatchPullResult,
  BatchSyncRequest,
  BatchSyncResult,
  BatchDeleteRequest,
  BatchDeleteResult,
  BatchOperationOptions
} from './batch-operations';
