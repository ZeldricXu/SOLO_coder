import { MetricType, Aggregation, TimeWindow } from '@prisma/client';
export declare class CreateMetricDto {
    name: string;
    description: string;
    type: MetricType;
    sqlTemplate?: string;
    templateId?: string;
    aggregation: Aggregation;
    timeWindow: TimeWindow;
    dimensions?: string[];
    dataSourceId: string;
    businessLineId: string;
    isAutoCompare?: boolean;
}
