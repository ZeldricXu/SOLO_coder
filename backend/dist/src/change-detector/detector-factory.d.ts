import { DataSourceType } from '@prisma/client';
import { BaseChangeDetector } from './base-detector';
export declare class DetectorFactory {
    static create(type: DataSourceType, dataSourceId: string, config: Record<string, any>): BaseChangeDetector;
}
