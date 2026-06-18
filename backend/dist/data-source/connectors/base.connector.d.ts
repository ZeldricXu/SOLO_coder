export interface SchemaTable {
    table: string;
    columns: SchemaColumn[];
}
export interface SchemaColumn {
    name: string;
    type: string;
    nullable: boolean;
}
export interface QueryResult {
    rows: Record<string, any>[];
    fields?: {
        name: string;
        type: string;
    }[];
    rowCount?: number;
}
export declare abstract class BaseConnector {
    abstract connect(): Promise<void>;
    abstract query(sql: string, params?: any[]): Promise<QueryResult>;
    abstract testConnection(): Promise<boolean>;
    abstract inferSchema(): Promise<SchemaTable[]>;
    abstract disconnect(): Promise<void>;
}
