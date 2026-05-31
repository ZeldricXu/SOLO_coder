"use strict";
Object.defineProperty(exports, "__esModule", { value: true });
exports.Semaphore = void 0;
exports.generateId = generateId;
exports.getCurrentTimestamp = getCurrentTimestamp;
exports.sleep = sleep;
exports.withTimeout = withTimeout;
exports.deepClone = deepClone;
exports.retryAsync = retryAsync;
const uuid_1 = require("uuid");
function generateId(prefix = '') {
    return `${prefix}${(0, uuid_1.v4)().replace(/-/g, '').slice(0, 24)}`;
}
function getCurrentTimestamp() {
    return new Date().toISOString();
}
function sleep(ms) {
    return new Promise(resolve => setTimeout(resolve, ms));
}
function withTimeout(promise, timeoutMs, errorMessage) {
    return Promise.race([
        promise,
        new Promise((_, reject) => setTimeout(() => reject(new Error(errorMessage || `操作超时 (${timeoutMs}ms)`)), timeoutMs))
    ]);
}
function deepClone(obj) {
    return JSON.parse(JSON.stringify(obj));
}
function retryAsync(fn, retries = 3, delay = 1000) {
    return fn().catch(error => {
        if (retries <= 0)
            throw error;
        return sleep(delay).then(() => retryAsync(fn, retries - 1, delay * 2));
    });
}
class Semaphore {
    constructor(permits) {
        this.waiters = [];
        this.permits = permits;
    }
    async acquire() {
        if (this.permits > 0) {
            this.permits--;
            return;
        }
        return new Promise(resolve => {
            this.waiters.push(resolve);
        });
    }
    release() {
        this.permits++;
        const next = this.waiters.shift();
        if (next) {
            this.permits--;
            next();
        }
    }
    get availablePermits() {
        return this.permits;
    }
    get queueLength() {
        return this.waiters.length;
    }
}
exports.Semaphore = Semaphore;
//# sourceMappingURL=utils.js.map