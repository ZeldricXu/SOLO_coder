export declare class CreateDashboardDto {
    name: string;
    description?: string;
    businessLineId: string;
    isPublic?: boolean;
    layout?: Record<string, any>;
    globalFilters?: Record<string, any>;
}
