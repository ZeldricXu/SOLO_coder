import { EventEmitter } from 'events';
interface FeatureDefinition {
    feature_id: string;
    name: string;
    description?: string;
    value_type: 'int' | 'float' | 'string' | 'bool' | 'vector' | 'json';
    dimensions: string[];
    default_value: unknown;
    is_online: boolean;
    is_offline: boolean;
    ttl_seconds?: number;
    created_at: string;
    created_by: string;
    version: number;
    tags: string[];
}
interface FeatureValue {
    feature_id: string;
    entity_id: string;
    value: unknown;
    timestamp: string;
    version: number;
    dimensions: Record<string, string>;
}
interface FeatureGroup {
    group_id: string;
    name: string;
    description?: string;
    feature_ids: string[];
    created_at: string;
    created_by: string;
}
interface BackfillJob {
    job_id: string;
    feature_id: string;
    status: 'pending' | 'running' | 'completed' | 'failed';
    start_time: string;
    end_time?: string;
    entity_ids: string[];
    progress: number;
    error?: string;
}
interface FeatureOnlineConfig {
    feature_id: string;
    enabled: boolean;
    cache_ttl: number;
    refresh_interval_ms?: number;
}
declare class FeatureStorageService extends EventEmitter {
    private featureDefinitions;
    private onlineFeatureValues;
    private offlineFeatureValues;
    private featureGroups;
    private backfillJobs;
    private onlineConfigs;
    private cache;
    private maxOfflineHistory;
    private maxCacheSize;
    constructor();
    registerFeature(name: string, valueType: FeatureDefinition['value_type'], dimensions: string[], createdBy: string, options?: Partial<Omit<FeatureDefinition, 'feature_id' | 'name' | 'value_type' | 'dimensions' | 'created_at' | 'created_by' | 'version'>>): FeatureDefinition;
    private getDefaultValue;
    updateFeature(featureId: string, updates: Partial<Omit<FeatureDefinition, 'feature_id' | 'created_at' | 'created_by'>>): FeatureDefinition | null;
    getFeature(featureId: string): FeatureDefinition | null;
    listFeatures(tags?: string[]): FeatureDefinition[];
    setOnlineFeatureValue(featureId: string, entityId: string, value: unknown, dimensions?: Record<string, string>): FeatureValue | null;
    getOnlineFeatureValue(featureId: string, entityId: string): FeatureValue | null;
    getOnlineFeatures(featureIds: string[], entityId: string): Record<string, FeatureValue | null>;
    recordOfflineFeatureValue(featureId: string, entityId: string, value: unknown, timestamp?: string, dimensions?: Record<string, string>): FeatureValue | null;
    getOfflineFeatureValues(featureId: string, entityId: string, startTime?: number, endTime?: number, limit?: number): FeatureValue[];
    backfillFeature(featureId: string, entityIds: string[], valueGenerator: (entityId: string) => Promise<unknown>): BackfillJob;
    private processBackfillJob;
    getBackfillJob(jobId: string): BackfillJob | null;
    listBackfillJobs(featureId?: string): BackfillJob[];
    createFeatureGroup(name: string, featureIds: string[], createdBy: string, description?: string): FeatureGroup;
    getFeatureGroup(groupId: string): FeatureGroup | null;
    getOnlineFeaturesByGroup(groupId: string, entityId: string): Record<string, FeatureValue | null> | null;
    configureOnlineFeature(featureId: string, config: Partial<FeatureOnlineConfig>): boolean;
    getOnlineConfig(featureId: string): FeatureOnlineConfig | null;
    checkConsistency(featureId: string, entityId: string): {
        consistent: boolean;
        online_value?: unknown;
        offline_value?: unknown;
    };
    invalidateCache(featureId?: string, entityId?: string): void;
    private getCacheKey;
    getStats(): {
        total_features: number;
        online_features: number;
        offline_features: number;
        feature_groups: number;
        backfill_jobs: number;
        cache_size: number;
    };
}
export declare const featureStorageService: FeatureStorageService;
export { FeatureStorageService, FeatureDefinition, FeatureValue, FeatureGroup, BackfillJob, FeatureOnlineConfig };
//# sourceMappingURL=index.d.ts.map