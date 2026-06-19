import api from './api';
import type { DataSource, ApiResponse } from '@/types';

export const dataSourceService = {
  list(businessLineId?: string) {
    return api.get<ApiResponse<DataSource[]>>('/data-sources', {
      params: { businessLineId },
    });
  },

  create(data: Partial<DataSource>) {
    return api.post<ApiResponse<DataSource>>('/data-sources', data);
  },

  update(id: string, data: Partial<DataSource>) {
    return api.put<ApiResponse<DataSource>>(`/data-sources/${id}`, data);
  },

  delete(id: string) {
    return api.delete(`/data-sources/${id}`);
  },

  test(id: string) {
    return api.post<ApiResponse<{ success: boolean; message?: string }>>(`/data-sources/${id}/test`);
  },

  query(id: string, sql: string, params?: Record<string, unknown>) {
    return api.post<ApiResponse<Record<string, unknown>[]>>(`/data-sources/${id}/query`, {
      sql,
      params,
    });
  },

  schema(id: string) {
    return api.get<ApiResponse<Record<string, unknown>>>(`/data-sources/${id}/schema`);
  },
};
