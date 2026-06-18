import { DataSourceType } from '@prisma/client';
export declare class CreateDataSourceDto {
    name: string;
    type: DataSourceType;
    config: Record<string, any>;
    poolSize?: number;
    queryTimeout?: number;
    businessLineId: string;
}
