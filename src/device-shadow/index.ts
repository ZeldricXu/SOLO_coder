import EventEmitter from 'eventemitter3';
import { DeviceShadow, ShadowUpdate, ShadowDiff, DeviceShadowConfig, ShadowHistoryEntry } from './types';

export class DeviceShadowService extends EventEmitter {
  private shadows: Map<string, DeviceShadow> = new Map();
  private history: Map<string, ShadowHistoryEntry[]> = new Map();
  private syncTimer?: NodeJS.Timeout;

  constructor(private config: DeviceShadowConfig) {
    super();
    this.startAutoSync();
  }

  createShadow(deviceId: string, initialState?: Record<string, unknown>): DeviceShadow {
    const now = new Date().toISOString();
    const shadow: DeviceShadow = {
      deviceId,
      desired: {},
      reported: initialState || {},
      delta: {},
      version: 1,
      timestamp: now,
      lastReportedAt: now,
      lastDesiredAt: now,
    };

    this.shadows.set(deviceId, shadow);
    this.addToHistory(shadow);
    this.emit('shadow-created', shadow);

    return shadow;
  }

  getShadow(deviceId: string): DeviceShadow | undefined {
    return this.shadows.get(deviceId);
  }

  deleteShadow(deviceId: string): boolean {
    const deleted = this.shadows.delete(deviceId);
    if (deleted) {
      this.history.delete(deviceId);
      this.emit('shadow-deleted', deviceId);
    }
    return deleted;
  }

  updateDesired(deviceId: string, state: Record<string, unknown>): DeviceShadow | null {
    const shadow = this.shadows.get(deviceId);
    if (!shadow) return null;

    shadow.desired = { ...shadow.desired, ...state };
    shadow.version++;
    shadow.timestamp = new Date().toISOString();
    shadow.lastDesiredAt = shadow.timestamp;

    if (this.config.enableDeltaCalculation) {
      shadow.delta = this.calculateDelta(shadow.desired, shadow.reported);
    }

    this.addToHistory(shadow);
    this.emit('desired-updated', shadow, state);
    this.emit('shadow-updated', shadow);

    if (Object.keys(shadow.delta).length > 0) {
      this.emit('delta-available', shadow);
    }

    return shadow;
  }

  updateReported(deviceId: string, state: Record<string, unknown>): DeviceShadow | null {
    const shadow = this.shadows.get(deviceId);
    if (!shadow) return null;

    shadow.reported = { ...shadow.reported, ...state };
    shadow.version++;
    shadow.timestamp = new Date().toISOString();
    shadow.lastReportedAt = shadow.timestamp;

    if (this.config.enableDeltaCalculation) {
      shadow.delta = this.calculateDelta(shadow.desired, shadow.reported);
    }

    this.addToHistory(shadow);
    this.emit('reported-updated', shadow, state);
    this.emit('shadow-updated', shadow);

    if (Object.keys(shadow.delta).length === 0) {
      this.emit('in-sync', shadow);
    }

    return shadow;
  }

  mergeState(deviceId: string, desired: Record<string, unknown>, reported: Record<string, unknown>): DeviceShadow | null {
    const shadow = this.shadows.get(deviceId);
    if (!shadow) return null;

    shadow.desired = { ...shadow.desired, ...desired };
    shadow.reported = { ...shadow.reported, ...reported };
    shadow.version++;
    shadow.timestamp = new Date().toISOString();

    if (this.config.enableDeltaCalculation) {
      shadow.delta = this.calculateDelta(shadow.desired, shadow.reported);
    }

    this.addToHistory(shadow);
    this.emit('shadow-updated', shadow);

    return shadow;
  }

  private calculateDelta(desired: Record<string, unknown>, reported: Record<string, unknown>): Record<string, unknown> {
    const delta: Record<string, unknown> = {};

    for (const [key, value] of Object.entries(desired)) {
      if (!this.deepEqual(value, reported[key])) {
        delta[key] = value;
      }
    }

    return delta;
  }

  private deepEqual(a: unknown, b: unknown): boolean {
    if (a === b) return true;
    if (typeof a !== typeof b) return false;
    if (typeof a !== 'object' || a === null || b === null) return false;

    const objA = a as Record<string, unknown>;
    const objB = b as Record<string, unknown>;

    const keysA = Object.keys(objA);
    const keysB = Object.keys(objB);

    if (keysA.length !== keysB.length) return false;

    for (const key of keysA) {
      if (!keysB.includes(key) || !this.deepEqual(objA[key], objB[key])) {
        return false;
      }
    }

    return true;
  }

  getDiff(oldState: Record<string, unknown>, newState: Record<string, unknown>): ShadowDiff {
    const added: string[] = [];
    const removed: string[] = [];
    const updated: string[] = [];

    for (const key of Object.keys(newState)) {
      if (!(key in oldState)) {
        added.push(key);
      } else if (!this.deepEqual(oldState[key], newState[key])) {
        updated.push(key);
      }
    }

    for (const key of Object.keys(oldState)) {
      if (!(key in newState)) {
        removed.push(key);
      }
    }

    return { added, removed, updated };
  }

  isInSync(deviceId: string): boolean {
    const shadow = this.shadows.get(deviceId);
    if (!shadow) return true;
    return Object.keys(shadow.delta).length === 0;
  }

  getHistory(deviceId: string, limit?: number): ShadowHistoryEntry[] {
    const history = this.history.get(deviceId) || [];
    return limit ? history.slice(-limit) : history;
  }

  rollback(deviceId: string, version: number): DeviceShadow | null {
    const history = this.history.get(deviceId) || [];
    const targetVersion = history.find(h => h.version === version);

    if (!targetVersion) return null;

    const shadow: DeviceShadow = {
      deviceId,
      desired: targetVersion.desired,
      reported: targetVersion.reported,
      delta: this.calculateDelta(targetVersion.desired, targetVersion.reported),
      version: this.getCurrentVersion(deviceId) + 1,
      timestamp: new Date().toISOString(),
    };

    this.shadows.set(deviceId, shadow);
    this.addToHistory(shadow);
    this.emit('shadow-rolled-back', shadow, version);

    return shadow;
  }

  private getCurrentVersion(deviceId: string): number {
    const shadow = this.shadows.get(deviceId);
    return shadow?.version || 0;
  }

  private addToHistory(shadow: DeviceShadow): void {
    const history = this.history.get(shadow.deviceId) || [];
    history.push({
      deviceId: shadow.deviceId,
      version: shadow.version,
      desired: { ...shadow.desired },
      reported: { ...shadow.reported },
      timestamp: shadow.timestamp,
    });

    if (history.length > this.config.maxHistorySize) {
      history.shift();
    }

    this.history.set(shadow.deviceId, history);
  }

  private startAutoSync(): void {
    this.syncTimer = setInterval(() => {
      for (const [deviceId, shadow] of this.shadows.entries()) {
        if (Object.keys(shadow.delta).length > 0) {
          this.emit('sync-required', shadow);
        }
      }
    }, this.config.syncInterval);
  }

  listDevices(): string[] {
    return Array.from(this.shadows.keys());
  }

  getOutSyncDevices(): DeviceShadow[] {
    return Array.from(this.shadows.values()).filter(s => Object.keys(s.delta).length > 0);
  }

  destroy(): void {
    if (this.syncTimer) {
      clearInterval(this.syncTimer);
    }
    this.removeAllListeners();
  }
}

export * from './types';
