export interface ImageLayer {
  digest: string;
  size: number;
  mediaType: string;
  urls: string[];
  downloaded: boolean;
  downloadProgress: number;
}

export interface ImageManifest {
  schemaVersion: number;
  mediaType: string;
  config: {
    digest: string;
    size: number;
    mediaType: string;
  };
  layers: ImageLayer[];
  annotations?: Record<string, string>;
}

export interface RegistryConfig {
  url: string;
  username?: string;
  password?: string;
  insecure?: boolean;
}

export interface P2PPeer {
  id: string;
  address: string;
  availableLayers: Set<string>;
  bandwidth: number;
  lastSeen: string;
}

export interface ImageDistributeTask {
  taskId: string;
  imageName: string;
  tag: string;
  sourceRegistry: RegistryConfig;
  targetRegistries: RegistryConfig[];
  manifest?: ImageManifest;
  status: 'pending' | 'downloading' | 'distributing' | 'completed' | 'failed';
  progress: number;
  layers: ImageLayer[];
  startTime: string;
  endTime?: string;
  error?: string;
}

export interface SyncStatus {
  totalLayers: number;
  downloadedLayers: number;
  uploadedLayers: number;
  peers: number;
  bandwidthUsage: number;
}

export interface PersistenceConfig {
  dataDir: string;
  snapshotInterval: number;
  autoRecover: boolean;
  maxSnapshots: number;
}

export interface LayerIndexEntry {
  digest: string;
  size: number;
  filePath: string;
  createdAt: string;
  lastAccessed: string;
  accessCount: number;
}

export interface RecoveryReport {
  recoveredTasks: number;
  recoveredLayers: number;
  failedRecoveries: number;
  details: Array<{ taskId: string; status: string; error?: string }>;
}
