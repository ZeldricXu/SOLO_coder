"use strict";
Object.defineProperty(exports, "__esModule", { value: true });
exports.PostgresConnector = void 0;
const pg_1 = require("pg");
const base_connector_1 = require("./base.connector");
const POSTGRES_TYPE_MAP = {
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
class PostgresConnector extends base_connector_1.BaseConnector {
    constructor(config) {
        super();
        this.pool = null;
        this.config = config;
    }
    async connect() {
        this.pool = new pg_1.Pool({
            host: this.config.host,
            port: this.config.port,
            database: this.config.database,
            user: this.config.username,
            password: this.config.password,
            max: this.config.poolSize ?? 10,
        });
    }
    async query(sql, params) {
        if (!this.pool) {
            throw new Error('PostgreSQL connector not connected');
        }
        const result = await this.pool.query(sql, params);
        return {
            rows: result.rows,
            fields: result.fields.map((f) => ({
                name: f.name,
                type: String(f.dataTypeID),
            })),
            rowCount: result.rowCount ?? 0,
        };
    }
    async testConnection() {
        if (!this.pool) {
            await this.connect();
        }
        try {
            await this.pool.query('SELECT 1');
            return true;
        }
        catch {
            return false;
        }
    }
    async inferSchema() {
        if (!this.pool) {
            throw new Error('PostgreSQL connector not connected');
        }
        const tablesResult = await this.pool.query('SELECT tablename FROM pg_tables WHERE schemaname = $1', ['public']);
        const schema = [];
        for (const row of tablesResult.rows) {
            const tableName = row.tablename;
            const columnsResult = await this.pool.query('SELECT column_name, data_type, is_nullable FROM information_schema.columns WHERE table_schema = $1 AND table_name = $2', ['public', tableName]);
            schema.push({
                table: tableName,
                columns: columnsResult.rows.map((col) => ({
                    name: col.column_name,
                    type: POSTGRES_TYPE_MAP[col.data_type] ?? 'unknown',
                    nullable: col.is_nullable === 'YES',
                })),
            });
        }
        return schema;
    }
    async disconnect() {
        if (this.pool) {
            await this.pool.end();
            this.pool = null;
        }
    }
}
exports.PostgresConnector = PostgresConnector;
//# sourceMappingURL=postgres.connector.js.map