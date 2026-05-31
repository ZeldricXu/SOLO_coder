"use strict";
var __importDefault = (this && this.__importDefault) || function (mod) {
    return (mod && mod.__esModule) ? mod : { "default": mod };
};
Object.defineProperty(exports, "__esModule", { value: true });
exports.ConfigurationManager = exports.configManager = void 0;
const events_1 = require("events");
const node_cache_1 = __importDefault(require("node-cache"));
const utils_1 = require("../shared/utils");
const logging_1 = require("../logging");
class ConfigurationManager extends events_1.EventEmitter {
    configs = new Map();
    activeVersions = new Map();
    rollbackHistory = [];
    cache;
    maxVersionsPerConfig = 50;
    maxRollbackHistory = 200;
    constructor() {
        super();
        this.cache = new node_cache_1.default({ stdTTL: 300, checkperiod: 60 });
    }
    createConfig(namespace, parameters, operator = 'system', enabled = true) {
        const configId = (0, utils_1.generateId)('cfg');
        const version = 1;
        const config = {
            config_id: configId,
            namespace,
            version,
            parameters,
            enabled,
            applied_at: (0, utils_1.nowISO)(),
        };
        this.configs.set(configId, [
            {
                version,
                config,
                applied_at: config.applied_at,
                applied_by: operator,
            },
        ]);
        this.activeVersions.set(configId, version);
        this.cache.set(this.getCacheKey(configId), config);
        logging_1.logger.info('Config created', { config_id: configId, namespace, version }, operator);
        this.emit('config.created', config);
        return config;
    }
    updateConfig(configId, parameters, operator = 'system', enabled) {
        const versions = this.configs.get(configId);
        if (!versions || versions.length === 0) {
            logging_1.logger.warn('Config not found for update', { config_id: configId }, operator);
            return null;
        }
        const latest = versions[versions.length - 1];
        const newVersion = latest.version + 1;
        const mergedParameters = (0, utils_1.mergeDeep)(latest.config.parameters, parameters);
        const config = {
            ...latest.config,
            version: newVersion,
            parameters: mergedParameters,
            enabled: enabled !== undefined ? enabled : latest.config.enabled,
            applied_at: (0, utils_1.nowISO)(),
        };
        versions.push({
            version: newVersion,
            config,
            applied_at: config.applied_at,
            applied_by: operator,
        });
        if (versions.length > this.maxVersionsPerConfig) {
            versions.shift();
        }
        this.activeVersions.set(configId, newVersion);
        this.cache.set(this.getCacheKey(configId), config);
        const diff = this.calculateDiff(latest.config.parameters, config.parameters);
        logging_1.logger.info('Config updated', { config_id: configId, version: newVersion, diff_count: diff.length }, operator);
        this.emit('config.updated', config, diff);
        return config;
    }
    rollbackConfig(configId, targetVersion, reason, operator) {
        const versions = this.configs.get(configId);
        if (!versions || versions.length === 0) {
            logging_1.logger.warn('Config not found for rollback', { config_id: configId }, operator);
            return null;
        }
        const targetConfigVersion = versions.find((v) => v.version === targetVersion);
        if (!targetConfigVersion) {
            logging_1.logger.warn('Target version not found for rollback', { config_id: configId, target_version: targetVersion }, operator);
            return null;
        }
        const latest = versions[versions.length - 1];
        const newVersion = latest.version + 1;
        const config = {
            ...targetConfigVersion.config,
            version: newVersion,
            applied_at: (0, utils_1.nowISO)(),
            rollback_from: latest.version,
        };
        versions.push({
            version: newVersion,
            config,
            applied_at: config.applied_at,
            applied_by: operator,
            rollback_from: latest.version,
        });
        this.activeVersions.set(configId, newVersion);
        this.cache.set(this.getCacheKey(configId), config);
        const rollbackRecord = {
            rollback_id: (0, utils_1.generateId)('rbk'),
            config_id: configId,
            from_version: latest.version,
            to_version: targetVersion,
            timestamp: (0, utils_1.nowISO)(),
            reason,
            operator,
        };
        this.rollbackHistory.push(rollbackRecord);
        if (this.rollbackHistory.length > this.maxRollbackHistory) {
            this.rollbackHistory.shift();
        }
        logging_1.logger.info('Config rolled back', { config_id: configId, from_version: latest.version, to_version: targetVersion }, operator);
        this.emit('config.rolledback', config, rollbackRecord);
        return config;
    }
    getConfig(configId) {
        const cached = this.cache.get(this.getCacheKey(configId));
        if (cached) {
            return cached;
        }
        const versions = this.configs.get(configId);
        if (!versions || versions.length === 0) {
            return null;
        }
        const activeVersion = this.activeVersions.get(configId) || versions[versions.length - 1].version;
        const active = versions.find((v) => v.version === activeVersion) || versions[versions.length - 1];
        this.cache.set(this.getCacheKey(configId), active.config);
        return active.config;
    }
    getConfigVersion(configId, version) {
        const versions = this.configs.get(configId);
        if (!versions)
            return null;
        const found = versions.find((v) => v.version === version);
        return found ? found.config : null;
    }
    listConfigVersions(configId) {
        const versions = this.configs.get(configId);
        if (!versions)
            return [];
        return versions.map((v) => v.config);
    }
    listConfigs(namespace) {
        const result = [];
        for (const [configId, versions] of this.configs.entries()) {
            const activeVersion = this.activeVersions.get(configId) || versions[versions.length - 1].version;
            const active = versions.find((v) => v.version === activeVersion);
            if (active && (!namespace || active.config.namespace === namespace)) {
                result.push(active.config);
            }
        }
        return result;
    }
    deleteConfig(configId) {
        const existed = this.configs.has(configId);
        if (existed) {
            this.configs.delete(configId);
            this.activeVersions.delete(configId);
            this.cache.del(this.getCacheKey(configId));
            logging_1.logger.info('Config deleted', { config_id: configId });
            this.emit('config.deleted', configId);
        }
        return existed;
    }
    enableConfig(configId) {
        return this.updateConfig(configId, {}, 'system', true);
    }
    disableConfig(configId) {
        return this.updateConfig(configId, {}, 'system', false);
    }
    diffConfigs(configId, versionA, versionB) {
        const cfgA = this.getConfigVersion(configId, versionA);
        const cfgB = this.getConfigVersion(configId, versionB);
        if (!cfgA || !cfgB) {
            return [];
        }
        return this.calculateDiff(cfgA.parameters, cfgB.parameters);
    }
    getRollbackHistory(configId) {
        if (configId) {
            return this.rollbackHistory.filter((r) => r.config_id === configId);
        }
        return [...this.rollbackHistory];
    }
    validateConfig(parameters, schema) {
        if (schema) {
            try {
                return schema(parameters);
            }
            catch {
                return false;
            }
        }
        return typeof parameters === 'object' && parameters !== null;
    }
    calculateDiff(oldObj, newObj, path = '') {
        const diffs = [];
        const allKeys = new Set([...Object.keys(oldObj), ...Object.keys(newObj)]);
        for (const key of allKeys) {
            const currentPath = path ? `${path}.${key}` : key;
            const oldVal = oldObj[key];
            const newVal = newObj[key];
            if (oldVal === undefined) {
                diffs.push({ path: currentPath, oldValue: undefined, newValue: newVal, operation: 'added' });
            }
            else if (newVal === undefined) {
                diffs.push({ path: currentPath, oldValue: oldVal, newValue: undefined, operation: 'removed' });
            }
            else if (typeof oldVal === 'object' &&
                oldVal !== null &&
                typeof newVal === 'object' &&
                newVal !== null &&
                !Array.isArray(oldVal) &&
                !Array.isArray(newVal)) {
                diffs.push(...this.calculateDiff(oldObj, newObj, currentPath));
            }
            else if (JSON.stringify(oldVal) !== JSON.stringify(newVal)) {
                diffs.push({ path: currentPath, oldValue: oldVal, newValue: newVal, operation: 'modified' });
            }
        }
        return diffs;
    }
    getCacheKey(configId) {
        return `config:${configId}`;
    }
    clearCache() {
        this.cache.flushAll();
        logging_1.logger.debug('Config cache cleared');
    }
}
exports.ConfigurationManager = ConfigurationManager;
exports.configManager = new ConfigurationManager();
//# sourceMappingURL=index.js.map