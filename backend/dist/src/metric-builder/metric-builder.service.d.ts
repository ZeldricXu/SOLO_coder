import { DataSourceService } from '../data-source/data-source.service';
import { MetricService } from '../metric/metric.service';
import { SchemaColumn, QueryResult } from '../data-source/connectors/base.connector';
import { VisualMetricConfig, CreateMetricFromVisualDto } from './dto/build-metric.dto';
interface TableInfo {
    name: string;
    columns: SchemaColumn[];
}
export declare class MetricBuilderService {
    private readonly dataSourceService;
    private readonly metricService;
    constructor(dataSourceService: DataSourceService, metricService: MetricService);
    listTables(dataSourceId: string): Promise<TableInfo[]>;
    listColumns(dataSourceId: string, tableName: string): Promise<SchemaColumn[]>;
    generateSql(dataSourceId: string, config: VisualMetricConfig): Promise<string>;
    buildMetric(dataSourceId: string, config: VisualMetricConfig): Promise<{
        sql: string;
        data: QueryResult;
    }>;
    createMetricFromVisual(userId: string, businessLineId: string, dataSourceId: string, config: CreateMetricFromVisualDto): Promise<{
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
    private buildSqlFromConfig;
    private buildMetricExpression;
    private buildGranularityExpression;
    private buildFilterClause;
    private formatValue;
    private escapeValue;
    private mapVisualAggregation;
    private mapTimeWindow;
}
export {};
