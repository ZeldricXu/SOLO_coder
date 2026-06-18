import { Pool, QueryResult as PgQueryResult } from 'pg';
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

const POSTGRES_TYPE_MAP: Record<string, string> = {
  smallint: 'number',
  integer: 'number',
  bigint: 'number',
  serial: 'number',
  bigserial: 'number',
  real: 'number',
  double: 'number',
  numeric: 'number',
  decimal: 'number',
  money: 'number',
  character: 'string',
  'character varying': 'string',
  text: 'string',
  char: 'string',
  varchar: 'string',
  boolean: 'boolean',
  bool: 'boolean',
  date: 'Date',
  timestamp: 'Date',
  timestampwithouttimezone: 'Date',
  timestampwithtimezone: 'Date',
  time: 'string',
  timewithtimezone: 'string',
  interval: 'string',
  uuid: 'string',
  json: 'object',
  jsonb: 'object',
  bytea: 'Buffer',
  array: 'array',
  hstore: 'object',
  inet: 'string',
  cidr: 'string',
  macaddr: 'string',
  point: 'object',
  line: 'object',
  lseg: 'object',
  box: 'object',
  path: 'object',
  polygon: 'object',
  circle: 'object',
};

export class PostgresConnector extends BaseConnector {
  private pool: Pool | null = null;
  private readonly config: PostgresConfig;

  constructor(config: PostgresConfig) {
    super();
    this.config = config;
  }

  async connect(): Promise<void> {
    this.pool = new Pool({
      host: this.config.host,
      port: this.config.port,
      database: this.config.database,
      user: this.config.username,
      password: this.config.password,
      max: this.config.poolSize ?? 10,
    });
  }

  async query(sql: string, params?: any[]): Promise<QueryResult> {
    if (!this.pool) {
      throw new Error('PostgreSQL connector not connected');
    }
    const result: PgQueryResult = await this.pool.query(sql, params);
    return {
      rows: result.rows,
      fields: result.fields.map((f) => ({
        name: f.name,
        type: String(f.dataTypeID),
      })),
      rowCount: result.rowCount ?? 0,
    };
  }

  async testConnection(): Promise<boolean> {
    if (!this.pool) {
      await this.connect();
    }
    try {
      await this.pool!.query('SELECT 1');
      return true;
    } catch {
      return false;
    }
  }

  async inferSchema(): Promise<SchemaTable[]> {
    if (!this.pool) {
      throw new Error('PostgreSQL connector not connected');
    }
    const tablesResult = await this.pool.query(
      'SELECT tablename FROM pg_tables WHERE schemaname = $1',
      ['public'],
    );
    const schema: SchemaTable[] = [];
    for (const row of tablesResult.rows) {
      const tableName = row.tablename as string;
      const columnsResult = await this.pool!.query(
        'SELECT column_name, data_type, is_nullable FROM information_schema.columns WHERE table_schema = $1 AND table_name = $2',
        ['public', tableName],
      );
      schema.push({
        table: tableName,
        columns: columnsResult.rows.map((col) => ({
          name: col.column_name as string,
          type: POSTGRES_TYPE_MAP[col.data_type as string] ?? 'unknown',
          nullable: (col.is_nullable as string) === 'YES',
        })),
      });
    }
    return schema;
  }

  async disconnect(): Promise<void> {
    if (this.pool) {
      await this.pool.end();
      this.pool = null;
    }
  }
}
