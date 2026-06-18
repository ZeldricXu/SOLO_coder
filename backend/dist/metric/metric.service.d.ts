import { PrismaService } from '../prisma/prisma.service';
import { DataSourceService } from '../data-source/data-source.service';
import { CreateMetricDto } from './dto/create-metric.dto';
import { UpdateMetricDto } from './dto/update-metric.dto';
import { ExecuteMetricDto } from './dto/execute-metric.dto';
import { ComparisonDto } from './dto/comparison.dto';
export declare class MetricService {
    private readonly prisma;
    private readonly dataSourceService;
    constructor(prisma: PrismaService, dataSourceService: DataSourceService);
    create(dto: CreateMetricDto): Promise<{
        name: string;
        type: import(".prisma/client").$Enums.MetricType;
        businessLineId: string;
        id: string;
        createdAt: Date;
        updatedAt: Date;
        description: string;
        sqlTemplate: string | null;
        templateId: string | null;
        aggregation: import(".prisma/client").$Enums.Aggregation;
        timeWindow: import(".prisma/client").$Enums.TimeWindow;
        dimensions: import("@prisma/client/runtime/library").JsonValue;
        dataSourceId: string;
        isAutoCompare: boolean;
    }>;
    findAll(businessLineId?: string): Promise<{
        name: string;
        type: import(".prisma/client").$Enums.MetricType;
        businessLineId: string;
        id: string;
        createdAt: Date;
        updatedAt: Date;
        description: string;
        sqlTemplate: string | null;
        templateId: string | null;
        aggregation: import(".prisma/client").$Enums.Aggregation;
        timeWindow: import(".prisma/client").$Enums.TimeWindow;
        dimensions: import("@prisma/client/runtime/library").JsonValue;
        dataSourceId: string;
        isAutoCompare: boolean;
    }[]>;
    findOne(id: string): Promise<{
        name: string;
        type: import(".prisma/client").$Enums.MetricType;
        businessLineId: string;
        id: string;
        createdAt: Date;
        updatedAt: Date;
        description: string;
        sqlTemplate: string | null;
        templateId: string | null;
        aggregation: import(".prisma/client").$Enums.Aggregation;
        timeWindow: import(".prisma/client").$Enums.TimeWindow;
        dimensions: import("@prisma/client/runtime/library").JsonValue;
        dataSourceId: string;
        isAutoCompare: boolean;
    }>;
    update(id: string, dto: UpdateMetricDto): Promise<{
        name: string;
        type: import(".prisma/client").$Enums.MetricType;
        businessLineId: string;
        id: string;
        createdAt: Date;
        updatedAt: Date;
        description: string;
        sqlTemplate: string | null;
        templateId: string | null;
        aggregation: import(".prisma/client").$Enums.Aggregation;
        timeWindow: import(".prisma/client").$Enums.TimeWindow;
        dimensions: import("@prisma/client/runtime/library").JsonValue;
        dataSourceId: string;
        isAutoCompare: boolean;
    }>;
    remove(id: string): Promise<{
        name: string;
        type: import(".prisma/client").$Enums.MetricType;
        businessLineId: string;
        id: string;
        createdAt: Date;
        updatedAt: Date;
        description: string;
        sqlTemplate: string | null;
        templateId: string | null;
        aggregation: import(".prisma/client").$Enums.Aggregation;
        timeWindow: import(".prisma/client").$Enums.TimeWindow;
        dimensions: import("@prisma/client/runtime/library").JsonValue;
        dataSourceId: string;
        isAutoCompare: boolean;
    }>;
    execute(id: string, dto: ExecuteMetricDto): Promise<{
        metric: {
            id: string;
            name: string;
            aggregation: import(".prisma/client").$Enums.Aggregation;
        };
        dateRange: {
            start: string;
            end: string;
        };
        dimensions: string[];
        data: Record<string, any>[];
    }>;
    getComparison(id: string, dto: ComparisonDto): Promise<{
        metric: {
            id: string;
            name: string;
            aggregation: import(".prisma/client").$Enums.Aggregation;
        };
        comparisonType: "yoy" | "mom";
        current: {
            dateRange: {
                start: string;
                end: string;
            };
            value: number;
            rows: Record<string, any>[];
        };
        previous: {
            dateRange: {
                start: string;
                end: string;
            };
            value: number;
            rows: Record<string, any>[];
        };
        changeRate: number | null;
    }>;
    getTemplates(category?: string): import("./templates").MetricTemplate[];
    private buildSql;
    private applyGranularity;
    private applyAggregation;
    private extractAggregateValue;
    private computeChangeRate;
    private shiftDateRange;
}
