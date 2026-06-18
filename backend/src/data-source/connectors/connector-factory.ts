import { DataSourceType } from '@prisma/client';
import { BaseConnector } from './base.connector';
import { MysqlConnector } from './mysql.connector';
import { ClickHouseConnector } from './clickhouse.connector';
import { PostgresConnector } from './postgres.connector';
import { HttpApiConnector } from './http-api.connector';

export class ConnectorFactory {
  static create(
    type: DataSourceType,
    config: Record<string, any>,
    poolSize?: number,
    queryTimeout?: number,
  ): BaseConnector {
    switch (type) {
      case DataSourceType.MYSQL:
        return new MysqlConnector({
          host: config.host,
          port: config.port,
          database: config.database,
          username: config.username,
          password: config.password,
          poolSize,
          queryTimeout,
        });
      case DataSourceType.CLICKHOUSE:
        return new ClickHouseConnector({
          host: config.host,
          port: config.port,
          database: config.database,
          username: config.username,
          password: config.password,
          queryTimeout,
        });
      case DataSourceType.POSTGRESQL:
        return new PostgresConnector({
          host: config.host,
          port: config.port,
          database: config.database,
          username: config.username,
          password: config.password,
          poolSize,
          queryTimeout,
        });
      case DataSourceType.HTTP_API:
        return new HttpApiConnector({
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
