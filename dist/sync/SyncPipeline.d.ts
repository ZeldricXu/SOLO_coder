import { ConfigManager } from '../sources/ConfigManager';
import { SyncItem, SyncPreview, SyncResult, DiffItem } from '../types';
import { SchemaValidator } from '../schemas/SchemaValidator';
export interface SyncOptions {
    dryRun?: boolean;
    validateBefore?: boolean;
    verifyAfter?: boolean;
    validator?: SchemaValidator;
    onPreview?: (previews: SyncPreview[]) => void;
    onProgress?: (key: string, targetEnv: string, done: number, total: number) => void;
}
export interface BatchSyncResult {
    previews: SyncPreview[];
    results: SyncResult[];
    skipped: SyncPreview[];
    summary: {
        total: number;
        success: number;
        failed: number;
        skipped: number;
        verified: number;
    };
}
export declare class SyncPipeline {
    private configManager;
    private diffEngine;
    constructor(configManager: ConfigManager);
    previewSync(item: SyncItem): Promise<SyncPreview[]>;
    previewBatch(items: SyncItem[]): Promise<SyncPreview[]>;
    executeSync(item: SyncItem, options?: SyncOptions): Promise<SyncResult[]>;
    executeBatch(items: SyncItem[], options?: SyncOptions): Promise<BatchSyncResult>;
    generateDiffFromPreviews(previews: SyncPreview[], sourceEnvName: string, targetEnvName: string): DiffItem[];
    private calculatePercent;
    formatPreviews(previews: SyncPreview[]): string;
    private formatVal;
}
