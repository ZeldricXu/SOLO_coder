import { BaseConnector, QueryResult, SchemaTable } from './base.connector';
interface ClickHouseConfig {
    host: string;
    port: number;
    database: string;
    username: string;
    password: string;
    queryTimeout?: number;
}
export declare class ClickHouseConnector extends BaseConnector {
    private client;
    private readonly config;
    constructor(config: ClickHouseConfig);
    connect(): Promise<void>;
    query(sql: string, params?: any[]): Promise<QueryResult>;
    testConnection(): Promise<boolean>;
    inferSchema(): Promise<SchemaTable[]>;
    disconnect(): Promise<void>;
}
export {};
