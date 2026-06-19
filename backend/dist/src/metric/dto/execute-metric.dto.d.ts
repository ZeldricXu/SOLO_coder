declare class DateRangeDto {
    start: string;
    end: string;
}
export declare class ExecuteMetricDto {
    dateRange: DateRangeDto;
    dimensions?: string[];
    filters?: Record<string, any>;
    granularity?: 'hour' | 'day' | 'week' | 'month';
}
export {};
