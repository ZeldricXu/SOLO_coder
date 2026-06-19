import { DataSourceType } from '@prisma/client';
import { BaseChangeDetector } from './base-detector';
import { MysqlBinlogDetector } from './mysql-binlog-detector';
import { ClickHouseVersionDetector } from './clickhouse-version-detector';

export class DetectorFactory {
  static create(
    type: DataSourceType,
    dataSourceId: string,
    config: Record<string, any>,
  ): BaseChangeDetector {
    switch (type) {
      case DataSourceType.MYSQL:
        return new MysqlBinlogDetector(dataSourceId, {
          host: config.host,
          port: config.port,
          database: config.database,
          username: config.username,
          password: config.password,
        });
      case DataSourceType.CLICKHOUSE:
        return new ClickHouseVersionDetector(dataSourceId, {
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
