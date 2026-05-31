import { Metric, MetricsQuery, MetricsAggregator, Snapshot } from './types';
import { generateId, currentDateTime, logger } from '../utils/common';

export class MetricsCollector {
  private metrics: Metric[] = [];
  private gauges: Map<string, number> = new Map();
  private counters: Map<string, number> = new Map();
  private maxHistorySize: number = 10000;
  private snapshots: Snapshot[] = [];

  recordMetric(
    name: string,
    value: number,
    type: Metric['type'] = 'gauge',
    labels: Record<string, string> = {},
    unit?: string,
    description?: string
  ): Metric {
    const metric: Metric = {
      metricId: generateId('met_'),
      name,
      type,
      value,
      labels,
      timestamp: currentDateTime(),
      unit,
      description,
    };

    this.metrics.push(metric);

    if (this.metrics.length > this.maxHistorySize) {
      this.metrics.shift();
    }

    return metric;
  }

  incrementCounter(name: string, labels: Record<string, string> = {}, amount: number = 1): number {
    const key = `${name}:${JSON.stringify(labels)}`;
    const current = this.counters.get(key) || 0;
    const newValue = current + amount;
    this.counters.set(key, newValue);

    this.recordMetric(name, newValue, 'counter', labels);

    return newValue;
  }

  setGauge(name: string, value: number, labels: Record<string, string> = {}): number {
    const key = `${name}:${JSON.stringify(labels)}`;
    this.gauges.set(key, value);

    this.recordMetric(name, value, 'gauge', labels);

    return value;
  }

  recordHistogram(name: string, value: number, labels: Record<string, string> = {}): void {
    this.recordMetric(name, value, 'histogram', labels);
  }

  recordSummary(name: string, value: number, labels: Record<string, string> = {}): void {
    this.recordMetric(name, value, 'summary', labels);
  }

  query(query: MetricsQuery = {}): Metric[] {
    let results = [...this.metrics];

    if (query.name) {
      results = results.filter(m => m.name === query.name);
    }

    if (query.labels) {
      results = results.filter(m =>
        Object.entries(query.labels!).every(([key, value]) => m.labels[key] === value)
      );
    }

    if (query.startTime) {
      results = results.filter(m => m.timestamp >= query.startTime!);
    }

    if (query.endTime) {
      results = results.filter(m => m.timestamp <= query.endTime!);
    }

    if (query.limit) {
      results = results.slice(-query.limit);
    }

    return results;
  }

  aggregate(query: MetricsQuery = {}): MetricsAggregator | null {
    const metrics = this.query(query);
    if (metrics.length === 0) return null;

    const values = metrics.map(m => m.value).sort((a, b) => a - b);
    const sum = values.reduce((a, b) => a + b, 0);

    return {
      min: values[0],
      max: values[values.length - 1],
      avg: sum / values.length,
      sum,
      count: values.length,
      p50: values[Math.floor(values.length * 0.50)] || 0,
      p95: values[Math.floor(values.length * 0.95)] || 0,
      p99: values[Math.floor(values.length * 0.99)] || 0,
    };
  }

  takeSnapshot(
    throughput: number,
    latencyP99: number,
    errorRate: number,
    dimensions: Record<string, string> = {}
  ): Snapshot {
    const snapshot: Snapshot = {
      snapshotId: generateId('snap_'),
      timestamp: currentDateTime(),
      metrics: { throughput, latencyP99, errorRate },
      dimensions,
    };

    this.snapshots.push(snapshot);

    if (this.snapshots.length > 1000) {
      this.snapshots.shift();
    }

    logger.debug(`Snapshot taken`, { snapshotId: snapshot.snapshotId, throughput, latencyP99, errorRate });

    return snapshot;
  }

  getSnapshots(limit?: number): Snapshot[] {
    const snapshots = [...this.snapshots];
    if (limit) {
      return snapshots.slice(-limit);
    }
    return snapshots;
  }

  getMetricNames(): string[] {
    return Array.from(new Set(this.metrics.map(m => m.name)));
  }

  clear(): void {
    this.metrics = [];
    this.gauges.clear();
    this.counters.clear();
    this.snapshots = [];
    logger.info(`Metrics cleared`);
  }

  getStats() {
    return {
      totalMetrics: this.metrics.length,
      activeGauges: this.gauges.size,
      activeCounters: this.counters.size,
      metricNames: this.getMetricNames(),
      snapshots: this.snapshots.length,
    };
  }
}

export const metricsCollector = new MetricsCollector();
