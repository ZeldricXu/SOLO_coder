"use strict";
Object.defineProperty(exports, "__esModule", { value: true });
exports.ConnectorFactory = void 0;
const client_1 = require("@prisma/client");
const mysql_connector_1 = require("./mysql.connector");
const clickhouse_connector_1 = require("./clickhouse.connector");
const postgres_connector_1 = require("./postgres.connector");
const http_api_connector_1 = require("./http-api.connector");
class ConnectorFactory {
    static create(type, config, poolSize, queryTimeout) {
        switch (type) {
            case client_1.DataSourceType.MYSQL:
                return new mysql_connector_1.MysqlConnector({
                    host: config.host,
                    port: config.port,
                    database: config.database,
                    username: config.username,
                    password: config.password,
                    poolSize,
                    queryTimeout,
                });
            case client_1.DataSourceType.CLICKHOUSE:
                return new clickhouse_connector_1.ClickHouseConnector({
                    host: config.host,
                    port: config.port,
                    database: config.database,
                    username: config.username,
                    password: config.password,
                    queryTimeout,
                });
            case client_1.DataSourceType.POSTGRESQL:
                return new postgres_connector_1.PostgresConnector({
                    host: config.host,
                    port: config.port,
                    database: config.database,
                    username: config.username,
                    password: config.password,
                    poolSize,
                    queryTimeout,
                });
            case client_1.DataSourceType.HTTP_API:
                return new http_api_connector_1.HttpApiConnector({
                    url: config.url,
                    method: config.method,
                    headers: config.headers,
                    body: config.body,
                    queryTimeout,
                });
            default:
                throw new Error(`Unsupported data source type: ${type}`);
        }
    }
}
exports.ConnectorFactory = ConnectorFactory;
//# sourceMappingURL=connector-factory.js.map