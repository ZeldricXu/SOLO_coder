"use strict";
var __createBinding = (this && this.__createBinding) || (Object.create ? (function(o, m, k, k2) {
    if (k2 === undefined) k2 = k;
    var desc = Object.getOwnPropertyDescriptor(m, k);
    if (!desc || ("get" in desc ? !m.__esModule : desc.writable || desc.configurable)) {
      desc = { enumerable: true, get: function() { return m[k]; } };
    }
    Object.defineProperty(o, k2, desc);
}) : (function(o, m, k, k2) {
    if (k2 === undefined) k2 = k;
    o[k2] = m[k];
}));
var __setModuleDefault = (this && this.__setModuleDefault) || (Object.create ? (function(o, v) {
    Object.defineProperty(o, "default", { enumerable: true, value: v });
}) : function(o, v) {
    o["default"] = v;
});
var __importStar = (this && this.__importStar) || (function () {
    var ownKeys = function(o) {
        ownKeys = Object.getOwnPropertyNames || function (o) {
            var ar = [];
            for (var k in o) if (Object.prototype.hasOwnProperty.call(o, k)) ar[ar.length] = k;
            return ar;
        };
        return ownKeys(o);
    };
    return function (mod) {
        if (mod && mod.__esModule) return mod;
        var result = {};
        if (mod != null) for (var k = ownKeys(mod), i = 0; i < k.length; i++) if (k[i] !== "default") __createBinding(result, mod, k[i]);
        __setModuleDefault(result, mod);
        return result;
    };
})();
Object.defineProperty(exports, "__esModule", { value: true });
exports.ConfigMapSource = void 0;
const ConfigSource_1 = require("./ConfigSource");
const yaml = __importStar(require("js-yaml"));
class ConfigMapSource extends ConfigSource_1.BaseConfigSource {
    type = 'configmap';
    priority;
    name;
    options;
    k8sApi;
    data = {};
    loaded = false;
    constructor(name, priority, options) {
        super();
        this.name = name;
        this.priority = priority;
        this.options = {
            namespace: 'default',
            ...options,
        };
    }
    async initClient() {
        if (this.k8sApi)
            return;
        const k8s = await Promise.resolve().then(() => __importStar(require('@kubernetes/client-node')));
        const kc = new k8s.KubeConfig();
        if (this.options.kubeconfig) {
            kc.loadFromFile(this.options.kubeconfig);
        }
        else {
            kc.loadFromDefault();
        }
        if (this.options.context) {
            kc.setCurrentContext(this.options.context);
        }
        this.k8sApi = kc.makeApiClient(k8s.CoreV1Api);
    }
    parseConfigMapData(cmData) {
        if (this.options.dataKey) {
            const value = cmData[this.options.dataKey];
            if (!value)
                return {};
            if (this.options.dataKey.endsWith('.json')) {
                return JSON.parse(value);
            }
            else if (this.options.dataKey.endsWith('.yaml') || this.options.dataKey.endsWith('.yml')) {
                return yaml.load(value);
            }
            else if (this.options.dataKey.endsWith('.env')) {
                return this.parseEnvContent(value);
            }
        }
        const result = {};
        for (const [key, value] of Object.entries(cmData)) {
            result[key] = this.parseValue(value);
        }
        return result;
    }
    parseEnvContent(content) {
        const result = {};
        const lines = content.split('\n');
        for (const line of lines) {
            const trimmed = line.trim();
            if (!trimmed || trimmed.startsWith('#') || !trimmed.includes('='))
                continue;
            const eqIndex = trimmed.indexOf('=');
            const key = trimmed.slice(0, eqIndex).trim();
            const value = trimmed.slice(eqIndex + 1).trim().replace(/^["']|["']$/g, '');
            result[key] = this.parseValue(value);
        }
        return result;
    }
    parseValue(value) {
        if (value === 'true')
            return true;
        if (value === 'false')
            return false;
        if (value === 'null')
            return null;
        if (value === '')
            return '';
        const num = Number(value);
        if (!isNaN(num) && value.trim() !== '')
            return num;
        return value;
    }
    serializeValue(value) {
        if (typeof value === 'string')
            return value;
        return JSON.stringify(value);
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
        await this.initClient();
        try {
            const response = await this.k8sApi.readNamespacedConfigMap(this.options.name, this.options.namespace);
            const cmData = response.body?.data || {};
            const parsed = this.parseConfigMapData(cmData);
            this.data = this.flattenData(parsed);
            this.loaded = true;
            return this.data;
        }
        catch (error) {
            if (error.response?.statusCode === 404) {
                this.data = {};
                this.loaded = true;
                return {};
            }
            throw new Error(`Failed to load from ConfigMap: ${error.message}`);
        }
    }
    async get(key) {
        if (!this.loaded) {
            await this.load();
        }
        return this.getNestedValue(this.data, key);
    }
    async set(key, value) {
        await this.initClient();
        try {
            const response = await this.k8sApi.readNamespacedConfigMap(this.options.name, this.options.namespace);
            const currentData = response.body?.data || {};
            if (this.options.dataKey) {
                const existingContent = currentData[this.options.dataKey] || '';
                let parsed;
                if (this.options.dataKey.endsWith('.json')) {
                    parsed = existingContent ? JSON.parse(existingContent) : {};
                }
                else if (this.options.dataKey.endsWith('.yaml') || this.options.dataKey.endsWith('.yml')) {
                    parsed = existingContent ? yaml.load(existingContent) : {};
                }
                else if (this.options.dataKey.endsWith('.env')) {
                    parsed = this.parseEnvContent(existingContent);
                }
                else {
                    parsed = existingContent ? JSON.parse(existingContent) : {};
                }
                this.setNestedValue(parsed, key, value);
                if (this.options.dataKey.endsWith('.json')) {
                    currentData[this.options.dataKey] = JSON.stringify(parsed, null, 2);
                }
                else if (this.options.dataKey.endsWith('.yaml') || this.options.dataKey.endsWith('.yml')) {
                    currentData[this.options.dataKey] = yaml.dump(parsed);
                }
                else if (this.options.dataKey.endsWith('.env')) {
                    const flat = this.flattenData(parsed);
                    currentData[this.options.dataKey] = Object.entries(flat)
                        .map(([k, v]) => `${k}=${this.serializeValue(v)}`)
                        .join('\n');
                }
                else {
                    currentData[this.options.dataKey] = JSON.stringify(parsed, null, 2);
                }
            }
            else {
                currentData[key] = this.serializeValue(value);
            }
            await this.k8sApi.patchNamespacedConfigMap(this.options.name, this.options.namespace, { data: currentData }, undefined, undefined, undefined, undefined, undefined, { headers: { 'Content-Type': 'application/merge-patch+json' } });
            this.setNestedValue(this.data, key, value);
        }
        catch (error) {
            throw new Error(`Failed to write to ConfigMap: ${error.message}`);
        }
    }
    async delete(key) {
        await this.initClient();
        try {
            const response = await this.k8sApi.readNamespacedConfigMap(this.options.name, this.options.namespace);
            const currentData = response.body?.data || {};
            if (this.options.dataKey) {
                const existingContent = currentData[this.options.dataKey] || '';
                let parsed;
                if (this.options.dataKey.endsWith('.json')) {
                    parsed = existingContent ? JSON.parse(existingContent) : {};
                }
                else if (this.options.dataKey.endsWith('.yaml') || this.options.dataKey.endsWith('.yml')) {
                    parsed = existingContent ? yaml.load(existingContent) : {};
                }
                else {
                    parsed = existingContent ? JSON.parse(existingContent) : {};
                }
                const parts = key.split('.');
                let target = parsed;
                for (let i = 0; i < parts.length - 1; i++) {
                    if (!target[parts[i]] || typeof target[parts[i]] !== 'object' || Array.isArray(target[parts[i]])) {
                        return;
                    }
                    target = target[parts[i]];
                }
                delete target[parts[parts.length - 1]];
                if (this.options.dataKey.endsWith('.json')) {
                    currentData[this.options.dataKey] = JSON.stringify(parsed, null, 2);
                }
                else if (this.options.dataKey.endsWith('.yaml') || this.options.dataKey.endsWith('.yml')) {
                    currentData[this.options.dataKey] = yaml.dump(parsed);
                }
                else {
                    currentData[this.options.dataKey] = JSON.stringify(parsed, null, 2);
                }
            }
            else {
                delete currentData[key];
            }
            await this.k8sApi.patchNamespacedConfigMap(this.options.name, this.options.namespace, { data: currentData }, undefined, undefined, undefined, undefined, undefined, { headers: { 'Content-Type': 'application/merge-patch+json' } });
            const parts = key.split('.');
            let targetData = this.data;
            for (let i = 0; i < parts.length - 1; i++) {
                const part = parts[i];
                if (!targetData[part] || typeof targetData[part] !== 'object' || Array.isArray(targetData[part])) {
                    return;
                }
                targetData = targetData[part];
            }
            delete targetData[parts[parts.length - 1]];
        }
        catch (error) {
            throw new Error(`Failed to delete from ConfigMap: ${error.message}`);
        }
    }
    async listKeys() {
        if (!this.loaded) {
            await this.load();
        }
        return Object.keys(this.data);
    }
}
exports.ConfigMapSource = ConfigMapSource;
//# sourceMappingURL=ConfigMapSource.js.map