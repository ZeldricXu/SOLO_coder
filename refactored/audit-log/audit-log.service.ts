import * as crypto from 'crypto';
import { IAuditLogService, LogCreationParams, VerificationResult } from './interfaces';
import { IHashProvider, IPowMiner, ILogRepository, IChainVerifier } from './interfaces';
import { AuditLogEntry } from '../core/types';

export class AuditLogService implements IAuditLogService {
  constructor(
    private readonly hashProvider: IHashProvider,
    private readonly powMiner: IPowMiner,
    private readonly logRepository: ILogRepository,
    private readonly chainVerifier: IChainVerifier
  ) {}

  public createLog(params: LogCreationParams): AuditLogEntry {
    const previousHash = this.logRepository.count() > 0 
      ? this.logRepository.getLast()!.hash 
      : this.logRepository.getGenesisHash();

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

    const entryData = this.chainVerifier.getEntryData(entry);
    const difficulty = this.powMiner.getDifficulty();
    const { hash, nonce } = this.powMiner.mine(entryData, previousHash, difficulty);
    
    entry.hash = hash;
    entry.nonce = nonce;
    
    this.logRepository.add(entry);

    return entry;
  }

  public getLogs(): AuditLogEntry[] {
    return this.logRepository.getAll();
  }

  public getLogById(id: string): AuditLogEntry | undefined {
    return this.logRepository.getById(id);
  }

  public getLogsByUserId(userId: string): AuditLogEntry[] {
    return this.logRepository.getByUserId(userId);
  }

  public getLogsByResource(resourceType: string, resourceId?: string): AuditLogEntry[] {
    return this.logRepository.getByResource(resourceType, resourceId);
  }

  public getLogsByTimeRange(startTime: number, endTime: number): AuditLogEntry[] {
    return this.logRepository.getByTimeRange(startTime, endTime);
  }

  public verifyChain(): VerificationResult {
    const logs = this.logRepository.getAll();
    const genesisHash = this.logRepository.getGenesisHash();
    const difficulty = this.powMiner.getDifficulty();
    
    return this.chainVerifier.verifyChain(logs, genesisHash, difficulty);
  }

  public verifyEntry(entryId: string): boolean {
    const entry = this.logRepository.getById(entryId);
    if (!entry) return false;

    const logs = this.logRepository.getAll();
    const index = logs.findIndex(log => log.id === entryId);
    const previousHash = index === 0 
      ? this.logRepository.getGenesisHash() 
      : logs[index - 1].hash;
    const difficulty = this.powMiner.getDifficulty();

    return this.chainVerifier.verifyEntry(entry, previousHash, difficulty, this.hashProvider);
  }

  public exportChain(): string {
    return JSON.stringify({
      genesisHash: this.logRepository.getGenesisHash(),
      logs: this.logRepository.getAll(),
      difficulty: this.powMiner.getDifficulty()
    }, null, 2);
  }

  public importChain(data: string): boolean {
    try {
      const parsed = JSON.parse(data);
      this.logRepository.setGenesisHash(parsed.genesisHash);
      this.logRepository.clear();
      parsed.logs.forEach((log: AuditLogEntry) => this.logRepository.add(log));
      this.powMiner.setDifficulty(parsed.difficulty || 4);
      return true;
    } catch {
      return false;
    }
  }

  public getChainStats() {
    const logs = this.logRepository.getAll();
    return {
      totalEntries: logs.length,
      firstEntryTimestamp: logs[0]?.timestamp || 0,
      lastEntryTimestamp: logs[logs.length - 1]?.timestamp || 0,
      difficulty: this.powMiner.getDifficulty(),
      genesisHash: this.logRepository.getGenesisHash()
    };
  }

  public setDifficulty(difficulty: number): void {
    this.powMiner.setDifficulty(difficulty);
  }
}

export const createAuditLogService = (
  hashProvider: IHashProvider,
  powMiner: IPowMiner,
  logRepository: ILogRepository,
  chainVerifier: IChainVerifier
): AuditLogService => {
  return new AuditLogService(
    hashProvider,
    powMiner,
    logRepository,
    chainVerifier
  );
};
