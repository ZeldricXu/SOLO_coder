export interface DeviceShadow {
  deviceId: string;
  desired: Record<string, unknown>;
  reported: Record<string, unknown>;
  delta: Record<string, unknown>;
  version: number;
  timestamp: string;
  lastReportedAt?: string;
  lastDesiredAt?: string;
}

export interface ShadowUpdate {
  deviceId: string;
  type: 'desired' | 'reported';
  state: Record<string, unknown>;
  version?: number;
}

export interface ShadowDiff {
  added: string[];
  removed: string[];
  updated: string[];
}

export interface DeviceShadowConfig {
  syncInterval: number;
  maxHistorySize: number;
  enableDeltaCalculation: boolean;
  conflictResolution: 'last-write-wins' | 'merge' | 'reject';
}

export interface ShadowHistoryEntry {
  deviceId: string;
  version: number;
  desired: Record<string, unknown>;
  reported: Record<string, unknown>;
  timestamp: string;
}
