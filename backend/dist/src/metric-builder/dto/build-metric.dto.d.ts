export type VisualAggregation = 'SUM' | 'COUNT' | 'AVG' | 'MAX' | 'MIN' | 'DISTINCT_COUNT';
export type VisualGranularity = 'HOUR' | 'DAY' | 'WEEK' | 'MONTH';
export type FilterOperator = 'eq' | 'ne' | 'gt' | 'gte' | 'lt' | 'lte' | 'in' | 'like' | 'between';
export declare class FilterCondition {
    field: string;
    operator: FilterOperator;
    value: any;
}
export declare class VisualMetricConfig {
    table: string;
    metricField: string;
    aggregation: VisualAggregation;
    alias?: string;
    timeField?: string;
    granularity?: VisualGranularity;
    startDate?: string;
    endDate?: string;
    dimensions?: string[];
    filters?: FilterCondition[];
}
export declare class ListTablesParams {
    dataSourceId: string;
}
export declare class ListColumnsParams {
    dataSourceId: string;
    tableName: string;
}
export declare class GenerateSqlDto extends VisualMetricConfig {
}
export declare class BuildMetricDto extends VisualMetricConfig {
}
export declare class CreateMetricFromVisualDto extends VisualMetricConfig {
    name: string;
    description: string;
    timeWindow?: string;
    isAutoCompare?: boolean;
}
