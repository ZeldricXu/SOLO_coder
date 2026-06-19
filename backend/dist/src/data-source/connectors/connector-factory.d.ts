import { DataSourceType } from '@prisma/client';
import { BaseConnector } from './base.connector';
export declare class ConnectorFactory {
    static create(type: DataSourceType, config: Record<string, any>, poolSize?: number, queryTimeout?: number): BaseConnector;
}
