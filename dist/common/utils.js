"use strict";
var __importDefault = (this && this.__importDefault) || function (mod) {
    return (mod && mod.__esModule) ? mod : { "default": mod };
};
Object.defineProperty(exports, "__esModule", { value: true });
exports.metricsCollector = exports.MetricsCollector = void 0;
exports.generateId = generateId;
exports.formatDate = formatDate;
exports.now = now;
exports.sleep = sleep;
exports.isAddress = isAddress;
exports.normalizeAddress = normalizeAddress;
exports.asChainId = asChainId;
exports.getErrorMessage = getErrorMessage;
exports.hexToNumber = hexToNumber;
exports.numberToHex = numberToHex;
exports.weiToEther = weiToEther;
exports.etherToWei = etherToWei;
exports.withRetry = withRetry;
exports.withTimeout = withTimeout;
exports.chunkArray = chunkArray;
exports.calculateMedian = calculateMedian;
exports.calculatePercentile = calculatePercentile;
exports.deepClone = deepClone;
exports.omit = omit;
exports.pick = pick;
const uuid_1 = require("uuid");
const async_retry_1 = __importDefault(require("async-retry"));
const config_1 = require("../config");
const logger_1 = require("./logger");
function generateId(prefix = 'id') {
    return `${prefix}_${(0, uuid_1.v4)().replace(/-/g, '').substring(0, 12)}`;
}
function formatDate(date = new Date()) {
    return date.toISOString();
}
function now() {
    return formatDate(new Date());
}
function sleep(ms) {
    return new Promise((resolve) => setTimeout(resolve, ms));
}
function isAddress(address) {
    return /^0x[a-fA-F0-9]{40}$/.test(address);
}
function normalizeAddress(address) {
    return address.toLowerCase();
}
function asChainId(chainId) {
    return chainId;
}
function getErrorMessage(error) {
    if (error instanceof Error) {
        return error.message;
    }
    return String(error);
}
function hexToNumber(hex) {
    return parseInt(hex, 16);
}
function numberToHex(num) {
    return `0x${BigInt(num).toString(16)}`;
}
function weiToEther(wei) {
    const value = typeof wei === 'string' ? BigInt(wei) : wei;
    return (value / BigInt(10 ** 18)).toString();
}
function etherToWei(ether) {
    return (BigInt(parseFloat(ether) * 10 ** 18)).toString();
}
async function withRetry(fn, options = {}) {
    const { retries = config_1.DEFAULT_RETRY_ATTEMPTS, delay = config_1.DEFAULT_RETRY_DELAY, onRetry } = options;
    return (0, async_retry_1.default)(async (bail, attempt) => {
        try {
            return await fn();
        }
        catch (error) {
            if (attempt > retries) {
                bail(error);
            }
            if (onRetry) {
                onRetry(error, attempt);
            }
            throw error;
        }
    }, {
        retries,
        minTimeout: delay,
        factor: 2,
    });
}
async function withTimeout(fn, timeout, timeoutMessage = 'Operation timed out') {
    return Promise.race([
        fn(),
        new Promise((_, reject) => setTimeout(() => reject(new Error(timeoutMessage)), timeout)),
    ]);
}
function chunkArray(array, size) {
    const chunks = [];
    for (let i = 0; i < array.length; i += size) {
        chunks.push(array.slice(i, i + size));
    }
    return chunks;
}
function calculateMedian(values) {
    if (values.length === 0)
        return 0;
    const sorted = [...values].sort((a, b) => a - b);
    const mid = Math.floor(sorted.length / 2);
    return sorted.length % 2 !== 0 ? sorted[mid] : (sorted[mid - 1] + sorted[mid]) / 2;
}
function calculatePercentile(values, percentile) {
    if (values.length === 0)
        return 0;
    const sorted = [...values].sort((a, b) => a - b);
    const index = Math.ceil((percentile / 100) * sorted.length) - 1;
    return sorted[Math.max(0, index)];
}
function deepClone(obj) {
    return JSON.parse(JSON.stringify(obj));
}
function omit(obj, keys) {
    const result = { ...obj };
    keys.forEach((key) => delete result[key]);
    return result;
}
function pick(obj, keys) {
    const result = {};
    keys.forEach((key) => {
        if (key in obj) {
            result[key] = obj[key];
        }
    });
    return result;
}
class MetricsCollector {
    metrics;
    logger;
    constructor() {
        this.metrics = new Map();
        this.logger = new logger_1.LoggerContext({ module: 'MetricsCollector' });
    }
    record(name, value) {
        if (!this.metrics.has(name)) {
            this.metrics.set(name, { values: [], timestamps: [] });
        }
        const metric = this.metrics.get(name);
        metric.values.push(value);
        metric.timestamps.push(Date.now());
        if (metric.values.length > 10000) {
            metric.values.shift();
            metric.timestamps.shift();
        }
    }
    getStats(name, windowMs = 3600000) {
        const metric = this.metrics.get(name);
        if (!metric || metric.values.length === 0) {
            return { count: 0, avg: 0, p50: 0, p95: 0, p99: 0, min: 0, max: 0 };
        }
        const cutoff = Date.now() - windowMs;
        const values = metric.values.filter((_, i) => metric.timestamps[i] >= cutoff);
        if (values.length === 0) {
            return { count: 0, avg: 0, p50: 0, p95: 0, p99: 0, min: 0, max: 0 };
        }
        const sorted = [...values].sort((a, b) => a - b);
        return {
            count: values.length,
            avg: values.reduce((a, b) => a + b, 0) / values.length,
            p50: calculatePercentile(values, 50),
            p95: calculatePercentile(values, 95),
            p99: calculatePercentile(values, 99),
            min: sorted[0],
            max: sorted[sorted.length - 1],
        };
    }
    reset(name) {
        if (name) {
            this.metrics.delete(name);
        }
        else {
            this.metrics.clear();
        }
    }
}
exports.MetricsCollector = MetricsCollector;
exports.metricsCollector = new MetricsCollector();
//# sourceMappingURL=utils.js.map