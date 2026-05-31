import logger from '../../utils/logger';
import { generateId, sleep } from '../../utils/helpers';
import { ImageManifest, ImageLayer, RegistryConfig } from './types';
import { IRegistryClient } from './interfaces';

export class RegistryClient implements IRegistryClient {
  async fetchManifest(imageName: string, tag: string, registry: RegistryConfig): Promise<ImageManifest> {
    logger.debug('Fetching manifest', { imageName, tag, registry: registry.url });
    
    await sleep(100);
    
    const mockLayers: ImageLayer[] = Array.from({ length: 5 }, (_, i) => ({
      digest: `sha256:${generateId('layer')}`,
      size: Math.floor(Math.random() * 10000000) + 1000000,
      mediaType: 'application/vnd.docker.image.rootfs.diff.tar.gzip',
      urls: [],
      downloaded: false,
      downloadProgress: 0,
    }));

    return {
      schemaVersion: 2,
      mediaType: 'application/vnd.docker.distribution.manifest.v2+json',
      config: {
        digest: `sha256:${generateId('cfg')}`,
        size: 1234,
        mediaType: 'application/vnd.docker.container.image.v1+json',
      },
      layers: mockLayers,
    };
  }

  async downloadLayer(
    layer: ImageLayer,
    registry: RegistryConfig,
    onProgress?: (progress: number) => void,
  ): Promise<Buffer> {
    logger.debug('Downloading layer from registry', { digest: layer.digest, registry: registry.url });
    
    const totalSteps = 10;
    for (let i = 1; i <= totalSteps; i++) {
      await sleep(50);
      onProgress?.(i / totalSteps);
    }
    
    return Buffer.alloc(layer.size);
  }

  async uploadLayer(
    layer: ImageLayer,
    buffer: Buffer,
    registry: RegistryConfig,
    imageName: string,
  ): Promise<void> {
    logger.debug('Uploading layer', { digest: layer.digest, registry: registry.url, imageName });
    await sleep(50);
  }

  async uploadManifest(
    manifest: ImageManifest,
    registry: RegistryConfig,
    imageName: string,
    tag: string,
  ): Promise<void> {
    logger.debug('Uploading manifest', { imageName, tag, registry: registry.url });
    await sleep(50);
  }
}
