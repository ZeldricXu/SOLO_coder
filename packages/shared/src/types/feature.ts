import type { Status, PaginatedResponse } from './common';

export type FeatureMode = 'online' | 'offline' | 'both';
export type FeatureValueType = 'float' | 'int' | 'string' | 'bool' | 'bytes' | 'json';

export interface FeatureSet {
  id: string;
  name: string;
  description?: string;
  projectId: string;
  ownerId: string;
  team: string;
  mode: FeatureMode;
  entities: FeatureEntity[];
  features: Feature[];
  tags: string[];
  status: Status;
  ttlSeconds?: number;
  onlineStorage?: FeatureStorageConfig;
  offlineStorage?: FeatureStorageConfig;
  createdAt: number;
  updatedAt: number;
  latestVersion?: FeatureSetVersion;
  versions: FeatureSetVersion[];
}

export interface FeatureEntity {
  name: string;
  description?: string;
  joinKey: string;
}

export interface Feature {
  name: string;
  description?: string;
  valueType: FeatureValueType;
  defaultValue?: unknown;
  isNullable: boolean;
  tags: string[];
  statistics?: FeatureStatistics;
}

export interface FeatureStatistics {
  min?: number;
  max?: number;
  mean?: number;
  std?: number;
  median?: number;
  nullCount?: number;
  uniqueCount?: number;
  histogram?: { bins: number[]; counts: number[] };
  lastUpdated?: number;
}

export interface FeatureSetVersion {
  id: string;
  featureSetId: string;
  version: string;
  featureSchema: { entities: FeatureEntity[]; features: Feature[] };
  sourceUri?: string;
  rowCount?: number;
  sizeBytes?: number;
  createdAt: number;
  status: 'active' | 'deprecated' | 'archived';
}

export interface FeatureStorageConfig {
  type: 'redis' | 'postgres' | 's3' | 'bigquery' | 'snowflake';
  connectionString?: string;
  tableName?: string;
  bucketName?: string;
  prefix?: string;
}

export interface FeatureValue {
  featureName: string;
  entityKey: string;
  value: unknown;
  timestamp: number;
}

export interface FeatureGetRequest {
  featureSetId: string;
  featureNames?: string[];
  entityKeys: string[];
  version?: string;
}

export interface FeatureGetResponse {
  featureSetId: string;
  version: string;
  values: Record<string, Record<string, unknown>>;
  timestamp: number;
}

export interface FeatureIngestRequest {
  featureSetId: string;
  version?: string;
  data: Record<string, unknown>[];
  entityKeyField: string;
  timestampField?: string;
  mode: 'overwrite' | 'append' | 'upsert';
}

export interface FeatureListRequest {
  name?: string;
  projectId?: string;
  ownerId?: string;
  team?: string;
  mode?: FeatureMode;
  tags?: string[];
  status?: Status;
  page?: number;
  pageSize?: number;
}

export type FeatureListResponse = PaginatedResponse<FeatureSet>;

export interface FeatureSetCreateRequest {
  name: string;
  description?: string;
  projectId: string;
  ownerId: string;
  team: string;
  mode: FeatureMode;
  entities: FeatureEntity[];
  features: Feature[];
  ttlSeconds?: number;
  onlineStorage?: FeatureStorageConfig;
  offlineStorage?: FeatureStorageConfig;
  tags?: string[];
}

export interface FeatureVersionCreateRequest {
  featureSetId: string;
  version: string;
  featureSchema: { entities: FeatureEntity[]; features: Feature[] };
  sourceUri?: string;
  rowCount?: number;
  sizeBytes?: number;
}

export interface FeatureDistributionResponse {
  featureName: string;
  statistics: FeatureStatistics;
  distribution?: { bins: number[]; counts: number[] };
}
