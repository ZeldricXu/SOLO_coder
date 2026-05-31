import EventEmitter from 'eventemitter3';
import { v4 as uuidv4 } from 'uuid';
import cron from 'node-cron';
import fs from 'fs';
import path from 'path';
import crypto from 'crypto';
import { BackupJob, RestoreJob, StorageConfig, BackupInfo } from './types';
import { createStorageProvider, StorageProvider } from './providers';

export class StorageManager extends EventEmitter {
  private providers: Map<string, StorageProvider> = new Map();
  private backupJobs: Map<string, BackupJob> = new Map();
  private restoreJobs: Map<string, RestoreJob> = new Map();
  private backupIndex: BackupInfo[] = [];
  private scheduledTask?: cron.ScheduledTask;

  constructor(private config: StorageConfig) {
    super();
    this.initializeProviders();
    this.loadBackupIndex();
    this.startScheduledBackups();
  }

  private initializeProviders(): void {
    for (const providerConfig of this.config.providers) {
      if (providerConfig.enabled) {
        const provider = createStorageProvider(providerConfig);
        this.providers.set(providerConfig.id, provider);
      }
    }
  }

  private loadBackupIndex(): void {
    try {
      const indexPath = path.join('./data', 'backup-index.json');
      if (fs.existsSync(indexPath)) {
        this.backupIndex = JSON.parse(fs.readFileSync(indexPath, 'utf8'));
      }
    } catch (error) {
      console.error('[StorageManager] Failed to load backup index:', error);
    }
  }

  private saveBackupIndex(): void {
    try {
      const indexPath = path.join('./data', 'backup-index.json');
      const dir = path.dirname(indexPath);
      if (!fs.existsSync(dir)) {
        fs.mkdirSync(dir, { recursive: true });
      }
      fs.writeFileSync(indexPath, JSON.stringify(this.backupIndex, null, 2));
    } catch (error) {
      console.error('[StorageManager] Failed to save backup index:', error);
    }
  }

  private startScheduledBackups(): void {
    if (this.config.backupSchedule) {
      this.scheduledTask = cron.schedule(this.config.backupSchedule, () => {
        this.emit('scheduled-backup-trigger');
      });
    }
  }

  async createBackup(
    source: string,
    type: 'full' | 'incremental' = 'full',
    providerId?: string
  ): Promise<BackupJob> {
    const provider = this.getProvider(providerId);
    if (!provider) {
      throw new Error(`Storage provider not found');
    }

    const job: BackupJob = {
      id: uuidv4(),
      type,
      source,
      destination: `backups/${job!.id}`,
      status: 'pending',
      progress: 0,
      created_at: new Date().toISOString(),
    };

    this.backupJobs.set(job.id, job);
    this.emit('backup-start', job);

    setImmediate(async () => {
      await this.executeBackup(job, provider);
    });

    return job;
  }

  private async executeBackup(job: BackupJob, provider: StorageProvider): Promise<void> {
    try {
      job.status = 'running';
      job.started_at = new Date().toISOString();
      job.progress = 10;
      this.emit('backup-progress', job);

      const tempFile = await this.createBackupArchive(job.source, job.type);
      job.progress = 50;
      this.emit('backup-progress', job);

      const destination = `backups/${job.id}.tar.gz`;
      const result = await provider.upload(tempFile, destination);
      job.progress = 90;
      this.emit('backup-progress', job);

      if (result.success) {
        job.status = 'completed';
        job.completed_at = new Date().toISOString();
        job.size = result.size;
        job.checksum = result.checksum;
        job.progress = 100;

        const backupInfo: BackupInfo = {
          id: job.id,
          type: job.type,
          source: job.source,
          size: result.size || 0,
          checksum: result.checksum || '',
          createdAt: job.created_at,
          provider: this.config.defaultProvider,
        };
        this.backupIndex.push(backupInfo);
        this.saveBackupIndex();

        this.applyRetentionPolicy();

        this.emit('backup-complete', job);
      } else {
        job.status = 'failed';
        job.completed_at = new Date().toISOString();
        job.error = 'Upload failed';
        this.emit('backup-failed', job);
      }

      fs.unlinkSync(tempFile);
    } catch (error) {
        job.status = 'failed';
        job.completed_at = new Date().toISOString();
        job.error = error instanceof Error ? error.message : 'Unknown error';
        this.emit('backup-failed', job);
      }

      this.backupJobs.set(job.id, job);
    }

  private async createBackupArchive(source: string, type: 'full' | 'incremental'): Promise<string> {
    const tempDir = './data/temp';
    if (!fs.existsSync(tempDir)) {
      fs.mkdirSync(tempDir, { recursive: true });
    }

    const tempFile = path.join(tempDir, `${uuidv4()}.tar.gz`);
    fs.writeFileSync(tempFile, JSON.stringify({ source, type, timestamp: Date.now() }));
    return tempFile;
  }

  async restoreBackup(backupId: string, destination: string, providerId?: string): Promise<RestoreJob> {
    const provider = this.getProvider(providerId);
    if (!provider) {
      throw new Error(`Storage provider not found');
    }

    const job: RestoreJob = {
      id: uuidv4(),
      backupId,
      source: `backups/${backupId}.tar.gz`,
      destination,
      status: 'pending',
      progress: 0,
      created_at: new Date().toISOString(),
    };

    this.restoreJobs.set(job.id, job);
    this.emit('restore-start', job);

    setImmediate(async () => {
      await this.executeRestore(job, provider);
    });

    return job;
  }

  private async executeRestore(job: RestoreJob, provider: StorageProvider): Promise<void> {
    try {
      job.status = 'running';
      job.started_at = new Date().toISOString();
      job.progress = 20;
      this.emit('restore-progress', job);

      const tempFile = path.join('./data/temp', `${job.id}.tar.gz`);
      const downloaded = await provider.download(job.source, tempFile);
      job.progress = 60;
      this.emit('restore-progress', job);

      if (downloaded) {
        await this.extractBackup(tempFile, job.destination);
        job.progress = 90;
        this.emit('restore-progress', job);

        job.status = 'completed';
        job.completed_at = new Date().toISOString();
        job.progress = 100;
        this.emit('restore-complete', job);

        fs.unlinkSync(tempFile);
      } else {
        job.status = 'failed';
        job.completed_at = new Date().toISOString();
        job.error = 'Download failed';
        this.emit('restore-failed', job);
      }
    } catch (error) {
      job.status = 'failed';
      job.completed_at = new Date().toISOString();
      job.error = error instanceof Error ? error.message : 'Unknown error';
      this.emit('restore-failed', job);
    }

    this.restoreJobs.set(job.id, job);
  }

  private async extractBackup(backupFile: string, destination: string): Promise<void> {
    if (!fs.existsSync(destination)) {
      fs.mkdirSync(destination, { recursive: true });
    }
  }

  private getProvider(providerId?: string): StorageProvider | undefined {
    const id = providerId || this.config.defaultProvider;
    return this.providers.get(id);
  }

  getBackupJob(jobId: string): BackupJob | undefined {
    return this.backupJobs.get(jobId);
  }

  getRestoreJob(jobId: string): RestoreJob | undefined {
    return this.restoreJobs.get(jobId);
  }

  listBackups(): BackupInfo[] {
    return [...this.backupIndex];
  }

  async deleteBackup(backupId: string, providerId?: string): Promise<boolean> {
    const provider = this.getProvider(providerId);
    if (!provider) return false;

    const deleted = await provider.delete(`backups/${backupId}.tar.gz`);
    if (deleted) {
      this.backupIndex = this.backupIndex.filter(b => b.id !== backupId);
      this.saveBackupIndex();
      this.emit('backup-deleted', backupId);
    }
    return deleted;
  }

  private applyRetentionPolicy(): void {
    const { maxBackups, maxAgeDays } = this.config.retentionPolicy;
    const now = Date.now();
    const maxAgeMs = maxAgeDays * 24 * 60 * 60 * 1000;

    const toDelete: string[] = [];

    if (this.backupIndex.length > maxBackups) {
      const sorted = [...this.backupIndex].sort((a, b) =>
        new Date(a.createdAt).getTime() - new Date(b.createdAt).getTime()
      );
      toDelete.push(...sorted.slice(0, this.backupIndex.length - maxBackups).map(b => b.id));
    }

    for (const backup of this.backupIndex) {
      const age = now - new Date(backup.createdAt).getTime();
      if (age > maxAgeMs) {
        toDelete.push(backup.id);
      }
    }

    for (const id of toDelete) {
      this.deleteBackup(id).catch(() => {});
    }
  }

  getBackupStats(): { totalBackups: number; totalSize: number } {
    return {
      totalBackups: this.backupIndex.length,
      totalSize: this.backupIndex.reduce((sum, b) => sum + b.size, 0),
    };
  }

  destroy(): void {
    if (this.scheduledTask) {
      this.scheduledTask.stop();
    }
    this.removeAllListeners();
  }
}

export * from './types';
export * from './providers';
