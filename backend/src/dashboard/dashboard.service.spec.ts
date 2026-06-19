import { Test, TestingModule } from '@nestjs/testing';
import { ConflictException, NotFoundException } from '@nestjs/common';
import { DashboardService } from './dashboard.service';
import { PrismaService } from '../prisma/prisma.service';

describe('DashboardService', () => {
  let service: DashboardService;
  let prisma: {
    dashboard: {
      create: jest.Mock;
      findUnique: jest.Mock;
      findMany: jest.Mock;
      update: jest.Mock;
      delete: jest.Mock;
    };
    widget: {
      create: jest.Mock;
      findUnique: jest.Mock;
      findMany: jest.Mock;
      update: jest.Mock;
      delete: jest.Mock;
    };
    $transaction: jest.Mock;
  };

  const mockDashboard = {
    id: 'dash-1',
    name: 'Test Dashboard',
    description: 'desc',
    layout: {},
    globalFilters: {},
    isPublic: false,
    businessLineId: 'bl-1',
    createdBy: 'user-1',
    version: 1,
    widgets: [],
    createdAt: new Date(),
    updatedAt: new Date(),
  };

  const mockWidget = {
    id: 'widget-1',
    dashboardId: 'dash-1',
    type: 'LINE_CHART',
    title: 'Test Widget',
    metricId: null,
    config: {},
    layout: { x: 0, y: 0, w: 6, h: 4 },
    filters: null,
    linkedWidgetIds: [],
  };

  beforeEach(async () => {
    prisma = {
      dashboard: {
        create: jest.fn(),
        findUnique: jest.fn(),
        findMany: jest.fn(),
        update: jest.fn(),
        delete: jest.fn(),
      },
      widget: {
        create: jest.fn(),
        findUnique: jest.fn(),
        findMany: jest.fn(),
        update: jest.fn(),
        delete: jest.fn(),
      },
      $transaction: jest.fn(),
    };

    const module: TestingModule = await Test.createTestingModule({
      providers: [
        DashboardService,
        { provide: PrismaService, useValue: prisma },
      ],
    }).compile();

    service = module.get<DashboardService>(DashboardService);
  });

  describe('create', () => {
    it('should create a dashboard and return with widgets', async () => {
      prisma.dashboard.create.mockResolvedValue({
        ...mockDashboard,
        widgets: [],
      });

      const result = await service.create(
        { name: 'Test Dashboard', businessLineId: 'bl-1' } as any,
        'user-1',
      );

      expect(result).toEqual({ ...mockDashboard, widgets: [] });
      expect(prisma.dashboard.create).toHaveBeenCalledWith({
        data: {
          name: 'Test Dashboard',
          description: '',
          layout: {},
          globalFilters: {},
          businessLineId: 'bl-1',
          isPublic: false,
          createdBy: 'user-1',
        },
        include: { widgets: true },
      });
    });
  });

  describe('findOne', () => {
    it('should return a dashboard with widgets and metric', async () => {
      const dashWithMetric = {
        ...mockDashboard,
        widgets: [{ ...mockWidget, metric: { id: 'metric-1' } }],
      };
      prisma.dashboard.findUnique.mockResolvedValue(dashWithMetric);

      const result = await service.findOne('dash-1');
      expect(result).toEqual(dashWithMetric);
      expect(prisma.dashboard.findUnique).toHaveBeenCalledWith({
        where: { id: 'dash-1' },
        include: { widgets: { include: { metric: true } } },
      });
    });

    it('should throw NotFoundException if dashboard not found', async () => {
      prisma.dashboard.findUnique.mockResolvedValue(null);

      await expect(service.findOne('non-existent')).rejects.toThrow(
        NotFoundException,
      );
      await expect(service.findOne('non-existent')).rejects.toThrow(
        'Dashboard non-existent not found',
      );
    });
  });

  describe('update - optimistic locking', () => {
    it('should throw ConflictException when expectedVersion does not match', async () => {
      const existing = { ...mockDashboard, version: 2 };
      prisma.dashboard.findUnique.mockResolvedValue(existing);

      await expect(
        service.update('dash-1', {
          name: 'Updated',
          expectedVersion: 1,
        } as any),
      ).rejects.toThrow(ConflictException);
    });

    it('should throw ConflictException with correct message', async () => {
      const existing = { ...mockDashboard, version: 3 };
      prisma.dashboard.findUnique.mockResolvedValue(existing);

      try {
        await service.update('dash-1', {
          name: 'Updated',
          expectedVersion: 1,
        } as any);
      } catch (error) {
        expect(error.message).toContain('Expected version 1');
        expect(error.message).toContain('current version is 3');
      }
    });

    it('should update successfully when expectedVersion matches and increment version', async () => {
      const existing = { ...mockDashboard, version: 1 };
      const updated = { ...mockDashboard, version: 2, name: 'Updated' };
      prisma.dashboard.findUnique.mockResolvedValue(existing);
      prisma.dashboard.update.mockResolvedValue(updated);

      const result = await service.update('dash-1', {
        name: 'Updated',
        expectedVersion: 1,
      } as any);

      expect(result).toEqual(updated);
      expect(prisma.dashboard.update).toHaveBeenCalledWith({
        where: { id: 'dash-1' },
        data: {
          name: 'Updated',
          version: { increment: 1 },
        },
        include: { widgets: true },
      });
    });

    it('should update without version check when expectedVersion is not provided', async () => {
      const existing = { ...mockDashboard, version: 5 };
      const updated = { ...mockDashboard, version: 6, name: 'Updated' };
      prisma.dashboard.findUnique.mockResolvedValue(existing);
      prisma.dashboard.update.mockResolvedValue(updated);

      const result = await service.update('dash-1', {
        name: 'Updated',
      } as any);

      expect(result).toEqual(updated);
      expect(prisma.dashboard.update).toHaveBeenCalled();
    });

    it('should throw NotFoundException if dashboard does not exist', async () => {
      prisma.dashboard.findUnique.mockResolvedValue(null);

      await expect(
        service.update('non-existent', { name: 'Updated' } as any),
      ).rejects.toThrow(NotFoundException);
    });
  });

  describe('remove', () => {
    it('should delete a dashboard', async () => {
      prisma.dashboard.findUnique.mockResolvedValue(mockDashboard);
      prisma.dashboard.delete.mockResolvedValue(mockDashboard);

      const result = await service.remove('dash-1');
      expect(result).toEqual(mockDashboard);
      expect(prisma.dashboard.delete).toHaveBeenCalledWith({
        where: { id: 'dash-1' },
      });
    });

    it('should throw NotFoundException if dashboard not found', async () => {
      prisma.dashboard.findUnique.mockResolvedValue(null);

      await expect(service.remove('non-existent')).rejects.toThrow(
        NotFoundException,
      );
    });
  });

  describe('addWidget', () => {
    it('should create a widget for an existing dashboard', async () => {
      prisma.dashboard.findUnique.mockResolvedValue(mockDashboard);
      prisma.widget.create.mockResolvedValue(mockWidget);

      const result = await service.addWidget('dash-1', {
        type: 'LINE_CHART' as any,
        title: 'Test Widget',
        layout: { x: 0, y: 0, w: 6, h: 4 },
      } as any);

      expect(result).toEqual(mockWidget);
      expect(prisma.widget.create).toHaveBeenCalledWith({
        data: {
          dashboardId: 'dash-1',
          type: 'LINE_CHART',
          title: 'Test Widget',
          metricId: null,
          config: {},
          layout: { x: 0, y: 0, w: 6, h: 4 },
          filters: expect.anything(),
          linkedWidgetIds: [],
        },
      });
    });

    it('should throw NotFoundException if dashboard does not exist', async () => {
      prisma.dashboard.findUnique.mockResolvedValue(null);

      await expect(
        service.addWidget('non-existent', {
          type: 'LINE_CHART' as any,
          title: 'Test',
          layout: { x: 0, y: 0, w: 6, h: 4 },
        } as any),
      ).rejects.toThrow(NotFoundException);
    });
  });

  describe('removeWidget - link cleanup', () => {
    it('should remove widget and clean up linkedWidgetIds from siblings', async () => {
      const widgetToDelete = {
        ...mockWidget,
        id: 'widget-to-delete',
      };
      const siblingWithLink = {
        id: 'sibling-1',
        dashboardId: 'dash-1',
        linkedWidgetIds: ['widget-to-delete', 'other-widget'],
      };
      const siblingWithoutLink = {
        id: 'sibling-2',
        dashboardId: 'dash-1',
        linkedWidgetIds: ['other-widget'],
      };

      prisma.dashboard.findUnique.mockResolvedValue(mockDashboard);
      prisma.widget.findUnique.mockResolvedValue(widgetToDelete);
      prisma.widget.delete.mockResolvedValue(widgetToDelete);
      prisma.widget.findMany.mockResolvedValue([siblingWithLink, siblingWithoutLink]);
      prisma.widget.update.mockResolvedValue({});

      const result = await service.removeWidget('dash-1', 'widget-to-delete');

      expect(result).toEqual({ deleted: true });
      expect(prisma.widget.delete).toHaveBeenCalledWith({
        where: { id: 'widget-to-delete' },
      });
      expect(prisma.widget.update).toHaveBeenCalledTimes(1);
      expect(prisma.widget.update).toHaveBeenCalledWith({
        where: { id: 'sibling-1' },
        data: { linkedWidgetIds: ['other-widget'] },
      });
    });

    it('should throw NotFoundException if widget not found in dashboard', async () => {
      prisma.dashboard.findUnique.mockResolvedValue(mockDashboard);
      prisma.widget.findUnique.mockResolvedValue(null);

      await expect(
        service.removeWidget('dash-1', 'non-existent-widget'),
      ).rejects.toThrow(NotFoundException);
    });
  });

  describe('batchUpdateLayout', () => {
    it('should update layout for multiple widgets in a transaction', async () => {
      const w1 = { ...mockWidget, id: 'w-1', layout: { x: 0, y: 0, w: 6, h: 4 } };
      const w2 = { ...mockWidget, id: 'w-2', layout: { x: 6, y: 0, w: 6, h: 4 } };

      prisma.dashboard.findUnique.mockResolvedValue(mockDashboard);
      prisma.widget.findMany.mockResolvedValue([w1, w2]);
      prisma.$transaction.mockImplementation((ops: any[]) => Promise.resolve(ops.map(() => ({}))));
      prisma.dashboard.findUnique.mockResolvedValueOnce(mockDashboard);
      prisma.dashboard.findUnique.mockResolvedValueOnce({
        ...mockDashboard,
        widgets: [w1, w2],
      });

      const items = [
        { widgetId: 'w-1', x: 1, y: 1, w: 6, h: 4 },
        { widgetId: 'w-2', x: 7, y: 1, w: 6, h: 4 },
      ];

      await service.batchUpdateLayout('dash-1', items as any);

      expect(prisma.$transaction).toHaveBeenCalled();
      const transactionCalls = prisma.$transaction.mock.calls[0][0];
      expect(transactionCalls.length).toBe(2);
    });

    it('should throw NotFoundException if some widgets are missing', async () => {
      prisma.dashboard.findUnique.mockResolvedValue(mockDashboard);
      prisma.widget.findMany.mockResolvedValue([{ id: 'w-1' }]);

      const items = [
        { widgetId: 'w-1', x: 1, y: 1, w: 6, h: 4 },
        { widgetId: 'w-missing', x: 7, y: 1, w: 6, h: 4 },
      ];

      await expect(
        service.batchUpdateLayout('dash-1', items as any),
      ).rejects.toThrow(NotFoundException);
    });
  });

  describe('exportDashboard', () => {
    it('should export a dashboard as a complete JSON structure', async () => {
      const dashboardWithWidgets = {
        ...mockDashboard,
        widgets: [mockWidget],
      };
      prisma.dashboard.findUnique.mockResolvedValue(dashboardWithWidgets);

      const result = await service.exportDashboard('dash-1');

      expect(result.version).toBe(1);
      expect(result.dashboard.name).toBe('Test Dashboard');
      expect(result.widgets).toHaveLength(1);
      expect(result.widgets[0]).toEqual({
        type: 'LINE_CHART',
        title: 'Test Widget',
        metricId: null,
        config: {},
        layout: { x: 0, y: 0, w: 6, h: 4 },
        filters: null,
        linkedWidgetIds: [],
      });
      expect(result.exportedAt).toBeDefined();
    });
  });

  describe('importDashboard', () => {
    it('should create a new dashboard and map widget IDs', async () => {
      const importData = {
        dashboard: { name: 'Imported Dashboard' },
        widgets: [
          { type: 'LINE_CHART', title: 'Widget A', linkedWidgetIds: ['1'] },
          { type: 'BAR_CHART', title: 'Widget B', linkedWidgetIds: ['0'] },
        ],
      };

      const createdDashboard = { ...mockDashboard, id: 'new-dash' };
      prisma.dashboard.create.mockResolvedValue(createdDashboard);

      prisma.widget.create
        .mockResolvedValueOnce({ id: 'new-w-0' })
        .mockResolvedValueOnce({ id: 'new-w-1' });

      prisma.widget.update.mockResolvedValue({});

      prisma.dashboard.findUnique.mockResolvedValue({
        ...createdDashboard,
        widgets: [
          { id: 'new-w-0', linkedWidgetIds: ['new-w-1'] },
          { id: 'new-w-1', linkedWidgetIds: ['new-w-0'] },
        ],
      });

      const result = await service.importDashboard(
        importData as any,
        'user-1',
        'bl-1',
      );

      expect(prisma.dashboard.create).toHaveBeenCalled();
      expect(prisma.widget.create).toHaveBeenCalledTimes(2);
      expect(prisma.widget.update).toHaveBeenCalledTimes(2);
    });

    it('should throw BadRequestException for invalid import structure', async () => {
      await expect(
        service.importDashboard({} as any, 'user-1', 'bl-1'),
      ).rejects.toThrow('Invalid dashboard import structure: missing dashboard or widgets');
    });

    it('should throw BadRequestException when dashboard name is missing', async () => {
      await expect(
        service.importDashboard(
          { dashboard: {}, widgets: [] } as any,
          'user-1',
          'bl-1',
        ),
      ).rejects.toThrow('Invalid dashboard import structure: dashboard name is required');
    });

    it('should throw BadRequestException when widget is missing type or title', async () => {
      const createdDashboard = { ...mockDashboard, id: 'new-dash' };
      prisma.dashboard.create.mockResolvedValue(createdDashboard);

      await expect(
        service.importDashboard(
          { dashboard: { name: 'Test' }, widgets: [{ type: 'LINE_CHART' }] } as any,
          'user-1',
          'bl-1',
        ),
      ).rejects.toThrow('Invalid widget at index 0: type and title are required');
    });
  });
});
