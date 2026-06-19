import { Prisma } from '@prisma/client';
import { PrismaService } from '../prisma/prisma.service';
import { CreateDashboardDto } from './dto/create-dashboard.dto';
import { UpdateDashboardDto } from './dto/update-dashboard.dto';
import { CreateWidgetDto } from './dto/create-widget.dto';
import { UpdateWidgetDto } from './dto/update-widget.dto';
import { LayoutItemDto } from './dto/layout-item.dto';
export declare class DashboardService {
    private readonly prisma;
    constructor(prisma: PrismaService);
    create(dto: CreateDashboardDto, userId: string): Promise<{
        widgets: {
            type: import(".prisma/client").$Enums.WidgetType;
            config: Prisma.JsonValue;
            id: string;
            createdAt: Date;
            updatedAt: Date;
            filters: Prisma.JsonValue | null;
            layout: Prisma.JsonValue;
            title: string;
            metricId: string | null;
            linkedWidgetIds: Prisma.JsonValue;
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
        layout: Prisma.JsonValue;
        globalFilters: Prisma.JsonValue;
        version: number;
        createdBy: string;
    }>;
    findAll(businessLineId?: string): Promise<({
        widgets: {
            type: import(".prisma/client").$Enums.WidgetType;
            config: Prisma.JsonValue;
            id: string;
            createdAt: Date;
            updatedAt: Date;
            filters: Prisma.JsonValue | null;
            layout: Prisma.JsonValue;
            title: string;
            metricId: string | null;
            linkedWidgetIds: Prisma.JsonValue;
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
        layout: Prisma.JsonValue;
        globalFilters: Prisma.JsonValue;
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
                dimensions: Prisma.JsonValue;
                dataSourceId: string;
                isAutoCompare: boolean;
            } | null;
        } & {
            type: import(".prisma/client").$Enums.WidgetType;
            config: Prisma.JsonValue;
            id: string;
            createdAt: Date;
            updatedAt: Date;
            filters: Prisma.JsonValue | null;
            layout: Prisma.JsonValue;
            title: string;
            metricId: string | null;
            linkedWidgetIds: Prisma.JsonValue;
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
        layout: Prisma.JsonValue;
        globalFilters: Prisma.JsonValue;
        version: number;
        createdBy: string;
    }>;
    update(id: string, dto: UpdateDashboardDto): Promise<{
        widgets: {
            type: import(".prisma/client").$Enums.WidgetType;
            config: Prisma.JsonValue;
            id: string;
            createdAt: Date;
            updatedAt: Date;
            filters: Prisma.JsonValue | null;
            layout: Prisma.JsonValue;
            title: string;
            metricId: string | null;
            linkedWidgetIds: Prisma.JsonValue;
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
        layout: Prisma.JsonValue;
        globalFilters: Prisma.JsonValue;
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
        layout: Prisma.JsonValue;
        globalFilters: Prisma.JsonValue;
        version: number;
        createdBy: string;
    }>;
    addWidget(dashboardId: string, dto: CreateWidgetDto): Promise<{
        type: import(".prisma/client").$Enums.WidgetType;
        config: Prisma.JsonValue;
        id: string;
        createdAt: Date;
        updatedAt: Date;
        filters: Prisma.JsonValue | null;
        layout: Prisma.JsonValue;
        title: string;
        metricId: string | null;
        linkedWidgetIds: Prisma.JsonValue;
        dashboardId: string;
    }>;
    updateWidget(dashboardId: string, widgetId: string, dto: UpdateWidgetDto): Promise<{
        type: import(".prisma/client").$Enums.WidgetType;
        config: Prisma.JsonValue;
        id: string;
        createdAt: Date;
        updatedAt: Date;
        filters: Prisma.JsonValue | null;
        layout: Prisma.JsonValue;
        title: string;
        metricId: string | null;
        linkedWidgetIds: Prisma.JsonValue;
        dashboardId: string;
    }>;
    removeWidget(dashboardId: string, widgetId: string): Promise<{
        deleted: boolean;
    }>;
    batchUpdateLayout(dashboardId: string, items: LayoutItemDto[]): Promise<({
        widgets: {
            type: import(".prisma/client").$Enums.WidgetType;
            config: Prisma.JsonValue;
            id: string;
            createdAt: Date;
            updatedAt: Date;
            filters: Prisma.JsonValue | null;
            layout: Prisma.JsonValue;
            title: string;
            metricId: string | null;
            linkedWidgetIds: Prisma.JsonValue;
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
        layout: Prisma.JsonValue;
        globalFilters: Prisma.JsonValue;
        version: number;
        createdBy: string;
    }) | null>;
    linkWidget(dashboardId: string, widgetId: string, targetWidgetId: string): Promise<{
        type: import(".prisma/client").$Enums.WidgetType;
        config: Prisma.JsonValue;
        id: string;
        createdAt: Date;
        updatedAt: Date;
        filters: Prisma.JsonValue | null;
        layout: Prisma.JsonValue;
        title: string;
        metricId: string | null;
        linkedWidgetIds: Prisma.JsonValue;
        dashboardId: string;
    } | null>;
    unlinkWidget(dashboardId: string, widgetId: string, targetWidgetId: string): Promise<{
        type: import(".prisma/client").$Enums.WidgetType;
        config: Prisma.JsonValue;
        id: string;
        createdAt: Date;
        updatedAt: Date;
        filters: Prisma.JsonValue | null;
        layout: Prisma.JsonValue;
        title: string;
        metricId: string | null;
        linkedWidgetIds: Prisma.JsonValue;
        dashboardId: string;
    } | null>;
    exportDashboard(id: string): Promise<{
        version: number;
        dashboard: {
            name: string;
            description: string;
            layout: Prisma.JsonValue;
            globalFilters: Prisma.JsonValue;
            isPublic: boolean;
        };
        widgets: {
            type: import(".prisma/client").$Enums.WidgetType;
            title: string;
            metricId: string | null;
            config: Prisma.JsonValue;
            layout: Prisma.JsonValue;
            filters: Prisma.JsonValue;
            linkedWidgetIds: Prisma.JsonValue;
        }[];
        exportedAt: string;
    }>;
    importDashboard(data: Record<string, any>, userId: string, businessLineId: string): Promise<({
        widgets: {
            type: import(".prisma/client").$Enums.WidgetType;
            config: Prisma.JsonValue;
            id: string;
            createdAt: Date;
            updatedAt: Date;
            filters: Prisma.JsonValue | null;
            layout: Prisma.JsonValue;
            title: string;
            metricId: string | null;
            linkedWidgetIds: Prisma.JsonValue;
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
        layout: Prisma.JsonValue;
        globalFilters: Prisma.JsonValue;
        version: number;
        createdBy: string;
    }) | null>;
}
