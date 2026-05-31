export type FeatureValueType = 'int64' | 'float64' | 'string' | 'bool' | 'bytes' | 'int64_list' | 'float64_list' | 'string_list';

export type FeatureStoreMode = 'online' | 'offline' | 'hybrid';

export interface Feature {
  name: string;
  valueType: FeatureValueType;
  description?: string;
  labels?: Record<string, string>;
}

export interface FeatureEntity {
  name: string;
  joinKey: string;
  features: Feature[];
  description?: string;
}

export interface FeatureView {
  name: string;
  entityName: string;
  features: string[];
  ttl?: number;
  version: number;
}

export interface FeatureValue {
  featureName: string;
  value: unknown;
  timestamp: string;
}

export interface EntityFeatureValues {
  entityKey: string;
  features: FeatureValue[];
}

export interface GetOnlineFeaturesRequest {
  featureViewNames: string[];
  entityKeys: string[];
}

export interface GetOnlineFeaturesResponse {
  results: EntityFeatureValues[];
}

export interface FeatureRegistration {
  entity: FeatureEntity;
  featureViews: FeatureView[];
}

export interface MaterializeJob {
  id: string;
  featureViewName: string;
  startTime: string;
  endTime: string;
  status: 'pending' | 'running' | 'completed' | 'failed';
  progress: number;
}

export interface FeatureStoreService {
  register(registration: FeatureRegistration): Promise<void>;
  getEntity(name: string): Promise<FeatureEntity | null>;
  getFeatureView(name: string): Promise<FeatureView | null>;
  getOnlineFeatures(request: GetOnlineFeaturesRequest): Promise<GetOnlineFeaturesResponse>;
  getHistoricalFeatures(
    featureViewNames: string[],
    entityKeys: string[],
    timestampRange: { start: string; end: string }
  ): Promise<EntityFeatureValues[]>;
  materialize(featureViewName: string, startTime: string, endTime: string): Promise<string>;
  getMaterializeJob(jobId: string): Promise<MaterializeJob | null>;
}
