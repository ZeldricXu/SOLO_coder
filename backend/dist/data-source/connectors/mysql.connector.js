"use strict";
Object.defineProperty(exports, "__esModule", { value: true });
exports.MysqlConnector = void 0;
const mysql = require("mysql2/promise");
const mysql2_1 = require("mysql2");
const base_connector_1 = require("./base.connector");
const MYSQL_FIELD_TYPE_MAP = {
    [mysql2_1.Types.DECIMAL]: 'number',
    [mysql2_1.Types.TINY]: 'number',
    [mysql2_1.Types.SHORT]: 'number',
    [mysql2_1.Types.LONG]: 'number',
    [mysql2_1.Types.FLOAT]: 'number',
    [mysql2_1.Types.DOUBLE]: 'number',
    [mysql2_1.Types.NULL]: 'null',
    [mysql2_1.Types.TIMESTAMP]: 'Date',
    [mysql2_1.Types.LONGLONG]: 'number',
    [mysql2_1.Types.INT24]: 'number',
    [mysql2_1.Types.DATE]: 'Date',
    [mysql2_1.Types.TIME]: 'string',
    [mysql2_1.Types.DATETIME]: 'Date',
    [mysql2_1.Types.YEAR]: 'number',
    [mysql2_1.Types.NEWDATE]: 'Date',
    [mysql2_1.Types.VARCHAR]: 'string',
    [mysql2_1.Types.BIT]: 'number',
    [mysql2_1.Types.JSON]: 'object',
    [mysql2_1.Types.NEWDECIMAL]: 'number',
    [mysql2_1.Types.ENUM]: 'string',
    [mysql2_1.Types.SET]: 'string',
    [mysql2_1.Types.TINY_BLOB]: 'Buffer',
    [mysql2_1.Types.MEDIUM_BLOB]: 'Buffer',
    [mysql2_1.Types.LONG_BLOB]: 'Buffer',
    [mysql2_1.Types.BLOB]: 'Buffer',
    [mysql2_1.Types.VAR_STRING]: 'string',
    [mysql2_1.Types.STRING]: 'string',
    [mysql2_1.Types.GEOMETRY]: 'object',
};
const MYSQL_SCHEMA_TYPE_MAP = {
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
class MysqlConnector extends base_connector_1.BaseConnector {
    constructor(config) {
        super();
        this.pool = null;
        this.config = config;
    }
    async connect() {
        this.pool = mysql.createPool({
            host: this.config.host,
            port: this.config.port,
            user: this.config.username,
            password: this.config.password,
            database: this.config.database,
            connectionLimit: this.config.poolSize ?? 10,
        });
    }
    async query(sql, params) {
        if (!this.pool) {
            throw new Error('MySQL connector not connected');
        }
        const [rows, fields] = await this.pool.query(sql, params);
        const resultRows = Array.isArray(rows) ? rows : [rows];
        return {
            rows: resultRows,
            fields: fields?.map((f) => ({ name: f.name, type: MYSQL_FIELD_TYPE_MAP[f.type] ?? 'unknown' })),
            rowCount: Array.isArray(rows) ? rows.length : rows.affectedRows,
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
            throw new Error('MySQL connector not connected');
        }
        const [tables] = await this.pool.query('SELECT TABLE_NAME FROM INFORMATION_SCHEMA.TABLES WHERE TABLE_SCHEMA = ?', [this.config.database]);
        const schema = [];
        for (const row of tables) {
            const tableName = row.TABLE_NAME;
            const [columns] = await this.pool.query('SELECT COLUMN_NAME, DATA_TYPE, IS_NULLABLE FROM INFORMATION_SCHEMA.COLUMNS WHERE TABLE_SCHEMA = ? AND TABLE_NAME = ?', [this.config.database, tableName]);
            schema.push({
                table: tableName,
                columns: columns.map((col) => ({
                    name: col.COLUMN_NAME,
                    type: MYSQL_SCHEMA_TYPE_MAP[col.DATA_TYPE] ?? 'unknown',
                    nullable: col.IS_NULLABLE === 'YES',
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
exports.MysqlConnector = MysqlConnector;
//# sourceMappingURL=mysql.connector.js.map