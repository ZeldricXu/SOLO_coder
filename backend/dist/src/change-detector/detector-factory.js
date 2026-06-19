"use strict";
Object.defineProperty(exports, "__esModule", { value: true });
exports.DetectorFactory = void 0;
const client_1 = require("@prisma/client");
const mysql_binlog_detector_1 = require("./mysql-binlog-detector");
const clickhouse_version_detector_1 = require("./clickhouse-version-detector");
class DetectorFactory {
    static create(type, dataSourceId, config) {
        switch (type) {
            case client_1.DataSourceType.MYSQL:
                return new mysql_binlog_detector_1.MysqlBinlogDetector(dataSourceId, {
                    host: config.host,
                    port: config.port,
                    database: config.database,
                    username: config.username,
                    password: config.password,
                });
            case client_1.DataSourceType.CLICKHOUSE:
                return new clickhouse_version_detector_1.ClickHouseVersionDetector(dataSourceId, {
                    host: config.host,
                    port: config.port,
                    database: config.database,
                    username: config.username,
                    password: config.password,
                    versionField: config.versionField,
                    watchedTables: config.watchedTables,
                });
            default:
                throw new Error(`Change detection not supported for type ${type}`);
        }
    }
}
exports.DetectorFactory = DetectorFactory;
//# sourceMappingURL=detector-factory.js.map