import {
  FeatureStoreService,
  FeatureEntity,
  FeatureView,
  FeatureValue,
  EntityFeatureValues,
  GetOnlineFeaturesRequest,
  GetOnlineFeaturesResponse,
  FeatureRegistration,
  MaterializeJob,
  Feature
} from '../../core/ports';
import { generateId, logger, ContextLogger, RequestContext, ValidationError } from '../../common';

interface StoredFeatureValue {
  entityKey: string;
  featureName: string;
  value: unknown;
  timestamp: string;
}

export class DefaultFeatureStoreService implements FeatureStoreService {
  private entities: Map<string, FeatureEntity> = new Map();
  private featureViews: Map<string, FeatureView> = new Map();
  private onlineStore: Map<string, StoredFeatureValue[]> = new Map();
  private offlineStore: Map<string, StoredFeatureValue[]> = new Map();
  private materializeJobs: Map<string, MaterializeJob> = new Map();

  async register(registration: FeatureRegistration): Promise<void> {
    const { entity, featureViews } = registration;

    if (this.entities.has(entity.name)) {
      throw new ValidationError(`Entity already exists: ${entity.name}`);
    }

    this.entities.set(entity.name, entity);
    logger.info('Feature entity registered', { entityName: entity.name });

    for (const view of featureViews) {
      if (view.entityName !== entity.name) {
        throw new ValidationError(`Feature view ${view.name} does not belong to entity ${entity.name}`);
      }

      for (const featureName of view.features) {
        if (!entity.features.find(f => f.name === featureName)) {
          throw new ValidationError(`Feature ${featureName} not defined in entity ${entity.name}`);
        }
      }

      this.featureViews.set(view.name, view);
      this.onlineStore.set(view.name, []);
      this.offlineStore.set(view.name, []);
      logger.info('Feature view registered', { viewName: view.name, entityName: entity.name });
    }
  }

  async getEntity(name: string): Promise<FeatureEntity | null> {
    return this.entities.get(name) || null;
  }

  async getFeatureView(name: string): Promise<FeatureView | null> {
    return this.featureViews.get(name) || null;
  }

  async listEntities(): Promise<FeatureEntity[]> {
    return Array.from(this.entities.values());
  }

  async listFeatureViews(entityName?: string): Promise<FeatureView[]> {
    const views = Array.from(this.featureViews.values());
    if (entityName) {
      return views.filter(v => v.entityName === entityName);
    }
    return views;
  }

  async getOnlineFeatures(request: GetOnlineFeaturesRequest): Promise<GetOnlineFeaturesResponse> {
    const results: EntityFeatureValues[] = [];

    for (const entityKey of request.entityKeys) {
      const entityFeatures: EntityFeatureValues = {
        entityKey,
        features: []
      };

      for (const viewName of request.featureViewNames) {
        const view = this.featureViews.get(viewName);
        if (!view) {
          logger.warn('Feature view not found', { viewName });
          continue;
        }

        const storedValues = this.onlineStore.get(viewName) || [];
        const entityValues = storedValues.filter(v => v.entityKey === entityKey);

        if (view.ttl) {
          const cutoffTime = Date.now() - view.ttl * 1000;
          for (const value of entityValues) {
            if (new Date(value.timestamp).getTime() >= cutoffTime) {
              entityFeatures.features.push({
                featureName: value.featureName,
                value: value.value,
                timestamp: value.timestamp
              });
            }
          }
        } else {
          for (const value of entityValues) {
            entityFeatures.features.push({
              featureName: value.featureName,
              value: value.value,
              timestamp: value.timestamp
            });
          }
        }
      }

      results.push(entityFeatures);
    }

    return { results };
  }

  async getHistoricalFeatures(
    featureViewNames: string[],
    entityKeys: string[],
    timestampRange: { start: string; end: string }
  ): Promise<EntityFeatureValues[]> {
    const results: EntityFeatureValues[] = [];
    const startTime = new Date(timestampRange.start).getTime();
    const endTime = new Date(timestampRange.end).getTime();

    for (const entityKey of entityKeys) {
      const entityFeatures: EntityFeatureValues = {
        entityKey,
        features: []
      };

      for (const viewName of featureViewNames) {
        const storedValues = this.offlineStore.get(viewName) || [];
        const entityValues = storedValues.filter(v => {
          const ts = new Date(v.timestamp).getTime();
          return v.entityKey === entityKey && ts >= startTime && ts <= endTime;
        });

        for (const value of entityValues) {
          entityFeatures.features.push({
            featureName: value.featureName,
            value: value.value,
            timestamp: value.timestamp
          });
        }
      }

      results.push(entityFeatures);
    }

    return results;
  }

  async ingestOnlineFeatures(
    featureViewName: string,
    data: Array<{ entityKey: string; features: Record<string, unknown> }>
  ): Promise<void> {
    const view = this.featureViews.get(featureViewName);
    if (!view) {
      throw new ValidationError(`Feature view not found: ${featureViewName}`);
    }

    const storedValues = this.onlineStore.get(featureViewName) || [];
    const now = new Date().toISOString();

    for (const record of data) {
      for (const [featureName, value] of Object.entries(record.features)) {
        if (!view.features.includes(featureName)) {
          continue;
        }

        const existingIndex = storedValues.findIndex(
          v => v.entityKey === record.entityKey && v.featureName === featureName
        );

        const featureValue: StoredFeatureValue = {
          entityKey: record.entityKey,
          featureName,
          value,
          timestamp: now
        };

        if (existingIndex >= 0) {
          storedValues[existingIndex] = featureValue;
        } else {
          storedValues.push(featureValue);
        }
      }
    }

    this.onlineStore.set(featureViewName, storedValues);
    logger.info('Online features ingested', { featureViewName, recordCount: data.length });
  }

  async ingestOfflineFeatures(
    featureViewName: string,
    data: Array<{ entityKey: string; features: Record<string, unknown>; timestamp?: string }>
  ): Promise<void> {
    const view = this.featureViews.get(featureViewName);
    if (!view) {
      throw new ValidationError(`Feature view not found: ${featureViewName}`);
    }

    const storedValues = this.offlineStore.get(featureViewName) || [];

    for (const record of data) {
      const timestamp = record.timestamp || new Date().toISOString();

      for (const [featureName, value] of Object.entries(record.features)) {
        if (!view.features.includes(featureName)) {
          continue;
        }

        storedValues.push({
          entityKey: record.entityKey,
          featureName,
          value,
          timestamp
        });
      }
    }

    this.offlineStore.set(featureViewName, storedValues);
    logger.info('Offline features ingested', { featureViewName, recordCount: data.length });
  }

  async materialize(featureViewName: string, startTime: string, endTime: string): Promise<string> {
    const view = this.featureViews.get(featureViewName);
    if (!view) {
      throw new ValidationError(`Feature view not found: ${featureViewName}`);
    }

    const jobId = generateId('entity');
    const job: MaterializeJob = {
      id: jobId,
      featureViewName,
      startTime,
      endTime,
      status: 'running',
      progress: 0
    };

    this.materializeJobs.set(jobId, job);

    setImmediate(async () => {
      try {
        const offlineValues = this.offlineStore.get(featureViewName) || [];
        const onlineValues = this.onlineStore.get(featureViewName) || [];
        const startTs = new Date(startTime).getTime();
        const endTs = new Date(endTime).getTime();

        const toMaterialize = offlineValues.filter(v => {
          const ts = new Date(v.timestamp).getTime();
          return ts >= startTs && ts <= endTs;
        });

        let processed = 0;
        for (const value of toMaterialize) {
          const existingIndex = onlineValues.findIndex(
            v => v.entityKey === value.entityKey && v.featureName === value.featureName
          );

          if (existingIndex >= 0) {
            onlineValues[existingIndex] = value;
          } else {
            onlineValues.push(value);
          }

          processed++;
          job.progress = Math.round((processed / toMaterialize.length) * 100);
        }

        this.onlineStore.set(featureViewName, onlineValues);
        job.status = 'completed';
        job.progress = 100;

        logger.info('Materialization job completed', { jobId, featureViewName, processedCount: toMaterialize.length });
      } catch (error) {
        job.status = 'failed';
        logger.error('Materialization job failed', { jobId, error: (error as Error).message });
      }
    });

    return jobId;
  }

  async getMaterializeJob(jobId: string): Promise<MaterializeJob | null> {
    return this.materializeJobs.get(jobId) || null;
  }

  async listMaterializeJobs(featureViewName?: string): Promise<MaterializeJob[]> {
    const jobs = Array.from(this.materializeJobs.values());
    if (featureViewName) {
      return jobs.filter(j => j.featureViewName === featureViewName);
    }
    return jobs;
  }

  async deleteOnlineFeatures(featureViewName: string, entityKeys: string[]): Promise<number> {
    const storedValues = this.onlineStore.get(featureViewName);
    if (!storedValues) {
      return 0;
    }

    const initialLength = storedValues.length;
    const filtered = storedValues.filter(v => !entityKeys.includes(v.entityKey));
    this.onlineStore.set(featureViewName, filtered);

    return initialLength - filtered.length;
  }

  async getFeatureStatistics(featureViewName: string): Promise<{
    totalFeatures: number;
    uniqueEntities: number;
    latestTimestamp?: string;
    oldestTimestamp?: string;
  }> {
    const onlineValues = this.onlineStore.get(featureViewName) || [];
    const offlineValues = this.offlineStore.get(featureViewName) || [];
    const allValues = [...onlineValues, ...offlineValues];

    if (allValues.length === 0) {
      return {
        totalFeatures: 0,
        uniqueEntities: 0
      };
    }

    const uniqueEntities = new Set(allValues.map(v => v.entityKey)).size;
    const timestamps = allValues.map(v => new Date(v.timestamp).getTime());

    return {
      totalFeatures: allValues.length,
      uniqueEntities,
      latestTimestamp: new Date(Math.max(...timestamps)).toISOString(),
      oldestTimestamp: new Date(Math.min(...timestamps)).toISOString()
    };
  }

  async checkConsistency(featureViewName: string): Promise<{
    consistent: boolean;
    onlineCount: number;
    offlineCount: number;
    mismatches: Array<{ entityKey: string; featureName: string; onlineValue: unknown; offlineValue: unknown }>;
  }> {
    const onlineValues = this.onlineStore.get(featureViewName) || [];
    const offlineValues = this.offlineStore.get(featureViewName) || [];

    const mismatches: Array<{ entityKey: string; featureName: string; onlineValue: unknown; offlineValue: unknown }> = [];

    const onlineMap = new Map<string, StoredFeatureValue>();
    for (const v of onlineValues) {
      onlineMap.set(`${v.entityKey}:${v.featureName}`, v);
    }

    const offlineMap = new Map<string, StoredFeatureValue>();
    for (const v of offlineValues) {
      offlineMap.set(`${v.entityKey}:${v.featureName}`, v);
    }

    for (const [key, onlineVal] of onlineMap.entries()) {
      const offlineVal = offlineMap.get(key);
      if (offlineVal && JSON.stringify(onlineVal.value) !== JSON.stringify(offlineVal.value)) {
        mismatches.push({
          entityKey: onlineVal.entityKey,
          featureName: onlineVal.featureName,
          onlineValue: onlineVal.value,
          offlineValue: offlineVal.value
        });
      }
    }

    return {
      consistent: mismatches.length === 0,
      onlineCount: onlineValues.length,
      offlineCount: offlineValues.length,
      mismatches
    };
  }
}
