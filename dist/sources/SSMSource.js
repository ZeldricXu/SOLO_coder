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
exports.SSMSource = void 0;
const ConfigSource_1 = require("./ConfigSource");
class SSMSource extends ConfigSource_1.BaseConfigSource {
    type = 'ssm';
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
        this.options = {
            withDecryption: true,
            recursive: true,
            ...options,
        };
    }
    async initClient() {
        if (this.client)
            return;
        const { SSMClient } = await Promise.resolve().then(() => __importStar(require('@aws-sdk/client-ssm')));
        const config = {
            region: this.options.region || process.env.AWS_REGION || 'us-east-1',
        };
        if (this.options.accessKeyId && this.options.secretAccessKey) {
            config.credentials = {
                accessKeyId: this.options.accessKeyId,
                secretAccessKey: this.options.secretAccessKey,
            };
        }
        this.client = new SSMClient(config);
    }
    stripPrefix(path) {
        const prefix = this.options.pathPrefix.replace(/\/$/, '');
        return path.startsWith(prefix) ? path.slice(prefix.length + 1) : path;
    }
    normalizeKey(key) {
        return key.replace(/\//g, '.');
    }
    denormalizeKey(key) {
        return key.replace(/\./g, '/');
    }
    convertValue(value) {
        if (value === 'true')
            return true;
        if (value === 'false')
            return false;
        if (value === 'null')
            return null;
        if (value === 'undefined')
            return undefined;
        const num = Number(value);
        if (!isNaN(num) && value.trim() !== '')
            return num;
        try {
            return JSON.parse(value);
        }
        catch {
            return value;
        }
    }
    valueToString(value) {
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
            const { GetParametersByPathCommand } = await Promise.resolve().then(() => __importStar(require('@aws-sdk/client-ssm')));
            const params = {
                Path: this.options.pathPrefix,
                Recursive: this.options.recursive,
                WithDecryption: this.options.withDecryption,
            };
            let nextToken;
            this.data = {};
            do {
                if (nextToken) {
                    params.NextToken = nextToken;
                }
                const command = new GetParametersByPathCommand(params);
                const response = await this.client.send(command);
                if (response.Parameters) {
                    for (const param of response.Parameters) {
                        const stripped = this.stripPrefix(param.Name);
                        const key = this.normalizeKey(stripped);
                        const value = this.convertValue(param.Value || '');
                        this.setNestedValue(this.data, key, value);
                    }
                }
                nextToken = response.NextToken;
            } while (nextToken);
            this.loaded = true;
            return this.flattenData(this.data);
        }
        catch (error) {
            throw new Error(`Failed to load from SSM: ${error.message}`);
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
            const { PutParameterCommand } = await Promise.resolve().then(() => __importStar(require('@aws-sdk/client-ssm')));
            const paramName = `${this.options.pathPrefix.replace(/\/$/, '')}/${this.denormalizeKey(key)}`;
            const command = new PutParameterCommand({
                Name: paramName,
                Value: this.valueToString(value),
                Type: typeof value === 'string' && value.length > 1000 ? 'SecureString' : 'String',
                Overwrite: true,
            });
            await this.client.send(command);
            this.setNestedValue(this.data, key, value);
        }
        catch (error) {
            throw new Error(`Failed to write to SSM: ${error.message}`);
        }
    }
    async delete(key) {
        await this.initClient();
        try {
            const { DeleteParameterCommand } = await Promise.resolve().then(() => __importStar(require('@aws-sdk/client-ssm')));
            const paramName = `${this.options.pathPrefix.replace(/\/$/, '')}/${this.denormalizeKey(key)}`;
            const command = new DeleteParameterCommand({ Name: paramName });
            await this.client.send(command);
            const parts = key.split('.');
            let target = this.data;
            for (let i = 0; i < parts.length - 1; i++) {
                const part = parts[i];
                if (!target[part] || typeof target[part] !== 'object' || Array.isArray(target[part])) {
                    return;
                }
                target = target[part];
            }
            delete target[parts[parts.length - 1]];
        }
        catch (error) {
            throw new Error(`Failed to delete from SSM: ${error.message}`);
        }
    }
    async listKeys() {
        if (!this.loaded) {
            await this.load();
        }
        return Object.keys(this.flattenData(this.data));
    }
}
exports.SSMSource = SSMSource;
//# sourceMappingURL=SSMSource.js.map