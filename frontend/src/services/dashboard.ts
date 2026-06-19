import api from './api';
import type { Dashboard, Widget, ApiResponse } from '@/types';

export const dashboardService = {
  list(businessLineId?: string) {
    return api.get<ApiResponse<Dashboard[]>>('/dashboards', {
      params: { businessLineId },
    });
  },

  create(data: Partial<Dashboard>) {
    return api.post<ApiResponse<Dashboard>>('/dashboards', data);
  },

  get(id: string) {
    return api.get<ApiResponse<Dashboard>>(`/dashboards/${id}`);
  },

  update(id: string, data: Partial<Dashboard>) {
    return api.put<ApiResponse<Dashboard>>(`/dashboards/${id}`, data);
  },

  delete(id: string) {
    return api.delete(`/dashboards/${id}`);
  },

  export(id: string) {
    return api.get<ApiResponse<Record<string, unknown>>>(`/dashboards/${id}/export`);
  },

  import(data: Record<string, unknown>) {
    return api.post<ApiResponse<Dashboard>>('/dashboards/import', { data });
  },

  addWidget(dashboardId: string, data: Partial<Widget>) {
    return api.post<ApiResponse<Widget>>(`/dashboards/${dashboardId}/widgets`, data);
  },

  updateWidget(dashboardId: string, widgetId: string, data: Partial<Widget>) {
    return api.put<ApiResponse<Widget>>(`/dashboards/${dashboardId}/widgets/${widgetId}`, data);
  },

  removeWidget(dashboardId: string, widgetId: string) {
    return api.delete(`/dashboards/${dashboardId}/widgets/${widgetId}`);
  },

  batchUpdateLayout(dashboardId: string, items: Array<{ widgetId: string; layout: Record<string, unknown> }>) {
    return api.put<ApiResponse<void>>(`/dashboards/${dashboardId}/layout`, { items });
  },

  linkWidget(dashboardId: string, widgetId: string, targetWidgetId: string) {
    return api.post<ApiResponse<void>>(`/dashboards/${dashboardId}/widgets/${widgetId}/link`, {
      targetWidgetId,
    });
  },

  unlinkWidget(dashboardId: string, widgetId: string, targetWidgetId: string) {
    return api.delete(`/dashboards/${dashboardId}/widgets/${widgetId}/link/${targetWidgetId}`);
  },
};
