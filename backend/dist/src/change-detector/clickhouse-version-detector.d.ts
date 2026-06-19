import { BaseChangeDetector } from './base-detector';
interface ClickHouseConfig {
    host: string;
    port: number;
    database: string;
    username: string;
    password: string;
    versionField?: string;
    watchedTables?: string[];
}
export declare class ClickHouseVersionDetector extends BaseChangeDetector {
    private readonly dataSourceId;
    private readonly config;
    private readonly logger;
    private readonly checkIntervalMs;
    private timer;
    private lastVersions;
    private client;
    private readonly versionField;
    constructor(dataSourceId: string, config: ClickHouseConfig);
    start(): Promise<void>;
    private initializeTables;
    private initializeVersions;
    private checkForChanges;
    private fetchChangedRows;
    private emitRowChange;
    private extractPrimaryKey;
    stop(): Promise<void>;
}
export {};
