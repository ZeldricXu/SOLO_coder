import { ImageLayer, RegistryConfig, P2PPeer, ImageDistributeTask, ImageManifest, LayerIndexEntry } from './types';

export interface ILayerPersister {
  initialize(): Promise<void>;
  persistTask(task: ImageDistributeTask): Promise<void>;
  persistLayer(digest: string, buffer: Buffer): Promise<void>;
  loadLayer(digest: string): Promise<Buffer | null>;
  loadLayerIndex(): Promise<Map<string, LayerIndexEntry>>;
  saveLayerIndex(index: Map<string, LayerIndexEntry>): Promise<void>;
  recoverTasks(): Promise<ImageDistributeTask[]>;
  createSnapshot(tasks: Map<string, ImageDistributeTask>, layerIndex: Map<string, LayerIndexEntry>, peers: Map<string, P2PPeer>): void;
  stop(): void;
}

export interface IPeerManager {
  startDiscovery(): void;
  stop(): void;
  findPeersWithLayer(digest: string): P2PPeer[];
  announceLayer(digest: string): void;
  getAllPeers(): Map<string, P2PPeer>;
}

export interface IRegistryClient {
  fetchManifest(imageName: string, tag: string, registry: RegistryConfig): Promise<ImageManifest>;
  downloadLayer(layer: ImageLayer, registry: RegistryConfig, onProgress?: (progress: number) => void): Promise<Buffer>;
  uploadLayer(layer: ImageLayer, buffer: Buffer, registry: RegistryConfig, imageName: string): Promise<void>;
  uploadManifest(manifest: ImageManifest, registry: RegistryConfig, imageName: string, tag: string): Promise<void>;
}

export interface IDownloadStrategy {
  download(
    layer: ImageLayer,
    registry: RegistryConfig,
    persister: ILayerPersister,
    registryClient: IRegistryClient,
    peerManager: IPeerManager,
    onProgress?: (progress: number) => void,
  ): Promise<Buffer>;
}
