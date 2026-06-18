import { ClickHouseClient, createClient } from '@clickhouse/client';
import { BaseConnector, QueryResult, SchemaTable } from './base.connector';

interface ClickHouseConfig {
  host: string;
  port: number;
  database: string;
  username: string;
  password: string;
  queryTimeout?: number;
}

const CLICKHOUSE_TYPE_MAP: Record<string, string> = {
  UInt8: 'number',
  UInt16: 'number',
  UInt32: 'number',
  UInt64: 'number',
  Int8: 'number',
  Int16: 'number',
  Int32: 'number',
  Int64: 'number',
  Float32: 'number',
  Float64: 'number',
  Decimal: 'number',
  String: 'string',
  FixedString: 'string',
  UUID: 'string',
  Date: 'string',
  Date32: 'string',
  DateTime: 'string',
  DateTime64: 'string',
  Nullable: 'null',
  Array: 'array',
  Map: 'object',
  Tuple: 'object',
  JSON: 'object',
  Bool: 'boolean',
};

function mapClickHouseType(rawType: string): string {
  const baseType = rawType.replace('Nullable(', '').replace(')', '').replace('LowCardinality(', '').replace(')', '');
  for (const [key, value] of Object.entries(CLICKHOUSE_TYPE_MAP)) {
    if (baseType.startsWith(key)) {
      return value;
    }
  }
  return 'unknown';
}

export class ClickHouseConnector extends BaseConnector {
  private client: ClickHouseClient | null = null;
  private readonly config: ClickHouseConfig;

  constructor(config: ClickHouseConfig) {
    super();
    this.config = config;
  }

  async connect(): Promise<void> {
    this.client = createClient({
      host: `http://${this.config.host}:${this.config.port}`,
      database: this.config.database,
      username: this.config.username,
      password: this.config.password,
    });
  }

  async query(sql: string, params?: any[]): Promise<QueryResult> {
    if (!this.client) {
      throw new Error('ClickHouse connector not connected');
    }
    const resultSet = await this.client.query({
      query: sql,
      format: 'JSONEachRow',
    });
    const rows = await resultSet.json<Record<string, any>[]>();
    const columns = (resultSet as any).columns as { name: string; type: string }[] | undefined;
    return {
      rows,
      fields: columns?.map((col: { name: string; type: string }) => ({
        name: col.name,
        type: mapClickHouseType(col.type),
      })),
      rowCount: rows.length,
    };
  }

  async testConnection(): Promise<boolean> {
    if (!this.client) {
      await this.connect();
    }
    try {
      await this.client!.query({ query: 'SELECT 1', format: 'JSONEachRow' });
      return true;
    } catch {
      return false;
    }
  }

  async inferSchema(): Promise<SchemaTable[]> {
    if (!this.client) {
      throw new Error('ClickHouse connector not connected');
    }
    const tablesResult = await this.client.query({
      query: 'SELECT name FROM system.tables WHERE database = {database:String}',
      query_params: { database: this.config.database },
      format: 'JSONEachRow',
    });
    const tables = await tablesResult.json<{ name: string }[]>();
    const schema: SchemaTable[] = [];
    for (const { name } of tables) {
      const columnsResult = await this.client!.query({
        query: 'SELECT name, type, nullable_from_type(type) AS is_nullable FROM system.columns WHERE database = {database:String} AND table = {table:String}',
        query_params: { database: this.config.database, table: name },
        format: 'JSONEachRow',
      });
      const columns = await columnsResult.json<{ name: string; type: string; is_nullable: number }[]>();
      schema.push({
        table: name,
        columns: columns.map((col) => ({
          name: col.name,
          type: mapClickHouseType(col.type),
          nullable: col.is_nullable === 1,
        })),
      });
    }
    return schema;
  }

  async disconnect(): Promise<void> {
    if (this.client) {
      await this.client.close();
      this.client = null;
    }
  }
}
