import api from './api';
import type { AxiosResponse } from 'axios';
import type { DataSource, ApiResponse, DataSourceType } from '@/types';

export interface CreateDataSourceData {
  name: string;
  type: DataSourceType;
  config: Record<string, unknown>;
  poolSize?: number;
  queryTimeout?: number;
  businessLineId: string;
}

export interface UpdateDataSourceData {
  name?: string;
  type?: DataSourceType;
  config?: Record<string, unknown>;
  poolSize?: number;
  queryTimeout?: number;
  businessLineId?: string;
  isActive?: boolean;
}

export interface QueryData {
  sql: string;
  params?: Record<string, unknown>;
}

export const dataSourceService = {
  list(businessLineId?: string): Promise<AxiosResponse<ApiResponse<DataSource[]>>> {
    return api.get<ApiResponse<DataSource[]>>('/data-sources', {
      params: { businessLineId },
    });
  },

  get(id: string): Promise<AxiosResponse<ApiResponse<DataSource>>> {
    return api.get<ApiResponse<DataSource>>(`/data-sources/${id}`);
  },

  create(data: CreateDataSourceData): Promise<AxiosResponse<ApiResponse<DataSource>>> {
    return api.post<ApiResponse<DataSource>>('/data-sources', data);
  },

  update(id: string, data: UpdateDataSourceData): Promise<AxiosResponse<ApiResponse<DataSource>>> {
    return api.put<ApiResponse<DataSource>>(`/data-sources/${id}`, data);
  },

  delete(id: string): Promise<AxiosResponse<ApiResponse<void>>> {
    return api.delete<ApiResponse<void>>(`/data-sources/${id}`);
  },

  test(id: string): Promise<AxiosResponse<ApiResponse<{ success: boolean; message?: string }>>> {
    return api.post<ApiResponse<{ success: boolean; message?: string }>>(`/data-sources/${id}/test`);
  },

  query(id: string, data: QueryData): Promise<AxiosResponse<ApiResponse<Record<string, unknown>[]>>> {
    return api.post<ApiResponse<Record<string, unknown>[]>>(`/data-sources/${id}/query`, data);
  },

  inferSchema(id: string): Promise<AxiosResponse<ApiResponse<Record<string, unknown>[]>>> {
    return api.get<ApiResponse<Record<string, unknown>[]>>(`/data-sources/${id}/schema`);
  },
};
