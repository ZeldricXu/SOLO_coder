import * as crypto from 'crypto';
import { AuditLogEntry } from './types';

export interface LogCreationParams {
  userId: string;
  action: string;
  resourceType: string;
  resourceId: string;
  details?: Record<string, unknown>;
}

export interface VerificationResult {
  isValid: boolean;
  tamperedEntries: string[];
  verifiedCount: number;
  totalCount: number;
}

export interface AuditLogConfig {
  maxEntries?: number;
  archiveEnabled?: boolean;
}

export class AuditLogModule {
  private logs: AuditLogEntry[] = [];
  private archivedLogs: AuditLogEntry[][] = [];
  private genesisHash: string;
  private difficulty: number = 4;
  private maxEntries: number;
  private archiveEnabled: boolean;

  constructor(config?: AuditLogConfig) {
    this.maxEntries = config?.maxEntries ?? 10000;
    this.archiveEnabled = config?.archiveEnabled ?? true;
    this.genesisHash = this.calculateHash('genesis', 0, '0');
  }

  public createLog(params: LogCreationParams): AuditLogEntry {
    const previousHash = this.logs.length > 0 
      ? this.logs[this.logs.length - 1].hash 
      : this.genesisHash;

    const entry: AuditLogEntry = {
      id: crypto.randomUUID(),
      timestamp: Date.now(),
      userId: params.userId,
      action: params.action,
      resourceType: params.resourceType,
      resourceId: params.resourceId,
      details: params.details || {},
      previousHash,
      hash: '',
      nonce: 0
    };

    entry.hash = this.mineBlock(entry);
    this.logs.push(entry);

    this.rotateIfNeeded();

    return entry;
  }

  public getLogs(): AuditLogEntry[] {
    return [...this.logs];
  }

  public getLogById(id: string): AuditLogEntry | undefined {
    return this.logs.find(log => log.id === id);
  }

  public getLogsByUserId(userId: string): AuditLogEntry[] {
    return this.logs.filter(log => log.userId === userId);
  }

  public getLogsByResource(resourceType: string, resourceId?: string): AuditLogEntry[] {
    return this.logs.filter(log => 
      log.resourceType === resourceType && 
      (!resourceId || log.resourceId === resourceId)
    );
  }

  public getLogsByTimeRange(startTime: number, endTime: number): AuditLogEntry[] {
    return this.logs.filter(log => 
      log.timestamp >= startTime && log.timestamp <= endTime
    );
  }

  public verifyChain(): VerificationResult {
    const tamperedEntries: string[] = [];
    let verifiedCount = 0;

    for (let i = 0; i < this.logs.length; i++) {
      const current = this.logs[i];
      const previousHash = i === 0 ? this.genesisHash : this.logs[i - 1].hash;

      if (current.previousHash !== previousHash) {
        tamperedEntries.push(current.id);
        continue;
      }

      const calculatedHash = this.calculateHash(
        this.getEntryData(current),
        current.nonce,
        current.previousHash
      );

      if (calculatedHash !== current.hash) {
        tamperedEntries.push(current.id);
        continue;
      }

      if (!this.hashMeetsDifficulty(current.hash)) {
        tamperedEntries.push(current.id);
        continue;
      }

      verifiedCount++;
    }

    return {
      isValid: tamperedEntries.length === 0,
      tamperedEntries,
      verifiedCount,
      totalCount: this.logs.length
    };
  }

  public verifyEntry(entryId: string): boolean {
    const entry = this.logs.find(log => log.id === entryId);
    if (!entry) return false;

    const index = this.logs.findIndex(log => log.id === entryId);
    const previousHash = index === 0 ? this.genesisHash : this.logs[index - 1].hash;

    if (entry.previousHash !== previousHash) return false;

    const calculatedHash = this.calculateHash(
      this.getEntryData(entry),
      entry.nonce,
      entry.previousHash
    );

    return calculatedHash === entry.hash && this.hashMeetsDifficulty(entry.hash);
  }

  public exportChain(): string {
    return JSON.stringify({
      genesisHash: this.genesisHash,
      logs: this.logs,
      difficulty: this.difficulty
    }, null, 2);
  }

  public importChain(data: string): boolean {
    try {
      const parsed = JSON.parse(data);
      this.genesisHash = parsed.genesisHash;
      this.logs = parsed.logs;
      this.difficulty = parsed.difficulty || 4;
      return true;
    } catch {
      return false;
    }
  }

  public getChainStats() {
    return {
      totalEntries: this.logs.length,
      firstEntryTimestamp: this.logs[0]?.timestamp || 0,
      lastEntryTimestamp: this.logs[this.logs.length - 1]?.timestamp || 0,
      difficulty: this.difficulty,
      genesisHash: this.genesisHash
    };
  }

  private getEntryData(entry: AuditLogEntry): string {
    return JSON.stringify({
      id: entry.id,
      timestamp: entry.timestamp,
      userId: entry.userId,
      action: entry.action,
      resourceType: entry.resourceType,
      resourceId: entry.resourceId,
      details: entry.details
    });
  }

  private calculateHash(data: string, nonce: number, previousHash: string): string {
    return crypto
      .createHash('sha256')
      .update(data + nonce + previousHash)
      .digest('hex');
  }

  private hashMeetsDifficulty(hash: string): boolean {
    const prefix = '0'.repeat(this.difficulty);
    return hash.startsWith(prefix);
  }

  private mineBlock(entry: AuditLogEntry): string {
    let nonce = 0;
    const data = this.getEntryData(entry);
    let hash: string;

    do {
      hash = this.calculateHash(data, nonce, entry.previousHash);
      nonce++;
    } while (!this.hashMeetsDifficulty(hash));

    entry.nonce = nonce - 1;
    return hash;
  }

  public setDifficulty(difficulty: number): void {
    this.difficulty = Math.max(1, Math.min(difficulty, 8));
  }

  public setMaxEntries(max: number): void {
    this.maxEntries = Math.max(1, max);
    this.rotateIfNeeded();
  }

  public getMaxEntries(): number {
    return this.maxEntries;
  }

  public getArchivedLogCount(): number {
    return this.archivedLogs.length;
  }

  public getArchivedLogs(index: number): AuditLogEntry[] | null {
    return this.archivedLogs[index] || null;
  }

  public clearArchivedLogs(): number {
    const count = this.archivedLogs.length;
    this.archivedLogs = [];
    return count;
  }

  public getTotalLogCount(): number {
    const archivedCount = this.archivedLogs.reduce((sum, archive) => sum + archive.length, 0);
    return this.logs.length + archivedCount;
  }

  private rotateIfNeeded(): void {
    while (this.logs.length > this.maxEntries) {
      const overflow = this.logs.length - this.maxEntries;
      const trimCount = Math.min(overflow, this.maxEntries);
      const trimmed = this.logs.splice(0, trimCount);

      if (this.archiveEnabled) {
        this.archivedLogs.push(trimmed);
      }
    }

    if (this.logs.length > 0 && this.logs[0].previousHash !== this.genesisHash) {
      this.rebuildChain();
    }
  }

  private rebuildChain(): void {
    for (let i = 0; i < this.logs.length; i++) {
      const previousHash = i === 0 ? this.genesisHash : this.logs[i - 1].hash;
      this.logs[i].previousHash = previousHash;
      this.logs[i].hash = this.mineBlock(this.logs[i]);
    }
  }
}

export const createAuditLog = (config?: AuditLogConfig): AuditLogModule => {
  return new AuditLogModule(config);
};
