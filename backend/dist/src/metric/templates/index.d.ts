import { Aggregation, TimeWindow } from '@prisma/client';
export interface MetricTemplate {
    id: string;
    name: string;
    description: string;
    category: 'ECOMMERCE' | 'ADVERTISING' | 'MEMBERSHIP';
    sqlTemplate: string;
    aggregation: Aggregation;
    timeWindow: TimeWindow;
    defaultDimensions: string[];
}
export declare const metricTemplates: MetricTemplate[];
