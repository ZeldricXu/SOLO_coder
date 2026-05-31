import { EventEmitter } from 'events';
import NodeCache from 'node-cache';
import { StatsSnapshot } from '../types';
import { generateId, nowISO, calculatePercentiles } from '../shared/utils';
import { logger } from '../logging';

interface MetricDataPoint {
  timestamp: number;
  value: number;
  dimensions: Record<string, string>;
}

interface MetricDefinition {
  name: string;
  description: string;
  unit: string;
  type: 'counter' | 'gauge' | 'histogram' | 'timer';
  labels: string[];
  retention_days: number;
}

interface AggregatedMetrics {
  count: number;
  sum: number;
  avg: number;
  min: number;
  max: number;
  p50: number;
  p95: number;
  p99: number;
}

interface AlertRule {
  rule_id: string;
  metric_name: string;
  condition: 'gt' | 'lt' | 'gte' | 'lte' | 'eq';
  threshold: number;
  duration: number;
  enabled: boolean;
  notification_channels: string[];
  created_at: string;
}

class MonitoringService extends EventEmitter {
  private metrics: Map<string, MetricDataPoint[]> = new Map();
  private definitions: Map<string, MetricDefinition> = new Map();
  private counters: Map<string, number> = new Map();
  private gauges: Map<string, number> = new Map();
  private timers: Map<string, number> = new Map();
  private snapshots: StatsSnapshot[] = [];
  private alertRules: AlertRule[] = [];
  private cache: NodeCache;
  private maxDataPointsPerMetric = 10000;
  private maxSnapshots = 1000;
  private aggregationIntervals: number[] = [60000, 300000, 3600000];

  constructor() {
    super();
    this.cache = new NodeCache({ stdTTL: 300, checkperiod: 60 });
    this.registerDefaultMetrics();
  }

  private registerDefaultMetrics(): void {
    const defaults: MetricDefinition[] = [
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

  registerMetric(definition: MetricDefinition): void {
    this.definitions.set(definition.name, definition);
    logger.info('Metric registered', { metric_name: definition.name });
    this.emit('metric.registered', definition);
  }

  incrementCounter(metricName: string, value: number = 1, dimensions: Record<string, string> = {}): void {
    const key = this.getDimensionKey(metricName, dimensions);
    const current = this.counters.get(key) || 0;
    this.counters.set(key, current + value);
    this.recordDataPoint(metricName, current + value, dimensions);
    this.checkAlertRules(metricName, current + value);
  }

  setGauge(metricName: string, value: number, dimensions: Record<string, string> = {}): void {
    const key = this.getDimensionKey(metricName, dimensions);
    this.gauges.set(key, value);
    this.recordDataPoint(metricName, value, dimensions);
    this.checkAlertRules(metricName, value);
  }

  startTimer(metricName: string, dimensions: Record<string, string> = {}): string {
    const timerId = generateId('tmr');
    const key = this.getDimensionKey(metricName, dimensions, timerId);
    this.timers.set(key, Date.now());
    return timerId;
  }

  stopTimer(metricName: string, timerId: string, dimensions: Record<string, string> = {}): number {
    const key = this.getDimensionKey(metricName, dimensions, timerId);
    const startTime = this.timers.get(key);
    if (!startTime) {
      logger.warn('Timer not found', { metric_name: metricName, timer_id: timerId });
      return -1;
    }
    const duration = Date.now() - startTime;
    this.timers.delete(key);
    this.recordDataPoint(metricName, duration, dimensions);
    this.checkAlertRules(metricName, duration);
    return duration;
  }

  recordLatency(metricName: string, duration: number, dimensions: Record<string, string> = {}): void {
    this.recordDataPoint(metricName, duration, dimensions);
    this.checkAlertRules(metricName, duration);
  }

  private recordDataPoint(metricName: string, value: number, dimensions: Record<string, string>): void {
    const dataPoint: MetricDataPoint = {
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

  getMetricValues(
    metricName: string,
    dimensions?: Record<string, string>,
    startTime?: number,
    endTime?: number
  ): MetricDataPoint[] {
    const keys = dimensions
      ? [this.getDimensionKey(metricName, dimensions)]
      : Array.from(this.metrics.keys()).filter((k) => k.startsWith(`${metricName}:`));

    const result: MetricDataPoint[] = [];
    for (const key of keys) {
      const points = this.metrics.get(key) || [];
      const filtered = points.filter((p) => {
        if (startTime && p.timestamp < startTime) return false;
        if (endTime && p.timestamp > endTime) return false;
        return true;
      });
      result.push(...filtered);
    }
    return result.sort((a, b) => a.timestamp - b.timestamp);
  }

  aggregateMetrics(
    metricName: string,
    dimensions?: Record<string, string>,
    startTime?: number,
    endTime?: number
  ): AggregatedMetrics | null {
    const values = this.getMetricValues(metricName, dimensions, startTime, endTime).map((p) => p.value);
    if (values.length === 0) return null;

    const sum = values.reduce((a, b) => a + b, 0);
    const percentiles = calculatePercentiles(values, [50, 95, 99]);

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

  createSnapshot(dimensions: { host: string; region: string; [key: string]: string }): StatsSnapshot {
    const throughput = this.aggregateMetrics('throughput')?.avg || 0;
    const latencyP99 = this.aggregateMetrics('latency')?.p99 || 0;
    const errorRate = this.aggregateMetrics('error_rate')?.avg || 0;

    const snapshot: StatsSnapshot = {
      snapshot_id: generateId('snap'),
      timestamp: nowISO(),
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
    logger.info('Stats snapshot created', { snapshot_id: snapshot.snapshot_id });
    this.emit('snapshot.created', snapshot);

    return snapshot;
  }

  getSnapshots(limit?: number, startTime?: number, endTime?: number): StatsSnapshot[] {
    let filtered = this.snapshots;
    if (startTime || endTime) {
      filtered = filtered.filter((s) => {
        const ts = new Date(s.timestamp).getTime();
        if (startTime && ts < startTime) return false;
        if (endTime && ts > endTime) return false;
        return true;
      });
    }
    if (limit) {
      filtered = filtered.slice(-limit);
    }
    return filtered.sort((a, b) => new Date(b.timestamp).getTime() - new Date(a.timestamp).getTime());
  }

  createAlertRule(
    metricName: string,
    condition: AlertRule['condition'],
    threshold: number,
    duration: number,
    notificationChannels: string[]
  ): AlertRule {
    const rule: AlertRule = {
      rule_id: generateId('alr'),
      metric_name: metricName,
      condition,
      threshold,
      duration,
      enabled: true,
      notification_channels: notificationChannels,
      created_at: nowISO(),
    };

    this.alertRules.push(rule);
    logger.info('Alert rule created', { rule_id: rule.rule_id, metric_name: metricName });
    this.emit('alert.rule_created', rule);

    return rule;
  }

  private checkAlertRules(metricName: string, value: number): void {
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
        logger.warn('Alert triggered', {
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

  getMetricDefinitions(): MetricDefinition[] {
    return Array.from(this.definitions.values());
  }

  getCurrentCounters(dimensions?: Record<string, string>): Record<string, number> {
    const result: Record<string, number> = {};
    for (const [key, value] of this.counters.entries()) {
      if (!dimensions || this.keyMatchesDimensions(key, dimensions)) {
        result[key] = value;
      }
    }
    return result;
  }

  getCurrentGauges(dimensions?: Record<string, string>): Record<string, number> {
    const result: Record<string, number> = {};
    for (const [key, value] of this.gauges.entries()) {
      if (!dimensions || this.keyMatchesDimensions(key, dimensions)) {
        result[key] = value;
      }
    }
    return result;
  }

  resetMetric(metricName: string, dimensions?: Record<string, string>): void {
    const keys = dimensions
      ? [this.getDimensionKey(metricName, dimensions)]
      : Array.from(this.metrics.keys()).filter((k) => k.startsWith(`${metricName}:`));

    for (const key of keys) {
      this.metrics.delete(key);
      this.counters.delete(key);
      this.gauges.delete(key);
    }
    logger.info('Metric reset', { metric_name: metricName });
  }

  resetAll(): void {
    this.metrics.clear();
    this.counters.clear();
    this.gauges.clear();
    this.timers.clear();
    logger.info('All metrics reset');
  }

  generateReport(startTime: number, endTime: number): Record<string, unknown> {
    const metrics = this.getMetricDefinitions();
    const report: Record<string, unknown> = {
      start_time: new Date(startTime).toISOString(),
      end_time: new Date(endTime).toISOString(),
      generated_at: nowISO(),
      metrics: {},
    };

    for (const def of metrics) {
      const aggregated = this.aggregateMetrics(def.name, undefined, startTime, endTime);
      if (aggregated) {
        (report.metrics as Record<string, unknown>)[def.name] = {
          definition: def,
          ...aggregated,
        };
      }
    }

    return report;
  }

  private getDimensionKey(metricName: string, dimensions: Record<string, string>, suffix?: string): string {
    const dims = Object.keys(dimensions)
      .sort()
      .map((k) => `${k}=${dimensions[k]}`)
      .join(',');
    return `${metricName}:${dims}${suffix ? `:${suffix}` : ''}`;
  }

  private keyMatchesDimensions(key: string, dimensions: Record<string, string>): boolean {
    for (const [k, v] of Object.entries(dimensions)) {
      if (!key.includes(`${k}=${v}`)) {
        return false;
      }
    }
    return true;
  }
}

export const monitoring = new MonitoringService();
export { MonitoringService, MetricDefinition, MetricDataPoint, AggregatedMetrics, AlertRule };
