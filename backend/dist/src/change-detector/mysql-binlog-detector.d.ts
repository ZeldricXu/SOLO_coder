import { BaseChangeDetector } from './base-detector';
interface MysqlConfig {
    host: string;
    port: number;
    database: string;
    username: string;
    password: string;
}
export declare class MysqlBinlogDetector extends BaseChangeDetector {
    private readonly dataSourceId;
    private readonly config;
    private readonly logger;
    private zongji;
    private reconnectAttempts;
    private readonly maxReconnectDelayMs;
    private readonly baseReconnectDelayMs;
    private reconnectTimer;
    constructor(dataSourceId: string, config: MysqlConfig);
    start(): Promise<void>;
    private connect;
    private handleRowsEvent;
    private extractPrimaryKey;
    private scheduleReconnect;
    private cleanupZongji;
    stop(): Promise<void>;
}
export {};
