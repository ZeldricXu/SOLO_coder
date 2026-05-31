import { AuditLogEntry, HashChainLink, ModuleResult } from '../../types';
interface AuditLogConfig {
    difficulty?: number;
    hashAlgorithm?: 'sha256';
    chainRetentionDays?: number;
}
interface VerifyResult {
    valid: boolean;
    firstInvalidIndex?: number;
    error?: string;
    totalLinks: number;
    validLinks: number;
}
export declare class AuditLogTamperProtection {
    private chain;
    private difficulty;
    private entries;
    constructor(config?: AuditLogConfig);
    log(entry: AuditLogEntry): ModuleResult<HashChainLink>;
    logBatch(entries: AuditLogEntry[]): ModuleResult<HashChainLink[]>;
    verifyChain(): ModuleResult<VerifyResult>;
    verifyEntry(entryId: string): ModuleResult<{
        valid: boolean;
        inChain: boolean;
    }>;
    getChain(): ModuleResult<HashChainLink[]>;
    getEntry(entryId: string): ModuleResult<AuditLogEntry | null>;
    getEntriesByUser(userId: string, limit?: number): ModuleResult<AuditLogEntry[]>;
    getEntriesByResource(resourceType: string, resourceId: string): ModuleResult<AuditLogEntry[]>;
    getChainStats(): ModuleResult<{
        totalEntries: number;
        chainLength: number;
        lastEntryTime: string | null;
        firstEntryTime: string | null;
    }>;
    private calculateEntryHash;
    private getGenesisHash;
    private calculateLinkHash;
    private mineBlock;
    private validateLink;
    private validateProofOfWork;
    exportChain(): ModuleResult<string>;
    importChain(data: string): ModuleResult<boolean>;
    createEntry(data: Omit<AuditLogEntry, 'id' | 'timestamp'>): AuditLogEntry;
}
export {};
