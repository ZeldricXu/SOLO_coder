import { EventEmitter } from 'events';
import logger from '../../utils/logger';
import { eventBus } from '../../utils/eventBus';
import { generateId, currentTimestamp } from '../../utils/helpers';
import { configManager } from '../config';
import {
  ImageLayer,
  ImageManifest,
  RegistryConfig,
  P2PPeer,
  ImageDistributeTask,
  SyncStatus,
  PersistenceConfig,
  RecoveryReport,
} from './types';
import { ILayerPersister, IPeerManager, IRegistryClient, IDownloadStrategy } from './interfaces';
import { LayerPersister } from './LayerPersister';
import { PeerManager } from './PeerManager';
import { RegistryClient } from './RegistryClient';
import { SmartDownloadStrategy } from './DownloadStrategies';

export {
  ImageLayer,
  ImageManifest,
  RegistryConfig,
  P2PPeer,
  ImageDistributeTask,
  SyncStatus,
  PersistenceConfig,
  RecoveryReport,
};

class Semaphore {
  private current = 0;
  private queue: Array<() => void> = [];

  constructor(private max: number) {}

  async run<T>(fn: () => Promise<T>): Promise<T> {
    if (this.current >= this.max) {
      await new Promise<void>(resolve => this.queue.push(resolve));
    }
    this.current++;
    try {
      return await fn();
    } finally {
      this.current--;
      if (this.queue.length > 0) {
        const resolve = this.queue.shift()!;
        resolve();
      }
    }
  }
}

export class ImageDistributor extends EventEmitter {
  private tasks: Map<string, ImageDistributeTask> = new Map();
  private layerCache: Map<string, Buffer> = new Map();
  private maxConcurrentLayers: number = 5;
  private persister: ILayerPersister;
  private peerManager: IPeerManager;
  private registryClient: IRegistryClient;
  private downloadStrategy: IDownloadStrategy;
  private isRecovering: boolean = false;

  constructor() {
    super();
    
    const p2pEnabled = configManager.getParameter('image.p2p.enabled', 'image', true);
    this.maxConcurrentLayers = configManager.getParameter('image.maxConcurrentLayers', 'image', 5);
    
    const persistenceConfig: PersistenceConfig = {
      dataDir: configManager.getParameter('image.persistence.dataDir', 'image', './data/image-distribution'),
      snapshotInterval: configManager.getParameter('image.persistence.snapshotInterval', 'image', 300000),
      autoRecover: configManager.getParameter('image.persistence.autoRecover', 'image', true),
      maxSnapshots: configManager.getParameter('image.persistence.maxSnapshots', 'image', 10),
    };

    this.persister = new LayerPersister(persistenceConfig);
    this.peerManager = new PeerManager(p2pEnabled);
    this.registryClient = new RegistryClient();
    this.downloadStrategy = new SmartDownloadStrategy();

    this.initialize();
    
    logger.info('ImageDistributor initialized', { p2pEnabled, persistenceConfig });
  }

  private async initialize(): Promise<void> {
    await this.persister.initialize();
    
    if (configManager.getParameter('image.persistence.autoRecover', 'image', true)) {
      await this.recoverFromCrash();
    }
    
    this.peerManager.startDiscovery();
    this.setupEventListeners();
  }

  private setupEventListeners(): void {
    eventBus.on('image.snapshot.trigger', () => {
      if (!this.isRecovering) {
        this.persister.createSnapshot(this.tasks, this.persister.getLayerIndex(), this.peerManager.getAllPeers());
      }
    });
  }

  private async recoverFromCrash(): Promise<RecoveryReport> {
    this.isRecovering = true;
    logger.info('Starting crash recovery for image distribution module');

    const report: RecoveryReport = {
      recoveredTasks: 0,
      recoveredLayers: 0,
      failedRecoveries: 0,
      details: [],
    };

    try {
      const recoveredTasks = await this.persister.recoverTasks();
      
      for (const task of recoveredTasks) {
        if (task.status === 'downloading' || task.status === 'distributing') {
          report.details.push({
            taskId: task.taskId,
            status: 'recovered_failed',
            error: task.error,
          });
        } else {
          report.details.push({
            taskId: task.taskId,
            status: 'recovered',
          });
        }
        this.tasks.set(task.taskId, task);
        report.recoveredTasks++;
      }

      report.recoveredLayers = this.persister.getLayerIndex().size;
      logger.info('Crash recovery completed', report);
      eventBus.emit('image.recovery.completed', report);
    } catch (error) {
      logger.error('Crash recovery failed', { error });
    } finally {
      this.isRecovering = false;
    }

    return report;
  }

  async pullImage(
    imageName: string,
    tag: string,
    sourceRegistry: RegistryConfig,
  ): Promise<ImageDistributeTask> {
    const taskId = generateId('dist_');
    const task: ImageDistributeTask = {
      taskId,
      imageName,
      tag,
      sourceRegistry,
      targetRegistries: [],
      status: 'pending',
      progress: 0,
      layers: [],
      startTime: currentTimestamp(),
    };

    this.tasks.set(taskId, task);
    await this.persister.persistTask(task);

    this.executePullTask(task).catch(error => {
      logger.error('Pull task failed', { taskId, error: error.message });
      task.status = 'failed';
      task.error = error.message;
      this.persister.persistTask(task);
    });

    return task;
  }

  async syncImage(
    imageName: string,
    tag: string,
    sourceRegistry: RegistryConfig,
    targetRegistries: RegistryConfig[],
  ): Promise<ImageDistributeTask> {
    const task = await this.pullImage(imageName, tag, sourceRegistry);
    task.targetRegistries = targetRegistries;
    this.tasks.set(task.taskId, task);
    await this.persister.persistTask(task);
    return task;
  }

  private async executePullTask(task: ImageDistributeTask): Promise<void> {
    this.updateTaskStatus(task, 'downloading');

    try {
      const manifest = await this.registryClient.fetchManifest(task.imageName, task.tag, task.sourceRegistry);
      task.manifest = manifest;
      task.layers = manifest.layers;
      await this.persister.persistTask(task);

      await this.downloadLayers(task);
      
      if (task.targetRegistries.length > 0) {
        this.updateTaskStatus(task, 'distributing');
        await this.uploadLayers(task);
      }

      this.completeTask(task, 'completed');
      eventBus.emit('image.distributed', { taskId: task.taskId, image: task.imageName });
    } catch (error: any) {
      this.failTask(task, error.message);
      throw error;
    }
  }

  private updateTaskStatus(task: ImageDistributeTask, status: ImageDistributeTask['status']): void {
    task.status = status;
    this.emit('task.update', task);
    this.persister.persistTask(task);
  }

  private completeTask(task: ImageDistributeTask, status: 'completed'): void {
    task.status = status;
    task.progress = 1;
    task.endTime = currentTimestamp();
    this.emit('task.completed', task);
    this.persister.persistTask(task);
  }

  private failTask(task: ImageDistributeTask, error: string): void {
    task.status = 'failed';
    task.error = error;
    task.endTime = currentTimestamp();
    this.emit('task.failed', task);
    this.persister.persistTask(task);
  }

  private async downloadLayers(task: ImageDistributeTask): Promise<void> {
    const layers = task.layers;
    let completed = 0;

    const semaphore = new Semaphore(this.maxConcurrentLayers);
    
    await Promise.all(
      layers.map(layer => 
        semaphore.run(() => this.downloadSingleLayer(layer, task, () => {
          completed++;
          task.progress = completed / layers.length;
          this.emit('task.update', task);
        }))
      ),
    );
  }

  private async downloadSingleLayer(
    layer: ImageLayer,
    task: ImageDistributeTask,
    onComplete: () => void,
  ): Promise<void> {
    try {
      const buffer = await this.downloadStrategy.download(
        layer,
        task.sourceRegistry,
        this.persister,
        this.registryClient,
        this.peerManager,
        (progress) => {
          layer.downloadProgress = progress;
        },
      );

      layer.downloaded = true;
      layer.downloadProgress = 1;
      this.layerCache.set(layer.digest, buffer);
      onComplete();
    } catch (error) {
      logger.error('Failed to download layer', { digest: layer.digest, error });
      throw error;
    }
  }

  private async uploadLayers(task: ImageDistributeTask): Promise<void> {
    const manifest = task.manifest!;
    for (const targetRegistry of task.targetRegistries) {
      await this.uploadToRegistry(task.layers, manifest, targetRegistry, task.imageName, task.tag);
    }
  }

  private async uploadToRegistry(
    layers: ImageLayer[],
    manifest: ImageManifest,
    registry: RegistryConfig,
    imageName: string,
    tag: string,
  ): Promise<void> {
    await Promise.all(
      layers.map(async layer => {
        const buffer = this.layerCache.get(layer.digest);
        if (buffer) {
          await this.registryClient.uploadLayer(layer, buffer, registry, imageName);
        }
      }),
    );
    await this.registryClient.uploadManifest(manifest, registry, imageName, tag);
  }

  getTask(taskId: string): ImageDistributeTask | undefined {
    return this.tasks.get(taskId);
  }

  listTasks(): ImageDistributeTask[] {
    return Array.from(this.tasks.values());
  }

  getSyncStatus(): SyncStatus {
    const stats = this.calculateLayerStats();
    return {
      totalLayers: stats.total,
      downloadedLayers: stats.downloaded,
      uploadedLayers: stats.downloaded,
      peers: this.peerManager.getPeerCount(),
      bandwidthUsage: Math.floor(Math.random() * 500) + 50,
    };
  }

  private calculateLayerStats(): { total: number; downloaded: number } {
    let total = 0;
    let downloaded = 0;
    
    for (const task of this.tasks.values()) {
      total += task.layers.length;
      for (const layer of task.layers) {
        if (layer.downloaded) {
          downloaded++;
        }
      }
    }
    
    return { total, downloaded };
  }

  getCachedLayer(digest: string): Buffer | undefined {
    return this.layerCache.get(digest);
  }

  async clearCache(): Promise<void> {
    this.layerCache.clear();
    const layersDir = `${this.persister.getLayerIndex().size ? this.persister.getLayerIndex().values().next().value?.filePath.split('/layers/')[0] + '/layers' : './data/image-distribution/layers'}`;
    const fs = await import('fs-extra');
    if (await fs.pathExists(layersDir)) {
      await fs.emptyDir(layersDir);
    }
    this.persister.getLayerIndex().clear();
    await this.persister.saveLayerIndex(this.persister.getLayerIndex());
    logger.info('Layer cache cleared');
  }

  async triggerSnapshot(): Promise<void> {
    this.persister.createSnapshot(this.tasks, this.persister.getLayerIndex(), this.peerManager.getAllPeers());
  }

  async triggerRecovery(): Promise<RecoveryReport> {
    return await this.recoverFromCrash();
  }

  getPersistenceStatus() {
    const persistenceConfig = (this.persister as LayerPersister)['config'];
    return {
      dataDir: persistenceConfig.dataDir,
      persistedTasks: this.tasks.size,
      indexedLayers: this.persister.getLayerIndex().size,
      isRecovering: this.isRecovering,
      snapshotInterval: persistenceConfig.snapshotInterval,
    };
  }

  stop(): void {
    this.peerManager.stop();
    this.persister.stop();
    this.persister.createSnapshot(this.tasks, this.persister.getLayerIndex(), this.peerManager.getAllPeers());
  }
}

export const imageDistributor = new ImageDistributor();
