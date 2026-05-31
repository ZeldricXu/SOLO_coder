export interface FirmwareVersion {
  version: string;
  checksum: string;
  size: number;
  releaseNotes: string;
  url: string;
  signature?: string;
  compatibleDevices: string[];
  releasedAt: string;
}

export interface OTABatch {
  id: string;
  firmwareVersion: string;
  deviceIds: string[];
  phase: number;
  status: 'pending' | 'in_progress' | 'paused' | 'completed' | 'rolled_back';
  rolloutPercentage: number;
  successCount: number;
  failedCount: number;
  startedAt?: string;
  completedAt?: string;
  autoRollbackThreshold: number;
}

export interface DeviceUpgradeStatus {
  deviceId: string;
  batchId: string;
  firmwareVersion: string;
  status: 'pending' | 'downloading' | 'installing' | 'completed' | 'failed' | 'rolled_back';
  progress: number;
  error?: string;
  startedAt?: string;
  completedAt?: string;
}

export interface DiffPatch {
  fromVersion: string;
  toVersion: string;
  checksum: string;
  size: number;
  url: string;
}

export interface OTAConfig {
  maxConcurrentUpgrades: number;
  downloadTimeout: number;
  installTimeout: number;
  rolloutPhases: number[];
  defaultRollbackThreshold: number;
  enableDiffUpdates: boolean;
  maxRollbackAttempts: number;
}

export interface UpgradeProgress {
  phase: string;
  percentage: number;
  details?: string;
}
