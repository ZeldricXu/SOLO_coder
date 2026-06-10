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
exports.EnvSource = void 0;
const ConfigSource_1 = require("./ConfigSource");
const fs = __importStar(require("fs"));
const path = __importStar(require("path"));
const dotenv = __importStar(require("dotenv"));
class EnvSource extends ConfigSource_1.BaseConfigSource {
    type = 'env';
    priority;
    name;
    options;
    data = {};
    loaded = false;
    constructor(name, priority, options = {}) {
        super();
        this.name = name;
        this.priority = priority;
        this.options = {
            useProcessEnv: true,
            lowerCaseKeys: true,
            ...options,
        };
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
        try {
            return JSON.parse(value);
        }
        catch {
            return value;
        }
    }
    normalizeKey(key) {
        let normalized = key;
        if (this.options.prefix) {
            if (!normalized.startsWith(this.options.prefix)) {
                return '';
            }
            normalized = normalized.slice(this.options.prefix.length);
        }
        if (this.options.lowerCaseKeys) {
            normalized = normalized.toLowerCase();
        }
        return normalized.replace(/_/g, '.');
    }
    async load() {
        this.data = {};
        if (this.options.filePath) {
            const resolvedPath = path.resolve(this.options.filePath);
            if (fs.existsSync(resolvedPath)) {
                const content = fs.readFileSync(resolvedPath, 'utf-8');
                const parsed = dotenv.parse(content);
                for (const [key, value] of Object.entries(parsed)) {
                    const normalized = this.normalizeKey(key);
                    if (normalized) {
                        this.setNestedValue(this.data, normalized, this.parseValue(value));
                    }
                }
            }
        }
        if (this.options.useProcessEnv) {
            for (const [key, value] of Object.entries(process.env)) {
                const normalized = this.normalizeKey(key);
                if (normalized && value !== undefined) {
                    this.setNestedValue(this.data, normalized, this.parseValue(value));
                }
            }
        }
        this.loaded = true;
        return this.data;
    }
    async get(key) {
        if (!this.loaded) {
            await this.load();
        }
        return this.getNestedValue(this.data, key);
    }
    async set(key, value) {
        if (!this.loaded) {
            await this.load();
        }
        this.setNestedValue(this.data, key, value);
        if (this.options.filePath) {
            const resolvedPath = path.resolve(this.options.filePath);
            let lines = [];
            if (fs.existsSync(resolvedPath)) {
                lines = fs.readFileSync(resolvedPath, 'utf-8').split('\n');
            }
            const envKey = key.toUpperCase().replace(/\./g, '_');
            if (this.options.prefix) {
                const fullKey = this.options.prefix + envKey;
                const lineIndex = lines.findIndex((l) => l.startsWith(fullKey + '=') || l.startsWith(fullKey + ' ='));
                const serialized = typeof value === 'string' ? value : JSON.stringify(value);
                const newLine = `${fullKey}=${serialized}`;
                if (lineIndex >= 0) {
                    lines[lineIndex] = newLine;
                }
                else {
                    lines.push(newLine);
                }
            }
            fs.writeFileSync(resolvedPath, lines.join('\n'));
        }
    }
    async delete(key) {
        if (!this.loaded) {
            await this.load();
        }
        const parts = key.split('.');
        let target = this.data;
        for (let i = 0; i < parts.length - 1; i++) {
            if (!target[parts[i]] || typeof target[parts[i]] !== 'object' || Array.isArray(target[parts[i]])) {
                return;
            }
            target = target[parts[i]];
        }
        delete target[parts[parts.length - 1]];
        if (this.options.filePath) {
            const resolvedPath = path.resolve(this.options.filePath);
            if (fs.existsSync(resolvedPath)) {
                const envKey = key.toUpperCase().replace(/\./g, '_');
                const fullKey = this.options.prefix ? this.options.prefix + envKey : envKey;
                let lines = fs.readFileSync(resolvedPath, 'utf-8').split('\n');
                lines = lines.filter((l) => !l.startsWith(fullKey + '=') && !l.startsWith(fullKey + ' ='));
                fs.writeFileSync(resolvedPath, lines.join('\n'));
            }
        }
    }
    async listKeys() {
        if (!this.loaded) {
            await this.load();
        }
        return Object.keys(this.data);
    }
}
exports.EnvSource = EnvSource;
//# sourceMappingURL=EnvSource.js.map