import { BaseConnector, QueryResult, SchemaTable } from './base.connector';
interface MysqlConfig {
    host: string;
    port: number;
    database: string;
    username: string;
    password: string;
    poolSize?: number;
    queryTimeout?: number;
}
export declare class MysqlConnector extends BaseConnector {
    private pool;
    private readonly config;
    constructor(config: MysqlConfig);
    connect(): Promise<void>;
    query(sql: string, params?: any[]): Promise<QueryResult>;
    testConnection(): Promise<boolean>;
    inferSchema(): Promise<SchemaTable[]>;
    disconnect(): Promise<void>;
}
export {};
