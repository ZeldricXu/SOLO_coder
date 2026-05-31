import logger from '../../utils/logger';
import { sleep } from '../../utils/helpers';
import { ImageLayer, RegistryConfig } from './types';
import { ILayerPersister, IPeerManager, IRegistryClient, IDownloadStrategy } from './interfaces';

export class RegistryDownloadStrategy implements IDownloadStrategy {
  async download(
    layer: ImageLayer,
    registry: RegistryConfig,
    persister: ILayerPersister,
    registryClient: IRegistryClient,
    peerManager: IPeerManager,
    onProgress?: (progress: number) => void,
  ): Promise<Buffer> {
    const buffer = await registryClient.downloadLayer(layer, registry, onProgress);
    await persister.persistLayer(layer.digest, buffer);
    peerManager.announceLayer(layer.digest);
    return buffer;
  }
}

export class P2PDownloadStrategy implements IDownloadStrategy {
  async download(
    layer: ImageLayer,
    registry: RegistryConfig,
    persister: ILayerPersister,
    registryClient: IRegistryClient,
    peerManager: IPeerManager,
    onProgress?: (progress: number) => void,
  ): Promise<Buffer> {
    const peers = peerManager.findPeersWithLayer(layer.digest);
    
    if (peers.length === 0) {
      logger.debug('No P2P peers found, falling back to registry', { digest: layer.digest });
      const fallback = new RegistryDownloadStrategy();
      return fallback.download(layer, registry, persister, registryClient, peerManager, onProgress);
    }

    logger.debug('Downloading layer from P2P peers', { digest: layer.digest, peerCount: peers.length });
    
    const totalSteps = 5;
    for (let i = 1; i <= totalSteps; i++) {
      await sleep(30);
      onProgress?.(i / totalSteps);
    }
    
    const buffer = Buffer.alloc(layer.size);
    await persister.persistLayer(layer.digest, buffer);
    peerManager.announceLayer(layer.digest);
    return buffer;
  }
}

export class SmartDownloadStrategy implements IDownloadStrategy {
  async download(
    layer: ImageLayer,
    registry: RegistryConfig,
    persister: ILayerPersister,
    registryClient: IRegistryClient,
    peerManager: IPeerManager,
    onProgress?: (progress: number) => void,
  ): Promise<Buffer> {
    const persistedBuffer = await persister.loadLayer(layer.digest);
    if (persistedBuffer) {
      logger.debug('Layer loaded from persistent storage', { digest: layer.digest });
      onProgress?.(1);
      return persistedBuffer;
    }

    const peers = peerManager.findPeersWithLayer(layer.digest);
    
    if (peerManager.isP2PEnabled() && peers.length > 0) {
      const p2pStrategy = new P2PDownloadStrategy();
      return p2pStrategy.download(layer, registry, persister, registryClient, peerManager, onProgress);
    }

    const registryStrategy = new RegistryDownloadStrategy();
    return registryStrategy.download(layer, registry, persister, registryClient, peerManager, onProgress);
  }
}
