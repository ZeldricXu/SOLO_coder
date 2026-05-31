"use strict";
Object.defineProperty(exports, "__esModule", { value: true });
exports.calculateDrift = exports.formatBytes = exports.mergeDeep = exports.deepClone = exports.safeJsonParse = exports.calculatePercentiles = exports.withTimeout = exports.retry = exports.sleep = exports.nowISO = exports.generateId = void 0;
const uuid_1 = require("uuid");
const generateId = (prefix = 'id') => {
    return `${prefix}_${(0, uuid_1.v4)().replace(/-/g, '').slice(0, 8)}`;
};
exports.generateId = generateId;
const nowISO = () => {
    return new Date().toISOString();
};
exports.nowISO = nowISO;
const sleep = (ms) => {
    return new Promise((resolve) => setTimeout(resolve, ms));
};
exports.sleep = sleep;
const retry = async (fn, retries = 3, delay = 1000, backoff = 2) => {
    let lastError;
    for (let i = 0; i < retries; i++) {
        try {
            return await fn();
        }
        catch (error) {
            lastError = error;
            if (i < retries - 1) {
                await (0, exports.sleep)(delay * Math.pow(backoff, i));
            }
        }
    }
    throw lastError;
};
exports.retry = retry;
const withTimeout = (promise, ms, message) => {
    return new Promise((resolve, reject) => {
        const timeout = setTimeout(() => {
            reject(new Error(message || `Operation timed out after ${ms}ms`));
        }, ms);
        promise
            .then((value) => {
            clearTimeout(timeout);
            resolve(value);
        })
            .catch((error) => {
            clearTimeout(timeout);
            reject(error);
        });
    });
};
exports.withTimeout = withTimeout;
const calculatePercentiles = (values, percentiles) => {
    if (values.length === 0) {
        return percentiles.reduce((acc, p) => ({ ...acc, [p]: 0 }), {});
    }
    const sorted = [...values].sort((a, b) => a - b);
    const result = {};
    for (const p of percentiles) {
        const index = Math.ceil((p / 100) * sorted.length) - 1;
        result[p] = sorted[Math.max(0, Math.min(index, sorted.length - 1))];
    }
    return result;
};
exports.calculatePercentiles = calculatePercentiles;
const safeJsonParse = (str, fallback) => {
    try {
        return JSON.parse(str);
    }
    catch {
        return fallback;
    }
};
exports.safeJsonParse = safeJsonParse;
const deepClone = (obj) => {
    return JSON.parse(JSON.stringify(obj));
};
exports.deepClone = deepClone;
const mergeDeep = (target, source) => {
    const result = { ...target };
    for (const key in source) {
        if (source.hasOwnProperty(key)) {
            const targetValue = result[key];
            const sourceValue = source[key];
            if (typeof targetValue === 'object' &&
                targetValue !== null &&
                typeof sourceValue === 'object' &&
                sourceValue !== null &&
                !Array.isArray(targetValue) &&
                !Array.isArray(sourceValue)) {
                result[key] = (0, exports.mergeDeep)(targetValue, sourceValue);
            }
            else if (sourceValue !== undefined) {
                result[key] = sourceValue;
            }
        }
    }
    return result;
};
exports.mergeDeep = mergeDeep;
const formatBytes = (bytes) => {
    if (bytes === 0)
        return '0 B';
    const k = 1024;
    const sizes = ['B', 'KB', 'MB', 'GB', 'TB'];
    const i = Math.floor(Math.log(bytes) / Math.log(k));
    return `${parseFloat((bytes / Math.pow(k, i)).toFixed(2))} ${sizes[i]}`;
};
exports.formatBytes = formatBytes;
const calculateDrift = (baseline, current) => {
    if (baseline.length === 0 || current.length === 0)
        return 0;
    const mean1 = baseline.reduce((a, b) => a + b, 0) / baseline.length;
    const mean2 = current.reduce((a, b) => a + b, 0) / current.length;
    const std1 = Math.sqrt(baseline.reduce((a, b) => a + Math.pow(b - mean1, 2), 0) / baseline.length);
    const std2 = Math.sqrt(current.reduce((a, b) => a + Math.pow(b - mean2, 2), 0) / current.length);
    const drift = Math.abs(mean1 - mean2) / Math.sqrt((std1 * std1 + std2 * std2) / 2 + 1e-10);
    return isFinite(drift) ? drift : 0;
};
exports.calculateDrift = calculateDrift;
//# sourceMappingURL=utils.js.map