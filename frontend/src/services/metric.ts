import api from './api';
import type { Metric, ApiResponse } from '@/types';

export const metricService = {
  list(businessLineId?: string) {
    return api.get<ApiResponse<Metric[]>>('/metrics', {
      params: { businessLineId },
    });
  },

  create(data: Partial<Metric>) {
    return api.post<ApiResponse<Metric>>('/metrics', data);
  },

  update(id: string, data: Partial<Metric>) {
    return api.put<ApiResponse<Metric>>(`/metrics/${id}`, data);
  },

  delete(id: string) {
    return api.delete(`/metrics/${id}`);
  },

  execute(id: string, params?: Record<string, unknown>) {
    return api.post<ApiResponse<Record<string, unknown>[]>>(`/metrics/${id}/execute`, params);
  },

  comparison(id: string, params: Record<string, unknown>) {
    return api.get<ApiResponse<Record<string, unknown>>>(`/metrics/${id}/comparison`, {
      params,
    });
  },

  templates(category?: string) {
    return api.get<ApiResponse<Record<string, unknown>[]>>('/metrics/templates', {
      params: { category },
    });
  },
};
