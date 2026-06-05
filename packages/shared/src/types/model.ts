import type { ModelFormat, StorageBackend, Status, PaginatedResponse, MetricValue } from './common';

export interface Model {
  id: string;
  name: string;
  description?: string;
  ownerId: string;
  team: string;
  tags: string[];
  status: Status;
  createdAt: number;
  updatedAt: number;
  latestVersion?: ModelVersion;
  versions: ModelVersion[];
  metadata: Record<string, unknown>;
}

export interface ModelVersion {
  id: string;
  modelId: string;
  version: string;
  semanticVersion: string;
  format: ModelFormat;
  sizeBytes: number;
  storageBackend: StorageBackend;
  storagePath: string;
  checksum: string;
  status: 'pending' | 'ready' | 'failed' | 'archived';
  createdAt: number;
  metrics: MetricValue[];
  hyperParameters: Record<string, string | number | boolean | null>;
  dataSchema: ModelDataSchema;
  loaderConfig: Record<string, unknown>;
  experimentId?: string;
  tags: string[];
}

export interface ModelDataSchema {
  inputs: ModelInputOutput[];
  outputs: ModelInputOutput[];
}

export interface ModelInputOutput {
  name: string;
  type: 'float32' | 'float64' | 'int32' | 'int64' | 'string' | 'bool';
  shape: (number | null)[];
  description?: string;
}

export interface RegisteredModel {
  model: Model;
  version: ModelVersion;
  loadStrategy: ModelLoadStrategy;
}

export interface ModelLoadStrategy {
  loaderType: string;
  preprocess?: string;
  postprocess?: string;
  batchSize: number;
  timeoutMs: number;
}

export interface ModelListRequest {
  name?: string;
  ownerId?: string;
  team?: string;
  tags?: string[];
  status?: Status;
  page?: number;
  pageSize?: number;
}

export type ModelListResponse = PaginatedResponse<Model>;

export interface ModelVersionListRequest {
  modelId: string;
  status?: ModelVersion['status'];
  page?: number;
  pageSize?: number;
}

export type ModelVersionListResponse = PaginatedResponse<ModelVersion>;

export interface ModelCreateRequest {
  name: string;
  description?: string;
  ownerId: string;
  team: string;
  tags?: string[];
  metadata?: Record<string, unknown>;
}

export interface ModelVersionCreateRequest {
  modelId: string;
  version: string;
  semanticVersion: string;
  format: ModelFormat;
  file: File | { name: string; size: number; data: Buffer };
  dataSchema: ModelDataSchema;
  metrics?: MetricValue[];
  hyperParameters?: Record<string, string | number | boolean | null>;
  loaderConfig?: Record<string, unknown>;
  experimentId?: string;
  tags?: string[];
}
