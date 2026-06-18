import api from './api';
import type { AxiosResponse } from 'axios';
import type { Dashboard, Widget, ApiResponse, WidgetType } from '@/types';

export interface CreateDashboardData {
  name: string;
  description?: string;
  layout?: Record<string, unknown>;
  globalFilters?: Record<string, unknown>;
  businessLineId: string;
  isPublic?: boolean;
}

export interface UpdateDashboardData {
  name?: string;
  description?: string;
  layout?: Record<string, unknown>;
  globalFilters?: Record<string, unknown>;
  businessLineId?: string;
  isPublic?: boolean;
}

export interface CreateWidgetData {
  type: WidgetType;
  title: string;
  metricId?: string;
  config: Record<string, unknown>;
  layout: Record<string, unknown>;
  filters?: Record<string, unknown>;
}

export interface UpdateWidgetData {
  type?: WidgetType;
  title?: string;
  metricId?: string;
  config?: Record<string, unknown>;
  layout?: Record<string, unknown>;
  filters?: Record<string, unknown>;
}

export interface LayoutItem {
  widgetId: string;
  layout: Record<string, unknown>;
}

export interface LinkWidgetData {
  targetWidgetId: string;
}

export interface ImportDashboardData {
  data: Record<string, unknown>;
}

export interface ExportedDashboard {
  dashboard: Dashboard;
  widgets: Widget[];
  metrics: Record<string, unknown>[];
  version: string;
  exportedAt: string;
}

export const dashboardService = {
  list(businessLineId?: string): Promise<AxiosResponse<ApiResponse<Dashboard[]>>> {
    return api.get<ApiResponse<Dashboard[]>>('/dashboards', {
      params: { businessLineId },
    });
  },

  get(id: string): Promise<AxiosResponse<ApiResponse<Dashboard>>> {
    return api.get<ApiResponse<Dashboard>>(`/dashboards/${id}`);
  },

  create(data: CreateDashboardData): Promise<AxiosResponse<ApiResponse<Dashboard>>> {
    return api.post<ApiResponse<Dashboard>>('/dashboards', data);
  },

  update(id: string, data: UpdateDashboardData): Promise<AxiosResponse<ApiResponse<Dashboard>>> {
    return api.put<ApiResponse<Dashboard>>(`/dashboards/${id}`, data);
  },

  delete(id: string): Promise<AxiosResponse<ApiResponse<void>>> {
    return api.delete<ApiResponse<void>>(`/dashboards/${id}`);
  },

  export(id: string): Promise<AxiosResponse<ApiResponse<ExportedDashboard>>> {
    return api.get<ApiResponse<ExportedDashboard>>(`/dashboards/${id}/export`);
  },

  import(data: ImportDashboardData): Promise<AxiosResponse<ApiResponse<Dashboard>>> {
    return api.post<ApiResponse<Dashboard>>('/dashboards/import', data);
  },

  addWidget(dashboardId: string, data: CreateWidgetData): Promise<AxiosResponse<ApiResponse<Widget>>> {
    return api.post<ApiResponse<Widget>>(`/dashboards/${dashboardId}/widgets`, data);
  },

  updateWidget(
    dashboardId: string,
    widgetId: string,
    data: UpdateWidgetData,
  ): Promise<AxiosResponse<ApiResponse<Widget>>> {
    return api.put<ApiResponse<Widget>>(`/dashboards/${dashboardId}/widgets/${widgetId}`, data);
  },

  removeWidget(dashboardId: string, widgetId: string): Promise<AxiosResponse<ApiResponse<void>>> {
    return api.delete<ApiResponse<void>>(`/dashboards/${dashboardId}/widgets/${widgetId}`);
  },

  batchUpdateLayout(
    dashboardId: string,
    items: LayoutItem[],
  ): Promise<AxiosResponse<ApiResponse<void>>> {
    return api.put<ApiResponse<void>>(`/dashboards/${dashboardId}/layout`, { items });
  },

  linkWidget(
    dashboardId: string,
    widgetId: string,
    data: LinkWidgetData,
  ): Promise<AxiosResponse<ApiResponse<void>>> {
    return api.post<ApiResponse<void>>(`/dashboards/${dashboardId}/widgets/${widgetId}/link`, data);
  },

  unlinkWidget(
    dashboardId: string,
    widgetId: string,
    targetWidgetId: string,
  ): Promise<AxiosResponse<ApiResponse<void>>> {
    return api.delete<ApiResponse<void>>(
      `/dashboards/${dashboardId}/widgets/${widgetId}/link/${targetWidgetId}`,
    );
  },
};
