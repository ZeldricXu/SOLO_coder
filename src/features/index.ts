import { EventEmitter } from 'events';
import NodeCache from 'node-cache';
import { generateId, nowISO } from '../shared/utils';
import { logger } from '../logging';
import { monitoring } from '../monitoring';

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

class FeatureStorageService extends EventEmitter {
  private featureDefinitions: Map<string, FeatureDefinition> = new Map();
  private onlineFeatureValues: Map<string, Map<string, FeatureValue>> = new Map();
  private offlineFeatureValues: Map<string, Map<string, FeatureValue[]>> = new Map();
  private featureGroups: Map<string, FeatureGroup> = new Map();
  private backfillJobs: Map<string, BackfillJob> = new Map();
  private onlineConfigs: Map<string, FeatureOnlineConfig> = new Map();
  private cache: NodeCache;
  private maxOfflineHistory = 1000;
  private maxCacheSize = 10000;

  constructor() {
    super();
    this.cache = new NodeCache({ stdTTL: 300, checkperiod: 60, maxKeys: this.maxCacheSize });
  }

  registerFeature(
    name: string,
    valueType: FeatureDefinition['value_type'],
    dimensions: string[],
    createdBy: string,
    options: Partial<Omit<FeatureDefinition, 'feature_id' | 'name' | 'value_type' | 'dimensions' | 'created_at' | 'created_by' | 'version'>> = {}
  ): FeatureDefinition {
    const featureId = generateId('ft');
    const now = nowISO();

    const definition: FeatureDefinition = {
      feature_id: featureId,
      name,
      description: options.description,
      value_type: valueType,
      dimensions,
      default_value: options.default_value ?? this.getDefaultValue(valueType),
      is_online: options.is_online ?? true,
      is_offline: options.is_offline ?? true,
      ttl_seconds: options.ttl_seconds,
      created_at: now,
      created_by: createdBy,
      version: 1,
      tags: options.tags || [],
    };

    this.featureDefinitions.set(featureId, definition);
    this.onlineFeatureValues.set(featureId, new Map());
    this.offlineFeatureValues.set(featureId, new Map());
    this.onlineConfigs.set(featureId, {
      feature_id: featureId,
      enabled: definition.is_online,
      cache_ttl: definition.ttl_seconds || 300,
    });

    logger.info('Feature registered', { feature_id: featureId, name, value_type: valueType }, createdBy);
    this.emit('feature.registered', definition);

    return definition;
  }

  private getDefaultValue(type: FeatureDefinition['value_type']): unknown {
    switch (type) {
      case 'int':
        return 0;
      case 'float':
        return 0.0;
      case 'string':
        return '';
      case 'bool':
        return false;
      case 'vector':
        return [];
      case 'json':
        return {};
      default:
        return null;
    }
  }

  updateFeature(
    featureId: string,
    updates: Partial<Omit<FeatureDefinition, 'feature_id' | 'created_at' | 'created_by'>>
  ): FeatureDefinition | null {
    const definition = this.featureDefinitions.get(featureId);
    if (!definition) {
      logger.warn('Feature not found for update', { feature_id: featureId });
      return null;
    }

    const updated: FeatureDefinition = {
      ...definition,
      ...updates,
      version: definition.version + 1,
    };

    this.featureDefinitions.set(featureId, updated);
    logger.info('Feature updated', { feature_id: featureId, version: updated.version });
    this.emit('feature.updated', updated);

    return updated;
  }

  getFeature(featureId: string): FeatureDefinition | null {
    return this.featureDefinitions.get(featureId) || null;
  }

  listFeatures(tags?: string[]): FeatureDefinition[] {
    let features = Array.from(this.featureDefinitions.values());
    if (tags && tags.length > 0) {
      features = features.filter((f) => tags.some((t) => f.tags.includes(t)));
    }
    return features;
  }

  setOnlineFeatureValue(
    featureId: string,
    entityId: string,
    value: unknown,
    dimensions: Record<string, string> = {}
  ): FeatureValue | null {
    const definition = this.featureDefinitions.get(featureId);
    if (!definition || !definition.is_online) {
      logger.warn('Feature not available for online serving', { feature_id: featureId });
      return null;
    }

    const featureValue: FeatureValue = {
      feature_id: featureId,
      entity_id: entityId,
      value,
      timestamp: nowISO(),
      version: definition.version,
      dimensions,
    };

    const entityMap = this.onlineFeatureValues.get(featureId);
    if (entityMap) {
      entityMap.set(entityId, featureValue);
    }

    const cacheKey = this.getCacheKey(featureId, entityId);
    this.cache.set(cacheKey, featureValue, definition.ttl_seconds || 300);

    monitoring.incrementCounter('feature_online_writes', 1, { feature_id: featureId });
    this.emit('feature.online_updated', featureValue);

    return featureValue;
  }

  getOnlineFeatureValue(
    featureId: string,
    entityId: string
  ): FeatureValue | null {
    const definition = this.featureDefinitions.get(featureId);
    if (!definition || !definition.is_online) {
      return null;
    }

    const cacheKey = this.getCacheKey(featureId, entityId);
    const cached = this.cache.get<FeatureValue>(cacheKey);
    if (cached) {
      monitoring.incrementCounter('feature_cache_hits', 1, { feature_id: featureId });
      return cached;
    }

    const entityMap = this.onlineFeatureValues.get(featureId);
    const value = entityMap?.get(entityId) || null;

    if (value) {
      this.cache.set(cacheKey, value, definition.ttl_seconds || 300);
    } else {
      monitoring.incrementCounter('feature_cache_misses', 1, { feature_id: featureId });
    }

    return value;
  }

  getOnlineFeatures(featureIds: string[], entityId: string): Record<string, FeatureValue | null> {
    const result: Record<string, FeatureValue | null> = {};
    for (const featureId of featureIds) {
      result[featureId] = this.getOnlineFeatureValue(featureId, entityId);
    }
    return result;
  }

  recordOfflineFeatureValue(
    featureId: string,
    entityId: string,
    value: unknown,
    timestamp?: string,
    dimensions: Record<string, string> = {}
  ): FeatureValue | null {
    const definition = this.featureDefinitions.get(featureId);
    if (!definition || !definition.is_offline) {
      logger.warn('Feature not available for offline storage', { feature_id: featureId });
      return null;
    }

    const featureValue: FeatureValue = {
      feature_id: featureId,
      entity_id: entityId,
      value,
      timestamp: timestamp || nowISO(),
      version: definition.version,
      dimensions,
    };

    const entityMap = this.offlineFeatureValues.get(featureId);
    if (entityMap) {
      if (!entityMap.has(entityId)) {
        entityMap.set(entityId, []);
      }
      const history = entityMap.get(entityId)!;
      history.push(featureValue);
      if (history.length > this.maxOfflineHistory) {
        history.shift();
      }
    }

    monitoring.incrementCounter('feature_offline_writes', 1, { feature_id: featureId });
    this.emit('feature.offline_recorded', featureValue);

    return featureValue;
  }

  getOfflineFeatureValues(
    featureId: string,
    entityId: string,
    startTime?: number,
    endTime?: number,
    limit?: number
  ): FeatureValue[] {
    const entityMap = this.offlineFeatureValues.get(featureId);
    if (!entityMap) return [];

    let values = entityMap.get(entityId) || [];

    if (startTime || endTime) {
      values = values.filter((v) => {
        const ts = new Date(v.timestamp).getTime();
        if (startTime && ts < startTime) return false;
        if (endTime && ts > endTime) return false;
        return true;
      });
    }

    if (limit) {
      values = values.slice(-limit);
    }

    return values.sort((a, b) => new Date(b.timestamp).getTime() - new Date(a.timestamp).getTime());
  }

  backfillFeature(
    featureId: string,
    entityIds: string[],
    valueGenerator: (entityId: string) => Promise<unknown>
  ): BackfillJob {
    const jobId = generateId('bf');
    const job: BackfillJob = {
      job_id: jobId,
      feature_id: featureId,
      status: 'pending',
      start_time: nowISO(),
      entity_ids: entityIds,
      progress: 0,
    };

    this.backfillJobs.set(jobId, job);
    this.processBackfillJob(job, valueGenerator);

    logger.info('Backfill job started', { job_id: jobId, feature_id: featureId, entity_count: entityIds.length });
    this.emit('backfill.started', job);

    return job;
  }

  private async processBackfillJob(
    job: BackfillJob,
    valueGenerator: (entityId: string) => Promise<unknown>
  ): Promise<void> {
    job.status = 'running';
    let completed = 0;

    for (const entityId of job.entity_ids) {
      try {
        const value = await valueGenerator(entityId);
        this.recordOfflineFeatureValue(job.feature_id, entityId, value);
        this.setOnlineFeatureValue(job.feature_id, entityId, value);
      } catch (error) {
        logger.error('Backfill failed for entity', {
          job_id: job.job_id,
          entity_id: entityId,
          error: (error as Error).message,
        });
      }
      completed++;
      job.progress = completed / job.entity_ids.length;
    }

    job.status = 'completed';
    job.end_time = nowISO();
    job.progress = 1;

    logger.info('Backfill job completed', { job_id: job.job_id, feature_id: job.feature_id });
    this.emit('backfill.completed', job);
  }

  getBackfillJob(jobId: string): BackfillJob | null {
    return this.backfillJobs.get(jobId) || null;
  }

  listBackfillJobs(featureId?: string): BackfillJob[] {
    let jobs = Array.from(this.backfillJobs.values());
    if (featureId) {
      jobs = jobs.filter((j) => j.feature_id === featureId);
    }
    return jobs.sort((a, b) => new Date(b.start_time).getTime() - new Date(a.start_time).getTime());
  }

  createFeatureGroup(
    name: string,
    featureIds: string[],
    createdBy: string,
    description?: string
  ): FeatureGroup {
    const groupId = generateId('fg');
    const group: FeatureGroup = {
      group_id: groupId,
      name,
      description,
      feature_ids: featureIds,
      created_at: nowISO(),
      created_by: createdBy,
    };

    this.featureGroups.set(groupId, group);
    logger.info('Feature group created', { group_id: groupId, name, feature_count: featureIds.length }, createdBy);
    this.emit('feature_group.created', group);

    return group;
  }

  getFeatureGroup(groupId: string): FeatureGroup | null {
    return this.featureGroups.get(groupId) || null;
  }

  getOnlineFeaturesByGroup(groupId: string, entityId: string): Record<string, FeatureValue | null> | null {
    const group = this.featureGroups.get(groupId);
    if (!group) return null;
    return this.getOnlineFeatures(group.feature_ids, entityId);
  }

  configureOnlineFeature(featureId: string, config: Partial<FeatureOnlineConfig>): boolean {
    const existing = this.onlineConfigs.get(featureId);
    if (!existing) return false;

    this.onlineConfigs.set(featureId, { ...existing, ...config });
    logger.info('Online feature config updated', { feature_id: featureId, config });
    return true;
  }

  getOnlineConfig(featureId: string): FeatureOnlineConfig | null {
    return this.onlineConfigs.get(featureId) || null;
  }

  checkConsistency(featureId: string, entityId: string): { consistent: boolean; online_value?: unknown; offline_value?: unknown } {
    const online = this.getOnlineFeatureValue(featureId, entityId);
    const offline = this.getOfflineFeatureValues(featureId, entityId, undefined, undefined, 1)[0];

    if (!online || !offline) {
      return { consistent: false, online_value: online?.value, offline_value: offline?.value };
    }

    const consistent = JSON.stringify(online.value) === JSON.stringify(offline.value);
    return { consistent, online_value: online.value, offline_value: offline.value };
  }

  invalidateCache(featureId?: string, entityId?: string): void {
    if (featureId && entityId) {
      this.cache.del(this.getCacheKey(featureId, entityId));
    } else if (featureId) {
      const keys = this.cache.keys();
      for (const key of keys) {
        if (key.startsWith(`feature:${featureId}:`)) {
          this.cache.del(key);
        }
      }
    } else {
      this.cache.flushAll();
    }
    logger.info('Feature cache invalidated', { feature_id: featureId, entity_id: entityId });
  }

  private getCacheKey(featureId: string, entityId: string): string {
    return `feature:${featureId}:${entityId}`;
  }

  getStats(): {
    total_features: number;
    online_features: number;
    offline_features: number;
    feature_groups: number;
    backfill_jobs: number;
    cache_size: number;
  } {
    const features = Array.from(this.featureDefinitions.values());
    return {
      total_features: features.length,
      online_features: features.filter((f) => f.is_online).length,
      offline_features: features.filter((f) => f.is_offline).length,
      feature_groups: this.featureGroups.size,
      backfill_jobs: this.backfillJobs.size,
      cache_size: this.cache.getStats().keys,
    };
  }
}

export const featureStorageService = new FeatureStorageService();
export { FeatureStorageService, FeatureDefinition, FeatureValue, FeatureGroup, BackfillJob, FeatureOnlineConfig };
