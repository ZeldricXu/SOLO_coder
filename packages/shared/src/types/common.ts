export type ModelFormat = 'pkl' | 'onnx' | 'pt' | 'joblib' | 'h5' | 'pb' | 'custom';

export type StorageBackend = 's3' | 'local';

export type Status = 'active' | 'inactive' | 'archived' | 'deleted';

export interface PaginatedRequest {
  page?: number;
  pageSize?: number;
  sortBy?: string;
  sortOrder?: 'asc' | 'desc';
}

export interface PaginatedResponse<T> {
  data: T[];
  total: number;
  page: number;
  pageSize: number;
  totalPages: number;
}

export interface MetricValue {
  name: string;
  value: number;
  timestamp: number;
  step?: number;
  context?: Record<string, unknown>;
}

export interface HyperParameter {
  name: string;
  value: string | number | boolean | null;
  type: 'string' | 'number' | 'boolean' | 'json';
}

export interface SimpleLineageNode {
  id: string;
  type: 'experiment' | 'model' | 'dataset' | 'feature_set';
  name: string;
  metadata?: Record<string, unknown>;
}

export interface SimpleLineageEdge {
  source: string;
  target: string;
  relation: 'parent' | 'child' | 'uses' | 'produces';
}

export interface LineageGraph {
  nodes: SimpleLineageNode[];
  edges: SimpleLineageEdge[];
}
