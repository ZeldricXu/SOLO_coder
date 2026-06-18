"use strict";
var __decorate = (this && this.__decorate) || function (decorators, target, key, desc) {
    var c = arguments.length, r = c < 3 ? target : desc === null ? desc = Object.getOwnPropertyDescriptor(target, key) : desc, d;
    if (typeof Reflect === "object" && typeof Reflect.decorate === "function") r = Reflect.decorate(decorators, target, key, desc);
    else for (var i = decorators.length - 1; i >= 0; i--) if (d = decorators[i]) r = (c < 3 ? d(r) : c > 3 ? d(target, key, r) : d(target, key)) || r;
    return c > 3 && r && Object.defineProperty(target, key, r), r;
};
var __metadata = (this && this.__metadata) || function (k, v) {
    if (typeof Reflect === "object" && typeof Reflect.metadata === "function") return Reflect.metadata(k, v);
};
Object.defineProperty(exports, "__esModule", { value: true });
exports.DashboardService = void 0;
const common_1 = require("@nestjs/common");
const client_1 = require("@prisma/client");
const prisma_service_1 = require("../prisma/prisma.service");
let DashboardService = class DashboardService {
    constructor(prisma) {
        this.prisma = prisma;
    }
    async create(dto, userId) {
        return this.prisma.dashboard.create({
            data: {
                name: dto.name,
                description: dto.description ?? '',
                layout: dto.layout ?? {},
                globalFilters: dto.globalFilters ?? {},
                businessLineId: dto.businessLineId,
                isPublic: dto.isPublic ?? false,
                createdBy: userId,
            },
            include: { widgets: true },
        });
    }
    async findAll(businessLineId) {
        const where = businessLineId ? { businessLineId } : {};
        return this.prisma.dashboard.findMany({
            where,
            orderBy: { createdAt: 'desc' },
            include: { widgets: true },
        });
    }
    async findOne(id) {
        const dashboard = await this.prisma.dashboard.findUnique({
            where: { id },
            include: { widgets: { include: { metric: true } } },
        });
        if (!dashboard) {
            throw new common_1.NotFoundException(`Dashboard ${id} not found`);
        }
        return dashboard;
    }
    async update(id, dto) {
        await this.findOne(id);
        const data = {};
        if (dto.name !== undefined)
            data.name = dto.name;
        if (dto.description !== undefined)
            data.description = dto.description;
        if (dto.layout !== undefined)
            data.layout = dto.layout;
        if (dto.globalFilters !== undefined)
            data.globalFilters = dto.globalFilters;
        if (dto.isPublic !== undefined)
            data.isPublic = dto.isPublic;
        if (dto.businessLineId !== undefined)
            data.businessLineId = dto.businessLineId;
        return this.prisma.dashboard.update({
            where: { id },
            data,
            include: { widgets: true },
        });
    }
    async remove(id) {
        await this.findOne(id);
        return this.prisma.dashboard.delete({ where: { id } });
    }
    async addWidget(dashboardId, dto) {
        await this.findOne(dashboardId);
        return this.prisma.widget.create({
            data: {
                dashboardId,
                type: dto.type,
                title: dto.title,
                metricId: dto.metricId ?? null,
                config: dto.config ?? {},
                layout: dto.layout,
                filters: dto.filters ? dto.filters : client_1.Prisma.DbNull,
                linkedWidgetIds: dto.linkedWidgetIds ?? [],
            },
        });
    }
    async updateWidget(dashboardId, widgetId, dto) {
        await this.findOne(dashboardId);
        const widget = await this.prisma.widget.findUnique({ where: { id: widgetId } });
        if (!widget || widget.dashboardId !== dashboardId) {
            throw new common_1.NotFoundException(`Widget ${widgetId} not found in dashboard ${dashboardId}`);
        }
        const data = {};
        if (dto.type !== undefined)
            data.type = dto.type;
        if (dto.title !== undefined)
            data.title = dto.title;
        if (dto.metricId !== undefined)
            data.metricId = dto.metricId;
        if (dto.config !== undefined)
            data.config = dto.config;
        if (dto.layout !== undefined)
            data.layout = dto.layout;
        if (dto.filters !== undefined)
            data.filters = dto.filters ? dto.filters : client_1.Prisma.DbNull;
        if (dto.linkedWidgetIds !== undefined)
            data.linkedWidgetIds = dto.linkedWidgetIds;
        return this.prisma.widget.update({
            where: { id: widgetId },
            data,
        });
    }
    async removeWidget(dashboardId, widgetId) {
        await this.findOne(dashboardId);
        const widget = await this.prisma.widget.findUnique({ where: { id: widgetId } });
        if (!widget || widget.dashboardId !== dashboardId) {
            throw new common_1.NotFoundException(`Widget ${widgetId} not found in dashboard ${dashboardId}`);
        }
        await this.prisma.widget.delete({ where: { id: widgetId } });
        const siblings = await this.prisma.widget.findMany({
            where: { dashboardId },
        });
        for (const sibling of siblings) {
            const linked = sibling.linkedWidgetIds || [];
            if (linked.includes(widgetId)) {
                await this.prisma.widget.update({
                    where: { id: sibling.id },
                    data: { linkedWidgetIds: linked.filter((id) => id !== widgetId) },
                });
            }
        }
        return { deleted: true };
    }
    async batchUpdateLayout(dashboardId, items) {
        await this.findOne(dashboardId);
        const widgetIds = items.map((item) => item.widgetId);
        const widgets = await this.prisma.widget.findMany({
            where: { id: { in: widgetIds }, dashboardId },
        });
        const foundIds = new Set(widgets.map((w) => w.id));
        const missing = widgetIds.filter((id) => !foundIds.has(id));
        if (missing.length > 0) {
            throw new common_1.NotFoundException(`Widgets not found in dashboard: ${missing.join(', ')}`);
        }
        await this.prisma.$transaction(items.map((item) => this.prisma.widget.update({
            where: { id: item.widgetId },
            data: {
                layout: {
                    ...(widgets.find((w) => w.id === item.widgetId)?.layout ?? {}),
                    x: item.x,
                    y: item.y,
                    w: item.w,
                    h: item.h,
                },
            },
        })));
        return this.prisma.dashboard.findUnique({
            where: { id: dashboardId },
            include: { widgets: true },
        });
    }
    async linkWidget(dashboardId, widgetId, targetWidgetId) {
        await this.findOne(dashboardId);
        const [widget, target] = await Promise.all([
            this.prisma.widget.findUnique({ where: { id: widgetId } }),
            this.prisma.widget.findUnique({ where: { id: targetWidgetId } }),
        ]);
        if (!widget || widget.dashboardId !== dashboardId) {
            throw new common_1.NotFoundException(`Widget ${widgetId} not found in dashboard ${dashboardId}`);
        }
        if (!target || target.dashboardId !== dashboardId) {
            throw new common_1.NotFoundException(`Widget ${targetWidgetId} not found in dashboard ${dashboardId}`);
        }
        const widgetLinks = (widget.linkedWidgetIds || []).filter((id) => id !== targetWidgetId);
        widgetLinks.push(targetWidgetId);
        const targetLinks = (target.linkedWidgetIds || []).filter((id) => id !== widgetId);
        targetLinks.push(widgetId);
        await this.prisma.$transaction([
            this.prisma.widget.update({
                where: { id: widgetId },
                data: { linkedWidgetIds: widgetLinks },
            }),
            this.prisma.widget.update({
                where: { id: targetWidgetId },
                data: { linkedWidgetIds: targetLinks },
            }),
        ]);
        return this.prisma.widget.findUnique({ where: { id: widgetId } });
    }
    async unlinkWidget(dashboardId, widgetId, targetWidgetId) {
        await this.findOne(dashboardId);
        const [widget, target] = await Promise.all([
            this.prisma.widget.findUnique({ where: { id: widgetId } }),
            this.prisma.widget.findUnique({ where: { id: targetWidgetId } }),
        ]);
        if (!widget || widget.dashboardId !== dashboardId) {
            throw new common_1.NotFoundException(`Widget ${widgetId} not found in dashboard ${dashboardId}`);
        }
        if (!target || target.dashboardId !== dashboardId) {
            throw new common_1.NotFoundException(`Widget ${targetWidgetId} not found in dashboard ${dashboardId}`);
        }
        const widgetLinks = (widget.linkedWidgetIds || []).filter((id) => id !== targetWidgetId);
        const targetLinks = (target.linkedWidgetIds || []).filter((id) => id !== widgetId);
        await this.prisma.$transaction([
            this.prisma.widget.update({
                where: { id: widgetId },
                data: { linkedWidgetIds: widgetLinks },
            }),
            this.prisma.widget.update({
                where: { id: targetWidgetId },
                data: { linkedWidgetIds: targetLinks },
            }),
        ]);
        return this.prisma.widget.findUnique({ where: { id: widgetId } });
    }
    async exportDashboard(id) {
        const dashboard = await this.findOne(id);
        return {
            version: 1,
            dashboard: {
                name: dashboard.name,
                description: dashboard.description,
                layout: dashboard.layout,
                globalFilters: dashboard.globalFilters,
                isPublic: dashboard.isPublic,
            },
            widgets: dashboard.widgets.map((w) => ({
                type: w.type,
                title: w.title,
                metricId: w.metricId,
                config: w.config,
                layout: w.layout,
                filters: w.filters,
                linkedWidgetIds: w.linkedWidgetIds,
            })),
            exportedAt: new Date().toISOString(),
        };
    }
    async importDashboard(data, userId, businessLineId) {
        if (!data.dashboard || !Array.isArray(data.widgets)) {
            throw new common_1.BadRequestException('Invalid dashboard import structure: missing dashboard or widgets');
        }
        const dashData = data.dashboard;
        if (!dashData.name) {
            throw new common_1.BadRequestException('Invalid dashboard import structure: dashboard name is required');
        }
        const dashboard = await this.prisma.dashboard.create({
            data: {
                name: dashData.name,
                description: dashData.description ?? '',
                layout: dashData.layout ?? {},
                globalFilters: dashData.globalFilters ?? {},
                isPublic: dashData.isPublic ?? false,
                businessLineId,
                createdBy: userId,
            },
        });
        const oldToNewIdMap = new Map();
        const widgetRecords = [];
        for (let i = 0; i < data.widgets.length; i++) {
            const w = data.widgets[i];
            if (!w.type || !w.title) {
                throw new common_1.BadRequestException(`Invalid widget at index ${i}: type and title are required`);
            }
            const created = await this.prisma.widget.create({
                data: {
                    dashboardId: dashboard.id,
                    type: w.type,
                    title: w.title,
                    metricId: w.metricId ?? null,
                    config: w.config ?? {},
                    layout: w.layout ?? { x: 0, y: 0, w: 6, h: 4 },
                    filters: w.filters ? w.filters : client_1.Prisma.DbNull,
                    linkedWidgetIds: [],
                },
            });
            oldToNewIdMap.set(String(i), created.id);
            widgetRecords.push({ oldIndex: i, newId: created.id, oldLinkedIds: w.linkedWidgetIds });
        }
        for (const record of widgetRecords) {
            const oldLinked = Array.isArray(record.oldLinkedIds) ? record.oldLinkedIds : [];
            const newLinked = oldLinked
                .map((oldId) => oldToNewIdMap.get(oldId))
                .filter(Boolean);
            if (newLinked.length > 0) {
                await this.prisma.widget.update({
                    where: { id: record.newId },
                    data: { linkedWidgetIds: newLinked },
                });
            }
        }
        return this.prisma.dashboard.findUnique({
            where: { id: dashboard.id },
            include: { widgets: true },
        });
    }
};
exports.DashboardService = DashboardService;
exports.DashboardService = DashboardService = __decorate([
    (0, common_1.Injectable)(),
    __metadata("design:paramtypes", [prisma_service_1.PrismaService])
], DashboardService);
//# sourceMappingURL=dashboard.service.js.map