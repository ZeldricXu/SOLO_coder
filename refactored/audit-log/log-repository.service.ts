import * as crypto from 'crypto';
import { ILogRepository } from './interfaces';
import { AuditLogEntry } from '../core/types';

export class LogRepository implements ILogRepository {
  private logs: AuditLogEntry[] = [];
  private genesisHash: string = '';

  constructor() {
    this.genesisHash = this.generateGenesisHash();
  }

  public add(entry: AuditLogEntry): void {
    this.logs.push(entry);
  }

  public getAll(): AuditLogEntry[] {
    return [...this.logs];
  }

  public getById(id: string): AuditLogEntry | undefined {
    return this.logs.find(log => log.id === id);
  }

  public getByUserId(userId: string): AuditLogEntry[] {
    return this.logs.filter(log => log.userId === userId);
  }

  public getByResource(resourceType: string, resourceId?: string): AuditLogEntry[] {
    return this.logs.filter(log => 
      log.resourceType === resourceType && 
      (!resourceId || log.resourceId === resourceId)
    );
  }

  public getByTimeRange(startTime: number, endTime: number): AuditLogEntry[] {
    return this.logs.filter(log => 
      log.timestamp >= startTime && log.timestamp <= endTime
    );
  }

  public getLast(): AuditLogEntry | undefined {
    return this.logs[this.logs.length - 1];
  }

  public count(): number {
    return this.logs.length;
  }

  public clear(): void {
    this.logs = [];
  }

  public setGenesisHash(hash: string): void {
    this.genesisHash = hash;
  }

  public getGenesisHash(): string {
    return this.genesisHash;
  }

  private generateGenesisHash(): string {
    return crypto
      .createHash('sha256')
      .update('genesis' + 0 + '0')
      .digest('hex');
  }
}

export const createLogRepository = (): LogRepository => {
  return new LogRepository();
};
