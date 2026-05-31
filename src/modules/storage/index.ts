import * as fs from 'fs-extra';
import * as path from 'path';
import { EventEmitter } from 'events';
import logger from '../../utils/logger';
import { eventBus } from '../../utils/eventBus';
import { generateId, currentTimestamp, sleep } from '../../utils/helpers';

export interface BackupConfig {
  backupDir: string;
  retentionDays: number;
  schedule: string;
  compression: 'none' | 'gzip' | 'tar';
  encryption: boolean;
  encryptionKey?: string;
  maxBackups: number;
}

export interface Backup {
  id: string;
  name: string;
  type: 'full' | 'incremental' | 'differential';
  source: string;
  filePath: string;
  size: number;
  status: 'pending' | 'running' | 'completed' | 'failed';
  createdAt: string;
  completedAt?: string;
  checksum: string;
  error?: string;
  metadata: Record<string, any>;
}

export interface RestoreJob {
  id: string;
  backupId: string;
  targetPath: string;
  status: 'pending' | 'running' | 'completed' | 'failed';
  createdAt: string;
  completedAt?: string;
  error?: string;
  options: RestoreOptions;
}

export interface RestoreOptions {
  overwrite: boolean;
  verify: boolean;
  parallel: number;
}

export interface Snapshot {
  id: string;
  name: string;
  source: string;
  path: string;
  createdAt: string;
  size: number;
}

export class StorageManager extends EventEmitter {
  private config: BackupConfig;
  private backups: Map<string, Backup> = new Map();
  private restoreJobs: Map<string, RestoreJob> = new Map();
  private snapshots: Map<string, Snapshot> = new Map();
  private backupTimer?: NodeJS.Timeout;

  constructor(config?: Partial<BackupConfig>) {
    super();
    this.config = {
      backupDir: process.env.BACKUP_DIR || './backups',
      retentionDays: 30,
      schedule: '0 2 * * *',
      compression: 'gzip',
      encryption: false,
      maxBackups: 100,
      ...config,
    };
    this.initialize();
    logger.info('StorageManager initialized', { backupDir: this.config.backupDir });
  }

  private async initialize(): Promise<void> {
    await fs.ensureDir(this.config.backupDir);
    await this.loadExistingBackups();
    this.startCleanupScheduler();
  }

  private async loadExistingBackups(): Promise<void> {
    try {
      const files = await fs.readdir(this.config.backupDir);
      for (const file of files) {
        if (file.endsWith('.json')) {
          const content = await fs.readFile(
            path.join(this.config.backupDir, file),
            'utf-8',
          );
          const backup = JSON.parse(content) as Backup;
          this.backups.set(backup.id, backup);
        }
      }
    } catch (error) {
      logger.warn('No existing backups found', { error });
    }
  }

  async createBackup(
    source: string,
    type: Backup['type'] = 'full',
    metadata: Record<string, any> = {},
  ): Promise<Backup> {
    const id = generateId('bkp_');
    const timestamp = new Date().toISOString().replace(/[:.]/g, '-');
    const fileName = `backup_${type}_${timestamp}.tar.gz`;
    const filePath = path.join(this.config.backupDir, fileName);

    const backup: Backup = {
      id,
      name: `backup-${type}-${timestamp}`,
      type,
      source,
      filePath,
      size: 0,
      status: 'pending',
      createdAt: currentTimestamp(),
      checksum: '',
      metadata,
    };

    this.backups.set(id, backup);
    this.saveBackupMetadata(backup);

    this.executeBackup(backup).catch(error => {
      logger.error('Backup execution failed', { backupId: id, error: error.message });
    });

    return backup;
  }

  private async executeBackup(backup: Backup): Promise<void> {
    backup.status = 'running';
    this.backups.set(backup.id, backup);
    this.saveBackupMetadata(backup);
    eventBus.emit('backup.started', backup);

    try {
      await this.createBackupDirectory(backup);

      if (backup.type === 'full') {
        await this.performFullBackup(backup);
      } else if (backup.type === 'incremental') {
        await this.performIncrementalBackup(backup);
      } else {
        await this.performDifferentialBackup(backup);
      }

      const stats = await fs.stat(backup.filePath);
      backup.size = stats.size;
      backup.checksum = await this.calculateChecksum(backup.filePath);
      backup.status = 'completed';
      backup.completedAt = currentTimestamp();

      this.backups.set(backup.id, backup);
      this.saveBackupMetadata(backup);

      logger.info('Backup completed', {
        backupId: backup.id,
        size: backup.size,
        duration: new Date(backup.completedAt!).getTime() - new Date(backup.createdAt).getTime(),
      });
      eventBus.emit('backup.completed', backup);

      await this.enforceRetentionPolicy();
    } catch (error: any) {
      backup.status = 'failed';
      backup.error = error.message;
      this.backups.set(backup.id, backup);
      this.saveBackupMetadata(backup);
      eventBus.emit('backup.failed', { backup, error: error.message });
      throw error;
    }
  }

  private async createBackupDirectory(backup: Backup): Promise<void> {
    await fs.ensureDir(path.dirname(backup.filePath));
  }

  private async performFullBackup(backup: Backup): Promise<void> {
    logger.debug('Performing full backup', { source: backup.source, target: backup.filePath });
    
    if (await fs.pathExists(backup.source)) {
      const stat = await fs.stat(backup.source);
      if (stat.isDirectory()) {
        await this.copyDirectory(backup.source, backup.filePath + '_data');
      } else {
        await fs.copyFile(backup.source, backup.filePath);
      }
    }

    await sleep(100);
  }

  private async performIncrementalBackup(backup: Backup): Promise<void> {
    logger.debug('Performing incremental backup', { source: backup.source });
    
    const lastBackup = this.getLastSuccessfulBackup();
    const since = lastBackup ? new Date(lastBackup.createdAt) : new Date(0);
    
    await this.backupChangedFiles(backup.source, backup.filePath, since);
    await sleep(50);
  }

  private async performDifferentialBackup(backup: Backup): Promise<void> {
    logger.debug('Performing differential backup', { source: backup.source });
    
    const lastFullBackup = this.getLastSuccessfulBackup('full');
    const since = lastFullBackup ? new Date(lastFullBackup.createdAt) : new Date(0);
    
    await this.backupChangedFiles(backup.source, backup.filePath, since);
    await sleep(75);
  }

  private async backupChangedFiles(
    source: string,
    target: string,
    since: Date,
  ): Promise<void> {
    if (!await fs.pathExists(source)) return;

    const files = await fs.readdir(source, { withFileTypes: true });
    for (const file of files) {
      const filePath = path.join(source, file.name);
      const stat = await fs.stat(filePath);
      
      if (stat.mtime > since) {
        if (file.isDirectory()) {
          await this.backupChangedFiles(filePath, path.join(target, file.name), since);
        } else {
          await fs.ensureDir(path.dirname(path.join(target + '_data', file.name)));
          await fs.copyFile(filePath, path.join(target + '_data', file.name));
        }
      }
    }
  }

  private async copyDirectory(src: string, dest: string): Promise<void> {
    await fs.ensureDir(dest);
    const files = await fs.readdir(src, { withFileTypes: true });
    
    for (const file of files) {
      const srcPath = path.join(src, file.name);
      const destPath = path.join(dest, file.name);
      
      if (file.isDirectory()) {
        await this.copyDirectory(srcPath, destPath);
      } else {
        await fs.copyFile(srcPath, destPath);
      }
    }
  }

  private async calculateChecksum(filePath: string): Promise<string> {
    try {
      const crypto = await import('crypto');
      const hash = crypto.createHash('sha256');
      const stream = fs.createReadStream(filePath);
      
      return new Promise((resolve, reject) => {
        stream.on('data', data => hash.update(data));
        stream.on('end', () => resolve(hash.digest('hex')));
        stream.on('error', reject);
      });
    } catch {
      return 'simulated-checksum-' + generateId('');
    }
  }

  private getLastSuccessfulBackup(type?: Backup['type']): Backup | undefined {
    return Array.from(this.backups.values())
      .filter(b => b.status === 'completed' && (!type || b.type === type))
      .sort((a, b) => new Date(b.createdAt).getTime() - new Date(a.createdAt).getTime())[0];
  }

  private async saveBackupMetadata(backup: Backup): Promise<void> {
    const metaPath = path.join(this.config.backupDir, `${backup.id}.json`);
    await fs.writeFile(metaPath, JSON.stringify(backup, null, 2));
  }

  async restoreBackup(
    backupId: string,
    targetPath: string,
    options: Partial<RestoreOptions> = {},
  ): Promise<RestoreJob> {
    const backup = this.backups.get(backupId);
    if (!backup) {
      throw new Error(`Backup ${backupId} not found`);
    }
    if (backup.status !== 'completed') {
      throw new Error(`Backup ${backupId} is not completed`);
    }

    const jobId = generateId('rst_');
    const job: RestoreJob = {
      id: jobId,
      backupId,
      targetPath,
      status: 'pending',
      createdAt: currentTimestamp(),
      options: {
        overwrite: false,
        verify: true,
        parallel: 4,
        ...options,
      },
    };

    this.restoreJobs.set(jobId, job);
    this.executeRestore(job, backup).catch(error => {
      logger.error('Restore execution failed', { jobId, error: error.message });
    });

    return job;
  }

  private async executeRestore(job: RestoreJob, backup: Backup): Promise<void> {
    job.status = 'running';
    this.restoreJobs.set(job.id, job);
    eventBus.emit('restore.started', { job, backup });

    try {
      await fs.ensureDir(job.targetPath);

      const dataPath = backup.filePath + '_data';
      if (await fs.pathExists(dataPath)) {
        await this.copyDirectory(dataPath, job.targetPath);
      } else if (await fs.pathExists(backup.filePath)) {
        if ((await fs.stat(backup.filePath)).isDirectory()) {
          await this.copyDirectory(backup.filePath, job.targetPath);
        } else {
          await fs.copyFile(backup.filePath, path.join(job.targetPath, path.basename(backup.filePath)));
        }
      }

      if (job.options.verify) {
        await this.verifyRestore(backup, job.targetPath);
      }

      job.status = 'completed';
      job.completedAt = currentTimestamp();
      this.restoreJobs.set(job.id, job);

      logger.info('Restore completed', {
        jobId: job.id,
        backupId: backup.id,
        target: job.targetPath,
      });
      eventBus.emit('restore.completed', { job, backup });
    } catch (error: any) {
      job.status = 'failed';
      job.error = error.message;
      this.restoreJobs.set(job.id, job);
      eventBus.emit('restore.failed', { job, error: error.message });
      throw error;
    }
  }

  private async verifyRestore(backup: Backup, targetPath: string): Promise<void> {
    if (backup.checksum) {
      const files = await fs.readdir(targetPath);
      if (files.length === 0) {
        logger.warn('Restore verification: target directory is empty');
      }
    }
  }

  async createSnapshot(source: string, name: string): Promise<Snapshot> {
    const id = generateId('snap_');
    const snapshotPath = path.join(this.config.backupDir, 'snapshots', id);
    
    await fs.ensureDir(snapshotPath);
    await this.copyDirectory(source, snapshotPath);

    const stats = await fs.stat(snapshotPath);
    const snapshot: Snapshot = {
      id,
      name,
      source,
      path: snapshotPath,
      createdAt: currentTimestamp(),
      size: stats.size,
    };

    this.snapshots.set(id, snapshot);
    logger.info('Snapshot created', { id, name, source });
    eventBus.emit('snapshot.created', snapshot);

    return snapshot;
  }

  deleteSnapshot(id: string): boolean {
    const snapshot = this.snapshots.get(id);
    if (!snapshot) return false;

    fs.remove(snapshot.path).catch(error => {
      logger.error('Failed to delete snapshot files', { id, error });
    });

    this.snapshots.delete(id);
    logger.info('Snapshot deleted', { id });
    return true;
  }

  listSnapshots(): Snapshot[] {
    return Array.from(this.snapshots.values());
  }

  getBackup(id: string): Backup | undefined {
    return this.backups.get(id);
  }

  listBackups(): Backup[] {
    return Array.from(this.backups.values()).sort(
      (a, b) => new Date(b.createdAt).getTime() - new Date(a.createdAt).getTime(),
    );
  }

  getRestoreJob(id: string): RestoreJob | undefined {
    return this.restoreJobs.get(id);
  }

  listRestoreJobs(): RestoreJob[] {
    return Array.from(this.restoreJobs.values()).sort(
      (a, b) => new Date(b.createdAt).getTime() - new Date(a.createdAt).getTime(),
    );
  }

  private async enforceRetentionPolicy(): Promise<void> {
    const now = new Date();
    const cutoffDate = new Date(now.getTime() - this.config.retentionDays * 24 * 60 * 60 * 1000);
    
    const backupsToDelete = Array.from(this.backups.values()).filter(
      b => new Date(b.createdAt) < cutoffDate,
    );

    const backups = Array.from(this.backups.values());
    if (backups.length > this.config.maxBackups) {
      const sorted = backups.sort(
        (a, b) => new Date(a.createdAt).getTime() - new Date(b.createdAt).getTime(),
      );
      const excess = sorted.slice(0, sorted.length - this.config.maxBackups);
      backupsToDelete.push(...excess);
    }

    for (const backup of backupsToDelete) {
      await this.deleteBackup(backup.id);
    }
  }

  async deleteBackup(id: string): Promise<boolean> {
    const backup = this.backups.get(id);
    if (!backup) return false;

    try {
      if (await fs.pathExists(backup.filePath)) {
        await fs.remove(backup.filePath);
      }
      const dataPath = backup.filePath + '_data';
      if (await fs.pathExists(dataPath)) {
        await fs.remove(dataPath);
      }
      const metaPath = path.join(this.config.backupDir, `${id}.json`);
      if (await fs.pathExists(metaPath)) {
        await fs.remove(metaPath);
      }
    } catch (error) {
      logger.error('Failed to delete backup files', { id, error });
    }

    this.backups.delete(id);
    logger.info('Backup deleted', { id });
    eventBus.emit('backup.deleted', { id });
    return true;
  }

  private startCleanupScheduler(): void {
    this.backupTimer = setInterval(() => {
      this.enforceRetentionPolicy().catch(error => {
        logger.error('Retention policy enforcement failed', { error });
      });
    }, 24 * 60 * 60 * 1000);
  }

  getStorageStats() {
    const totalSize = Array.from(this.backups.values())
      .filter(b => b.status === 'completed')
      .reduce((sum, b) => sum + b.size, 0);

    return {
      totalBackups: this.backups.size,
      completedBackups: Array.from(this.backups.values()).filter(b => b.status === 'completed').length,
      failedBackups: Array.from(this.backups.values()).filter(b => b.status === 'failed').length,
      runningBackups: Array.from(this.backups.values()).filter(b => b.status === 'running').length,
      totalSize,
      snapshots: this.snapshots.size,
      restoreJobs: this.restoreJobs.size,
    };
  }

  stop(): void {
    if (this.backupTimer) {
      clearInterval(this.backupTimer);
    }
  }
}

export const storageManager = new StorageManager();
