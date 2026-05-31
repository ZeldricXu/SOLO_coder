"use strict";
var __importDefault = (this && this.__importDefault) || function (mod) {
    return (mod && mod.__esModule) ? mod : { "default": mod };
};
Object.defineProperty(exports, "__esModule", { value: true });
exports.MonitoringService = exports.monitoring = void 0;
const events_1 = require("events");
const node_cache_1 = __importDefault(require("node-cache"));
const utils_1 = require("../shared/utils");
const logging_1 = require("../logging");
class MonitoringService extends events_1.EventEmitter {
    metrics = new Map();
    definitions = new Map();
    counters = new Map();
    gauges = new Map();
    timers = new Map();
    snapshots = [];
    alertRules = [];
    cache;
    maxDataPointsPerMetric = 10000;
    maxSnapshots = 1000;
    aggregationIntervals = [60000, 300000, 3600000];
    constructor() {
        super();
        this.cache = new node_cache_1.default({ stdTTL: 300, checkperiod: 60 });
        this.registerDefaultMetrics();
    }
    registerDefaultMetrics() {
        const defaults = [
            {
                name: 'throughput',
                description: 'Number of requests processed per second',
                unit: 'req/s',
                type: 'gauge',
                labels: ['service', 'endpoint'],
                retention_days: 30,
            },
            {
                name: 'latency',
                description: 'Request latency in milliseconds',
                unit: 'ms',
                type: 'histogram',
                labels: ['service', 'endpoint'],
                retention_days: 30,
            },
            {
                name: 'error_rate',
                description: 'Percentage of failed requests',
                unit: '%',
                type: 'gauge',
                labels: ['service', 'error_type'],
                retention_days: 30,
            },
            {
                name: 'memory_usage',
                description: 'Memory usage in bytes',
                unit: 'bytes',
                type: 'gauge',
                labels: ['host'],
                retention_days: 7,
            },
            {
                name: 'cpu_usage',
                description: 'CPU usage percentage',
                unit: '%',
                type: 'gauge',
                labels: ['host'],
                retention_days: 7,
            },
        ];
        for (const def of defaults) {
            this.definitions.set(def.name, def);
        }
    }
    registerMetric(definition) {
        this.definitions.set(definition.name, definition);
        logging_1.logger.info('Metric registered', { metric_name: definition.name });
        this.emit('metric.registered', definition);
    }
    incrementCounter(metricName, value = 1, dimensions = {}) {
        const key = this.getDimensionKey(metricName, dimensions);
        const current = this.counters.get(key) || 0;
        this.counters.set(key, current + value);
        this.recordDataPoint(metricName, current + value, dimensions);
        this.checkAlertRules(metricName, current + value);
    }
    setGauge(metricName, value, dimensions = {}) {
        const key = this.getDimensionKey(metricName, dimensions);
        this.gauges.set(key, value);
        this.recordDataPoint(metricName, value, dimensions);
        this.checkAlertRules(metricName, value);
    }
    startTimer(metricName, dimensions = {}) {
        const timerId = (0, utils_1.generateId)('tmr');
        const key = this.getDimensionKey(metricName, dimensions, timerId);
        this.timers.set(key, Date.now());
        return timerId;
    }
    stopTimer(metricName, timerId, dimensions = {}) {
        const key = this.getDimensionKey(metricName, dimensions, timerId);
        const startTime = this.timers.get(key);
        if (!startTime) {
            logging_1.logger.warn('Timer not found', { metric_name: metricName, timer_id: timerId });
            return -1;
        }
        const duration = Date.now() - startTime;
        this.timers.delete(key);
        this.recordDataPoint(metricName, duration, dimensions);
        this.checkAlertRules(metricName, duration);
        return duration;
    }
    recordLatency(metricName, duration, dimensions = {}) {
        this.recordDataPoint(metricName, duration, dimensions);
        this.checkAlertRules(metricName, duration);
    }
    recordDataPoint(metricName, value, dimensions) {
        const dataPoint = {
            timestamp: Date.now(),
            value,
            dimensions,
        };
        const key = this.getDimensionKey(metricName, dimensions);
        let points = this.metrics.get(key);
        if (!points) {
            points = [];
            this.metrics.set(key, points);
        }
        points.push(dataPoint);
        if (points.length > this.maxDataPointsPerMetric) {
            points.shift();
        }
        this.emit('metric.recorded', metricName, dataPoint);
    }
    getMetricValues(metricName, dimensions, startTime, endTime) {
        const keys = dimensions
            ? [this.getDimensionKey(metricName, dimensions)]
            : Array.from(this.metrics.keys()).filter((k) => k.startsWith(`${metricName}:`));
        const result = [];
        for (const key of keys) {
            const points = this.metrics.get(key) || [];
            const filtered = points.filter((p) => {
                if (startTime && p.timestamp < startTime)
                    return false;
                if (endTime && p.timestamp > endTime)
                    return false;
                return true;
            });
            result.push(...filtered);
        }
        return result.sort((a, b) => a.timestamp - b.timestamp);
    }
    aggregateMetrics(metricName, dimensions, startTime, endTime) {
        const values = this.getMetricValues(metricName, dimensions, startTime, endTime).map((p) => p.value);
        if (values.length === 0)
            return null;
        const sum = values.reduce((a, b) => a + b, 0);
        const percentiles = (0, utils_1.calculatePercentiles)(values, [50, 95, 99]);
        return {
            count: values.length,
            sum,
            avg: sum / values.length,
            min: Math.min(...values),
            max: Math.max(...values),
            p50: percentiles[50],
            p95: percentiles[95],
            p99: percentiles[99],
        };
    }
    createSnapshot(dimensions) {
        const throughput = this.aggregateMetrics('throughput')?.avg || 0;
        const latencyP99 = this.aggregateMetrics('latency')?.p99 || 0;
        const errorRate = this.aggregateMetrics('error_rate')?.avg || 0;
        const snapshot = {
            snapshot_id: (0, utils_1.generateId)('snap'),
            timestamp: (0, utils_1.nowISO)(),
            metrics: {
                throughput,
                latency_p99: latencyP99,
                error_rate: errorRate,
            },
            dimensions,
        };
        this.snapshots.push(snapshot);
        if (this.snapshots.length > this.maxSnapshots) {
            this.snapshots.shift();
        }
        this.cache.set(`snapshot:${snapshot.snapshot_id}`, snapshot);
        logging_1.logger.info('Stats snapshot created', { snapshot_id: snapshot.snapshot_id });
        this.emit('snapshot.created', snapshot);
        return snapshot;
    }
    getSnapshots(limit, startTime, endTime) {
        let filtered = this.snapshots;
        if (startTime || endTime) {
            filtered = filtered.filter((s) => {
                const ts = new Date(s.timestamp).getTime();
                if (startTime && ts < startTime)
                    return false;
                if (endTime && ts > endTime)
                    return false;
                return true;
            });
        }
        if (limit) {
            filtered = filtered.slice(-limit);
        }
        return filtered.sort((a, b) => new Date(b.timestamp).getTime() - new Date(a.timestamp).getTime());
    }
    createAlertRule(metricName, condition, threshold, duration, notificationChannels) {
        const rule = {
            rule_id: (0, utils_1.generateId)('alr'),
            metric_name: metricName,
            condition,
            threshold,
            duration,
            enabled: true,
            notification_channels: notificationChannels,
            created_at: (0, utils_1.nowISO)(),
        };
        this.alertRules.push(rule);
        logging_1.logger.info('Alert rule created', { rule_id: rule.rule_id, metric_name: metricName });
        this.emit('alert.rule_created', rule);
        return rule;
    }
    checkAlertRules(metricName, value) {
        const relevantRules = this.alertRules.filter((r) => r.metric_name === metricName && r.enabled);
        for (const rule of relevantRules) {
            let triggered = false;
            switch (rule.condition) {
                case 'gt':
                    triggered = value > rule.threshold;
                    break;
                case 'lt':
                    triggered = value < rule.threshold;
                    break;
                case 'gte':
                    triggered = value >= rule.threshold;
                    break;
                case 'lte':
                    triggered = value <= rule.threshold;
                    break;
                case 'eq':
                    triggered = value === rule.threshold;
                    break;
            }
            if (triggered) {
                logging_1.logger.warn('Alert triggered', {
                    rule_id: rule.rule_id,
                    metric_name: metricName,
                    value,
                    threshold: rule.threshold,
                    condition: rule.condition,
                });
                this.emit('alert.triggered', rule, value);
            }
        }
    }
    getMetricDefinitions() {
        return Array.from(this.definitions.values());
    }
    getCurrentCounters(dimensions) {
        const result = {};
        for (const [key, value] of this.counters.entries()) {
            if (!dimensions || this.keyMatchesDimensions(key, dimensions)) {
                result[key] = value;
            }
        }
        return result;
    }
    getCurrentGauges(dimensions) {
        const result = {};
        for (const [key, value] of this.gauges.entries()) {
            if (!dimensions || this.keyMatchesDimensions(key, dimensions)) {
                result[key] = value;
            }
        }
        return result;
    }
    resetMetric(metricName, dimensions) {
        const keys = dimensions
            ? [this.getDimensionKey(metricName, dimensions)]
            : Array.from(this.metrics.keys()).filter((k) => k.startsWith(`${metricName}:`));
        for (const key of keys) {
            this.metrics.delete(key);
            this.counters.delete(key);
            this.gauges.delete(key);
        }
        logging_1.logger.info('Metric reset', { metric_name: metricName });
    }
    resetAll() {
        this.metrics.clear();
        this.counters.clear();
        this.gauges.clear();
        this.timers.clear();
        logging_1.logger.info('All metrics reset');
    }
    generateReport(startTime, endTime) {
        const metrics = this.getMetricDefinitions();
        const report = {
            start_time: new Date(startTime).toISOString(),
            end_time: new Date(endTime).toISOString(),
            generated_at: (0, utils_1.nowISO)(),
            metrics: {},
        };
        for (const def of metrics) {
            const aggregated = this.aggregateMetrics(def.name, undefined, startTime, endTime);
            if (aggregated) {
                report.metrics[def.name] = {
                    definition: def,
                    ...aggregated,
                };
            }
        }
        return report;
    }
    getDimensionKey(metricName, dimensions, suffix) {
        const dims = Object.keys(dimensions)
            .sort()
            .map((k) => `${k}=${dimensions[k]}`)
            .join(',');
        return `${metricName}:${dims}${suffix ? `:${suffix}` : ''}`;
    }
    keyMatchesDimensions(key, dimensions) {
        for (const [k, v] of Object.entries(dimensions)) {
            if (!key.includes(`${k}=${v}`)) {
                return false;
            }
        }
        return true;
    }
}
exports.MonitoringService = MonitoringService;
exports.monitoring = new MonitoringService();
//# sourceMappingURL=index.js.map