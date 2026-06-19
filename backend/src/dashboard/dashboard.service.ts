import { Injectable, NotFoundException, BadRequestException, ConflictException } from '@nestjs/common';
import { Prisma } from '@prisma/client';
import { PrismaService } from '../prisma/prisma.service';
import { CreateDashboardDto } from './dto/create-dashboard.dto';
import { UpdateDashboardDto } from './dto/update-dashboard.dto';
import { CreateWidgetDto } from './dto/create-widget.dto';
import { UpdateWidgetDto } from './dto/update-widget.dto';
import { LayoutItemDto } from './dto/layout-item.dto';

@Injectable()
export class DashboardService {
  constructor(private readonly prisma: PrismaService) {}

  async create(dto: CreateDashboardDto, userId: string) {
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

  async findAll(businessLineId?: string) {
    const where = businessLineId ? { businessLineId } : {};
    return this.prisma.dashboard.findMany({
      where,
      orderBy: { createdAt: 'desc' },
      include: { widgets: true },
    });
  }

  async findOne(id: string) {
    const dashboard = await this.prisma.dashboard.findUnique({
      where: { id },
      include: { widgets: { include: { metric: true } } },
    });
    if (!dashboard) {
      throw new NotFoundException(`Dashboard ${id} not found`);
    }
    return dashboard;
  }

  async update(id: string, dto: UpdateDashboardDto) {
    const existing = await this.findOne(id);

    if (dto.expectedVersion !== undefined && existing.version !== dto.expectedVersion) {
      throw new ConflictException(
        `Dashboard was modified by another user. Expected version ${dto.expectedVersion}, current version is ${existing.version}. Please refresh and try again.`,
      );
    }

    const data: Record<string, any> = {};
    if (dto.name !== undefined) data.name = dto.name;
    if (dto.description !== undefined) data.description = dto.description;
    if (dto.layout !== undefined) data.layout = dto.layout;
    if (dto.globalFilters !== undefined) data.globalFilters = dto.globalFilters;
    if (dto.isPublic !== undefined) data.isPublic = dto.isPublic;
    if (dto.businessLineId !== undefined) data.businessLineId = dto.businessLineId;
    data.version = { increment: 1 };

    return this.prisma.dashboard.update({
      where: { id },
      data,
      include: { widgets: true },
    });
  }

  async remove(id: string) {
    await this.findOne(id);
    return this.prisma.dashboard.delete({ where: { id } });
  }

  async addWidget(dashboardId: string, dto: CreateWidgetDto) {
    await this.findOne(dashboardId);
    return this.prisma.widget.create({
      data: {
        dashboardId,
        type: dto.type,
        title: dto.title,
        metricId: dto.metricId ?? null,
        config: dto.config ?? {},
        layout: dto.layout,
        filters: dto.filters ? (dto.filters as Prisma.InputJsonValue) : Prisma.DbNull,
        linkedWidgetIds: dto.linkedWidgetIds ?? [],
      },
    });
  }

  async updateWidget(dashboardId: string, widgetId: string, dto: UpdateWidgetDto) {
    await this.findOne(dashboardId);
    const widget = await this.prisma.widget.findUnique({ where: { id: widgetId } });
    if (!widget || widget.dashboardId !== dashboardId) {
      throw new NotFoundException(`Widget ${widgetId} not found in dashboard ${dashboardId}`);
    }

    const data: Record<string, any> = {};
    if (dto.type !== undefined) data.type = dto.type;
    if (dto.title !== undefined) data.title = dto.title;
    if (dto.metricId !== undefined) data.metricId = dto.metricId;
    if (dto.config !== undefined) data.config = dto.config;
    if (dto.layout !== undefined) data.layout = dto.layout;
    if (dto.filters !== undefined) data.filters = dto.filters ? (dto.filters as Prisma.InputJsonValue) : Prisma.DbNull;
    if (dto.linkedWidgetIds !== undefined) data.linkedWidgetIds = dto.linkedWidgetIds;

    return this.prisma.widget.update({
      where: { id: widgetId },
      data,
    });
  }

  async removeWidget(dashboardId: string, widgetId: string) {
    await this.findOne(dashboardId);
    const widget = await this.prisma.widget.findUnique({ where: { id: widgetId } });
    if (!widget || widget.dashboardId !== dashboardId) {
      throw new NotFoundException(`Widget ${widgetId} not found in dashboard ${dashboardId}`);
    }

    await this.prisma.widget.delete({ where: { id: widgetId } });

    const siblings = await this.prisma.widget.findMany({
      where: { dashboardId },
    });
    for (const sibling of siblings) {
      const linked = (sibling.linkedWidgetIds as string[]) || [];
      if (linked.includes(widgetId)) {
        await this.prisma.widget.update({
          where: { id: sibling.id },
          data: { linkedWidgetIds: linked.filter((id) => id !== widgetId) },
        });
      }
    }

    return { deleted: true };
  }

  async batchUpdateLayout(dashboardId: string, items: LayoutItemDto[]) {
    await this.findOne(dashboardId);

    const widgetIds = items.map((item) => item.widgetId);
    const widgets = await this.prisma.widget.findMany({
      where: { id: { in: widgetIds }, dashboardId },
    });
    const foundIds = new Set(widgets.map((w) => w.id));
    const missing = widgetIds.filter((id) => !foundIds.has(id));
    if (missing.length > 0) {
      throw new NotFoundException(`Widgets not found in dashboard: ${missing.join(', ')}`);
    }

    await this.prisma.$transaction(
      items.map((item) =>
        this.prisma.widget.update({
          where: { id: item.widgetId },
          data: {
            layout: {
              ...((widgets.find((w) => w.id === item.widgetId)?.layout as Record<string, any>) ?? {}),
              x: item.x,
              y: item.y,
              w: item.w,
              h: item.h,
            },
          },
        }),
      ),
    );

    return this.prisma.dashboard.findUnique({
      where: { id: dashboardId },
      include: { widgets: true },
    });
  }

  async linkWidget(dashboardId: string, widgetId: string, targetWidgetId: string) {
    await this.findOne(dashboardId);

    const [widget, target] = await Promise.all([
      this.prisma.widget.findUnique({ where: { id: widgetId } }),
      this.prisma.widget.findUnique({ where: { id: targetWidgetId } }),
    ]);

    if (!widget || widget.dashboardId !== dashboardId) {
      throw new NotFoundException(`Widget ${widgetId} not found in dashboard ${dashboardId}`);
    }
    if (!target || target.dashboardId !== dashboardId) {
      throw new NotFoundException(`Widget ${targetWidgetId} not found in dashboard ${dashboardId}`);
    }

    const widgetLinks = ((widget.linkedWidgetIds as string[]) || []).filter(
      (id) => id !== targetWidgetId,
    );
    widgetLinks.push(targetWidgetId);

    const targetLinks = ((target.linkedWidgetIds as string[]) || []).filter(
      (id) => id !== widgetId,
    );
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

  async unlinkWidget(dashboardId: string, widgetId: string, targetWidgetId: string) {
    await this.findOne(dashboardId);

    const [widget, target] = await Promise.all([
      this.prisma.widget.findUnique({ where: { id: widgetId } }),
      this.prisma.widget.findUnique({ where: { id: targetWidgetId } }),
    ]);

    if (!widget || widget.dashboardId !== dashboardId) {
      throw new NotFoundException(`Widget ${widgetId} not found in dashboard ${dashboardId}`);
    }
    if (!target || target.dashboardId !== dashboardId) {
      throw new NotFoundException(`Widget ${targetWidgetId} not found in dashboard ${dashboardId}`);
    }

    const widgetLinks = ((widget.linkedWidgetIds as string[]) || []).filter(
      (id) => id !== targetWidgetId,
    );
    const targetLinks = ((target.linkedWidgetIds as string[]) || []).filter(
      (id) => id !== widgetId,
    );

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

  async exportDashboard(id: string) {
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

  async importDashboard(data: Record<string, any>, userId: string, businessLineId: string) {
    if (!data.dashboard || !Array.isArray(data.widgets)) {
      throw new BadRequestException('Invalid dashboard import structure: missing dashboard or widgets');
    }

    const dashData = data.dashboard;
    if (!dashData.name) {
      throw new BadRequestException('Invalid dashboard import structure: dashboard name is required');
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

    const oldToNewIdMap = new Map<string, string>();
    const widgetRecords: { oldIndex: number; newId: string; oldLinkedIds: any }[] = [];

    for (let i = 0; i < data.widgets.length; i++) {
      const w = data.widgets[i];
      if (!w.type || !w.title) {
        throw new BadRequestException(`Invalid widget at index ${i}: type and title are required`);
      }

      const created = await this.prisma.widget.create({
        data: {
          dashboardId: dashboard.id,
          type: w.type,
          title: w.title,
          metricId: w.metricId ?? null,
          config: w.config ?? {},
          layout: w.layout ?? { x: 0, y: 0, w: 6, h: 4 },
          filters: w.filters ? (w.filters as Prisma.InputJsonValue) : Prisma.DbNull,
          linkedWidgetIds: [],
        },
      });

      oldToNewIdMap.set(String(i), created.id);
      widgetRecords.push({ oldIndex: i, newId: created.id, oldLinkedIds: w.linkedWidgetIds });
    }

    for (const record of widgetRecords) {
      const oldLinked = Array.isArray(record.oldLinkedIds) ? record.oldLinkedIds : [];
      const newLinked = oldLinked
        .map((oldId: string) => oldToNewIdMap.get(oldId))
        .filter(Boolean) as string[];

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
}
