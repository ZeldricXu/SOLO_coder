import * as fs from 'fs';
import * as path from 'path';
import { spawn } from 'child_process';
import { BackupConfig, BackupJob, RestoreJob, BackupFile, StorageStats } from '../../types/storage';
import { generateId, getCurrentTimestamp, retryWithBackoff } from '../../common/utils';
import { AppError, NotFoundError } from '../../common/errors';
import { CronJob } from 'cron';

export class StorageManager {
  private backupPath: string;
  private backups: Map<string, BackupJob>;
  private restores: Map<string, RestoreJob>;
  private configs: Map<string, BackupConfig>;
  private scheduledJobs: Map<string, CronJob>;

  constructor(backupPath: string = './backups') {
    this.backupPath = path.resolve(backupPath);
    this.backups = new Map();
    this.restores = new Map();
    this.configs = new Map();
    this.scheduledJobs = new Map();

    this.ensureDirectoryExists(this.backupPath);
  }

  private ensureDirectoryExists(dirPath: string): void {
    if (!fs.existsSync(dirPath)) {
      fs.mkdirSync(dirPath, { recursive: true });
    }
  }

  createBackupConfig(config: Omit<BackupConfig, 'id' | 'createdAt' | 'updatedAt'>): BackupConfig {
    const now = getCurrentTimestamp();
    const backupConfig: BackupConfig = {
      ...config,
      id: generateId('bcfg'),
      createdAt: now,
      updatedAt: now
    };

    this.configs.set(backupConfig.id, backupConfig);
    return backupConfig;
  }

  getBackupConfig(configId: string): BackupConfig {
    const config = this.configs.get(configId);
    if (!config) {
      throw new NotFoundError(`备份配置不存在: ${configId}`);
    }
    return config;
  }

  listBackupConfigs(): BackupConfig[] {
    return Array.from(this.configs.values());
  }

  updateBackupConfig(
    configId: string,
    updates: Partial<Omit<BackupConfig, 'id' | 'createdAt'>>
  ): BackupConfig {
    const config = this.getBackupConfig(configId);
    const updated = {
      ...config,
      ...updates,
      updatedAt: getCurrentTimestamp()
    };

    this.configs.set(configId, updated);
    return updated;
  }

  deleteBackupConfig(configId: string): void {
    this.stopScheduledBackup(configId);
    this.configs.delete(configId);
  }

  async createBackup(configId: string, type: BackupJob['type'] = 'full'): Promise<BackupJob> {
    const config = this.getBackupConfig(configId);

    const now = getCurrentTimestamp();
    const job: BackupJob = {
      id: generateId('bjob'),
      configId,
      status: 'running',
      type,
      startTime: now,
      createdAt: now
    };

    this.backups.set(job.id, job);

    try {
      await this.executeBackup(config, job);
      job.status = 'completed';
      job.endTime = getCurrentTimestamp();
      job.durationMs = new Date(job.endTime).getTime() - new Date(job.startTime).getTime();
    } catch (error) {
      job.status = 'failed';
      job.error = error instanceof Error ? error.message : '备份失败';
      job.endTime = getCurrentTimestamp();
    }

    this.backups.set(job.id, job);
    return job;
  }

  private async executeBackup(config: BackupConfig, job: BackupJob): Promise<void> {
    const timestamp = new Date().toISOString().replace(/[:.]/g, '-');
    const backupDir = path.join(this.backupPath, config.id, timestamp);
    this.ensureDirectoryExists(backupDir);

    if (config.source.type === 'database') {
      await this.backupDatabase(config, job, backupDir);
    } else if (config.source.type === 'file') {
      await this.backupFiles(config, job, backupDir);
    } else {
      throw new AppError(`不支持的备份源类型: ${config.source.type}`, 'UNSUPPORTED_BACKUP_TYPE', 400);
    }

    if (config.compression.enabled) {
      await this.compressBackup(backupDir, config);
    }

    if (config.encryption?.enabled) {
      await this.encryptBackup(backupDir, config);
    }

    job.filesBackedUp = fs.readdirSync(backupDir).length;
    job.sizeBytes = this.getDirectorySize(backupDir);
  }

  private async backupDatabase(config: BackupConfig, job: BackupJob, backupDir: string): Promise<void> {
    const dbConfig = config.source.config as any;
    const backupFile = path.join(backupDir, 'database.sql');

    const dumpCommand = `pg_dump ${dbConfig.connectionString} > ${backupFile}`;

    return new Promise((resolve, reject) => {
      const child = spawn('sh', ['-c', dumpCommand]);

      child.on('error', reject);
      child.on('exit', (code) => {
        if (code === 0) {
          resolve();
        } else {
          reject(new Error(`数据库备份失败，退出码: ${code}`));
        }
      });
    });
  }

  private async backupFiles(config: BackupConfig, job: BackupJob, backupDir: string): Promise<void> {
    const fileConfig = config.source.config as any;
    const sourcePath = fileConfig.path;

    if (!fs.existsSync(sourcePath)) {
      throw new AppError(`源路径不存在: ${sourcePath}`, 'SOURCE_NOT_FOUND', 404);
    }

    const copyRecursive = (src: string, dest: string) => {
      const stat = fs.statSync(src);

      if (stat.isDirectory()) {
        this.ensureDirectoryExists(dest);
        const entries = fs.readdirSync(src);

        for (const entry of entries) {
          const srcPath = path.join(src, entry);
          const destPath = path.join(dest, entry);
          copyRecursive(srcPath, destPath);
        }
      } else {
        fs.copyFileSync(src, dest);
      }
    };

    copyRecursive(sourcePath, backupDir);
  }

  private async compressBackup(backupDir: string, config: BackupConfig): Promise<void> {
    const parentDir = path.dirname(backupDir);
    const archiveName = `${path.basename(backupDir)}.tar.gz`;
    const archivePath = path.join(parentDir, archiveName);

    return new Promise((resolve, reject) => {
      const child = spawn('tar', ['-czf', archivePath, '-C', parentDir, path.basename(backupDir)]);

      child.on('error', reject);
      child.on('exit', (code) => {
        if (code === 0) {
          fs.rmSync(backupDir, { recursive: true, force: true });
          resolve();
        } else {
          reject(new Error(`压缩备份失败，退出码: ${code}`));
        }
      });
    });
  }

  private async encryptBackup(backupDir: string, config: BackupConfig): Promise<void> {
    console.log('备份加密功能需要配置加密密钥');
  }

  getBackupJob(jobId: string): BackupJob {
    const job = this.backups.get(jobId);
    if (!job) {
      throw new NotFoundError(`备份任务不存在: ${jobId}`);
    }
    return job;
  }

  listBackupJobs(configId?: string): BackupJob[] {
    let jobs = Array.from(this.backups.values());
    if (configId) {
      jobs = jobs.filter(j => j.configId === configId);
    }
    return jobs.sort((a, b) => new Date(b.createdAt).getTime() - new Date(a.createdAt).getTime());
  }

  async restoreBackup(jobId: string, target?: any): Promise<RestoreJob> {
    const backupJob = this.getBackupJob(jobId);

    const now = getCurrentTimestamp();
    const restoreJob: RestoreJob = {
      id: generateId('rjob'),
      backupId: jobId,
      backupConfigId: backupJob.configId,
      status: 'running',
      target: target || this.getBackupConfig(backupJob.configId).source,
      startTime: now,
      createdAt: now
    };

    this.restores.set(restoreJob.id, restoreJob);

    try {
      await this.executeRestore(backupJob, restoreJob);
      restoreJob.status = 'completed';
      restoreJob.endTime = getCurrentTimestamp();
    } catch (error) {
      restoreJob.status = 'failed';
      restoreJob.error = error instanceof Error ? error.message : '恢复失败';
      restoreJob.endTime = getCurrentTimestamp();
    }

    this.restores.set(restoreJob.id, restoreJob);
    return restoreJob;
  }

  private async executeRestore(backupJob: BackupJob, restoreJob: RestoreJob): Promise<void> {
    const config = this.getBackupConfig(backupJob.configId);
    const backupDir = path.join(this.backupPath, config.id, new Date(backupJob.startTime).toISOString().replace(/[:.]/g, '-'));

    if (!fs.existsSync(backupDir)) {
      throw new NotFoundError(`备份文件不存在: ${backupDir}`);
    }

    if (restoreJob.target.type === 'database') {
      await this.restoreDatabase(backupDir, restoreJob);
    } else if (restoreJob.target.type === 'file') {
      await this.restoreFiles(backupDir, restoreJob);
    }

    restoreJob.filesRestored = fs.readdirSync(backupDir).length;
  }

  private async restoreDatabase(backupDir: string, restoreJob: RestoreJob): Promise<void> {
    const dbConfig = restoreJob.target.config as any;
    const backupFile = path.join(backupDir, 'database.sql');

    if (!fs.existsSync(backupFile)) {
      throw new NotFoundError(`数据库备份文件不存在: ${backupFile}`);
    }

    const restoreCommand = `psql ${dbConfig.connectionString} < ${backupFile}`;

    return new Promise((resolve, reject) => {
      const child = spawn('sh', ['-c', restoreCommand]);

      child.on('error', reject);
      child.on('exit', (code) => {
        if (code === 0) {
          resolve();
        } else {
          reject(new Error(`数据库恢复失败，退出码: ${code}`));
        }
      });
    });
  }

  private async restoreFiles(backupDir: string, restoreJob: RestoreJob): Promise<void> {
    const targetConfig = restoreJob.target.config as any;
    const targetPath = targetConfig.path;

    this.ensureDirectoryExists(targetPath);

    const copyRecursive = (src: string, dest: string) => {
      const stat = fs.statSync(src);

      if (stat.isDirectory()) {
        this.ensureDirectoryExists(dest);
        const entries = fs.readdirSync(src);

        for (const entry of entries) {
          const srcPath = path.join(src, entry);
          const destPath = path.join(dest, entry);
          copyRecursive(srcPath, destPath);
        }
      } else {
        fs.copyFileSync(src, dest);
      }
    };

    copyRecursive(backupDir, targetPath);
  }

  getRestoreJob(jobId: string): RestoreJob {
    const job = this.restores.get(jobId);
    if (!job) {
      throw new NotFoundError(`恢复任务不存在: ${jobId}`);
    }
    return job;
  }

  listRestoreJobs(): RestoreJob[] {
    return Array.from(this.restores.values()).sort(
      (a, b) => new Date(b.createdAt).getTime() - new Date(a.createdAt).getTime()
    );
  }

  scheduleBackup(configId: string): void {
    const config = this.getBackupConfig(configId);

    if (!config.schedule) {
      throw new AppError('备份配置没有设置调度时间', 'NO_SCHEDULE', 400);
    }

    this.stopScheduledBackup(configId);

    const job = new CronJob(config.schedule, () => {
      this.createBackup(configId, 'incremental').catch(error => {
        console.error('定时备份失败:', error);
      });
    });

    job.start();
    this.scheduledJobs.set(configId, job);
  }

  stopScheduledBackup(configId: string): void {
    const job = this.scheduledJobs.get(configId);
    if (job) {
      job.stop();
      this.scheduledJobs.delete(configId);
    }
  }

  listScheduledBackups(): { configId: string; schedule: string; nextDate: Date | null }[] {
    const result: { configId: string; schedule: string; nextDate: Date | null }[] = [];

    for (const [configId, job] of this.scheduledJobs) {
      const config = this.configs.get(configId);
      if (config) {
        result.push({
          configId,
          schedule: config.schedule!,
          nextDate: job.nextDate().toJSDate()
        });
      }
    }

    return result;
  }

  deleteBackup(jobId: string): void {
    const job = this.getBackupJob(jobId);
    const config = this.getBackupConfig(job.configId);

    const backupDir = path.join(this.backupPath, config.id, new Date(job.startTime).toISOString().replace(/[:.]/g, '-'));

    if (fs.existsSync(backupDir)) {
      fs.rmSync(backupDir, { recursive: true, force: true });
    }

    this.backups.delete(jobId);
  }

  getBackupFiles(configId: string): BackupFile[] {
    const configDir = path.join(this.backupPath, configId);
    if (!fs.existsSync(configDir)) {
      return [];
    }

    const files: BackupFile[] = [];
    const entries = fs.readdirSync(configDir);

    for (const entry of entries) {
      const entryPath = path.join(configDir, entry);
      const stat = fs.statSync(entryPath);

      if (stat.isDirectory()) {
        files.push({
          id: generateId('bfile'),
          jobId: entry,
          name: entry,
          path: entryPath,
          sizeBytes: this.getDirectorySize(entryPath),
          checksum: '',
          createdAt: stat.birthtime.toISOString()
        });
      }
    }

    return files.sort((a, b) => new Date(b.createdAt).getTime() - new Date(a.createdAt).getTime());
  }

  getStats(): StorageStats {
    const backupDirs = fs.existsSync(this.backupPath) ? fs.readdirSync(this.backupPath) : [];
    let totalSize = 0;
    let backupCount = 0;

    for (const dir of backupDirs) {
      const dirPath = path.join(this.backupPath, dir);
      const stat = fs.statSync(dirPath);

      if (stat.isDirectory()) {
        const backups = fs.readdirSync(dirPath);
        backupCount += backups.length;

        for (const backup of backups) {
          totalSize += this.getDirectorySize(path.join(dirPath, backup));
        }
      }
    }

    const diskStats = fs.statSync(this.backupPath);
    const freeSpace = 1024 * 1024 * 1024 * 10;

    return {
      totalSizeBytes: freeSpace,
      usedSizeBytes: totalSize,
      freeSizeBytes: freeSpace - totalSize,
      backupCount,
      totalBackupSizeBytes: totalSize
    };
  }

  private getDirectorySize(dirPath: string): number {
    if (!fs.existsSync(dirPath)) return 0;

    let totalSize = 0;
    const entries = fs.readdirSync(dirPath);

    for (const entry of entries) {
      const entryPath = path.join(dirPath, entry);
      const stat = fs.statSync(entryPath);

      if (stat.isDirectory()) {
        totalSize += this.getDirectorySize(entryPath);
      } else {
        totalSize += stat.size;
      }
    }

    return totalSize;
  }

  cleanupOldBackups(configId: string, retentionDays: number): number {
    const config = this.getBackupConfig(configId);
    const configDir = path.join(this.backupPath, configId);

    if (!fs.existsSync(configDir)) {
      return 0;
    }

    const cutoffTime = Date.now() - retentionDays * 24 * 60 * 60 * 1000;
    let deletedCount = 0;

    const entries = fs.readdirSync(configDir);
    for (const entry of entries) {
      const entryPath = path.join(configDir, entry);
      const stat = fs.statSync(entryPath);

      if (stat.birthtime.getTime() < cutoffTime) {
        fs.rmSync(entryPath, { recursive: true, force: true });
        deletedCount++;

        const backupJob = Array.from(this.backups.values()).find(
          job => job.configId === configId && new Date(job.startTime).toISOString().replace(/[:.]/g, '-') === entry
        );

        if (backupJob) {
          this.backups.delete(backupJob.id);
        }
      }
    }

    return deletedCount;
  }

  destroy(): void {
    for (const job of this.scheduledJobs.values()) {
      job.stop();
    }
    this.scheduledJobs.clear();
  }
}
