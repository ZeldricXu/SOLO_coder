import EventEmitter from 'eventemitter3';
import { v4 as uuidv4 } from 'uuid';
import crypto from 'crypto';
import {
  FirmwareVersion,
  OTABatch,
  DeviceUpgradeStatus,
  DiffPatch,
  OTAConfig,
  UpgradeProgress,
} from './types';

export class OTAUpgradeService extends EventEmitter {
  private firmwareVersions: Map<string, FirmwareVersion> = new Map();
  private batches: Map<string, OTABatch> = new Map();
  private deviceStatuses: Map<string, DeviceUpgradeStatus> = new Map();
  private diffPatches: Map<string, DiffPatch> = new Map();
  private activeUpgrades: Set<string> = new Set();

  constructor(private config: OTAConfig) {
    super();
  }

  addFirmwareVersion(
    version: Omit<FirmwareVersion, 'releasedAt'>
  ): FirmwareVersion {
    const firmware: FirmwareVersion = {
      ...version,
      releasedAt: new Date().toISOString(),
    };
    this.firmwareVersions.set(version.version, firmware);
    this.emit('firmware-added', firmware);
    return firmware;
  }

  getFirmwareVersion(version: string): FirmwareVersion | undefined {
    return this.firmwareVersions.get(version);
  }

  listFirmwareVersions(): FirmwareVersion[] {
    return Array.from(this.firmwareVersions.values()).sort((a, b) =>
      b.releasedAt.localeCompare(a.releasedAt)
    );
  }

  generateDiffPatch(fromVersion: string, toVersion: string): DiffPatch | null {
    const from = this.firmwareVersions.get(fromVersion);
    const to = this.firmwareVersions.get(toVersion);

    if (!from || !to) return null;

    const patchId = `${fromVersion}->${toVersion}`;
    const diffSize = Math.max(1, Math.floor(to.size * 0.3));

    const patch: DiffPatch = {
      fromVersion,
      toVersion,
      checksum: crypto.createHash('sha256').update(patchId).digest('hex'),
      size: diffSize,
      url: `/api/ota/patches/${patchId}`,
    };

    this.diffPatches.set(patchId, patch);
    this.emit('diff-patch-generated', patch);
    return patch;
  }

  createBatch(
    firmwareVersion: string,
    deviceIds: string[],
    autoRollbackThreshold?: number
  ): OTABatch | null {
    const firmware = this.firmwareVersions.get(firmwareVersion);
    if (!firmware) return null;

    const batch: OTABatch = {
      id: uuidv4(),
      firmwareVersion,
      deviceIds: [...deviceIds],
      phase: 0,
      status: 'pending',
      rolloutPercentage: 0,
      successCount: 0,
      failedCount: 0,
      autoRollbackThreshold: autoRollbackThreshold || this.config.defaultRollbackThreshold,
    };

    this.batches.set(batch.id, batch);

    for (const deviceId of deviceIds) {
      this.deviceStatuses.set(`${batch.id}:${deviceId}`, {
        deviceId,
        batchId: batch.id,
        firmwareVersion,
        status: 'pending',
        progress: 0,
      });
    }

    this.emit('batch-created', batch);
    return batch;
  }

  startBatch(batchId: string): boolean {
    const batch = this.batches.get(batchId);
    if (!batch || batch.status !== 'pending') return false;

    batch.status = 'in_progress';
    batch.startedAt = new Date().toISOString();
    this.emit('batch-started', batch);
    this.advancePhase(batchId);
    return true;
  }

  private advancePhase(batchId: string): void {
    const batch = this.batches.get(batchId);
    if (!batch || batch.status !== 'in_progress') return;

    const phases = this.config.rolloutPhases;
    if (batch.phase >= phases.length) {
      batch.status = 'completed';
      batch.completedAt = new Date().toISOString();
      this.emit('batch-completed', batch);
      return;
    }

    batch.rolloutPercentage = phases[batch.phase];
    batch.phase++;

    const devicesToUpgrade = Math.ceil((batch.deviceIds.length * batch.rolloutPercentage) / 100);
    const currentDevices = batch.deviceIds.slice(0, devicesToUpgrade);

    this.emit('phase-advanced', batch, currentDevices);

    for (const deviceId of currentDevices) {
      if (this.activeUpgrades.size < this.config.maxConcurrentUpgrades) {
        this.startDeviceUpgrade(batchId, deviceId);
      }
    }
  }

  private async startDeviceUpgrade(batchId: string, deviceId: string): Promise<void> {
    const statusKey = `${batchId}:${deviceId}`;
    const status = this.deviceStatuses.get(statusKey);
    if (!status || status.status !== 'pending') return;

    this.activeUpgrades.add(statusKey);
    status.status = 'downloading';
    status.startedAt = new Date().toISOString();
    this.emit('device-upgrade-started', status);

    try {
      await this.simulateProgress(status, 'downloading', 30);
      status.status = 'installing';
      await this.simulateProgress(status, 'installing', 70);
      status.status = 'completed';
      status.progress = 100;
      status.completedAt = new Date().toISOString();

      const batch = this.batches.get(batchId);
      if (batch) batch.successCount++;

      this.emit('device-upgrade-completed', status);
    } catch (error) {
      status.status = 'failed';
      status.error = error instanceof Error ? error.message : 'Unknown error';
      status.completedAt = new Date().toISOString();

      const batch = this.batches.get(batchId);
      if (batch) {
        batch.failedCount++;
        this.checkAutoRollback(batch);
      }

      this.emit('device-upgrade-failed', status);
    } finally {
      this.activeUpgrades.delete(statusKey);
      this.checkBatchProgress(batchId);
    }
  }

  private async simulateProgress(
    status: DeviceUpgradeStatus,
    phase: string,
    targetProgress: number
  ): Promise<void> {
    return new Promise((resolve, reject) => {
      const interval = setInterval(() => {
        status.progress += 10;
        this.emit('device-upgrade-progress', status, {
          phase,
          percentage: status.progress,
        });

        if (status.progress >= targetProgress) {
          clearInterval(interval);
          if (Math.random() < 0.05) {
            reject(new Error(`${phase} failed`));
          } else {
            resolve();
          }
        }
      }, 200);
    });
  }

  private checkBatchProgress(batchId: string): void {
    const batch = this.batches.get(batchId);
    if (!batch || batch.status !== 'in_progress') return;

    const total = batch.deviceIds.length;
    const processed = batch.successCount + batch.failedCount;

    if (processed >= Math.ceil((total * batch.rolloutPercentage) / 100)) {
      setTimeout(() => this.advancePhase(batchId), 5000);
    }
  }

  private checkAutoRollback(batch: OTABatch): void {
    if (batch.successCount + batch.failedCount === 0) return;

    const failureRate = batch.failedCount / (batch.successCount + batch.failedCount);
    if (failureRate > batch.autoRollbackThreshold) {
      this.rollbackBatch(batch.id, 'Failure rate exceeded threshold');
    }
  }

  rollbackBatch(batchId: string, reason: string): boolean {
    const batch = this.batches.get(batchId);
    if (!batch || batch.status === 'rolled_back' || batch.status === 'completed') return false;

    batch.status = 'rolled_back';
    batch.completedAt = new Date().toISOString();

    for (const deviceId of batch.deviceIds) {
      const statusKey = `${batchId}:${deviceId}`;
      const status = this.deviceStatuses.get(statusKey);
      if (status && (status.status === 'completed' || status.status === 'failed')) {
        status.status = 'rolled_back';
      }
    }

    this.emit('batch-rolled-back', batch, reason);
    return true;
  }

  pauseBatch(batchId: string): boolean {
    const batch = this.batches.get(batchId);
    if (!batch || batch.status !== 'in_progress') return false;
    batch.status = 'paused';
    this.emit('batch-paused', batch);
    return true;
  }

  resumeBatch(batchId: string): boolean {
    const batch = this.batches.get(batchId);
    if (!batch || batch.status !== 'paused') return false;
    batch.status = 'in_progress';
    this.emit('batch-resumed', batch);
    this.advancePhase(batchId);
    return true;
  }

  getBatch(batchId: string): OTABatch | undefined {
    return this.batches.get(batchId);
  }

  listBatches(): OTABatch[] {
    return Array.from(this.batches.values());
  }

  getDeviceStatus(batchId: string, deviceId: string): DeviceUpgradeStatus | undefined {
    return this.deviceStatuses.get(`${batchId}:${deviceId}`);
  }

  getBatchDeviceStatuses(batchId: string): DeviceUpgradeStatus[] {
    return Array.from(this.deviceStatuses.values()).filter(s => s.batchId === batchId);
  }

  getDiffPatch(fromVersion: string, toVersion: string): DiffPatch | undefined {
    return this.diffPatches.get(`${fromVersion}->${toVersion}`);
  }

  getCompatibleVersions(deviceType: string): FirmwareVersion[] {
    return Array.from(this.firmwareVersions.values()).filter(v =>
      v.compatibleDevices.includes(deviceType)
    );
  }

  destroy(): void {
    this.removeAllListeners();
  }
}

export * from './types';
