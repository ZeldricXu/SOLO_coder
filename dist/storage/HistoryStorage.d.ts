import { RotationRecord, ValidationReport, DiffReport, NotificationMessage, ConfigValue, SyncResult } from '../types';
export type HistoryEvent = {
    type: 'rotation';
    data: RotationRecord;
} | {
    type: 'validation';
    data: ValidationReport;
} | {
    type: 'diff';
    data: DiffReport;
} | {
    type: 'notification';
    data: NotificationMessage;
    results: {
        channelId: string;
        success: boolean;
        error?: string;
    }[];
} | {
    type: 'sync';
    data: {
        item: {
            key: string;
            sourceEnvironment: string;
            targetEnvironments: string[];
        };
        results: SyncResult[];
        dryRun: boolean;
        operator: string;
    };
};
export declare class HistoryStorage {
    private dbPath;
    private db;
    constructor(dbPath: string);
    private init;
    private createTables;
    recordRotation(record: RotationRecord): Promise<void>;
    recordValidation(report: ValidationReport): Promise<void>;
    recordDiff(report: DiffReport): Promise<void>;
    recordNotification(message: NotificationMessage, results: {
        channelId: string;
        success: boolean;
        error?: string;
    }[]): Promise<void>;
    recordSync(item: {
        key: string;
        sourceEnvironment: string;
        targetEnvironments: string[];
    }, results: SyncResult[], dryRun: boolean, operator: string): Promise<void>;
    recordKeyValueChange(environment: string, keyPath: string, oldValue: ConfigValue | undefined, newValue: ConfigValue | undefined, operator: string, commitHash?: string): Promise<void>;
    getRotationHistory(filters?: {
        environment?: string;
        key?: string;
        status?: string;
        since?: number;
        until?: number;
        limit?: number;
    }): Promise<RotationRecord[]>;
    getValidationHistory(filters?: {
        environment?: string;
        since?: number;
        until?: number;
        limit?: number;
        invalidOnly?: boolean;
    }): Promise<ValidationReport[]>;
    getDiffHistory(filters?: {
        environmentA?: string;
        environmentB?: string;
        since?: number;
        until?: number;
        limit?: number;
    }): Promise<DiffReport[]>;
    getKeyValueHistory(filters: {
        environment: string;
        keyPath?: string;
        since?: number;
        until?: number;
        limit?: number;
    }): Promise<{
        environment: string;
        keyPath: string;
        oldValue: ConfigValue | undefined;
        newValue: ConfigValue | undefined;
        operator: string;
        changeType: string;
        commitHash: string | undefined;
        timestamp: number;
    }[]>;
    close(): void;
    getDbPath(): string;
}
