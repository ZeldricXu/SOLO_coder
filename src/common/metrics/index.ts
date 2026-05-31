import { MetricsSnapshot } from '../types';
import { getEventBus, EventTypes } from '../events';
import { getCacheClient, TTL, generateCacheKey } from '../cache';

export interface MetricRecord {
  name: string;
  value: number;
  type: 'counter' | 'gauge' | 'histogram';
  labels?: Record<string, string>;
  timestamp?: Date;
}

export interface MetricsSummary {
  throughput: number;
  latency_p50: number;
  latency_p95: number;
  latency_p99: number;
  error_rate: number;
  active_requests: number;
}

class MetricsCollector {
  private counters: Map<string, { value: number; labels: Record<string, string> }>;
  private gauges: Map<string, { value: number; labels: Record<string, string> }>;
  private histograms: Map<string, { values: number[]; labels: Record<string, string> }>;
  private requestLatencies: number[];
  private startTime: Date;

  constructor() {
    this.counters = new Map();
    this.gauges = new Map();
    this.histograms = new Map();
    this.requestLatencies = [];
    this.startTime = new Date();
  }

  increment(name: string, value: number = 1, labels: Record<string, string> = {}): void {
    const key = this.getKey(name, labels);
    const current = this.counters.get(key) || { value: 0, labels };
    current.value += value;
    this.counters.set(key, current);
    this.publishEvent('counter', name, current.value, labels);
  }

  decrement(name: string, value: number = 1, labels: Record<string, string> = {}): void {
    this.increment(name, -value, labels);
  }

  gauge(name: string, value: number, labels: Record<string, string> = {}): void {
    const key = this.getKey(name, labels);
    this.gauges.set(key, { value, labels });
    this.publishEvent('gauge', name, value, labels);
  }

  histogram(name: string, value: number, labels: Record<string, string> = {}): void {
    const key = this.getKey(name, labels);
    const current = this.histograms.get(key) || { values: [], labels };
    current.values.push(value);
    if (current.values.length > 1000) {
      current.values = current.values.slice(-1000);
    }
    this.histograms.set(key, current);
  }

  recordLatency(name: string, durationMs: number, labels: Record<string, string> = {}): void {
    this.histogram(name, durationMs, labels);
    this.requestLatencies.push(durationMs);
    if (this.requestLatencies.length > 10000) {
      this.requestLatencies = this.requestLatencies.slice(-10000);
    }
  }

  getCounter(name: string, labels: Record<string, string> = {}): number {
    const key = this.getKey(name, labels);
    return this.counters.get(key)?.value ?? 0;
  }

  getGauge(name: string, labels: Record<string, string> = {}): number {
    const key = this.getKey(name, labels);
    return this.gauges.get(key)?.value ?? 0;
  }

  getHistogramStats(name: string, labels: Record<string, string> = {}) {
    const key = this.getKey(name, labels);
    const data = this.histograms.get(key);
    if (!data || data.values.length === 0) {
      return { count: 0, sum: 0, avg: 0, p50: 0, p95: 0, p99: 0, min: 0, max: 0 };
    }
    const sorted = [...data.values].sort((a, b) => a - b);
    const count = sorted.length;
    const sum = sorted.reduce((a, b) => a + b, 0);
    return {
      count,
      sum,
      avg: sum / count,
      p50: this.percentile(sorted, 50),
      p95: this.percentile(sorted, 95),
      p99: this.percentile(sorted, 99),
      min: sorted[0],
      max: sorted[sorted.length - 1]
    };
  }

  getSummary(): MetricsSummary {
    const elapsed = (Date.now() - this.startTime.getTime()) / 1000;
    const requests = this.getCounter('http_requests_total');
    const errors = this.getCounter('http_requests_errors');
    
    const sorted = [...this.requestLatencies].sort((a, b) => a - b);
    
    return {
      throughput: elapsed > 0 ? requests / elapsed : 0,
      latency_p50: this.percentile(sorted, 50),
      latency_p95: this.percentile(sorted, 95),
      latency_p99: this.percentile(sorted, 99),
      error_rate: requests > 0 ? errors / requests : 0,
      active_requests: this.getGauge('http_active_requests')
    };
  }

  async takeSnapshot(dimensions: Record<string, string> = {}): Promise<MetricsSnapshot> {
    const summary = this.getSummary();
    const snapshot: MetricsSnapshot = {
      snapshotId: `snap_${Date.now()}`,
      timestamp: new Date(),
      metrics: {
        throughput: summary.throughput,
        latency_p50: summary.latency_p50,
        latency_p95: summary.latency_p95,
        latency_p99: summary.latency_p99,
        error_rate: summary.error_rate,
        active_requests: summary.active_requests,
        total_requests: this.getCounter('http_requests_total'),
        total_errors: this.getCounter('http_requests_errors')
      },
      dimensions
    };

    const cache = getCacheClient();
    await cache.set(
      generateCacheKey('metrics', 'snapshot', snapshot.snapshotId),
      snapshot,
      TTL.DAY
    );

    getEventBus().publish(EventTypes.METRICS_RECORDED, snapshot);

    return snapshot;
  }

  reset(): void {
    this.counters.clear();
    this.gauges.clear();
    this.histograms.clear();
    this.requestLatencies = [];
    this.startTime = new Date();
  }

  getAllMetrics() {
    return {
      counters: Array.from(this.counters.entries()).map(([k, v]) => ({ name: k, ...v })),
      gauges: Array.from(this.gauges.entries()).map(([k, v]) => ({ name: k, ...v })),
      histograms: Array.from(this.histograms.entries()).map(([k, v]) => ({
        name: k,
        labels: v.labels,
        stats: this.getHistogramStats(k, v.labels)
      }))
    };
  }

  getSnapshot() {
    return {
      ...this.getSummary(),
      counters: Object.fromEntries(
        Array.from(this.counters.entries()).map(([k, v]) => [k, v.value])
      ),
      gauges: Object.fromEntries(
        Array.from(this.gauges.entries()).map(([k, v]) => [k, v.value])
      ),
      uptime: process.uptime(),
      timestamp: new Date().toISOString()
    };
  }

  private getKey(name: string, labels: Record<string, string>): string {
    const labelStr = Object.entries(labels)
      .sort(([a], [b]) => a.localeCompare(b))
      .map(([k, v]) => `${k}=${v}`)
      .join(',');
    return labelStr ? `${name}{${labelStr}}` : name;
  }

  private percentile(sorted: number[], p: number): number {
    if (sorted.length === 0) return 0;
    const index = Math.ceil((p / 100) * sorted.length) - 1;
    return sorted[Math.max(0, Math.min(index, sorted.length - 1))];
  }

  private publishEvent(type: string, name: string, value: number, labels: Record<string, string>): void {
    getEventBus().publish(EventTypes.METRICS_RECORDED, {
      metricType: type,
      metricName: name,
      value,
      labels,
      timestamp: new Date()
    });
  }
}

const metricsCollector = new MetricsCollector();

export const getMetricsCollector = (): MetricsCollector => metricsCollector;

export const withMetrics = async <T>(
  operation: string,
  labels: Record<string, string>,
  fn: () => Promise<T>
): Promise<T> => {
  const collector = getMetricsCollector();
  const start = Date.now();
  collector.gauge('http_active_requests', collector.getGauge('http_active_requests') + 1);
  collector.increment('http_requests_total', 1, labels);

  try {
    const result = await fn();
    collector.increment('http_requests_success', 1, labels);
    return result;
  } catch (err) {
    collector.increment('http_requests_errors', 1, labels);
    getEventBus().publish(EventTypes.ERROR_OCCURRED, {
      operation,
      error: err instanceof Error ? err.message : String(err),
      labels
    });
    throw err;
  } finally {
    const duration = Date.now() - start;
    collector.recordLatency('http_request_duration_ms', duration, labels);
    collector.gauge('http_active_requests', Math.max(0, collector.getGauge('http_active_requests') - 1));
  }
};
