import { create } from 'zustand';
import type { Dashboard, Widget } from '@/types';
import { dashboardService } from '@/services/dashboard';

interface DashboardState {
  currentDashboard: Dashboard | null;
  widgets: Widget[];
  globalFilters: Record<string, unknown>;
  loading: boolean;

  loadDashboard: (id: string) => Promise<void>;
  setCurrentDashboard: (dashboard: Dashboard | null) => void;
  setGlobalFilters: (filters: Record<string, unknown>) => void;

  addWidget: (data: Partial<Widget>) => Promise<void>;
  updateWidget: (widgetId: string, data: Partial<Widget>) => Promise<void>;
  removeWidget: (widgetId: string) => Promise<void>;
  batchUpdateLayout: (items: Array<{ widgetId: string; layout: Record<string, unknown> }>) => Promise<void>;
  linkWidget: (widgetId: string, targetWidgetId: string) => Promise<void>;
  unlinkWidget: (widgetId: string, targetWidgetId: string) => Promise<void>;
}

export const useDashboardStore = create<DashboardState>((set, get) => ({
  currentDashboard: null,
  widgets: [],
  globalFilters: {},
  loading: false,

  loadDashboard: async (id: string) => {
    set({ loading: true });
    try {
      const res = await dashboardService.get(id);
      const dashboard = res.data.data;
      set({
        currentDashboard: dashboard,
        widgets: dashboard.widgets || [],
        globalFilters: (dashboard.globalFilters as Record<string, unknown>) || {},
      });
    } finally {
      set({ loading: false });
    }
  },

  setCurrentDashboard: (dashboard) => {
    set({
      currentDashboard: dashboard,
      widgets: dashboard?.widgets || [],
      globalFilters: (dashboard?.globalFilters as Record<string, unknown>) || {},
    });
  },

  setGlobalFilters: (filters) => {
    set({ globalFilters: filters });
  },

  addWidget: async (data) => {
    const dashboard = get().currentDashboard;
    if (!dashboard) return;
    const res = await dashboardService.addWidget(dashboard.id, data);
    const widget = res.data.data;
    set((state) => ({ widgets: [...state.widgets, widget] }));
  },

  updateWidget: async (widgetId, data) => {
    const dashboard = get().currentDashboard;
    if (!dashboard) return;
    const res = await dashboardService.updateWidget(dashboard.id, widgetId, data);
    const updated = res.data.data;
    set((state) => ({
      widgets: state.widgets.map((w) => (w.id === widgetId ? updated : w)),
    }));
  },

  removeWidget: async (widgetId) => {
    const dashboard = get().currentDashboard;
    if (!dashboard) return;
    await dashboardService.removeWidget(dashboard.id, widgetId);
    set((state) => ({
      widgets: state.widgets.filter((w) => w.id !== widgetId),
    }));
  },

  batchUpdateLayout: async (items) => {
    const dashboard = get().currentDashboard;
    if (!dashboard) return;
    await dashboardService.batchUpdateLayout(dashboard.id, items);
    set((state) => ({
      widgets: state.widgets.map((w) => {
        const item = items.find((i) => i.widgetId === w.id);
        return item ? { ...w, layout: item.layout } : w;
      }),
    }));
  },

  linkWidget: async (widgetId, targetWidgetId) => {
    const dashboard = get().currentDashboard;
    if (!dashboard) return;
    await dashboardService.linkWidget(dashboard.id, widgetId, targetWidgetId);
    set((state) => ({
      widgets: state.widgets.map((w) =>
        w.id === widgetId
          ? { ...w, linkedWidgetIds: [...w.linkedWidgetIds, targetWidgetId] }
          : w,
      ),
    }));
  },

  unlinkWidget: async (widgetId, targetWidgetId) => {
    const dashboard = get().currentDashboard;
    if (!dashboard) return;
    await dashboardService.unlinkWidget(dashboard.id, widgetId, targetWidgetId);
    set((state) => ({
      widgets: state.widgets.map((w) =>
        w.id === widgetId
          ? { ...w, linkedWidgetIds: w.linkedWidgetIds.filter((id) => id !== targetWidgetId) }
          : w,
      ),
    }));
  },
}));
