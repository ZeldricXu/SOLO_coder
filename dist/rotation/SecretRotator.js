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
var __importDefault = (this && this.__importDefault) || function (mod) {
    return (mod && mod.__esModule) ? mod : { "default": mod };
};
Object.defineProperty(exports, "__esModule", { value: true });
exports.SecretRotator = void 0;
const axios_1 = __importDefault(require("axios"));
const crypto = __importStar(require("crypto"));
class SecretRotator {
    storage;
    constructor(storage) {
        this.storage = storage;
    }
    generateSecret(length = 32, characters) {
        const chars = characters || 'abcdefghijklmnopqrstuvwxyzABCDEFGHIJKLMNOPQRSTUVWXYZ0123456789!@#$%^&*()_+-=[]{}|;:,.<>?';
        const randomBytes = crypto.randomBytes(length);
        let result = '';
        for (let i = 0; i < length; i++) {
            result += chars[randomBytes[i] % chars.length];
        }
        return result;
    }
    async rotateSecret(env, config, operator) {
        const record = {
            id: crypto.randomUUID(),
            key: config.key,
            environment: config.environment,
            sourceType: config.sourceType,
            timestamp: Date.now(),
            operator,
            status: 'success',
        };
        try {
            const newSecret = this.generateSecret(config.length, config.characters);
            await env.set(config.key, newSecret, config.sourceType);
            const verifyValue = await env.get(config.key);
            if (verifyValue !== newSecret) {
                throw new Error('Secret verification failed after rotation');
            }
            if (config.notifyWebhook) {
                try {
                    await this.notifyService(config.notifyWebhook, config.key, config.environment);
                }
                catch (notifyError) {
                    console.warn(`Failed to notify service: ${notifyError.message}`);
                }
            }
            if (this.storage) {
                await this.storage.saveRotationRecord(record);
            }
            return record;
        }
        catch (error) {
            record.status = 'failed';
            record.message = error.message;
            if (this.storage) {
                await this.storage.saveRotationRecord(record);
            }
            return record;
        }
    }
    async rotateMultiple(env, configs, operator) {
        const results = [];
        for (const config of configs) {
            const result = await this.rotateSecret(env, config, operator);
            results.push(result);
        }
        return results;
    }
    async notifyService(webhookUrl, key, environment) {
        await axios_1.default.post(webhookUrl, {
            event: 'secret_rotated',
            key,
            environment,
            timestamp: Date.now(),
        });
    }
    async getHistory(key, environment, limit = 20) {
        if (!this.storage) {
            throw new Error('Storage not configured for rotation history');
        }
        return this.storage.getRotationHistory(key, environment, limit);
    }
    async scheduleRotation(env, config, operator, intervalMs) {
        const interval = setInterval(async () => {
            await this.rotateSecret(env, config, operator);
        }, intervalMs);
        return interval;
    }
    async batchRotateByPattern(env, pattern, sourceType, operator, length = 32) {
        const keys = await env.listKeys();
        const matchingKeys = keys.filter((k) => pattern.test(k));
        const configs = matchingKeys.map((key) => ({
            key,
            environment: env.name,
            sourceType,
            length,
        }));
        return this.rotateMultiple(env, configs, operator);
    }
}
exports.SecretRotator = SecretRotator;
//# sourceMappingURL=SecretRotator.js.map