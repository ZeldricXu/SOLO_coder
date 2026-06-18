import { BaseConnector, QueryResult, SchemaTable } from './base.connector';
interface HttpApiConfig {
    url: string;
    method?: string;
    headers?: Record<string, string>;
    body?: Record<string, any>;
    queryTimeout?: number;
}
export declare class HttpApiConnector extends BaseConnector {
    private axiosInstance;
    private readonly config;
    constructor(config: HttpApiConfig);
    connect(): Promise<void>;
    query(sql: string, params?: any[]): Promise<QueryResult>;
    testConnection(): Promise<boolean>;
    inferSchema(): Promise<SchemaTable[]>;
    disconnect(): Promise<void>;
}
export {};
