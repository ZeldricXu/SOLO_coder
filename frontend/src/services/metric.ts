import api from './api';
import type { AxiosResponse } from 'axios';
import type { Metric, ApiResponse, MetricType, Aggregation, TimeWindow } from '@/types';

export interface CreateMetricData {
  name: string;
  description?: string;
  type: MetricType;
  sqlTemplate?: string;
  templateId?: string;
  aggregation: Aggregation;
  timeWindow: TimeWindow;
  dimensions: Record<string, unknown>;
  dataSourceId: string;
  businessLineId: string;
  isAutoCompare?: boolean;
}

export interface UpdateMetricData {
  name?: string;
  description?: string;
  type?: MetricType;
  sqlTemplate?: string;
  templateId?: string;
  aggregation?: Aggregation;
  timeWindow?: TimeWindow;
  dimensions?: Record<string, unknown>;
  dataSourceId?: string;
  businessLineId?: string;
  isAutoCompare?: boolean;
}

export interface ExecuteMetricData {
  params?: Record<string, unknown>;
  filters?: Record<string, unknown>;
}

export interface DateRange {
  start: string;
  end: string;
}

export interface ComparisonParams {
  type: 'yoy' | 'mom';
  dateRange: DateRange;
}

export interface MetricTemplate {
  id: string;
  name: string;
  category: string;
  description: string;
  sqlTemplate: string;
  aggregation: Aggregation;
  timeWindow: TimeWindow;
  dimensions: string[];
}

export const metricService = {
  list(businessLineId?: string): Promise<AxiosResponse<ApiResponse<Metric[]>>> {
    return api.get<ApiResponse<Metric[]>>('/metrics', {
      params: { businessLineId },
    });
  },

  get(id: string): Promise<AxiosResponse<ApiResponse<Metric>>> {
    return api.get<ApiResponse<Metric>>(`/metrics/${id}`);
  },

  create(data: CreateMetricData): Promise<AxiosResponse<ApiResponse<Metric>>> {
    return api.post<ApiResponse<Metric>>('/metrics', data);
  },

  update(id: string, data: UpdateMetricData): Promise<AxiosResponse<ApiResponse<Metric>>> {
    return api.put<ApiResponse<Metric>>(`/metrics/${id}`, data);
  },

  delete(id: string): Promise<AxiosResponse<ApiResponse<void>>> {
    return api.delete<ApiResponse<void>>(`/metrics/${id}`);
  },

  execute(id: string, data: ExecuteMetricData): Promise<AxiosResponse<ApiResponse<Record<string, unknown>[]>>> {
    return api.post<ApiResponse<Record<string, unknown>[]>>(`/metrics/${id}/execute`, data);
  },

  getComparison(id: string, params: ComparisonParams): Promise<AxiosResponse<ApiResponse<{
    current: Record<string, unknown>[];
    previous: Record<string, unknown>[];
    changeRate: number;
  }>>> {
    return api.get<ApiResponse<{
      current: Record<string, unknown>[];
      previous: Record<string, unknown>[];
      changeRate: number;
    }>>(`/metrics/${id}/comparison`, {
      params,
    });
  },

  getTemplates(category?: string): Promise<AxiosResponse<ApiResponse<MetricTemplate[]>>> {
    return api.get<ApiResponse<MetricTemplate[]>>('/metrics/templates', {
      params: { category },
    });
  },
};
