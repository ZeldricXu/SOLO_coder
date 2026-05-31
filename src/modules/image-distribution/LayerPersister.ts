import * as fs from 'fs-extra';
import * as path from 'path';
import logger from '../../utils/logger';
import { eventBus } from '../../utils/eventBus';
import { currentTimestamp } from '../../utils/helpers';
import { ImageDistributeTask, P2PPeer, LayerIndexEntry, PersistenceConfig, RecoveryReport } from './types';
import { ILayerPersister } from './interfaces';

export class LayerPersister implements ILayerPersister {
  private layerIndex: Map<string, LayerIndexEntry> = new Map();
  private snapshotTimer?: NodeJS.Timeout;
  private isRecovering: boolean = false;

  constructor(private config: PersistenceConfig) {}

  async initialize(): Promise<void> {
    await this.ensureDirectories();
    this.layerIndex = await this.loadLayerIndex();
    this.startSnapshotScheduler();
  }

  private async ensureDirectories(): Promise<void> {
    await fs.ensureDir(this.config.dataDir);
    await fs.ensureDir(path.join(this.config.dataDir, 'tasks'));
    await fs.ensureDir(path.join(this.config.dataDir, 'layers'));
    await fs.ensureDir(path.join(this.config.dataDir, 'snapshots'));
  }

  async loadLayerIndex(): Promise<Map<string, LayerIndexEntry>> {
    const indexPath = path.join(this.config.dataDir, 'layer-index.json');
    const index = new Map<string, LayerIndexEntry>();
    
    if (await fs.pathExists(indexPath)) {
      try {
        const content = await fs.readFile(indexPath, 'utf-8');
        const entries: LayerIndexEntry[] = JSON.parse(content);
        for (const entry of entries) {
          index.set(entry.digest, entry);
        }
        logger.info('Layer index loaded', { count: entries.length });
      } catch (error) {
        logger.warn('Failed to load layer index', { error });
      }
    }
    return index;
  }

  async saveLayerIndex(index: Map<string, LayerIndexEntry>): Promise<void> {
    const indexPath = path.join(this.config.dataDir, 'layer-index.json');
    const entries = Array.from(index.values());
    await fs.writeFile(indexPath, JSON.stringify(entries, null, 2));
    this.layerIndex = index;
  }

  async persistTask(task: ImageDistributeTask): Promise<void> {
    const taskPath = path.join(this.config.dataDir, 'tasks', `${task.taskId}.json`);
    await fs.writeFile(taskPath, JSON.stringify(task, null, 2));
  }

  async persistLayer(digest: string, buffer: Buffer): Promise<void> {
    const layerPath = path.join(this.config.dataDir, 'layers', `${digest.replace(':', '_')}.bin`);
    await fs.writeFile(layerPath, buffer);

    const entry: LayerIndexEntry = {
      digest,
      size: buffer.length,
      filePath: layerPath,
      createdAt: currentTimestamp(),
      lastAccessed: currentTimestamp(),
      accessCount: 1,
    };
    this.layerIndex.set(digest, entry);
    await this.saveLayerIndex(this.layerIndex);
  }

  async loadLayer(digest: string): Promise<Buffer | null> {
    const entry = this.layerIndex.get(digest);
    if (!entry) return null;

    if (!await fs.pathExists(entry.filePath)) {
      this.layerIndex.delete(digest);
      await this.saveLayerIndex(this.layerIndex);
      return null;
    }

    entry.lastAccessed = currentTimestamp();
    entry.accessCount++;
    this.layerIndex.set(digest, entry);
    await this.saveLayerIndex(this.layerIndex);

    return await fs.readFile(entry.filePath);
  }

  async recoverTasks(): Promise<ImageDistributeTask[]> {
    this.isRecovering = true;
    logger.info('Starting crash recovery for image distribution module');

    const recoveredTasks: ImageDistributeTask[] = [];
    const report: RecoveryReport = {
      recoveredTasks: 0,
      recoveredLayers: 0,
      failedRecoveries: 0,
      details: [],
    };

    try {
      const tasksDir = path.join(this.config.dataDir, 'tasks');
      const taskFiles = await fs.readdir(tasksDir);

      for (const file of taskFiles) {
        if (!file.endsWith('.json')) continue;

        try {
          const content = await fs.readFile(path.join(tasksDir, file), 'utf-8');
          const task: ImageDistributeTask = JSON.parse(content);

          if (task.status === 'downloading' || task.status === 'distributing') {
            task.status = 'failed';
            task.error = 'Interrupted by system crash';
            task.endTime = currentTimestamp();
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

          recoveredTasks.push(task);
          report.recoveredTasks++;
        } catch (error) {
          report.failedRecoveries++;
          report.details.push({
            taskId: file.replace('.json', ''),
            status: 'failed',
            error: error instanceof Error ? error.message : 'Unknown error',
          });
        }
      }

      const layersDir = path.join(this.config.dataDir, 'layers');
      if (await fs.pathExists(layersDir)) {
        const layerFiles = await fs.readdir(layersDir);
        report.recoveredLayers = layerFiles.filter(f => f.endsWith('.bin')).length;
      }

      logger.info('Crash recovery completed', report);
      eventBus.emit('image.recovery.completed', report);
    } catch (error) {
      logger.error('Crash recovery failed', { error });
    } finally {
      this.isRecovering = false;
    }

    return recoveredTasks;
  }

  createSnapshot(
    tasks: Map<string, ImageDistributeTask>,
    layerIndex: Map<string, LayerIndexEntry>,
    peers: Map<string, P2PPeer>,
  ): void {
    if (this.isRecovering) return;

    const snapshotData = {
      timestamp: currentTimestamp(),
      tasks: Array.from(tasks.values()),
      layerIndex: Array.from(layerIndex.values()),
      peers: Array.from(peers.values()).map(p => ({
        ...p,
        availableLayers: Array.from(p.availableLayers),
      })),
    };

    const snapshotPath = path.join(
      this.config.dataDir,
      'snapshots',
      `snapshot-${Date.now()}.json`,
    );

    fs.writeFile(snapshotPath, JSON.stringify(snapshotData))
      .then(() => logger.debug('Snapshot created', { path: snapshotPath }))
      .catch(error => logger.error('Snapshot creation failed', { error }));

    this.cleanupOldSnapshots().catch(error => {
      logger.warn('Old snapshot cleanup failed', { error });
    });
  }

  private async cleanupOldSnapshots(): Promise<void> {
    const snapshotsDir = path.join(this.config.dataDir, 'snapshots');
    const files = await fs.readdir(snapshotsDir);
    const snapshots = files
      .filter(f => f.startsWith('snapshot-') && f.endsWith('.json'))
      .sort()
      .reverse();

    if (snapshots.length > this.config.maxSnapshots) {
      const toDelete = snapshots.slice(this.config.maxSnapshots);
      await Promise.all(toDelete.map(file => fs.remove(path.join(snapshotsDir, file))));
      logger.debug('Old snapshots cleaned up', { count: toDelete.length });
    }
  }

  private startSnapshotScheduler(): void {
    this.snapshotTimer = setInterval(() => {
      if (!this.isRecovering) {
        eventBus.emit('image.snapshot.trigger');
      }
    }, this.config.snapshotInterval);
  }

  getLayerIndex(): Map<string, LayerIndexEntry> {
    return this.layerIndex;
  }

  stop(): void {
    if (this.snapshotTimer) {
      clearInterval(this.snapshotTimer);
    }
  }
}
