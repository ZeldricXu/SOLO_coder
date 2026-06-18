import * as mysql from 'mysql2/promise';
import { Types } from 'mysql2';
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

const MYSQL_FIELD_TYPE_MAP: Record<number, string> = {
  [Types.DECIMAL]: 'number',
  [Types.TINY]: 'number',
  [Types.SHORT]: 'number',
  [Types.LONG]: 'number',
  [Types.FLOAT]: 'number',
  [Types.DOUBLE]: 'number',
  [Types.NULL]: 'null',
  [Types.TIMESTAMP]: 'Date',
  [Types.LONGLONG]: 'number',
  [Types.INT24]: 'number',
  [Types.DATE]: 'Date',
  [Types.TIME]: 'string',
  [Types.DATETIME]: 'Date',
  [Types.YEAR]: 'number',
  [Types.NEWDATE]: 'Date',
  [Types.VARCHAR]: 'string',
  [Types.BIT]: 'number',
  [Types.JSON]: 'object',
  [Types.NEWDECIMAL]: 'number',
  [Types.ENUM]: 'string',
  [Types.SET]: 'string',
  [Types.TINY_BLOB]: 'Buffer',
  [Types.MEDIUM_BLOB]: 'Buffer',
  [Types.LONG_BLOB]: 'Buffer',
  [Types.BLOB]: 'Buffer',
  [Types.VAR_STRING]: 'string',
  [Types.STRING]: 'string',
  [Types.GEOMETRY]: 'object',
};

const MYSQL_SCHEMA_TYPE_MAP: Record<string, string> = {
  tinyint: 'boolean',
  smallint: 'number',
  mediumint: 'number',
  int: 'number',
  integer: 'number',
  bigint: 'number',
  float: 'number',
  double: 'number',
  decimal: 'number',
  date: 'Date',
  datetime: 'Date',
  timestamp: 'Date',
  time: 'string',
  year: 'number',
  char: 'string',
  varchar: 'string',
  binary: 'Buffer',
  varbinary: 'Buffer',
  tinyblob: 'Buffer',
  blob: 'Buffer',
  mediumblob: 'Buffer',
  longblob: 'Buffer',
  tinytext: 'string',
  text: 'string',
  mediumtext: 'string',
  longtext: 'string',
  enum: 'string',
  set: 'string',
  json: 'object',
  boolean: 'boolean',
  bool: 'boolean',
};

export class MysqlConnector extends BaseConnector {
  private pool: mysql.Pool | null = null;
  private readonly config: MysqlConfig;

  constructor(config: MysqlConfig) {
    super();
    this.config = config;
  }

  async connect(): Promise<void> {
    this.pool = mysql.createPool({
      host: this.config.host,
      port: this.config.port,
      user: this.config.username,
      password: this.config.password,
      database: this.config.database,
      connectionLimit: this.config.poolSize ?? 10,
    });
  }

  async query(sql: string, params?: any[]): Promise<QueryResult> {
    if (!this.pool) {
      throw new Error('MySQL connector not connected');
    }
    const [rows, fields] = await this.pool.query(sql, params);
    const resultRows = Array.isArray(rows) ? rows : [rows];
    return {
      rows: resultRows as Record<string, any>[],
      fields: fields?.map((f) => ({ name: f.name, type: MYSQL_FIELD_TYPE_MAP[f.type as number] ?? 'unknown' })),
      rowCount: Array.isArray(rows) ? rows.length : (rows as mysql.ResultSetHeader).affectedRows,
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
      throw new Error('MySQL connector not connected');
    }
    const [tables] = await this.pool.query<mysql.RowDataPacket[]>(
      'SELECT TABLE_NAME FROM INFORMATION_SCHEMA.TABLES WHERE TABLE_SCHEMA = ?',
      [this.config.database],
    );
    const schema: SchemaTable[] = [];
    for (const row of tables) {
      const tableName = row.TABLE_NAME as string;
      const [columns] = await this.pool!.query<mysql.RowDataPacket[]>(
        'SELECT COLUMN_NAME, DATA_TYPE, IS_NULLABLE FROM INFORMATION_SCHEMA.COLUMNS WHERE TABLE_SCHEMA = ? AND TABLE_NAME = ?',
        [this.config.database, tableName],
      );
      schema.push({
        table: tableName,
        columns: columns.map((col) => ({
          name: col.COLUMN_NAME as string,
          type: MYSQL_SCHEMA_TYPE_MAP[col.DATA_TYPE as string] ?? 'unknown',
          nullable: (col.IS_NULLABLE as string) === 'YES',
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
