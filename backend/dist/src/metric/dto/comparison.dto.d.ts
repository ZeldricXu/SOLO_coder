declare class DateRangeDto {
    start: string;
    end: string;
}
export declare class ComparisonDto {
    type: 'yoy' | 'mom';
    dateRange: DateRangeDto;
}
export {};
