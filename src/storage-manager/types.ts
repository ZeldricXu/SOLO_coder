export interface BackupJob {
  id: string;
  type: 'full' | 'incremental';
  source: string;
  destination: string;
  status: 'pending' | 'running' | 'completed' | 'failed';
  progress: number;
  size?: number;
  checksum?: string;
  error?: string;
  created_at: string;
  started_at?: string;
  completed_at?: string;
}

export interface RestoreJob {
  id: string;
  backupId: string;
  source: string;
  destination: string;
  status: 'pending' | 'running' | 'completed' | 'failed';
  progress: number;
  error?: string;
  created_at: string;
  started_at?: string;
  completed_at?: string;
}

export interface StorageConfig {
  providers: StorageProviderConfig[];
  defaultProvider: string;
  backupSchedule?: string;
  retentionPolicy: {
    maxBackups: number;
    maxAgeDays: number;
  };
  encryption?: {
    enabled: boolean;
    algorithm: string;
    key?: string;
  };
}

export interface StorageProviderConfig {
  id: string;
  type: 'local' | 's3' | 'gcs' | 'azure';
  enabled: boolean;
  options: Record<string, unknown>;
}

export interface BackupInfo {
  id: string;
  type: 'full' | 'incremental';
  source: string;
  size: number;
  checksum: string;
  createdAt: string;
  provider: string;
}
