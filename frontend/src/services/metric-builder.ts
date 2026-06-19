import api from './api';

export interface FilterCondition {
  field: string;
  operator: 'eq' | 'ne' | 'gt' | 'gte' | 'lt' | 'lte' | 'in' | 'like' | 'between';
  value: any;
}

export interface VisualMetricConfig {
  table: string;
  metricField: string;
  aggregation: 'SUM' | 'COUNT' | 'AVG' | 'MAX' | 'MIN' | 'DISTINCT_COUNT';
  alias?: string;
  timeField?: string;
  granularity?: 'HOUR' | 'DAY' | 'WEEK' | 'MONTH';
  startDate?: string;
  endDate?: string;
  dimensions?: string[];
  filters?: FilterCondition[];
}

export interface SchemaTable {
  table: string;
  columns: {
    name: string;
    type: string;
    nullable: boolean;
  }[];
}

export interface PreviewResult {
  sql: string;
  data: Record<string, any>[];
  fields?: { name: string; type: string }[];
  rowCount: number;
}

export const metricBuilderApi = {
  listTables: (dataSourceId: string) =>
    api.get<SchemaTable[]>(`/metric-builder/${dataSourceId}/tables`).then(r => r.data),

  listColumns: (dataSourceId: string, tableName: string) =>
    api.get<{ name: string; type: string; nullable: boolean }[]>(`/metric-builder/${dataSourceId}/columns`, {
      params: { tableName },
    }).then(r => r.data),

  generateSql: (dataSourceId: string, config: VisualMetricConfig) =>
    api.post<{ sql: string }>(`/metric-builder/${dataSourceId}/generate-sql`, config).then(r => r.data),

  preview: (dataSourceId: string, config: VisualMetricConfig) =>
    api.post<PreviewResult>(`/metric-builder/${dataSourceId}/preview`, config).then(r => r.data),

  createMetric: (dataSourceId: string, config: VisualMetricConfig & {
    name: string;
    description?: string;
    timeWindow?: 'HOUR' | 'DAY' | 'WEEK' | 'MONTH' | 'QUARTER' | 'YEAR';
    isAutoCompare?: boolean;
    businessLineId: string;
  }) =>
    api.post(`/metric-builder/${dataSourceId}/create`, config, {
      params: { businessLineId: config.businessLineId },
    }).then(r => r.data),
};
