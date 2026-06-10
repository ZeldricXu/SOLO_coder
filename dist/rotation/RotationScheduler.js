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
exports.RotationScheduler = void 0;
const crypto = __importStar(require("crypto"));
class RotationScheduler {
    records = [];
    scheduledRotations = new Map();
    defaultOperator;
    constructor(defaultOperator) {
        this.defaultOperator = defaultOperator || process.env.USER || 'system';
    }
    generateRandomKey(length = 32, characters = 'abcdefghijklmnopqrstuvwxyzABCDEFGHIJKLMNOPQRSTUVWXYZ0123456789!@#$%^&*()_+-=[]{}|;:,.<>?') {
        const array = new Uint32Array(length);
        crypto.randomFillSync(array);
        let result = '';
        for (let i = 0; i < length; i++) {
            result += characters[array[i] % characters.length];
        }
        return result;
    }
    async rotateSecret(environment, key, options = {}) {
        const operator = options.operator || this.defaultOperator;
        const recordId = this.generateRecordId();
        let sourceType = 'default';
        const vaultSource = environment.getSourceByType('vault');
        const ssmSource = environment.getSourceByType('ssm');
        const source = vaultSource || ssmSource;
        if (vaultSource)
            sourceType = 'vault';
        else if (ssmSource)
            sourceType = 'ssm';
        let oldValue;
        let newValue;
        try {
            if (options.onBeforeRotate) {
                await options.onBeforeRotate(key, environment.name);
            }
            if (source) {
                oldValue = await source.get(key);
            }
            else {
                oldValue = await environment.get(key);
            }
            newValue = this.generateRandomKey(32);
            const highestSource = source || environment.getHighestPrioritySource();
            await highestSource.set(key, newValue);
            if (options.verify) {
                const verifiedValue = await environment.get(key);
                if (verifiedValue !== newValue) {
                    throw new Error(`Verification failed: value does not match after rotation`);
                }
            }
            if (options.onAfterRotate) {
                await options.onAfterRotate(key, environment.name, oldValue, newValue);
            }
            if (options.onNotify) {
                await options.onNotify(`Secret rotated: ${key} in ${environment.name} by ${operator}`);
            }
            const record = {
                id: recordId,
                key,
                environment: environment.name,
                sourceType,
                timestamp: Date.now(),
                operator,
                status: 'success',
            };
            this.records.push(record);
            return record;
        }
        catch (error) {
            const record = {
                id: recordId,
                key,
                environment: environment.name,
                sourceType,
                timestamp: Date.now(),
                operator,
                status: 'failed',
                message: error.message,
            };
            this.records.push(record);
            throw error;
        }
    }
    async rotateBatch(environment, keys, options = {}) {
        const results = [];
        for (const key of keys) {
            try {
                const record = await this.rotateSecret(environment, key, options);
                results.push(record);
            }
            catch (error) {
                const failedRecord = {
                    id: this.generateRecordId(),
                    key,
                    environment: environment.name,
                    sourceType: 'unknown',
                    timestamp: Date.now(),
                    operator: options.operator || this.defaultOperator,
                    status: 'failed',
                    message: error.message,
                };
                results.push(failedRecord);
            }
        }
        return results;
    }
    scheduleRotation(environment, config, intervalMs, options = {}) {
        const scheduleId = `${environment.name}:${config.key}:${Date.now()}`;
        const timeout = setInterval(async () => {
            try {
                await this.rotateSecret(environment, config.key, options);
            }
            catch (error) {
                console.error(`Scheduled rotation failed for ${config.key} in ${environment.name}:`, error);
            }
        }, intervalMs);
        this.scheduledRotations.set(scheduleId, timeout);
        return scheduleId;
    }
    cancelScheduledRotation(scheduleId) {
        const timeout = this.scheduledRotations.get(scheduleId);
        if (timeout) {
            clearInterval(timeout);
            this.scheduledRotations.delete(scheduleId);
            return true;
        }
        return false;
    }
    cancelAllScheduled() {
        for (const timeout of this.scheduledRotations.values()) {
            clearInterval(timeout);
        }
        this.scheduledRotations.clear();
    }
    getRotationHistory(filters) {
        return this.records.filter((record) => {
            if (filters?.environment && record.environment !== filters.environment)
                return false;
            if (filters?.key && record.key !== filters.key)
                return false;
            if (filters?.operator && record.operator !== filters.operator)
                return false;
            if (filters?.status && record.status !== filters.status)
                return false;
            if (filters?.since && record.timestamp < filters.since)
                return false;
            if (filters?.until && record.timestamp > filters.until)
                return false;
            return true;
        }).sort((a, b) => b.timestamp - a.timestamp);
    }
    getLastRotation(environment, key) {
        return this.records
            .filter((r) => r.environment === environment && r.key === key && r.status === 'success')
            .sort((a, b) => b.timestamp - a.timestamp)[0];
    }
    getRotationAge(environment, key) {
        const last = this.getLastRotation(environment, key);
        if (!last)
            return undefined;
        return Date.now() - last.timestamp;
    }
    needsRotation(environment, key, maxAgeMs) {
        const age = this.getRotationAge(environment, key);
        if (age === undefined)
            return true;
        return age > maxAgeMs;
    }
    generateRecordId() {
        return 'rot_' + Date.now().toString(36) + Math.random().toString(36).slice(2, 8);
    }
    setRecords(records) {
        this.records = records;
    }
    getAllRecords() {
        return [...this.records];
    }
}
exports.RotationScheduler = RotationScheduler;
//# sourceMappingURL=RotationScheduler.js.map