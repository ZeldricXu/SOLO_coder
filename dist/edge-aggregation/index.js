"use strict";
var __importDefault = (this && this.__importDefault) || function (mod) {
    return (mod && mod.__esModule) ? mod : { "default": mod };
};
Object.defineProperty(exports, "__esModule", { value: true });
exports.EdgeDataAggregator = void 0;
const uuid_1 = require("uuid");
const logger_1 = __importDefault(require("../common/logger"));
class EdgeDataAggregator {
    constructor() {
        this.rules = new Map();
        this.dataBuffers = new Map();
        this.windowTimers = new Map();
    }
    setAggregationCallback(callback) {
        this.onAggregationComplete = callback;
    }
    setUploadCallback(callback) {
        this.onUploadReady = callback;
    }
    registerRule(rule) {
        this.rules.set(rule.ruleId, rule);
        const bufferKey = this.getBufferKey(rule);
        this.dataBuffers.set(bufferKey, []);
        this.startWindowTimer(rule);
        logger_1.default.info({ ruleId: rule.ruleId, name: rule.name, metricName: rule.metricName }, '注册聚合规则');
    }
    unregisterRule(ruleId) {
        const rule = this.rules.get(ruleId);
        if (rule) {
            const bufferKey = this.getBufferKey(rule);
            this.dataBuffers.delete(bufferKey);
            const timer = this.windowTimers.get(ruleId);
            if (timer)
                clearInterval(timer);
            this.windowTimers.delete(ruleId);
            this.rules.delete(ruleId);
            logger_1.default.info({ ruleId }, '注销聚合规则');
        }
    }
    ingestDataPoint(metricName, value, tags = {}) {
        const point = {
            timestamp: Date.now(),
            value,
            tags
        };
        for (const rule of this.rules.values()) {
            if (rule.enabled && rule.metricName === metricName) {
                if (this.matchesTags(rule, tags)) {
                    const bufferKey = this.getBufferKey(rule, tags);
                    if (!this.dataBuffers.has(bufferKey)) {
                        this.dataBuffers.set(bufferKey, []);
                    }
                    this.dataBuffers.get(bufferKey).push(point);
                    logger_1.default.debug({ ruleId: rule.ruleId, metricName, value }, '数据点已接收');
                }
            }
        }
    }
    ingestBatch(metricName, values) {
        for (const v of values) {
            const point = {
                timestamp: v.timestamp ?? Date.now(),
                value: v.value,
                tags: v.tags
            };
            for (const rule of this.rules.values()) {
                if (rule.enabled && rule.metricName === metricName) {
                    if (this.matchesTags(rule, v.tags)) {
                        const bufferKey = this.getBufferKey(rule, v.tags);
                        if (!this.dataBuffers.has(bufferKey)) {
                            this.dataBuffers.set(bufferKey, []);
                        }
                        this.dataBuffers.get(bufferKey).push(point);
                    }
                }
            }
        }
        logger_1.default.debug({ metricName, count: values.length }, '批量数据点已接收');
    }
    forceAggregate(ruleId) {
        const rule = this.rules.get(ruleId);
        if (!rule)
            return [];
        const results = [];
        for (const [bufferKey, points] of this.dataBuffers.entries()) {
            if (bufferKey.startsWith(`${rule.ruleId}:`) && points.length > 0) {
                const result = this.aggregatePoints(rule, points);
                results.push(result);
                this.dataBuffers.set(bufferKey, []);
            }
        }
        return results;
    }
    triggerUpload() {
        const allAggregated = [];
        for (const rule of this.rules.values()) {
            const results = this.forceAggregate(rule.ruleId);
            for (const r of results) {
                allAggregated.push(r.data);
            }
        }
        if (allAggregated.length > 0 && this.onUploadReady) {
            logger_1.default.info({ count: allAggregated.length }, '触发聚合数据上传');
            this.onUploadReady(allAggregated);
        }
    }
    aggregatePoints(rule, points) {
        const sortedPoints = [...points].sort((a, b) => a.timestamp - b.timestamp);
        const values = sortedPoints.map(p => p.value);
        const sum = values.reduce((acc, v) => acc + v, 0);
        const avg = sum / values.length;
        const variance = values.reduce((acc, v) => acc + Math.pow(v - avg, 2), 0) / values.length;
        const stdDev = Math.sqrt(variance);
        const tags = {};
        for (const tagKey of rule.tags) {
            const tagValue = sortedPoints[0]?.tags[tagKey];
            if (tagValue)
                tags[tagKey] = tagValue;
        }
        const data = {
            aggregationId: (0, uuid_1.v4)(),
            startTime: sortedPoints[0].timestamp,
            endTime: sortedPoints[sortedPoints.length - 1].timestamp,
            metrics: {
                count: values.length,
                sum,
                avg,
                min: Math.min(...values),
                max: Math.max(...values),
                first: values[0],
                last: values[values.length - 1],
                variance,
                stdDev
            },
            tags,
            rawDataPoints: points.length
        };
        const shouldUpload = this.checkUploadThreshold(rule, data);
        const result = {
            ruleId: rule.ruleId,
            metricName: rule.metricName,
            windowStart: sortedPoints[0].timestamp,
            windowEnd: sortedPoints[sortedPoints.length - 1].timestamp,
            data,
            shouldUpload
        };
        this.onAggregationComplete?.(result);
        logger_1.default.debug({ ruleId: rule.ruleId, points: points.length, shouldUpload }, '聚合完成');
        return result;
    }
    checkUploadThreshold(rule, data) {
        if (!rule.uploadThreshold)
            return true;
        const { minDataPoints = 0, maxDelayMs } = rule.uploadThreshold;
        if (data.rawDataPoints >= minDataPoints)
            return true;
        if (maxDelayMs) {
            const age = Date.now() - data.startTime;
            if (age >= maxDelayMs)
                return true;
        }
        return false;
    }
    startWindowTimer(rule) {
        if (this.windowTimers.has(rule.ruleId))
            return;
        const timer = setInterval(() => {
            this.processWindow(rule).catch(error => {
                logger_1.default.error({ ruleId: rule.ruleId, error }, '窗口处理失败');
            });
        }, rule.window.durationMs);
        this.windowTimers.set(rule.ruleId, timer);
    }
    async processWindow(rule) {
        for (const [bufferKey, points] of this.dataBuffers.entries()) {
            if (bufferKey.startsWith(`${rule.ruleId}:`) && points.length > 0) {
                const result = this.aggregatePoints(rule, points);
                this.dataBuffers.set(bufferKey, []);
                if (result.shouldUpload && this.onUploadReady) {
                    this.onUploadReady([result.data]);
                }
            }
        }
    }
    matchesTags(rule, tags) {
        for (const requiredTag of rule.tags) {
            if (tags[requiredTag] === undefined)
                return false;
        }
        return true;
    }
    getBufferKey(rule, tags = {}) {
        const tagValues = rule.tags.map(t => tags[t] || '').join(',');
        return `${rule.ruleId}:${tagValues}`;
    }
    getBufferStats() {
        const stats = [];
        for (const rule of this.rules.values()) {
            let totalSize = 0;
            for (const [key, points] of this.dataBuffers.entries()) {
                if (key.startsWith(`${rule.ruleId}:`)) {
                    totalSize += points.length;
                }
            }
            stats.push({ ruleId: rule.ruleId, bufferSize: totalSize });
        }
        return stats;
    }
    stop() {
        for (const [ruleId, timer] of this.windowTimers) {
            clearInterval(timer);
        }
        this.windowTimers.clear();
    }
    clearBuffers() {
        this.dataBuffers.clear();
    }
    getRules() {
        return Array.from(this.rules.values());
    }
}
exports.EdgeDataAggregator = EdgeDataAggregator;
//# sourceMappingURL=index.js.map