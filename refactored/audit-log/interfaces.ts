import { AuditLogEntry } from '../core/types';

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

export interface IHashProvider {
  calculateHash(data: string, nonce: number, previousHash: string): string;
  hashMeetsDifficulty(hash: string, difficulty: number): boolean;
}

export interface IPowMiner {
  mine(entryData: string, previousHash: string, difficulty: number): { hash: string; nonce: number };
  setDifficulty(difficulty: number): void;
  getDifficulty(): number;
}

export interface ILogRepository {
  add(entry: AuditLogEntry): void;
  getAll(): AuditLogEntry[];
  getById(id: string): AuditLogEntry | undefined;
  getByUserId(userId: string): AuditLogEntry[];
  getByResource(resourceType: string, resourceId?: string): AuditLogEntry[];
  getByTimeRange(startTime: number, endTime: number): AuditLogEntry[];
  getLast(): AuditLogEntry | undefined;
  count(): number;
  clear(): void;
  setGenesisHash(hash: string): void;
  getGenesisHash(): string;
}

export interface IChainVerifier {
  verifyChain(logs: AuditLogEntry[], genesisHash: string, difficulty: number): VerificationResult;
  verifyEntry(entry: AuditLogEntry, previousHash: string, difficulty: number, hashProvider: IHashProvider): boolean;
  getEntryData(entry: AuditLogEntry): string;
}

export interface IAuditLogService {
  createLog(params: LogCreationParams): AuditLogEntry;
  getLogs(): AuditLogEntry[];
  getLogById(id: string): AuditLogEntry | undefined;
  getLogsByUserId(userId: string): AuditLogEntry[];
  getLogsByResource(resourceType: string, resourceId?: string): AuditLogEntry[];
  getLogsByTimeRange(startTime: number, endTime: number): AuditLogEntry[];
  verifyChain(): VerificationResult;
  verifyEntry(entryId: string): boolean;
  exportChain(): string;
  importChain(data: string): boolean;
  getChainStats(): {
    totalEntries: number;
    firstEntryTimestamp: number;
    lastEntryTimestamp: number;
    difficulty: number;
    genesisHash: string;
  };
  setDifficulty(difficulty: number): void;
}
