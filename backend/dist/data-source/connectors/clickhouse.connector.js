"use strict";
Object.defineProperty(exports, "__esModule", { value: true });
exports.ClickHouseConnector = void 0;
const client_1 = require("@clickhouse/client");
const base_connector_1 = require("./base.connector");
const CLICKHOUSE_TYPE_MAP = {
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
function mapClickHouseType(rawType) {
    const baseType = rawType.replace('Nullable(', '').replace(')', '').replace('LowCardinality(', '').replace(')', '');
    for (const [key, value] of Object.entries(CLICKHOUSE_TYPE_MAP)) {
        if (baseType.startsWith(key)) {
            return value;
        }
    }
    return 'unknown';
}
class ClickHouseConnector extends base_connector_1.BaseConnector {
    constructor(config) {
        super();
        this.client = null;
        this.config = config;
    }
    async connect() {
        this.client = (0, client_1.createClient)({
            host: `http://${this.config.host}:${this.config.port}`,
            database: this.config.database,
            username: this.config.username,
            password: this.config.password,
        });
    }
    async query(sql, params) {
        if (!this.client) {
            throw new Error('ClickHouse connector not connected');
        }
        const resultSet = await this.client.query({
            query: sql,
            format: 'JSONEachRow',
        });
        const rows = await resultSet.json();
        const columns = resultSet.columns;
        return {
            rows,
            fields: columns?.map((col) => ({
                name: col.name,
                type: mapClickHouseType(col.type),
            })),
            rowCount: rows.length,
        };
    }
    async testConnection() {
        if (!this.client) {
            await this.connect();
        }
        try {
            await this.client.query({ query: 'SELECT 1', format: 'JSONEachRow' });
            return true;
        }
        catch {
            return false;
        }
    }
    async inferSchema() {
        if (!this.client) {
            throw new Error('ClickHouse connector not connected');
        }
        const tablesResult = await this.client.query({
            query: 'SELECT name FROM system.tables WHERE database = {database:String}',
            query_params: { database: this.config.database },
            format: 'JSONEachRow',
        });
        const tables = await tablesResult.json();
        const schema = [];
        for (const { name } of tables) {
            const columnsResult = await this.client.query({
                query: 'SELECT name, type, nullable_from_type(type) AS is_nullable FROM system.columns WHERE database = {database:String} AND table = {table:String}',
                query_params: { database: this.config.database, table: name },
                format: 'JSONEachRow',
            });
            const columns = await columnsResult.json();
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
    async disconnect() {
        if (this.client) {
            await this.client.close();
            this.client = null;
        }
    }
}
exports.ClickHouseConnector = ClickHouseConnector;
//# sourceMappingURL=clickhouse.connector.js.map