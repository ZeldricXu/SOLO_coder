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
exports.VaultSource = void 0;
const ConfigSource_1 = require("./ConfigSource");
class VaultSource extends ConfigSource_1.BaseConfigSource {
    type = 'vault';
    priority;
    name;
    options;
    client;
    data = {};
    loaded = false;
    constructor(name, priority, options) {
        super();
        this.name = name;
        this.priority = priority;
        this.options = options;
    }
    async initClient() {
        if (this.client)
            return;
        const vault = await Promise.resolve().then(() => __importStar(require('node-vault')));
        const clientOptions = {
            apiVersion: 'v1',
            endpoint: this.options.endpoint || process.env.VAULT_ADDR || 'http://127.0.0.1:8200',
        };
        if (this.options.namespace) {
            clientOptions.namespace = this.options.namespace;
        }
        this.client = vault.default(clientOptions);
        if (this.options.roleId && this.options.secretId) {
            const result = await this.client.approleLogin({
                role_id: this.options.roleId,
                secret_id: this.options.secretId,
            });
            this.client.token = result.auth.client_token;
        }
        else {
            this.client.token = this.options.token || process.env.VAULT_TOKEN;
        }
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
            const result = await this.client.read(this.options.path);
            const rawData = result?.data?.data || result?.data || {};
            this.data = rawData;
            this.loaded = true;
            return this.flattenData(rawData);
        }
        catch (error) {
            throw new Error(`Failed to load from Vault: ${error.message}`);
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
            const current = await this.client.read(this.options.path);
            const existingData = current?.data?.data || current?.data || {};
            const parts = key.split('.');
            let target = existingData;
            for (let i = 0; i < parts.length - 1; i++) {
                if (!target[parts[i]] || typeof target[parts[i]] !== 'object' || Array.isArray(target[parts[i]])) {
                    target[parts[i]] = {};
                }
                target = target[parts[i]];
            }
            target[parts[parts.length - 1]] = value;
            await this.client.write(this.options.path, { data: existingData });
            this.setNestedValue(this.data, key, value);
        }
        catch (error) {
            throw new Error(`Failed to write to Vault: ${error.message}`);
        }
    }
    async delete(key) {
        await this.initClient();
        try {
            const current = await this.client.read(this.options.path);
            const existingData = current?.data?.data || current?.data || {};
            const parts = key.split('.');
            let target = existingData;
            for (let i = 0; i < parts.length - 1; i++) {
                if (!target[parts[i]] || typeof target[parts[i]] !== 'object') {
                    return;
                }
                target = target[parts[i]];
            }
            delete target[parts[parts.length - 1]];
            await this.client.write(this.options.path, { data: existingData });
            const data = this.data;
            let targetData = data;
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
            throw new Error(`Failed to delete from Vault: ${error.message}`);
        }
    }
    async listKeys() {
        if (!this.loaded) {
            await this.load();
        }
        return Object.keys(this.flattenData(this.data));
    }
}
exports.VaultSource = VaultSource;
//# sourceMappingURL=VaultSource.js.map