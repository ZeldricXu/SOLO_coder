import { BaseConnector, QueryResult, SchemaTable } from './base.connector';
interface PostgresConfig {
    host: string;
    port: number;
    database: string;
    username: string;
    password: string;
    poolSize?: number;
    queryTimeout?: number;
}
export declare class PostgresConnector extends BaseConnector {
    private pool;
    private readonly config;
    constructor(config: PostgresConfig);
    connect(): Promise<void>;
    query(sql: string, params?: any[]): Promise<QueryResult>;
    testConnection(): Promise<boolean>;
    inferSchema(): Promise<SchemaTable[]>;
    disconnect(): Promise<void>;
}
export {};
