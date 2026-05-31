"use strict";
var __importDefault = (this && this.__importDefault) || function (mod) {
    return (mod && mod.__esModule) ? mod : { "default": mod };
};
Object.defineProperty(exports, "__esModule", { value: true });
exports.CacheInvalidationManager = exports.DataAccessLayer = exports.DataSourceManager = exports.ConsistentHashRouter = void 0;
const node_cache_1 = __importDefault(require("node-cache"));
const logger_1 = __importDefault(require("../common/logger"));
const utils_1 = require("../common/utils");
class HistogramRecorder {
    constructor(buckets = [1, 5, 10, 25, 50, 100, 250, 500, 1000]) {
        this.values = [];
        this.sum = 0;
        this.count = 0;
        this.bucketCounts = new Map();
        this.buckets = buckets.sort((a, b) => a - b);
        for (const bucket of this.buckets) {
            this.bucketCounts.set(bucket, 0);
        }
    }
    record(value) {
        this.values.push(value);
        this.sum += value;
        this.count++;
        for (const bucket of this.buckets) {
            if (value <= bucket) {
                this.bucketCounts.set(bucket, (this.bucketCounts.get(bucket) || 0) + 1);
            }
        }
    }
    getStats() {
        if (this.count === 0) {
            return {
                totalOperations: 0,
                totalTimeMs: 0,
                avgTimeMs: 0,
                p50TimeMs: 0,
                p95TimeMs: 0,
                p99TimeMs: 0,
                minTimeMs: 0,
                maxTimeMs: 0
            };
        }
        const sorted = [...this.values].sort((a, b) => a - b);
        return {
            totalOperations: this.count,
            totalTimeMs: this.sum,
            avgTimeMs: this.sum / this.count,
            p50TimeMs: this.percentile(sorted, 50),
            p95TimeMs: this.percentile(sorted, 95),
            p99TimeMs: this.percentile(sorted, 99),
            minTimeMs: sorted[0],
            maxTimeMs: sorted[sorted.length - 1]
        };
    }
    getBuckets() {
        return this.buckets.map(bucket => ({
            le: bucket,
            count: this.bucketCounts.get(bucket) || 0
        }));
    }
    getSum() {
        return this.sum;
    }
    getCount() {
        return this.count;
    }
    reset() {
        this.values = [];
        this.sum = 0;
        this.count = 0;
        for (const bucket of this.buckets) {
            this.bucketCounts.set(bucket, 0);
        }
    }
    percentile(sorted, p) {
        if (sorted.length === 0)
            return 0;
        const index = Math.ceil((p / 100) * sorted.length) - 1;
        return sorted[Math.max(0, Math.min(index, sorted.length - 1))];
    }
}
class ConsistentHashRouter {
    constructor(virtualNodes = 100) {
        this.ring = [];
        this.sourceWeights = new Map();
        this.virtualNodes = virtualNodes;
    }
    addSource(sourceId, weight = 1) {
        this.sourceWeights.set(sourceId, weight);
        this.rebuildRing();
    }
    removeSource(sourceId) {
        this.sourceWeights.delete(sourceId);
        this.rebuildRing();
    }
    rebuildRing() {
        this.ring = [];
        for (const [sourceId, weight] of this.sourceWeights) {
            const nodes = this.virtualNodes * weight;
            for (let i = 0; i < nodes; i++) {
                const hash = this.hash(`${sourceId}:${i}`);
                this.ring.push({ hash, sourceId });
            }
        }
        this.ring.sort((a, b) => a.hash - b.hash);
    }
    hash(key) {
        let hash = 0;
        for (let i = 0; i < key.length; i++) {
            const char = key.charCodeAt(i);
            hash = ((hash << 5) - hash) + char;
            hash = hash & hash;
        }
        return Math.abs(hash);
    }
    getSource(key) {
        if (this.ring.length === 0)
            return null;
        const hash = this.hash(key);
        for (const node of this.ring) {
            if (node.hash >= hash) {
                return node.sourceId;
            }
        }
        return this.ring[0].sourceId;
    }
    getSources(key, count) {
        if (this.ring.length === 0)
            return [];
        if (count >= this.sourceWeights.size) {
            return Array.from(this.sourceWeights.keys());
        }
        const sources = [];
        const seen = new Set();
        const hash = this.hash(key);
        let startIndex = 0;
        for (let i = 0; i < this.ring.length; i++) {
            if (this.ring[i].hash >= hash) {
                startIndex = i;
                break;
            }
        }
        for (let i = 0; i < this.ring.length && seen.size < count; i++) {
            const index = (startIndex + i) % this.ring.length;
            const sourceId = this.ring[index].sourceId;
            if (!seen.has(sourceId)) {
                seen.add(sourceId);
                sources.push(sourceId);
            }
        }
        return sources;
    }
}
exports.ConsistentHashRouter = ConsistentHashRouter;
class DataSourceManager {
    constructor(enableHealthCheck = true, healthCheckIntervalMs = 30000) {
        this.enableHealthCheck = enableHealthCheck;
        this.healthCheckIntervalMs = healthCheckIntervalMs;
        this.sources = new Map();
        this.circuitBreakerStates = new Map();
        if (enableHealthCheck) {
            this.startHealthCheckLoop();
        }
    }
    addSource(source) {
        this.sources.set(source.id, source);
        this.circuitBreakerStates.set(source.id, { failures: 0, lastFailure: 0, open: false });
        logger_1.default.info({ sourceId: source.id, name: source.name, type: source.type }, '添加数据源');
    }
    removeSource(sourceId) {
        this.circuitBreakerStates.delete(sourceId);
        return this.sources.delete(sourceId);
    }
    getSource(sourceId) {
        return this.sources.get(sourceId);
    }
    getReadableSources() {
        return Array.from(this.sources.values())
            .filter(s => s.isReadable && s.health !== 'unhealthy')
            .sort((a, b) => a.priority - b.priority);
    }
    getWritableSources() {
        return Array.from(this.sources.values())
            .filter(s => s.isWritable && s.health !== 'unhealthy')
            .sort((a, b) => a.priority - b.priority);
    }
    recordFailure(sourceId) {
        const state = this.circuitBreakerStates.get(sourceId);
        if (!state)
            return;
        state.failures++;
        state.lastFailure = Date.now();
        if (state.failures >= 5) {
            state.open = true;
            const source = this.sources.get(sourceId);
            if (source) {
                source.health = 'unhealthy';
                logger_1.default.warn({ sourceId }, '数据源熔断器已打开');
            }
        }
    }
    recordSuccess(sourceId) {
        const state = this.circuitBreakerStates.get(sourceId);
        if (state) {
            state.failures = Math.max(0, state.failures - 1);
            if (state.open && state.failures === 0) {
                state.open = false;
                const source = this.sources.get(sourceId);
                if (source) {
                    source.health = 'healthy';
                    logger_1.default.info({ sourceId }, '数据源熔断器已关闭');
                }
            }
        }
    }
    async startHealthCheckLoop() {
        this.healthCheckTimer = setInterval(() => {
            this.checkAllSources().catch(error => {
                logger_1.default.error({ error }, '数据源健康检查失败');
            });
        }, this.healthCheckIntervalMs);
    }
    async checkAllSources() {
        for (const source of this.sources.values()) {
            try {
                if (source.has) {
                    const healthy = await source.has('health-check');
                    source.health = healthy !== null ? 'healthy' : 'degraded';
                    source.lastHealthCheck = Date.now();
                }
            }
            catch (error) {
                source.health = 'degraded';
                logger_1.default.warn({ sourceId: source.id, error }, '数据源健康检查失败');
            }
        }
    }
    stop() {
        if (this.healthCheckTimer) {
            clearInterval(this.healthCheckTimer);
        }
    }
    listSources() {
        return Array.from(this.sources.values());
    }
    getCircuitBreakerStats() {
        return Array.from(this.circuitBreakerStates.entries()).map(([sourceId, state]) => ({
            sourceId,
            failures: state.failures,
            open: state.open,
            lastFailure: state.lastFailure
        }));
    }
}
exports.DataSourceManager = DataSourceManager;
class DataAccessLayer {
    constructor(config = {}, routingConfig = {}) {
        this.routingRules = [];
        this.pendingAsyncWrites = new Map();
        this.config = {
            defaultTTL: config.defaultTTL ?? 300,
            checkperiod: config.checkperiod ?? 60,
            maxKeys: config.maxKeys ?? 10000,
            useMemoryCache: config.useMemoryCache ?? true,
            useRedis: config.useRedis ?? false
        };
        this.routingConfig = {
            defaultReadStrategy: routingConfig.defaultReadStrategy ?? { type: 'first' },
            defaultWriteStrategy: routingConfig.defaultWriteStrategy ?? { type: 'sync' },
            enableHealthCheck: routingConfig.enableHealthCheck ?? true,
            healthCheckIntervalMs: routingConfig.healthCheckIntervalMs ?? 30000,
            enableAutoFailover: routingConfig.enableAutoFailover ?? true,
            circuitBreakerThreshold: routingConfig.circuitBreakerThreshold ?? 5
        };
        this.memoryCache = new node_cache_1.default({
            stdTTL: this.config.defaultTTL,
            checkperiod: this.config.checkperiod,
            maxKeys: this.config.maxKeys
        });
        this.cacheStats = { hits: 0, misses: 0, sets: 0, deletes: 0 };
        this.setupExpirationListener();
        this.sourceManager = new DataSourceManager(this.routingConfig.enableHealthCheck, this.routingConfig.healthCheckIntervalMs);
        this.hashRouter = new ConsistentHashRouter(100);
        const defaultBuckets = [1, 5, 10, 25, 50, 100, 250, 500, 1000, 2500];
        this.getHistogram = new HistogramRecorder(defaultBuckets);
        this.setHistogram = new HistogramRecorder(defaultBuckets);
        this.deleteHistogram = new HistogramRecorder(defaultBuckets);
        this.hasHistogram = new HistogramRecorder(defaultBuckets);
        this.getManyHistogram = new HistogramRecorder(defaultBuckets);
        this.setManyHistogram = new HistogramRecorder(defaultBuckets);
        this.operationStats = {
            get: this.getHistogram.getStats(),
            set: this.setHistogram.getStats(),
            delete: this.deleteHistogram.getStats(),
            has: this.hasHistogram.getStats(),
            getMany: this.getManyHistogram.getStats(),
            setMany: this.setManyHistogram.getStats()
        };
        this.statsResetAt = Date.now();
        if (this.routingConfig.defaultWriteStrategy.type === 'async') {
            this.startAsyncWriteLoop();
        }
    }
    setupExpirationListener() {
        this.memoryCache.on('expired', (key) => {
            logger_1.default.debug({ key }, '缓存条目已过期');
        });
        this.memoryCache.on('del', (key) => {
            logger_1.default.debug({ key }, '缓存条目已删除');
        });
    }
    startAsyncWriteLoop() {
        this.asyncWriteTimer = setInterval(() => {
            this.processAsyncWrites().catch(error => {
                logger_1.default.error({ error }, '异步写入处理失败');
            });
        }, this.routingConfig.defaultWriteStrategy.asyncWriteDelayMs ?? 1000);
    }
    async processAsyncWrites() {
        const entries = Array.from(this.pendingAsyncWrites.entries());
        this.pendingAsyncWrites.clear();
        for (const [key, { value, ttl, sources }] of entries) {
            for (const sourceId of sources) {
                const source = this.sourceManager.getSource(sourceId);
                if (source && source.set) {
                    try {
                        await source.set(key, value, ttl);
                        this.sourceManager.recordSuccess(sourceId);
                    }
                    catch (error) {
                        this.sourceManager.recordFailure(sourceId);
                        logger_1.default.warn({ key, sourceId, error }, '异步写入失败，将重试');
                        this.pendingAsyncWrites.set(key, { value, ttl, sources });
                    }
                }
            }
        }
    }
    addDataSource(source) {
        this.sourceManager.addSource(source);
        this.hashRouter.addSource(source.id, source.priority);
    }
    removeDataSource(sourceId) {
        this.hashRouter.removeSource(sourceId);
        return this.sourceManager.removeSource(sourceId);
    }
    addRoutingRule(rule) {
        const fullRule = {
            ...rule,
            id: (0, utils_1.generateId)('rule_')
        };
        this.routingRules.push(fullRule);
        this.routingRules.sort((a, b) => b.priority - a.priority);
        logger_1.default.info({ ruleId: fullRule.id, pattern: rule.pattern.source }, '添加路由规则');
        return fullRule;
    }
    removeRoutingRule(ruleId) {
        const index = this.routingRules.findIndex(r => r.id === ruleId);
        if (index > -1) {
            this.routingRules.splice(index, 1);
            return true;
        }
        return false;
    }
    matchRoutingRules(key, value) {
        for (const rule of this.routingRules) {
            if (!rule.enabled)
                continue;
            if (rule.pattern.test(key)) {
                if (!rule.condition || rule.condition(key, value)) {
                    return rule;
                }
            }
        }
        return null;
    }
    async readFromSources(key, sources, strategy) {
        const availableSources = sources
            .map(id => this.sourceManager.getSource(id))
            .filter((s) => s !== undefined && s.isReadable && s.health !== 'unhealthy');
        if (availableSources.length === 0) {
            return null;
        }
        switch (strategy.type) {
            case 'first': {
                for (const source of availableSources) {
                    try {
                        if (source.get) {
                            const result = await source.get(key);
                            if (result !== null) {
                                this.sourceManager.recordSuccess(source.id);
                                return result;
                            }
                        }
                    }
                    catch (error) {
                        this.sourceManager.recordFailure(source.id);
                        logger_1.default.warn({ key, sourceId: source.id, error }, '读取数据源失败，尝试下一个');
                    }
                }
                return null;
            }
            case 'parallel': {
                const maxSources = strategy.maxParallelSources ?? availableSources.length;
                const selectedSources = availableSources.slice(0, maxSources);
                const promises = selectedSources.map(async (s) => {
                    try {
                        if (s.get) {
                            const result = await s.get(key);
                            this.sourceManager.recordSuccess(s.id);
                            return result;
                        }
                    }
                    catch (error) {
                        this.sourceManager.recordFailure(s.id);
                    }
                    return null;
                });
                const results = await Promise.all(promises);
                return results.find(r => r !== null) ?? null;
            }
            case 'sticky': {
                const sourceId = this.hashRouter.getSource(key);
                if (sourceId) {
                    const source = availableSources.find(s => s.id === sourceId);
                    if (source && source.get) {
                        try {
                            const result = await source.get(key);
                            this.sourceManager.recordSuccess(source.id);
                            return result;
                        }
                        catch (error) {
                            this.sourceManager.recordFailure(source.id);
                        }
                    }
                }
                for (const source of availableSources) {
                    try {
                        if (source.get) {
                            const result = await source.get(key);
                            if (result !== null) {
                                this.sourceManager.recordSuccess(source.id);
                                return result;
                            }
                        }
                    }
                    catch (error) {
                        this.sourceManager.recordFailure(source.id);
                    }
                }
                return null;
            }
            case 'consistent-hash': {
                const sourceId = this.hashRouter.getSource(key);
                if (sourceId) {
                    const source = availableSources.find(s => s.id === sourceId);
                    if (source && source.get) {
                        try {
                            const result = await source.get(key);
                            this.sourceManager.recordSuccess(source.id);
                            return result;
                        }
                        catch (error) {
                            this.sourceManager.recordFailure(source.id);
                        }
                    }
                }
                return null;
            }
            default:
                return null;
        }
    }
    async writeToSources(key, value, sources, strategy, ttl) {
        const availableSources = sources
            .map(id => this.sourceManager.getSource(id))
            .filter((s) => s !== undefined && s.isWritable && s.health !== 'unhealthy');
        if (availableSources.length === 0) {
            throw new Error('没有可用的写入数据源');
        }
        switch (strategy.type) {
            case 'sync': {
                const requiredWrites = strategy.requiredWrites ?? availableSources.length;
                const promises = availableSources.map(async (s) => {
                    if (s.set) {
                        try {
                            const result = await s.set(key, value, ttl);
                            this.sourceManager.recordSuccess(s.id);
                            return result;
                        }
                        catch (error) {
                            this.sourceManager.recordFailure(s.id);
                            throw error;
                        }
                    }
                    return false;
                });
                const results = await Promise.allSettled(promises);
                const successful = results.filter(r => r.status === 'fulfilled' && r.value).length;
                if (successful < requiredWrites) {
                    throw new Error(`写入失败：成功 ${successful} 次，需要 ${requiredWrites} 次`);
                }
                break;
            }
            case 'async': {
                for (const source of availableSources) {
                    if (source.set) {
                        this.pendingAsyncWrites.set(key, {
                            value,
                            ttl,
                            sources: availableSources.map(s => s.id)
                        });
                    }
                }
                break;
            }
            case 'quorum': {
                const requiredWrites = strategy.requiredWrites ?? Math.ceil(availableSources.length / 2) + 1;
                let successful = 0;
                const promises = availableSources.map(async (s) => {
                    if (s.set) {
                        try {
                            const result = await s.set(key, value, ttl);
                            this.sourceManager.recordSuccess(s.id);
                            return result;
                        }
                        catch (error) {
                            this.sourceManager.recordFailure(s.id);
                        }
                    }
                    return false;
                });
                for (const promise of promises) {
                    const result = await promise;
                    if (result)
                        successful++;
                    if (successful >= requiredWrites)
                        break;
                }
                if (successful < requiredWrites) {
                    throw new Error(`Quorum写入失败：成功 ${successful} 次，需要 ${requiredWrites} 次`);
                }
                break;
            }
        }
    }
    async get(key, options = {}) {
        const startTime = Date.now();
        try {
            if (options.skipCache) {
                logger_1.default.debug({ key }, '跳过缓存读取');
                return this.getFromDataSource(key, options);
            }
            const value = this.memoryCache.get(key);
            if (value !== undefined) {
                this.cacheStats.hits++;
                logger_1.default.debug({ key }, '缓存命中');
                return value;
            }
            this.cacheStats.misses++;
            logger_1.default.debug({ key }, '缓存未命中，尝试从数据源读取');
            const sourceValue = await this.getFromDataSource(key, options);
            if (sourceValue !== null && !options.skipCache) {
                await this.set(key, sourceValue, options);
            }
            return sourceValue;
        }
        finally {
            this.getHistogram.record(Date.now() - startTime);
        }
    }
    async getFromDataSource(key, options) {
        const rule = this.matchRoutingRules(key);
        let sources;
        let strategy = this.routingConfig.defaultReadStrategy;
        if (options.preferredSource) {
            sources = [options.preferredSource];
        }
        else if (rule) {
            sources = rule.readSources;
        }
        else {
            sources = this.sourceManager.getReadableSources().map(s => s.id);
        }
        if (sources.length === 0) {
            return null;
        }
        return this.readFromSources(key, sources, strategy);
    }
    async set(key, value, options = {}) {
        const startTime = Date.now();
        try {
            const ttl = options.ttl ?? this.config.defaultTTL;
            const success = this.memoryCache.set(key, value, ttl);
            if (success) {
                this.cacheStats.sets++;
                logger_1.default.debug({ key, ttl }, '内存缓存设置成功');
            }
            else {
                logger_1.default.warn({ key }, '内存缓存设置失败');
            }
            await this.setToDataSource(key, value, options);
        }
        finally {
            this.setHistogram.record(Date.now() - startTime);
        }
    }
    async setToDataSource(key, value, options) {
        const rule = this.matchRoutingRules(key, value);
        let sources;
        let strategy = this.routingConfig.defaultWriteStrategy;
        if (rule) {
            sources = rule.writeSources;
        }
        else {
            sources = this.sourceManager.getWritableSources().map(s => s.id);
        }
        if (sources.length === 0) {
            return;
        }
        try {
            await this.writeToSources(key, value, sources, strategy, options.ttl);
        }
        catch (error) {
            logger_1.default.error({ key, error }, '数据源写入失败');
            if (this.routingConfig.enableAutoFailover) {
                const fallbackSources = this.sourceManager.getWritableSources()
                    .map(s => s.id)
                    .filter(id => !sources.includes(id));
                if (fallbackSources.length > 0) {
                    logger_1.default.info({ key, fallbackSources }, '尝试使用备用数据源写入');
                    await this.writeToSources(key, value, fallbackSources, strategy, options.ttl);
                }
            }
        }
    }
    async delete(key) {
        const startTime = Date.now();
        try {
            let deleted = false;
            const memoryDeleted = this.memoryCache.del(key);
            if (memoryDeleted > 0) {
                this.cacheStats.deletes++;
                deleted = true;
                logger_1.default.debug({ key }, '内存缓存删除成功');
            }
            for (const source of this.sourceManager.getWritableSources()) {
                if (source.delete) {
                    try {
                        const sourceDeleted = await source.delete(key);
                        if (sourceDeleted) {
                            deleted = true;
                            this.sourceManager.recordSuccess(source.id);
                        }
                    }
                    catch (error) {
                        this.sourceManager.recordFailure(source.id);
                        logger_1.default.warn({ key, sourceId: source.id, error }, '数据源删除失败');
                    }
                }
            }
            return deleted;
        }
        finally {
            this.deleteHistogram.record(Date.now() - startTime);
        }
    }
    async has(key) {
        const startTime = Date.now();
        try {
            if (this.memoryCache.has(key)) {
                return true;
            }
            for (const source of this.sourceManager.getReadableSources()) {
                if (source.has) {
                    try {
                        const exists = await source.has(key);
                        if (exists) {
                            this.sourceManager.recordSuccess(source.id);
                            return true;
                        }
                    }
                    catch (error) {
                        this.sourceManager.recordFailure(source.id);
                    }
                }
            }
            return false;
        }
        finally {
            this.hasHistogram.record(Date.now() - startTime);
        }
    }
    async getOrSet(key, fetcher, options = {}) {
        if (!options.forceRefresh) {
            const cached = await this.get(key, options);
            if (cached !== null) {
                return cached;
            }
        }
        logger_1.default.debug({ key }, '执行数据获取');
        const value = await fetcher();
        await this.set(key, value, options);
        return value;
    }
    async getMany(keys) {
        const startTime = Date.now();
        try {
            const result = new Map();
            const values = this.memoryCache.mget(keys);
            const missingKeys = [];
            for (const key of keys) {
                const value = values[key];
                if (value !== undefined) {
                    this.cacheStats.hits++;
                    result.set(key, value);
                }
                else {
                    this.cacheStats.misses++;
                    missingKeys.push(key);
                }
            }
            if (missingKeys.length > 0) {
                const readableSources = this.sourceManager.getReadableSources();
                for (const source of readableSources) {
                    if (source.getMany && missingKeys.length > 0) {
                        try {
                            const sourceResults = await source.getMany(missingKeys);
                            for (const [key, value] of sourceResults) {
                                if (value !== null && !result.has(key)) {
                                    result.set(key, value);
                                    const index = missingKeys.indexOf(key);
                                    if (index > -1)
                                        missingKeys.splice(index, 1);
                                }
                            }
                            this.sourceManager.recordSuccess(source.id);
                        }
                        catch (error) {
                            this.sourceManager.recordFailure(source.id);
                        }
                    }
                }
            }
            for (const key of missingKeys) {
                if (!result.has(key)) {
                    result.set(key, null);
                }
            }
            return result;
        }
        finally {
            this.getManyHistogram.record(Date.now() - startTime);
        }
    }
    async setMany(entries) {
        const startTime = Date.now();
        try {
            for (const entry of entries) {
                await this.set(entry.key, entry.value, { ttl: entry.ttl });
            }
        }
        finally {
            this.setManyHistogram.record(Date.now() - startTime);
        }
    }
    async deleteMany(keys) {
        let deleted = 0;
        for (const key of keys) {
            if (await this.delete(key)) {
                deleted++;
            }
        }
        return deleted;
    }
    async invalidatePattern(pattern) {
        const regex = new RegExp(pattern);
        const allKeys = this.memoryCache.keys();
        const matchingKeys = allKeys.filter(key => regex.test(key));
        return this.deleteMany(matchingKeys);
    }
    async clear() {
        this.memoryCache.flushAll();
        logger_1.default.info('内存缓存已全部清空');
        for (const source of this.sourceManager.getWritableSources()) {
            if (source.type === 'memory') {
                logger_1.default.info({ sourceId: source.id }, '跳过内存数据源清空');
            }
        }
    }
    getTimingStats() {
        return {
            get: this.getHistogram.getStats(),
            set: this.setHistogram.getStats(),
            delete: this.deleteHistogram.getStats(),
            has: this.hasHistogram.getStats(),
            getMany: this.getManyHistogram.getStats(),
            setMany: this.setManyHistogram.getStats()
        };
    }
    getPrometheusMetrics() {
        const metrics = [];
        const totalRequests = this.cacheStats.hits + this.cacheStats.misses;
        metrics.push({
            name: 'data_access_cache_hits_total',
            help: 'Total number of cache hits',
            type: 'counter',
            value: this.cacheStats.hits
        });
        metrics.push({
            name: 'data_access_cache_misses_total',
            help: 'Total number of cache misses',
            type: 'counter',
            value: this.cacheStats.misses
        });
        metrics.push({
            name: 'data_access_cache_hit_rate',
            help: 'Cache hit rate',
            type: 'gauge',
            value: totalRequests > 0 ? this.cacheStats.hits / totalRequests : 0
        });
        metrics.push({
            name: 'data_access_cache_sets_total',
            help: 'Total number of cache sets',
            type: 'counter',
            value: this.cacheStats.sets
        });
        metrics.push({
            name: 'data_access_cache_entries',
            help: 'Number of entries in cache',
            type: 'gauge',
            value: this.memoryCache.keys().length
        });
        const timingStats = this.getTimingStats();
        for (const [op, stats] of Object.entries(timingStats)) {
            metrics.push({
                name: `data_access_operation_duration_seconds_count`,
                help: `Count of ${op} operations`,
                type: 'histogram',
                labels: { operation: op },
                value: stats.totalOperations
            });
            metrics.push({
                name: `data_access_operation_duration_seconds_sum`,
                help: `Sum of ${op} operation durations`,
                type: 'histogram',
                labels: { operation: op },
                value: stats.totalTimeMs / 1000
            });
        }
        const sources = this.sourceManager.listSources();
        for (const source of sources) {
            metrics.push({
                name: 'data_access_source_health',
                help: 'Health status of data source',
                type: 'gauge',
                labels: { source_id: source.id, source_name: source.name, source_type: source.type },
                value: source.health === 'healthy' ? 1 : source.health === 'degraded' ? 0.5 : 0
            });
        }
        const circuitBreakerStats = this.sourceManager.getCircuitBreakerStats();
        for (const stat of circuitBreakerStats) {
            metrics.push({
                name: 'data_access_circuit_breaker_open',
                help: 'Circuit breaker status (1=open, 0=closed)',
                type: 'gauge',
                labels: { source_id: stat.sourceId },
                value: stat.open ? 1 : 0
            });
            metrics.push({
                name: 'data_access_circuit_breaker_failures',
                help: 'Number of consecutive failures',
                type: 'gauge',
                labels: { source_id: stat.sourceId },
                value: stat.failures
            });
        }
        return metrics;
    }
    getPrometheusTextFormat() {
        const metrics = this.getPrometheusMetrics();
        let output = '';
        const groupedMetrics = new Map();
        for (const metric of metrics) {
            if (!groupedMetrics.has(metric.name)) {
                groupedMetrics.set(metric.name, []);
            }
            groupedMetrics.get(metric.name).push(metric);
        }
        for (const [name, metricGroup] of groupedMetrics) {
            const first = metricGroup[0];
            output += `# HELP ${name} ${first.help}\n`;
            output += `# TYPE ${name} ${first.type}\n`;
            for (const metric of metricGroup) {
                const labelStr = metric.labels
                    ? Object.entries(metric.labels)
                        .map(([k, v]) => `${k}="${v}"`)
                        .join(',')
                    : '';
                if (labelStr) {
                    output += `${name}{${labelStr}} ${metric.value}\n`;
                }
                else {
                    output += `${name} ${metric.value}\n`;
                }
            }
        }
        return output;
    }
    resetTimingStats() {
        this.getHistogram.reset();
        this.setHistogram.reset();
        this.deleteHistogram.reset();
        this.hasHistogram.reset();
        this.getManyHistogram.reset();
        this.setManyHistogram.reset();
        this.statsResetAt = Date.now();
        logger_1.default.info('数据访问层计时统计已重置');
    }
    getStats() {
        const total = this.cacheStats.hits + this.cacheStats.misses;
        return {
            hits: this.cacheStats.hits,
            misses: this.cacheStats.misses,
            hitRate: total > 0 ? this.cacheStats.hits / total : 0,
            sets: this.cacheStats.sets,
            deletes: this.cacheStats.deletes,
            keys: this.memoryCache.keys().length,
            dataSources: this.sourceManager.listSources().map(s => ({
                id: s.id,
                name: s.name,
                health: s.health,
                type: s.type
            })),
            routingRules: this.routingRules.filter(r => r.enabled).length,
            pendingAsyncWrites: this.pendingAsyncWrites.size,
            timingStats: this.getTimingStats(),
            statsResetAt: this.statsResetAt
        };
    }
    async getEntry(key) {
        const value = this.memoryCache.get(key);
        if (value === undefined)
            return null;
        const ttl = this.memoryCache.getTtl(key);
        return {
            key,
            value,
            createdAt: ttl ? ttl - (this.config.defaultTTL * 1000) : Date.now(),
            expiresAt: ttl ?? Date.now() + this.config.defaultTTL * 1000,
            hits: 0
        };
    }
    generateCacheKey(...parts) {
        return parts.join(':');
    }
    resetStats() {
        this.cacheStats = { hits: 0, misses: 0, sets: 0, deletes: 0 };
        this.resetTimingStats();
    }
    getSourceManager() {
        return this.sourceManager;
    }
    getRoutingRules() {
        return [...this.routingRules];
    }
    stop() {
        this.sourceManager.stop();
        if (this.asyncWriteTimer) {
            clearInterval(this.asyncWriteTimer);
        }
    }
}
exports.DataAccessLayer = DataAccessLayer;
class CacheInvalidationManager {
    constructor() {
        this.invalidationRules = new Map();
    }
    registerRule(entityType, pattern, ttl) {
        if (!this.invalidationRules.has(entityType)) {
            this.invalidationRules.set(entityType, []);
        }
        this.invalidationRules.get(entityType).push({ pattern, ttl });
        logger_1.default.info({ entityType, pattern }, '注册缓存失效规则');
    }
    async invalidate(cache, entityType, entityId) {
        const rules = this.invalidationRules.get(entityType);
        if (!rules)
            return 0;
        let totalInvalidated = 0;
        for (const rule of rules) {
            const pattern = entityId ? rule.pattern.replace('{id}', entityId) : rule.pattern;
            const invalidated = await cache.invalidatePattern(pattern);
            totalInvalidated += invalidated;
        }
        logger_1.default.info({ entityType, entityId, totalInvalidated }, '缓存失效完成');
        return totalInvalidated;
    }
    getRules() {
        return new Map(this.invalidationRules);
    }
}
exports.CacheInvalidationManager = CacheInvalidationManager;
exports.default = DataAccessLayer;
//# sourceMappingURL=index.js.map