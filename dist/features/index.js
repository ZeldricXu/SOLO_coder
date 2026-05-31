"use strict";
var __importDefault = (this && this.__importDefault) || function (mod) {
    return (mod && mod.__esModule) ? mod : { "default": mod };
};
Object.defineProperty(exports, "__esModule", { value: true });
exports.FeatureStorageService = exports.featureStorageService = void 0;
const events_1 = require("events");
const node_cache_1 = __importDefault(require("node-cache"));
const utils_1 = require("../shared/utils");
const logging_1 = require("../logging");
const monitoring_1 = require("../monitoring");
class FeatureStorageService extends events_1.EventEmitter {
    featureDefinitions = new Map();
    onlineFeatureValues = new Map();
    offlineFeatureValues = new Map();
    featureGroups = new Map();
    backfillJobs = new Map();
    onlineConfigs = new Map();
    cache;
    maxOfflineHistory = 1000;
    maxCacheSize = 10000;
    constructor() {
        super();
        this.cache = new node_cache_1.default({ stdTTL: 300, checkperiod: 60, maxKeys: this.maxCacheSize });
    }
    registerFeature(name, valueType, dimensions, createdBy, options = {}) {
        const featureId = (0, utils_1.generateId)('ft');
        const now = (0, utils_1.nowISO)();
        const definition = {
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
        logging_1.logger.info('Feature registered', { feature_id: featureId, name, value_type: valueType }, createdBy);
        this.emit('feature.registered', definition);
        return definition;
    }
    getDefaultValue(type) {
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
    updateFeature(featureId, updates) {
        const definition = this.featureDefinitions.get(featureId);
        if (!definition) {
            logging_1.logger.warn('Feature not found for update', { feature_id: featureId });
            return null;
        }
        const updated = {
            ...definition,
            ...updates,
            version: definition.version + 1,
        };
        this.featureDefinitions.set(featureId, updated);
        logging_1.logger.info('Feature updated', { feature_id: featureId, version: updated.version });
        this.emit('feature.updated', updated);
        return updated;
    }
    getFeature(featureId) {
        return this.featureDefinitions.get(featureId) || null;
    }
    listFeatures(tags) {
        let features = Array.from(this.featureDefinitions.values());
        if (tags && tags.length > 0) {
            features = features.filter((f) => tags.some((t) => f.tags.includes(t)));
        }
        return features;
    }
    setOnlineFeatureValue(featureId, entityId, value, dimensions = {}) {
        const definition = this.featureDefinitions.get(featureId);
        if (!definition || !definition.is_online) {
            logging_1.logger.warn('Feature not available for online serving', { feature_id: featureId });
            return null;
        }
        const featureValue = {
            feature_id: featureId,
            entity_id: entityId,
            value,
            timestamp: (0, utils_1.nowISO)(),
            version: definition.version,
            dimensions,
        };
        const entityMap = this.onlineFeatureValues.get(featureId);
        if (entityMap) {
            entityMap.set(entityId, featureValue);
        }
        const cacheKey = this.getCacheKey(featureId, entityId);
        this.cache.set(cacheKey, featureValue, definition.ttl_seconds || 300);
        monitoring_1.monitoring.incrementCounter('feature_online_writes', 1, { feature_id: featureId });
        this.emit('feature.online_updated', featureValue);
        return featureValue;
    }
    getOnlineFeatureValue(featureId, entityId) {
        const definition = this.featureDefinitions.get(featureId);
        if (!definition || !definition.is_online) {
            return null;
        }
        const cacheKey = this.getCacheKey(featureId, entityId);
        const cached = this.cache.get(cacheKey);
        if (cached) {
            monitoring_1.monitoring.incrementCounter('feature_cache_hits', 1, { feature_id: featureId });
            return cached;
        }
        const entityMap = this.onlineFeatureValues.get(featureId);
        const value = entityMap?.get(entityId) || null;
        if (value) {
            this.cache.set(cacheKey, value, definition.ttl_seconds || 300);
        }
        else {
            monitoring_1.monitoring.incrementCounter('feature_cache_misses', 1, { feature_id: featureId });
        }
        return value;
    }
    getOnlineFeatures(featureIds, entityId) {
        const result = {};
        for (const featureId of featureIds) {
            result[featureId] = this.getOnlineFeatureValue(featureId, entityId);
        }
        return result;
    }
    recordOfflineFeatureValue(featureId, entityId, value, timestamp, dimensions = {}) {
        const definition = this.featureDefinitions.get(featureId);
        if (!definition || !definition.is_offline) {
            logging_1.logger.warn('Feature not available for offline storage', { feature_id: featureId });
            return null;
        }
        const featureValue = {
            feature_id: featureId,
            entity_id: entityId,
            value,
            timestamp: timestamp || (0, utils_1.nowISO)(),
            version: definition.version,
            dimensions,
        };
        const entityMap = this.offlineFeatureValues.get(featureId);
        if (entityMap) {
            if (!entityMap.has(entityId)) {
                entityMap.set(entityId, []);
            }
            const history = entityMap.get(entityId);
            history.push(featureValue);
            if (history.length > this.maxOfflineHistory) {
                history.shift();
            }
        }
        monitoring_1.monitoring.incrementCounter('feature_offline_writes', 1, { feature_id: featureId });
        this.emit('feature.offline_recorded', featureValue);
        return featureValue;
    }
    getOfflineFeatureValues(featureId, entityId, startTime, endTime, limit) {
        const entityMap = this.offlineFeatureValues.get(featureId);
        if (!entityMap)
            return [];
        let values = entityMap.get(entityId) || [];
        if (startTime || endTime) {
            values = values.filter((v) => {
                const ts = new Date(v.timestamp).getTime();
                if (startTime && ts < startTime)
                    return false;
                if (endTime && ts > endTime)
                    return false;
                return true;
            });
        }
        if (limit) {
            values = values.slice(-limit);
        }
        return values.sort((a, b) => new Date(b.timestamp).getTime() - new Date(a.timestamp).getTime());
    }
    backfillFeature(featureId, entityIds, valueGenerator) {
        const jobId = (0, utils_1.generateId)('bf');
        const job = {
            job_id: jobId,
            feature_id: featureId,
            status: 'pending',
            start_time: (0, utils_1.nowISO)(),
            entity_ids: entityIds,
            progress: 0,
        };
        this.backfillJobs.set(jobId, job);
        this.processBackfillJob(job, valueGenerator);
        logging_1.logger.info('Backfill job started', { job_id: jobId, feature_id: featureId, entity_count: entityIds.length });
        this.emit('backfill.started', job);
        return job;
    }
    async processBackfillJob(job, valueGenerator) {
        job.status = 'running';
        let completed = 0;
        for (const entityId of job.entity_ids) {
            try {
                const value = await valueGenerator(entityId);
                this.recordOfflineFeatureValue(job.feature_id, entityId, value);
                this.setOnlineFeatureValue(job.feature_id, entityId, value);
            }
            catch (error) {
                logging_1.logger.error('Backfill failed for entity', {
                    job_id: job.job_id,
                    entity_id: entityId,
                    error: error.message,
                });
            }
            completed++;
            job.progress = completed / job.entity_ids.length;
        }
        job.status = 'completed';
        job.end_time = (0, utils_1.nowISO)();
        job.progress = 1;
        logging_1.logger.info('Backfill job completed', { job_id: job.job_id, feature_id: job.feature_id });
        this.emit('backfill.completed', job);
    }
    getBackfillJob(jobId) {
        return this.backfillJobs.get(jobId) || null;
    }
    listBackfillJobs(featureId) {
        let jobs = Array.from(this.backfillJobs.values());
        if (featureId) {
            jobs = jobs.filter((j) => j.feature_id === featureId);
        }
        return jobs.sort((a, b) => new Date(b.start_time).getTime() - new Date(a.start_time).getTime());
    }
    createFeatureGroup(name, featureIds, createdBy, description) {
        const groupId = (0, utils_1.generateId)('fg');
        const group = {
            group_id: groupId,
            name,
            description,
            feature_ids: featureIds,
            created_at: (0, utils_1.nowISO)(),
            created_by: createdBy,
        };
        this.featureGroups.set(groupId, group);
        logging_1.logger.info('Feature group created', { group_id: groupId, name, feature_count: featureIds.length }, createdBy);
        this.emit('feature_group.created', group);
        return group;
    }
    getFeatureGroup(groupId) {
        return this.featureGroups.get(groupId) || null;
    }
    getOnlineFeaturesByGroup(groupId, entityId) {
        const group = this.featureGroups.get(groupId);
        if (!group)
            return null;
        return this.getOnlineFeatures(group.feature_ids, entityId);
    }
    configureOnlineFeature(featureId, config) {
        const existing = this.onlineConfigs.get(featureId);
        if (!existing)
            return false;
        this.onlineConfigs.set(featureId, { ...existing, ...config });
        logging_1.logger.info('Online feature config updated', { feature_id: featureId, config });
        return true;
    }
    getOnlineConfig(featureId) {
        return this.onlineConfigs.get(featureId) || null;
    }
    checkConsistency(featureId, entityId) {
        const online = this.getOnlineFeatureValue(featureId, entityId);
        const offline = this.getOfflineFeatureValues(featureId, entityId, undefined, undefined, 1)[0];
        if (!online || !offline) {
            return { consistent: false, online_value: online?.value, offline_value: offline?.value };
        }
        const consistent = JSON.stringify(online.value) === JSON.stringify(offline.value);
        return { consistent, online_value: online.value, offline_value: offline.value };
    }
    invalidateCache(featureId, entityId) {
        if (featureId && entityId) {
            this.cache.del(this.getCacheKey(featureId, entityId));
        }
        else if (featureId) {
            const keys = this.cache.keys();
            for (const key of keys) {
                if (key.startsWith(`feature:${featureId}:`)) {
                    this.cache.del(key);
                }
            }
        }
        else {
            this.cache.flushAll();
        }
        logging_1.logger.info('Feature cache invalidated', { feature_id: featureId, entity_id: entityId });
    }
    getCacheKey(featureId, entityId) {
        return `feature:${featureId}:${entityId}`;
    }
    getStats() {
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
exports.FeatureStorageService = FeatureStorageService;
exports.featureStorageService = new FeatureStorageService();
//# sourceMappingURL=index.js.map