export interface BackupConfig {
  id: string;
  name: string;
  source: BackupSource;
  destination: BackupDestination;
  schedule?: string;
  retention: RetentionPolicy;
  compression: CompressionConfig;
  encryption?: EncryptionConfig;
  status: 'enabled' | 'disabled' | 'paused';
  createdAt: string;
  updatedAt: string;
}

export interface BackupSource {
  type: 'database' | 'file' | 'volume' | 'cloud';
  config: DatabaseSourceConfig | FileSourceConfig | CloudSourceConfig;
}

export interface DatabaseSourceConfig {
  connectionString: string;
  databaseName: string;
  tables?: string[];
  excludeTables?: string[];
}

export interface FileSourceConfig {
  path: string;
  includePatterns?: string[];
  excludePatterns?: string[];
}

export interface CloudSourceConfig {
  provider: 'aws' | 'gcp' | 'azure';
  bucket: string;
  prefix?: string;
  credentials?: Record<string, string>;
}

export interface BackupDestination {
  type: 'local' | 's3' | 'gcs' | 'azure_blob';
  config: LocalDestinationConfig | CloudDestinationConfig;
}

export interface LocalDestinationConfig {
  path: string;
}

export interface CloudDestinationConfig {
  provider: 'aws' | 'gcp' | 'azure';
  bucket: string;
  prefix?: string;
  credentials?: Record<string, string>;
}

export interface RetentionPolicy {
  days: number;
  maxBackups?: number;
}

export interface CompressionConfig {
  enabled: boolean;
  algorithm: 'gzip' | 'zip' | 'tar.gz' | 'none';
  level?: number;
}

export interface EncryptionConfig {
  enabled: boolean;
  algorithm: 'aes-256-cbc' | 'aes-256-gcm';
  keyId: string;
}

export interface BackupJob {
  id: string;
  configId: string;
  status: 'pending' | 'running' | 'completed' | 'failed' | 'cancelled';
  type: 'full' | 'incremental' | 'differential';
  startTime: string;
  endTime?: string;
  sizeBytes?: number;
  error?: string;
  filesBackedUp?: number;
  createdAt: string;
}

export interface RestoreJob {
  id: string;
  backupId: string;
  backupConfigId: string;
  status: 'pending' | 'running' | 'completed' | 'failed' | 'cancelled';
  target: BackupSource;
  startTime: string;
  endTime?: string;
  error?: string;
  filesRestored?: number;
  createdAt: string;
}

export interface BackupFile {
  id: string;
  jobId: string;
  name: string;
  path: string;
  sizeBytes: number;
  checksum: string;
  createdAt: string;
}

export interface StorageStats {
  totalSizeBytes: number;
  usedSizeBytes: number;
  freeSizeBytes: number;
  backupCount: number;
  totalBackupSizeBytes: number;
}
