"use strict";
Object.defineProperty(exports, "__esModule", { value: true });
exports.ConfigManager = exports.Environment = void 0;
const ConfigSource_1 = require("./ConfigSource");
const VaultSource_1 = require("./VaultSource");
const SSMSource_1 = require("./SSMSource");
const ConfigMapSource_1 = require("./ConfigMapSource");
const EnvSource_1 = require("./EnvSource");
class Environment {
    name;
    sources;
    labels;
    constructor(name, sources, labels) {
        this.name = name;
        this.sources = sources.sort((a, b) => b.priority - a.priority);
        this.labels = labels;
    }
    async loadAll() {
        const merged = {};
        for (const source of this.sources) {
            try {
                const data = await source.load();
                this.deepMerge(merged, data);
            }
            catch (error) {
                console.warn(`Failed to load from source ${source.name} (${source.type}): ${error.message}`);
            }
        }
        return merged;
    }
    async get(key) {
        for (const source of this.sources) {
            try {
                const value = await source.get(key);
                if (value !== undefined) {
                    return value;
                }
            }
            catch (error) {
                console.warn(`Failed to get from source ${source.name}: ${error.message}`);
            }
        }
        return undefined;
    }
    async set(key, value, sourceType) {
        const source = sourceType
            ? this.sources.find((s) => s.type === sourceType)
            : this.sources[0];
        if (!source) {
            throw new Error(`No suitable source found for type: ${sourceType || 'default'}`);
        }
        await source.set(key, value);
    }
    async delete(key, sourceType) {
        const source = sourceType
            ? this.sources.find((s) => s.type === sourceType)
            : this.sources[0];
        if (!source) {
            throw new Error(`No suitable source found`);
        }
        await source.delete(key);
    }
    async listKeys() {
        const keys = new Set();
        for (const source of this.sources) {
            try {
                const sourceKeys = await source.listKeys();
                sourceKeys.forEach((k) => keys.add(k));
            }
            catch (error) {
                console.warn(`Failed to list keys from source ${source.name}: ${error.message}`);
            }
        }
        return Array.from(keys).sort();
    }
    deepMerge(target, source) {
        for (const key of Object.keys(source)) {
            const targetValue = target[key];
            const sourceValue = source[key];
            if (sourceValue !== null &&
                typeof sourceValue === 'object' &&
                !Array.isArray(sourceValue) &&
                targetValue !== null &&
                typeof targetValue === 'object' &&
                !Array.isArray(targetValue)) {
                this.deepMerge(targetValue, sourceValue);
            }
            else {
                target[key] = sourceValue;
            }
        }
    }
    getSourceByType(type) {
        return this.sources.find((s) => s.type === type);
    }
    getHighestPrioritySource() {
        return this.sources[0];
    }
}
exports.Environment = Environment;
class ConfigManager {
    environments = new Map();
    addEnvironment(env) {
        const sources = env.sources.map((config) => this.createSource(config));
        const environment = new Environment(env.name, sources, env.labels);
        this.environments.set(env.name, environment);
        return environment;
    }
    createSource(config) {
        const name = `${config.type}-${config.priority}`;
        switch (config.type) {
            case 'env':
                return new EnvSource_1.EnvSource(name, config.priority, config.options);
            case 'vault':
                return new VaultSource_1.VaultSource(name, config.priority, config.options);
            case 'ssm':
                return new SSMSource_1.SSMSource(name, config.priority, config.options);
            case 'configmap':
                return new ConfigMapSource_1.ConfigMapSource(name, config.priority, config.options);
            case 'default':
                return new DefaultSource(name, config.priority, config.options);
            default:
                throw new Error(`Unsupported source type: ${config.type}`);
        }
    }
    getEnvironment(name) {
        return this.environments.get(name);
    }
    listEnvironments() {
        return Array.from(this.environments.keys()).sort();
    }
    async loadAll() {
        const result = new Map();
        for (const [name, env] of this.environments) {
            result.set(name, await env.loadAll());
        }
        return result;
    }
}
exports.ConfigManager = ConfigManager;
class DefaultSource extends ConfigSource_1.BaseConfigSource {
    type = 'default';
    priority;
    name;
    data;
    constructor(name, priority, options) {
        super();
        this.name = name;
        this.priority = priority;
        this.data = options.defaults || {};
    }
    flattenData(obj, prefix = '') {
        const result = {};
        for (const [key, value] of Object.entries(obj)) {
            const fullKey = prefix ? `${prefix}.${key}` : key;
            if (value !== null && typeof value === 'object' && !Array.isArray(value)) {
                Object.assign(result, this.flattenData(value, fullKey));
            }
            else {
                result[fullKey] = value;
            }
        }
        return result;
    }
    async load() {
        return this.flattenData(this.data);
    }
    async get(key) {
        return this.getNestedValue(this.data, key);
    }
    async set(key, value) {
        this.setNestedValue(this.data, key, value);
    }
    async delete(key) {
        const parts = key.split('.');
        let target = this.data;
        for (let i = 0; i < parts.length - 1; i++) {
            if (!target[parts[i]] || typeof target[parts[i]] !== 'object' || Array.isArray(target[parts[i]])) {
                return;
            }
            target = target[parts[i]];
        }
        delete target[parts[parts.length - 1]];
    }
    async listKeys() {
        return Object.keys(this.flattenData(this.data));
    }
}
//# sourceMappingURL=ConfigManager.js.map