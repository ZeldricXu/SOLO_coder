import { DashboardService } from './dashboard.service';
import { CreateDashboardDto } from './dto/create-dashboard.dto';
import { UpdateDashboardDto } from './dto/update-dashboard.dto';
import { CreateWidgetDto } from './dto/create-widget.dto';
import { UpdateWidgetDto } from './dto/update-widget.dto';
import { BatchLayoutDto } from './dto/batch-layout.dto';
import { LinkWidgetDto } from './dto/link-widget.dto';
import { ImportDashboardDto } from './dto/import-dashboard.dto';
export declare class DashboardController {
    private readonly dashboardService;
    constructor(dashboardService: DashboardService);
    create(dto: CreateDashboardDto, user: any): Promise<{
        widgets: {
            type: import(".prisma/client").$Enums.WidgetType;
            config: import("@prisma/client/runtime/library").JsonValue;
            id: string;
            createdAt: Date;
            updatedAt: Date;
            filters: import("@prisma/client/runtime/library").JsonValue | null;
            layout: import("@prisma/client/runtime/library").JsonValue;
            title: string;
            metricId: string | null;
            linkedWidgetIds: import("@prisma/client/runtime/library").JsonValue;
            dashboardId: string;
        }[];
    } & {
        name: string;
        businessLineId: string;
        id: string;
        createdAt: Date;
        updatedAt: Date;
        description: string;
        isPublic: boolean;
        layout: import("@prisma/client/runtime/library").JsonValue;
        globalFilters: import("@prisma/client/runtime/library").JsonValue;
        version: number;
        createdBy: string;
    }>;
    findAll(businessLineId?: string): Promise<({
        widgets: {
            type: import(".prisma/client").$Enums.WidgetType;
            config: import("@prisma/client/runtime/library").JsonValue;
            id: string;
            createdAt: Date;
            updatedAt: Date;
            filters: import("@prisma/client/runtime/library").JsonValue | null;
            layout: import("@prisma/client/runtime/library").JsonValue;
            title: string;
            metricId: string | null;
            linkedWidgetIds: import("@prisma/client/runtime/library").JsonValue;
            dashboardId: string;
        }[];
    } & {
        name: string;
        businessLineId: string;
        id: string;
        createdAt: Date;
        updatedAt: Date;
        description: string;
        isPublic: boolean;
        layout: import("@prisma/client/runtime/library").JsonValue;
        globalFilters: import("@prisma/client/runtime/library").JsonValue;
        version: number;
        createdBy: string;
    })[]>;
    findOne(id: string): Promise<{
        widgets: ({
            metric: {
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
            } | null;
        } & {
            type: import(".prisma/client").$Enums.WidgetType;
            config: import("@prisma/client/runtime/library").JsonValue;
            id: string;
            createdAt: Date;
            updatedAt: Date;
            filters: import("@prisma/client/runtime/library").JsonValue | null;
            layout: import("@prisma/client/runtime/library").JsonValue;
            title: string;
            metricId: string | null;
            linkedWidgetIds: import("@prisma/client/runtime/library").JsonValue;
            dashboardId: string;
        })[];
    } & {
        name: string;
        businessLineId: string;
        id: string;
        createdAt: Date;
        updatedAt: Date;
        description: string;
        isPublic: boolean;
        layout: import("@prisma/client/runtime/library").JsonValue;
        globalFilters: import("@prisma/client/runtime/library").JsonValue;
        version: number;
        createdBy: string;
    }>;
    update(id: string, dto: UpdateDashboardDto): Promise<{
        widgets: {
            type: import(".prisma/client").$Enums.WidgetType;
            config: import("@prisma/client/runtime/library").JsonValue;
            id: string;
            createdAt: Date;
            updatedAt: Date;
            filters: import("@prisma/client/runtime/library").JsonValue | null;
            layout: import("@prisma/client/runtime/library").JsonValue;
            title: string;
            metricId: string | null;
            linkedWidgetIds: import("@prisma/client/runtime/library").JsonValue;
            dashboardId: string;
        }[];
    } & {
        name: string;
        businessLineId: string;
        id: string;
        createdAt: Date;
        updatedAt: Date;
        description: string;
        isPublic: boolean;
        layout: import("@prisma/client/runtime/library").JsonValue;
        globalFilters: import("@prisma/client/runtime/library").JsonValue;
        version: number;
        createdBy: string;
    }>;
    remove(id: string): Promise<{
        name: string;
        businessLineId: string;
        id: string;
        createdAt: Date;
        updatedAt: Date;
        description: string;
        isPublic: boolean;
        layout: import("@prisma/client/runtime/library").JsonValue;
        globalFilters: import("@prisma/client/runtime/library").JsonValue;
        version: number;
        createdBy: string;
    }>;
    addWidget(id: string, dto: CreateWidgetDto): Promise<{
        type: import(".prisma/client").$Enums.WidgetType;
        config: import("@prisma/client/runtime/library").JsonValue;
        id: string;
        createdAt: Date;
        updatedAt: Date;
        filters: import("@prisma/client/runtime/library").JsonValue | null;
        layout: import("@prisma/client/runtime/library").JsonValue;
        title: string;
        metricId: string | null;
        linkedWidgetIds: import("@prisma/client/runtime/library").JsonValue;
        dashboardId: string;
    }>;
    updateWidget(id: string, widgetId: string, dto: UpdateWidgetDto): Promise<{
        type: import(".prisma/client").$Enums.WidgetType;
        config: import("@prisma/client/runtime/library").JsonValue;
        id: string;
        createdAt: Date;
        updatedAt: Date;
        filters: import("@prisma/client/runtime/library").JsonValue | null;
        layout: import("@prisma/client/runtime/library").JsonValue;
        title: string;
        metricId: string | null;
        linkedWidgetIds: import("@prisma/client/runtime/library").JsonValue;
        dashboardId: string;
    }>;
    removeWidget(id: string, widgetId: string): Promise<{
        deleted: boolean;
    }>;
    batchUpdateLayout(id: string, dto: BatchLayoutDto): Promise<({
        widgets: {
            type: import(".prisma/client").$Enums.WidgetType;
            config: import("@prisma/client/runtime/library").JsonValue;
            id: string;
            createdAt: Date;
            updatedAt: Date;
            filters: import("@prisma/client/runtime/library").JsonValue | null;
            layout: import("@prisma/client/runtime/library").JsonValue;
            title: string;
            metricId: string | null;
            linkedWidgetIds: import("@prisma/client/runtime/library").JsonValue;
            dashboardId: string;
        }[];
    } & {
        name: string;
        businessLineId: string;
        id: string;
        createdAt: Date;
        updatedAt: Date;
        description: string;
        isPublic: boolean;
        layout: import("@prisma/client/runtime/library").JsonValue;
        globalFilters: import("@prisma/client/runtime/library").JsonValue;
        version: number;
        createdBy: string;
    }) | null>;
    linkWidget(id: string, widgetId: string, dto: LinkWidgetDto): Promise<{
        type: import(".prisma/client").$Enums.WidgetType;
        config: import("@prisma/client/runtime/library").JsonValue;
        id: string;
        createdAt: Date;
        updatedAt: Date;
        filters: import("@prisma/client/runtime/library").JsonValue | null;
        layout: import("@prisma/client/runtime/library").JsonValue;
        title: string;
        metricId: string | null;
        linkedWidgetIds: import("@prisma/client/runtime/library").JsonValue;
        dashboardId: string;
    } | null>;
    unlinkWidget(id: string, widgetId: string, targetWidgetId: string): Promise<{
        type: import(".prisma/client").$Enums.WidgetType;
        config: import("@prisma/client/runtime/library").JsonValue;
        id: string;
        createdAt: Date;
        updatedAt: Date;
        filters: import("@prisma/client/runtime/library").JsonValue | null;
        layout: import("@prisma/client/runtime/library").JsonValue;
        title: string;
        metricId: string | null;
        linkedWidgetIds: import("@prisma/client/runtime/library").JsonValue;
        dashboardId: string;
    } | null>;
    exportDashboard(id: string): Promise<{
        version: number;
        dashboard: {
            name: string;
            description: string;
            layout: import("@prisma/client/runtime/library").JsonValue;
            globalFilters: import("@prisma/client/runtime/library").JsonValue;
            isPublic: boolean;
        };
        widgets: {
            type: import(".prisma/client").$Enums.WidgetType;
            title: string;
            metricId: string | null;
            config: import("@prisma/client/runtime/library").JsonValue;
            layout: import("@prisma/client/runtime/library").JsonValue;
            filters: import("@prisma/client/runtime/library").JsonValue;
            linkedWidgetIds: import("@prisma/client/runtime/library").JsonValue;
        }[];
        exportedAt: string;
    }>;
    importDashboard(dto: ImportDashboardDto, user: any): Promise<({
        widgets: {
            type: import(".prisma/client").$Enums.WidgetType;
            config: import("@prisma/client/runtime/library").JsonValue;
            id: string;
            createdAt: Date;
            updatedAt: Date;
            filters: import("@prisma/client/runtime/library").JsonValue | null;
            layout: import("@prisma/client/runtime/library").JsonValue;
            title: string;
            metricId: string | null;
            linkedWidgetIds: import("@prisma/client/runtime/library").JsonValue;
            dashboardId: string;
        }[];
    } & {
        name: string;
        businessLineId: string;
        id: string;
        createdAt: Date;
        updatedAt: Date;
        description: string;
        isPublic: boolean;
        layout: import("@prisma/client/runtime/library").JsonValue;
        globalFilters: import("@prisma/client/runtime/library").JsonValue;
        version: number;
        createdBy: string;
    }) | null>;
}
