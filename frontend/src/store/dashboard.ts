import { create } from 'zustand';
import type { Dashboard, Widget } from '@/types';
import { dashboardService, type LayoutItem } from '@/services/dashboard';
import { metricService, type ExecuteMetricData } from '@/services/metric';
import { realtimeService, type DashboardUpdateData } from '@/services/realtime';

interface WidgetDataCache {
  [widgetId: string]: {
    data: Record<string, unknown>[];
    timestamp: number;
    loading: boolean;
    error: string | null;
  };
}

interface WidgetFilters {
  [widgetId: string]: Record<string, unknown>;
}

interface DashboardState {
  currentDashboard: Dashboard | null;
  widgets: Widget[];
  globalFilters: Record<string, unknown>;
  widgetFilters: WidgetFilters;
  widgetDataCache: WidgetDataCache;
  loading: boolean;
  error: string | null;

  loadDashboard: (id: string) => Promise<void>;
  setCurrentDashboard: (dashboard: Dashboard | null) => void;
  clearDashboard: () => void;

  setGlobalFilters: (filters: Record<string, unknown>) => void;
  setWidgetFilters: (widgetId: string, filters: Record<string, unknown>) => void;
  mergeWidgetFilters: (widgetId: string, filters: Record<string, unknown>) => void;
  getCombinedFilters: (widgetId: string) => Record<string, unknown>;

  loadWidgetData: (widgetId: string, force?: boolean) => Promise<void>;
  loadAllWidgetData: () => Promise<void>;
  setWidgetData: (widgetId: string, data: Record<string, unknown>[]) => void;
  updateWidgetDataIncremental: (widgetId: string, update: DashboardUpdateData) => void;
  clearWidgetData: (widgetId: string) => void;
  clearAllWidgetData: () => void;

  addWidget: (data: {
    type: Widget['type'];
    title: string;
    metricId?: string;
    config: Record<string, unknown>;
    layout: Record<string, unknown>;
    filters?: Record<string, unknown>;
  }) => Promise<void>;
  updateWidget: (widgetId: string, data: Partial<Widget>) => Promise<void>;
  removeWidget: (widgetId: string) => Promise<void>;
  batchUpdateLayout: (items: LayoutItem[]) => Promise<void>;
  linkWidget: (widgetId: string, targetWidgetId: string) => Promise<void>;
  unlinkWidget: (widgetId: string, targetWidgetId: string) => Promise<void>;

  subscribeRealtime: () => () => void;
}

const CACHE_TTL = 60000;

export const useDashboardStore = create<DashboardState>((set, get) => ({
  currentDashboard: null,
  widgets: [],
  globalFilters: {},
  widgetFilters: {},
  widgetDataCache: {},
  loading: false,
  error: null,

  loadDashboard: async (id: string) => {
    set({ loading: true, error: null });
    try {
      const res = await dashboardService.get(id);
      const dashboard = res.data.data;
      set({
        currentDashboard: dashboard,
        widgets: dashboard.widgets || [],
        globalFilters: (dashboard.globalFilters as Record<string, unknown>) || {},
        widgetFilters: {},
        widgetDataCache: {},
      });
    } catch (err) {
      set({ error: (err as Error).message });
      throw err;
    } finally {
      set({ loading: false });
    }
  },

  setCurrentDashboard: (dashboard) => {
    set({
      currentDashboard: dashboard,
      widgets: dashboard?.widgets || [],
      globalFilters: (dashboard?.globalFilters as Record<string, unknown>) || {},
      widgetFilters: {},
      widgetDataCache: {},
    });
  },

  clearDashboard: () => {
    set({
      currentDashboard: null,
      widgets: [],
      globalFilters: {},
      widgetFilters: {},
      widgetDataCache: {},
      error: null,
    });
  },

  setGlobalFilters: (filters) => {
    set({ globalFilters: filters });
  },

  setWidgetFilters: (widgetId, filters) => {
    set((state) => ({
      widgetFilters: { ...state.widgetFilters, [widgetId]: filters },
    }));
  },

  mergeWidgetFilters: (widgetId, filters) => {
    set((state) => ({
      widgetFilters: {
        ...state.widgetFilters,
        [widgetId]: { ...state.widgetFilters[widgetId], ...filters },
      },
    }));
  },

  getCombinedFilters: (widgetId) => {
    const state = get();
    const widget = state.widgets.find((w) => w.id === widgetId);
    return {
      ...state.globalFilters,
      ...(widget?.filters as Record<string, unknown>),
      ...state.widgetFilters[widgetId],
    };
  },

  loadWidgetData: async (widgetId, force = false) => {
    const state = get();
    const widget = state.widgets.find((w) => w.id === widgetId);
    if (!widget?.metricId) return;

    const cache = state.widgetDataCache[widgetId];
    const now = Date.now();

    if (!force && cache && !cache.loading && now - cache.timestamp < CACHE_TTL) {
      return;
    }

    set((state) => ({
      widgetDataCache: {
        ...state.widgetDataCache,
        [widgetId]: {
          ...state.widgetDataCache[widgetId],
          loading: true,
          error: null,
        },
      },
    }));

    try {
      const filters = state.getCombinedFilters(widgetId);
      const params: ExecuteMetricData = {
        filters,
      };
      const res = await metricService.execute(widget.metricId, params);
      const data = res.data.data;

      set((state) => ({
        widgetDataCache: {
          ...state.widgetDataCache,
          [widgetId]: {
            data,
            timestamp: Date.now(),
            loading: false,
            error: null,
          },
        },
      }));
    } catch (err) {
      set((state) => ({
        widgetDataCache: {
          ...state.widgetDataCache,
          [widgetId]: {
            ...state.widgetDataCache[widgetId],
            loading: false,
            error: (err as Error).message,
          },
        },
      }));
      throw err;
    }
  },

  loadAllWidgetData: async () => {
    const state = get();
    const promises = state.widgets
      .filter((w) => w.metricId)
      .map((w) => state.loadWidgetData(w.id));
    await Promise.allSettled(promises);
  },

  setWidgetData: (widgetId, data) => {
    set((state) => ({
      widgetDataCache: {
        ...state.widgetDataCache,
        [widgetId]: {
          data,
          timestamp: Date.now(),
          loading: false,
          error: null,
        },
      },
    }));
  },

  updateWidgetDataIncremental: (widgetId, update) => {
    const state = get();
    const cache = state.widgetDataCache[widgetId];
    if (!cache) return;

    const updatedData = realtimeService.applyIncrementalUpdate(cache.data, update);
    set((state) => ({
      widgetDataCache: {
        ...state.widgetDataCache,
        [widgetId]: {
          ...state.widgetDataCache[widgetId],
          data: updatedData,
          timestamp: Date.now(),
        },
      },
    }));
  },

  clearWidgetData: (widgetId) => {
    set((state) => {
      const newCache = { ...state.widgetDataCache };
      delete newCache[widgetId];
      return { widgetDataCache: newCache };
    });
  },

  clearAllWidgetData: () => {
    set({ widgetDataCache: {} });
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
    get().clearWidgetData(widgetId);
  },

  removeWidget: async (widgetId) => {
    const dashboard = get().currentDashboard;
    if (!dashboard) return;
    await dashboardService.removeWidget(dashboard.id, widgetId);
    set((state) => ({
      widgets: state.widgets.filter((w) => w.id !== widgetId),
    }));
    get().clearWidgetData(widgetId);
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
    await dashboardService.linkWidget(dashboard.id, widgetId, { targetWidgetId });
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

  subscribeRealtime: () => {
    const dashboard = get().currentDashboard;
    if (!dashboard) return () => {};

    realtimeService.subscribeDashboard(dashboard.id);

    const unsubscribers: Array<() => void> = [];

    unsubscribers.push(
      realtimeService.onDashboardUpdate((update) => {
        if (update.widgetId) {
          get().updateWidgetDataIncremental(update.widgetId, update);
        }
      }),
    );

    unsubscribers.push(
      realtimeService.onFilterUpdate((data) => {
        if (data.dashboardId !== dashboard.id) return;

        const affectedWidgets = data.linkedWidgetIds || [data.widgetId];
        affectedWidgets.forEach((widgetId) => {
          get().mergeWidgetFilters(widgetId, data.filters);
          get().loadWidgetData(widgetId, true);
        });
      }),
    );

    return () => {
      realtimeService.unsubscribeDashboard(dashboard.id);
      unsubscribers.forEach((unsub) => unsub());
    };
  },
}));
