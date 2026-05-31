import { IChainVerifier, IHashProvider, VerificationResult } from './interfaces';
import { AuditLogEntry } from '../core/types';

export class ChainVerifier implements IChainVerifier {
  constructor(private readonly hashProvider: IHashProvider) {}

  public verifyChain(logs: AuditLogEntry[], genesisHash: string, difficulty: number): VerificationResult {
    const tamperedEntries: string[] = [];
    let verifiedCount = 0;

    for (let i = 0; i < logs.length; i++) {
      const current = logs[i];
      const previousHash = i === 0 ? genesisHash : logs[i - 1].hash;

      if (current.previousHash !== previousHash) {
        tamperedEntries.push(current.id);
        continue;
      }

      const entryData = this.getEntryData(current);
      const calculatedHash = this.hashProvider.calculateHash(entryData, current.nonce, current.previousHash);

      if (calculatedHash !== current.hash) {
        tamperedEntries.push(current.id);
        continue;
      }

      if (!this.hashProvider.hashMeetsDifficulty(current.hash, difficulty)) {
        tamperedEntries.push(current.id);
        continue;
      }

      verifiedCount++;
    }

    return {
      isValid: tamperedEntries.length === 0,
      tamperedEntries,
      verifiedCount,
      totalCount: logs.length
    };
  }

  public verifyEntry(
    entry: AuditLogEntry, 
    previousHash: string, 
    difficulty: number,
    hashProvider: IHashProvider
  ): boolean {
    if (entry.previousHash !== previousHash) return false;

    const entryData = this.getEntryData(entry);
    const calculatedHash = hashProvider.calculateHash(entryData, entry.nonce, entry.previousHash);

    return calculatedHash === entry.hash && hashProvider.hashMeetsDifficulty(entry.hash, difficulty);
  }

  public getEntryData(entry: AuditLogEntry): string {
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
}

export const createChainVerifier = (hashProvider: IHashProvider): ChainVerifier => {
  return new ChainVerifier(hashProvider);
};
