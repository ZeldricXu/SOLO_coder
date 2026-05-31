"use strict";
var __importDefault = (this && this.__importDefault) || function (mod) {
    return (mod && mod.__esModule) ? mod : { "default": mod };
};
Object.defineProperty(exports, "__esModule", { value: true });
exports.MonitoringService = void 0;
const uuid_1 = require("uuid");
const logger_1 = __importDefault(require("../common/logger"));
const utils_1 = require("../common/utils");
class PriorityBatcher {
    constructor(maxSize, maxWaitMs, onFlush) {
        this.queues = new Map();
        this.maxSize = maxSize;
        this.maxWaitMs = maxWaitMs;
        this.onFlush = onFlush;
    }
    add(item, priority = 1) {
        const queueKey = `priority-${priority}`;
        if (!this.queues.has(queueKey)) {
            this.queues.set(queueKey, []);
        }
        const queue = this.queues.get(queueKey);
        queue.push(item);
        if (queue.length >= this.maxSize) {
            this.flushPriority(queueKey);
        }
        if (!this.timer) {
            this.startTimer();
        }
    }
    startTimer() {
        this.timer = setTimeout(() => {
            this.flushAll();
            this.timer = undefined;
        }, this.maxWaitMs);
    }
    flushPriority(queueKey) {
        const queue = this.queues.get(queueKey);
        if (queue && queue.length > 0) {
            this.onFlush([...queue]);
            queue.length = 0;
        }
    }
    flushAll() {
        const priorities = Array.from(this.queues.keys()).sort((a, b) => {
            const aNum = parseInt(a.replace('priority-', ''));
            const bNum = parseInt(b.replace('priority-', ''));
            return bNum - aNum;
        });
        for (const priority of priorities) {
            this.flushPriority(priority);
        }
    }
    flush() {
        this.flushAll();
        if (this.timer) {
            clearTimeout(this.timer);
            this.timer = undefined;
        }
    }
    size() {
        return Array.from(this.queues.values()).reduce((sum, q) => sum + q.length, 0);
    }
    stop() {
        if (this.timer) {
            clearTimeout(this.timer);
            this.timer = undefined;
        }
        this.flushAll();
    }
}
class AsyncBatchProcessor {
    constructor(maxConcurrency, maxBatchSize, processFn) {
        this.queue = [];
        this.processing = false;
        this.semaphore = { count: 0, max: 1 };
        this.maxConcurrency = maxConcurrency;
        this.maxBatchSize = maxBatchSize;
        this.processFn = processFn;
        this.semaphore = { count: 0, max: maxConcurrency };
    }
    async add(items) {
        this.queue.push(...items);
        await this.tryProcess();
    }
    async tryProcess() {
        if (this.processing || this.queue.length === 0 || this.semaphore.count >= this.semaphore.max) {
            return;
        }
        this.processing = true;
        this.semaphore.count++;
        try {
            while (this.queue.length > 0) {
                const batch = this.queue.splice(0, this.maxBatchSize);
                await this.processFn(batch);
            }
        }
        finally {
            this.processing = false;
            this.semaphore.count--;
        }
    }
    size() {
        return this.queue.length;
    }
    async flush() {
        while (this.queue.length > 0) {
            await this.tryProcess();
            if (this.queue.length > 0) {
                await (0, utils_1.sleep)(10);
            }
        }
    }
}
class MonitoringService {
    constructor(config = {}) {
        this.metrics = new Map();
        this.aggregatedMetrics = new Map();
        this.preAggregationCache = new Map();
        this.batchBuffer = [];
        this.mergeRules = [];
        this.downsamplingRules = [];
        this.batchStats = {
            totalOperations: 0,
            mergedOperations: 0,
            batchCount: 0,
            totalBatchSize: 0
        };
        this.operationBatches = new Map();
        this.config = {
            retentionPeriodMs: config.retentionPeriodMs ?? 3600000,
            aggregationIntervalMs: config.aggregationIntervalMs ?? 60000,
            maxPointsPerMetric: config.maxPointsPerMetric ?? 10000,
            enableBatching: config.enableBatching ?? true,
            batchSize: config.batchSize ?? 1000,
            batchTimeoutMs: config.batchTimeoutMs ?? 5000,
            enableMerging: config.enableMerging ?? true,
            enableDownsampling: config.enableDownsampling ?? true,
            alignToWallClock: config.alignToWallClock ?? true,
            preAggregationCacheSize: config.preAggregationCacheSize ?? 10000,
            enableBatchOperations: config.enableBatchOperations ?? true,
            maxBatchOperations: config.maxBatchOperations ?? 1000,
            autoMergeSimilarMetrics: config.autoMergeSimilarMetrics ?? true,
            mergeThresholdMs: config.mergeThresholdMs ?? 100,
            asyncBatchProcessing: config.asyncBatchProcessing ?? false,
            asyncBatchConcurrency: config.asyncBatchConcurrency ?? 2
        };
        this.startAggregationLoop();
        if (this.config.enableBatching) {
            this.startBatchLoop();
        }
        if (this.config.enableBatchOperations) {
            this.initializeBatchOperationProcessor();
        }
        if (this.config.asyncBatchProcessing) {
            this.asyncProcessor = new AsyncBatchProcessor(this.config.asyncBatchConcurrency, this.config.batchSize, async (batch) => this.processBatchRecords(batch));
        }
    }
    initializeBatchOperationProcessor() {
        this.priorityBatcher = new PriorityBatcher(this.config.maxBatchOperations, this.config.batchTimeoutMs, (batch) => this.processOperationBatch(batch));
    }
    setSnapshotCallback(callback) {
        this.onSnapshot = callback;
    }
    setBatchFlushedCallback(callback) {
        this.onBatchFlushed = callback;
    }
    addMergeRule(rule) {
        this.mergeRules.push(rule);
        logger_1.default.info({ sourcePattern: rule.sourcePattern.source, targetName: rule.targetName }, '添加指标合并规则');
    }
    removeMergeRule(index) {
        if (index >= 0 && index < this.mergeRules.length) {
            this.mergeRules.splice(index, 1);
            return true;
        }
        return false;
    }
    addDownsamplingRule(rule) {
        this.downsamplingRules.push(rule);
        logger_1.default.info({ metricPattern: rule.metricPattern.source }, '添加降采样规则');
    }
    recordMetric(name, value, tags = {}) {
        if (this.config.enableBatching) {
            this.batchBuffer.push({ name, value, tags, timestamp: Date.now() });
            if (this.batchBuffer.length >= this.config.batchSize) {
                this.flushBatch();
            }
        }
        else {
            this.processMetricPoint(name, value, tags, Date.now());
        }
    }
    recordBatch(records) {
        if (this.config.asyncBatchProcessing && this.asyncProcessor) {
            this.asyncProcessor.add(records.map(r => ({
                ...r,
                timestamp: r.timestamp ?? Date.now()
            })));
        }
        else if (this.config.enableBatching) {
            this.batchBuffer.push(...records.map(r => ({
                ...r,
                timestamp: r.timestamp ?? Date.now()
            })));
            if (this.batchBuffer.length >= this.config.batchSize) {
                this.flushBatch();
            }
        }
        else {
            for (const record of records) {
                this.processMetricPoint(record.name, record.value, record.tags || {}, record.timestamp || Date.now());
            }
        }
        this.batchStats.totalOperations += records.length;
        logger_1.default.debug({ count: records.length }, '批量指标已接收');
    }
    async executeBatchOperations(operations) {
        const startTime = Date.now();
        const operationId = (0, uuid_1.v4)();
        let successCount = 0;
        let failedCount = 0;
        let mergedCount = 0;
        if (this.config.autoMergeSimilarMetrics) {
            const { merged, count } = this.mergeSimilarOperations(operations);
            operations = merged;
            mergedCount = count;
        }
        this.batchStats.totalOperations += operations.length;
        this.batchStats.mergedOperations += mergedCount;
        for (const op of operations) {
            try {
                this.executeSingleOperation(op);
                successCount++;
            }
            catch (e) {
                failedCount++;
                logger_1.default.error({ operation: op.operation, name: op.name, e }, '批量操作执行失败');
            }
        }
        return {
            operationId,
            successCount,
            failedCount,
            mergedCount,
            totalTimeMs: Date.now() - startTime
        };
    }
    submitBatchOperations(operations, priority = 1) {
        if (!this.priorityBatcher) {
            this.executeBatchOperations(operations);
            return;
        }
        for (const op of operations) {
            this.priorityBatcher.add(op, priority);
        }
    }
    mergeSimilarOperations(operations) {
        const mergedMap = new Map();
        let mergedCount = 0;
        const now = Date.now();
        for (const op of operations) {
            const key = this.getOperationMergeKey(op);
            const existing = mergedMap.get(key);
            if (!existing) {
                mergedMap.set(key, { ...op });
            }
            else {
                const timestamp = op.timestamp ?? now;
                const existingTimestamp = existing.timestamp ?? now;
                if (Math.abs(timestamp - existingTimestamp) <= this.config.mergeThresholdMs) {
                    mergedCount++;
                    if (op.operation === 'increment') {
                        existing.value = (existing.value ?? 0) + (op.value ?? 1);
                    }
                    else if (op.operation === 'gauge') {
                        existing.value = op.value;
                    }
                    else if (op.operation === 'histogram') {
                        existing.value = op.value;
                    }
                }
                else {
                    mergedMap.set((0, uuid_1.v4)(), { ...op });
                }
            }
        }
        return {
            merged: Array.from(mergedMap.values()),
            count: mergedCount
        };
    }
    getOperationMergeKey(op) {
        const tagsStr = op.tags ? Object.entries(op.tags).sort().map(([k, v]) => `${k}=${v}`).join(',') : '';
        return `${op.operation}:${op.name}:${tagsStr}`;
    }
    executeSingleOperation(op) {
        const tags = op.tags || {};
        const timestamp = op.timestamp ?? Date.now();
        switch (op.operation) {
            case 'increment':
                this.processMetricPoint(op.name, op.value ?? 1, tags, timestamp);
                break;
            case 'gauge':
                this.processMetricPoint(op.name, op.value ?? 0, tags, timestamp);
                break;
            case 'histogram':
                this.processMetricPoint(op.name, op.value ?? 0, tags, timestamp);
                break;
            case 'timer':
                if (op.durationMs !== undefined) {
                    const statusTags = op.status ? { ...tags, status: op.status } : tags;
                    this.processMetricPoint(`${op.name}.duration`, op.durationMs, statusTags, timestamp);
                    this.processMetricPoint(`${op.name}.count`, 1, statusTags, timestamp);
                }
                break;
            case 'custom':
                if (op.value !== undefined) {
                    this.processMetricPoint(op.name, op.value, tags, timestamp);
                }
                break;
        }
    }
    processOperationBatch(batch) {
        this.batchStats.batchCount++;
        this.batchStats.totalBatchSize += batch.length;
        this.executeBatchOperations(batch);
    }
    async processBatchRecords(batch) {
        const now = Date.now();
        for (const record of batch) {
            this.processMetricPoint(record.name, record.value, record.tags || {}, record.timestamp || now);
        }
    }
    startBatchLoop() {
        this.batchTimer = setInterval(() => {
            if (this.batchBuffer.length > 0) {
                this.flushBatch();
            }
        }, this.config.batchTimeoutMs);
    }
    flushBatch() {
        if (this.batchBuffer.length === 0)
            return;
        const batch = [...this.batchBuffer];
        this.batchBuffer = [];
        const now = Date.now();
        const aggregated = new Map();
        const tempMetrics = new Map();
        for (const record of batch) {
            const timestamp = record.timestamp || now;
            const tags = record.tags || {};
            const key = this.getMetricKey(record.name, tags);
            const point = {
                name: record.name,
                value: record.value,
                timestamp,
                tags
            };
            if (!tempMetrics.has(key)) {
                tempMetrics.set(key, []);
            }
            tempMetrics.get(key).push(point);
            this.processMetricPoint(record.name, record.value, tags, timestamp);
        }
        for (const [key, points] of tempMetrics.entries()) {
            if (points.length > 0) {
                const values = points.map(p => p.value).sort((a, b) => a - b);
                const sum = values.reduce((acc, v) => acc + v, 0);
                const windowStart = this.alignTimestamp(points[0].timestamp);
                const windowEnd = windowStart + this.config.aggregationIntervalMs;
                aggregated.set(key, {
                    name: points[0].name,
                    count: values.length,
                    sum,
                    avg: sum / values.length,
                    min: values[0],
                    max: values[values.length - 1],
                    p50: values[Math.floor(values.length * 0.5)],
                    p95: values[Math.floor(values.length * 0.95)],
                    p99: values[Math.floor(values.length * 0.99)],
                    tags: points[0].tags,
                    windowStart,
                    windowEnd
                });
            }
        }
        if (this.config.enableMerging) {
            this.applyMergeRules(aggregated);
        }
        logger_1.default.debug({ batchSize: batch.length, aggregatedCount: aggregated.size }, '批量指标已处理');
        this.onBatchFlushed?.(batch, aggregated);
    }
    processMetricPoint(name, value, tags, timestamp) {
        const alignedTimestamp = this.config.alignToWallClock ? this.alignTimestamp(timestamp) : timestamp;
        const key = this.getMetricKey(name, tags);
        const preAggKey = `${key}:${alignedTimestamp}`;
        let preAgg = this.preAggregationCache.get(preAggKey);
        if (!preAgg) {
            preAgg = { values: [], count: 0, sum: 0, min: Infinity, max: -Infinity };
            if (this.preAggregationCache.size >= this.config.preAggregationCacheSize) {
                const firstKey = this.preAggregationCache.keys().next().value;
                if (firstKey !== undefined) {
                    this.preAggregationCache.delete(firstKey);
                }
            }
            this.preAggregationCache.set(preAggKey, preAgg);
        }
        preAgg.values.push(value);
        preAgg.count++;
        preAgg.sum += value;
        preAgg.min = Math.min(preAgg.min, value);
        preAgg.max = Math.max(preAgg.max, value);
        const point = {
            name,
            value,
            timestamp,
            tags
        };
        if (!this.metrics.has(key)) {
            this.metrics.set(key, []);
        }
        const points = this.metrics.get(key);
        points.push(point);
        if (points.length > this.config.maxPointsPerMetric) {
            const toRemove = points.length - this.config.maxPointsPerMetric;
            points.splice(0, toRemove);
        }
        logger_1.default.debug({ name, value, tags, timestamp }, '指标已记录');
    }
    alignTimestamp(timestamp) {
        return Math.floor(timestamp / this.config.aggregationIntervalMs) * this.config.aggregationIntervalMs;
    }
    applyMergeRules(aggregated) {
        for (const rule of this.mergeRules) {
            const matchingKeys = [];
            for (const key of aggregated.keys()) {
                if (rule.sourcePattern.test(key)) {
                    matchingKeys.push(key);
                }
            }
            if (matchingKeys.length === 0)
                continue;
            let merged = null;
            const allValues = [];
            for (const key of matchingKeys) {
                const metric = aggregated.get(key);
                allValues.push(...this.getValuesFromAggregated(metric));
                if (!merged) {
                    merged = { ...metric, tags: {} };
                }
                else {
                    merged = this.mergeAggregatedMetrics(merged, metric, rule);
                }
            }
            if (merged && allValues.length > 0) {
                const sortedValues = allValues.sort((a, b) => a - b);
                merged.p50 = sortedValues[Math.floor(sortedValues.length * 0.5)];
                merged.p95 = sortedValues[Math.floor(sortedValues.length * 0.95)];
                merged.p99 = sortedValues[Math.floor(sortedValues.length * 0.99)];
                merged.name = rule.targetName;
                aggregated.set(this.getMetricKey(rule.targetName, {}), merged);
            }
        }
    }
    getValuesFromAggregated(metric) {
        const values = [];
        for (let i = 0; i < metric.count; i++) {
            values.push(metric.avg);
        }
        return values;
    }
    mergeAggregatedMetrics(a, b, rule) {
        const merged = {
            ...a,
            count: a.count + b.count,
            sum: a.sum + b.sum,
            min: Math.min(a.min, b.min),
            max: Math.max(a.max, b.max),
            windowStart: Math.min(a.windowStart, b.windowStart),
            windowEnd: Math.max(a.windowEnd, b.windowEnd)
        };
        merged.avg = merged.sum / merged.count;
        if (rule.tagAggregation === 'combine') {
            merged.tags = { ...a.tags, ...b.tags };
        }
        else if (rule.tagAggregation === 'keep') {
            merged.tags = a.tags;
        }
        else {
            merged.tags = {};
        }
        return merged;
    }
    increment(name, tags = {}, value = 1) {
        this.recordMetric(name, value, tags);
    }
    gauge(name, value, tags = {}) {
        this.recordMetric(name, value, tags);
    }
    histogram(name, value, tags = {}) {
        this.recordMetric(name, value, tags);
    }
    async withTimer(name, fn, tags = {}) {
        const start = Date.now();
        try {
            const result = await fn();
            this.recordMetric(`${name}.duration`, Date.now() - start, { ...tags, status: 'success' });
            this.increment(`${name}.count`, { ...tags, status: 'success' });
            return result;
        }
        catch (error) {
            this.recordMetric(`${name}.duration`, Date.now() - start, { ...tags, status: 'error' });
            this.increment(`${name}.count`, { ...tags, status: 'error' });
            throw error;
        }
    }
    getMetric(name, tags = {}) {
        const key = this.getMetricKey(name, tags);
        return this.metrics.get(key) || [];
    }
    getAggregatedMetrics(name, tags = {}) {
        const key = this.getMetricKey(name, tags);
        return this.aggregatedMetrics.get(key) || [];
    }
    queryMetrics(namePattern, startTime, endTime, tags) {
        const results = [];
        const regex = new RegExp(namePattern);
        for (const [key, points] of this.metrics.entries()) {
            if (!regex.test(key))
                continue;
            if (tags) {
                const pointTags = points[0]?.tags || {};
                let match = true;
                for (const [k, v] of Object.entries(tags)) {
                    if (pointTags[k] !== v) {
                        match = false;
                        break;
                    }
                }
                if (!match)
                    continue;
            }
            results.push(...points.filter(p => p.timestamp >= startTime && p.timestamp <= endTime));
        }
        return results.sort((a, b) => a.timestamp - b.timestamp);
    }
    queryAggregated(namePattern, startTime, endTime, tags) {
        const results = [];
        const regex = new RegExp(namePattern);
        for (const [key, metrics] of this.aggregatedMetrics.entries()) {
            if (!regex.test(key))
                continue;
            if (tags) {
                const metricTags = metrics[0]?.tags || {};
                let match = true;
                for (const [k, v] of Object.entries(tags)) {
                    if (metricTags[k] !== v) {
                        match = false;
                        break;
                    }
                }
                if (!match)
                    continue;
            }
            results.push(...metrics.filter(m => m.windowStart >= startTime && m.windowEnd <= endTime));
        }
        return results.sort((a, b) => a.windowStart - b.windowStart);
    }
    createSnapshot(dimensions = {}) {
        const now = Date.now();
        const windowStart = now - this.config.aggregationIntervalMs;
        const throughput = this.calculateRate('requests.count', windowStart, now);
        const latencyP99 = this.calculatePercentile('requests.duration', 99, windowStart, now);
        const errorRate = this.calculateErrorRate(windowStart, now);
        const snapshot = {
            snapshot_id: (0, uuid_1.v4)(),
            timestamp: new Date().toISOString(),
            metrics: {
                throughput,
                latency_p99: latencyP99,
                error_rate: errorRate
            },
            dimensions
        };
        this.onSnapshot?.(snapshot);
        return snapshot;
    }
    createBatchSnapshot(windowStart, windowEnd, dimensions = {}) {
        const metrics = [];
        for (const [key, aggList] of this.aggregatedMetrics.entries()) {
            const filtered = aggList.filter(a => a.windowStart >= windowStart && a.windowEnd <= windowEnd);
            if (filtered.length > 0) {
                const merged = this.mergeMultipleAggregated(filtered);
                metrics.push({ name: key, aggregated: merged });
            }
        }
        return {
            snapshotId: (0, uuid_1.v4)(),
            windowStart: new Date(windowStart).toISOString(),
            windowEnd: new Date(windowEnd).toISOString(),
            metrics,
            dimensions
        };
    }
    mergeMultipleAggregated(metrics) {
        if (metrics.length === 0) {
            throw new Error('Cannot merge empty metrics array');
        }
        const first = metrics[0];
        let result = { ...first };
        for (let i = 1; i < metrics.length; i++) {
            result = {
                ...result,
                count: result.count + metrics[i].count,
                sum: result.sum + metrics[i].sum,
                min: Math.min(result.min, metrics[i].min),
                max: Math.max(result.max, metrics[i].max),
                windowStart: Math.min(result.windowStart, metrics[i].windowStart),
                windowEnd: Math.max(result.windowEnd, metrics[i].windowEnd)
            };
        }
        result.avg = result.sum / result.count;
        return result;
    }
    calculateRate(name, start, end) {
        const allPoints = [];
        for (const [key, points] of this.metrics.entries()) {
            if (key.startsWith(name)) {
                allPoints.push(...points.filter(p => p.timestamp >= start && p.timestamp <= end));
            }
        }
        const durationSeconds = (end - start) / 1000;
        return durationSeconds > 0 ? allPoints.length / durationSeconds : 0;
    }
    calculatePercentile(name, percentile, start, end) {
        const allPoints = [];
        for (const [key, points] of this.metrics.entries()) {
            if (key.startsWith(name)) {
                allPoints.push(...points.filter(p => p.timestamp >= start && p.timestamp <= end));
            }
        }
        if (allPoints.length === 0)
            return 0;
        const values = allPoints.map(p => p.value).sort((a, b) => a - b);
        const index = Math.ceil((percentile / 100) * values.length) - 1;
        return values[Math.max(0, index)];
    }
    calculateErrorRate(start, end) {
        let total = 0;
        let errors = 0;
        for (const [key, points] of this.metrics.entries()) {
            if (key.includes('.count')) {
                const filtered = points.filter(p => p.timestamp >= start && p.timestamp <= end);
                total += filtered.length;
                if (key.includes('status:error') || key.includes('status=error')) {
                    errors += filtered.length;
                }
            }
        }
        return total > 0 ? errors / total : 0;
    }
    aggregate() {
        const now = Date.now();
        const windowStart = now - this.config.aggregationIntervalMs;
        const newAggregations = new Map();
        for (const [key, points] of this.metrics.entries()) {
            const windowPoints = points.filter(p => p.timestamp >= windowStart);
            if (windowPoints.length === 0)
                continue;
            const values = windowPoints.map(p => p.value).sort((a, b) => a - b);
            const sum = values.reduce((acc, v) => acc + v, 0);
            const aggregated = {
                name: windowPoints[0].name,
                count: values.length,
                sum,
                avg: sum / values.length,
                min: values[0],
                max: values[values.length - 1],
                p50: values[Math.floor(values.length * 0.5)],
                p95: values[Math.floor(values.length * 0.95)],
                p99: values[Math.floor(values.length * 0.99)],
                tags: windowPoints[0].tags,
                windowStart: this.alignTimestamp(windowPoints[0].timestamp),
                windowEnd: now
            };
            newAggregations.set(key, aggregated);
            if (!this.aggregatedMetrics.has(key)) {
                this.aggregatedMetrics.set(key, []);
            }
            this.aggregatedMetrics.get(key).push(aggregated);
        }
        if (this.config.enableMerging && newAggregations.size > 0) {
            this.applyMergeRules(newAggregations);
        }
        if (this.config.enableDownsampling) {
            this.applyDownsampling();
        }
        this.cleanupOldData();
    }
    applyDownsampling() {
        const now = Date.now();
        for (const rule of this.downsamplingRules) {
            for (const [key, metrics] of this.aggregatedMetrics.entries()) {
                if (!rule.metricPattern.test(key))
                    continue;
                const cutoff = now - rule.retentionMs;
                const recentMetrics = metrics.filter(m => m.windowEnd >= cutoff);
                if (recentMetrics.length < 2)
                    continue;
                const downsampled = [];
                let currentBucket = [];
                let bucketStart = recentMetrics[0].windowStart;
                for (const metric of recentMetrics) {
                    if (metric.windowStart >= bucketStart + rule.targetResolutionMs) {
                        if (currentBucket.length > 0) {
                            downsampled.push(this.mergeMultipleAggregated(currentBucket));
                        }
                        currentBucket = [metric];
                        bucketStart = metric.windowStart;
                    }
                    else {
                        currentBucket.push(metric);
                    }
                }
                if (currentBucket.length > 0) {
                    downsampled.push(this.mergeMultipleAggregated(currentBucket));
                }
                this.aggregatedMetrics.set(key, downsampled);
            }
        }
    }
    cleanupOldData() {
        const cutoff = Date.now() - this.config.retentionPeriodMs;
        for (const [key, points] of this.metrics.entries()) {
            this.metrics.set(key, points.filter(p => p.timestamp >= cutoff));
        }
        for (const [key, agg] of this.aggregatedMetrics.entries()) {
            this.aggregatedMetrics.set(key, agg.filter(a => a.windowEnd >= cutoff));
        }
        const preAggCutoff = Date.now() - this.config.aggregationIntervalMs * 2;
        for (const [key] of this.preAggregationCache) {
            const timestamp = parseInt(key.split(':').pop() || '0');
            if (timestamp < preAggCutoff) {
                this.preAggregationCache.delete(key);
            }
        }
    }
    startAggregationLoop() {
        this.aggregationTimer = setInterval(() => {
            try {
                this.aggregate();
            }
            catch (error) {
                logger_1.default.error({ error }, '指标聚合失败');
            }
        }, this.config.aggregationIntervalMs);
    }
    getBatchProcessorStats() {
        return {
            totalOperations: this.batchStats.totalOperations,
            mergedOperations: this.batchStats.mergedOperations,
            batchCount: this.batchStats.batchCount,
            avgBatchSize: this.batchStats.batchCount > 0 ? this.batchStats.totalBatchSize / this.batchStats.batchCount : 0,
            avgMergeRatio: this.batchStats.totalOperations > 0 ? this.batchStats.mergedOperations / this.batchStats.totalOperations : 0,
            pendingOperations: this.batchBuffer.length + (this.asyncProcessor?.size() ?? 0)
        };
    }
    stop() {
        if (this.aggregationTimer) {
            clearInterval(this.aggregationTimer);
        }
        if (this.batchTimer) {
            clearInterval(this.batchTimer);
        }
        if (this.priorityBatcher) {
            this.priorityBatcher.stop();
        }
        if (this.operationBatchTimer) {
            clearTimeout(this.operationBatchTimer);
        }
    }
    getAllMetrics() {
        return Array.from(this.metrics.keys());
    }
    reset() {
        this.metrics.clear();
        this.aggregatedMetrics.clear();
        this.preAggregationCache.clear();
        this.batchBuffer = [];
        this.batchStats = {
            totalOperations: 0,
            mergedOperations: 0,
            batchCount: 0,
            totalBatchSize: 0
        };
    }
    getStats() {
        return {
            totalMetrics: this.metrics.size,
            totalAggregated: this.aggregatedMetrics.size,
            metricNames: Array.from(this.metrics.keys()),
            batchBufferSize: this.batchBuffer.length,
            preAggregationCacheSize: this.preAggregationCache.size,
            mergeRuleCount: this.mergeRules.length,
            downsamplingRuleCount: this.downsamplingRules.length,
            batchProcessor: this.config.enableBatchOperations ? this.getBatchProcessorStats() : undefined
        };
    }
    forceFlush() {
        const count = this.batchBuffer.length;
        this.flushBatch();
        if (this.priorityBatcher) {
            this.priorityBatcher.flush();
        }
        return count;
    }
    getMetricKey(name, tags) {
        const sortedTags = Object.entries(tags)
            .sort(([a], [b]) => a.localeCompare(b))
            .map(([k, v]) => `${k}=${v}`)
            .join(',');
        return sortedTags ? `${name}[${sortedTags}]` : name;
    }
}
exports.MonitoringService = MonitoringService;
exports.default = MonitoringService;
//# sourceMappingURL=index.js.map