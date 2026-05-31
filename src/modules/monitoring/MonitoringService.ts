import { MetricsSnapshot, RequestContext } from '../../common';
import { generateId, logger } from '../../common';

export type MetricType = 'counter' | 'gauge' | 'histogram' | 'summary';

export interface MetricDefinition {
  name: string;
  type: MetricType;
  description: string;
  labels: string[];
  unit?: string;
}

export interface MetricValue {
  name: string;
  value: number;
  labels: Record<string, string>;
  timestamp: number;
}

export interface HistogramBucket {
  upperBound: number;
  count: number;
}

export interface HistogramValue extends MetricValue {
  sum: number;
  count: number;
  buckets: HistogramBucket[];
}

export interface SummaryValue extends MetricValue {
  sum: number;
  count: number;
  quantiles: { quantile: number; value: number }[];
}

export interface MetricsQuery {
  name?: string;
  labels?: Record<string, string>;
  startTime?: number;
  endTime?: number;
  limit?: number;
}

export class MonitoringService {
  private metricDefinitions: Map<string, MetricDefinition> = new Map();
  private counters: Map<string, Map<string, number>> = new Map();
  private gauges: Map<string, Map<string, number>> = new Map();
  private histograms: Map<string, Map<string, { sum: number; count: number; buckets: HistogramBucket[] }>> = new Map();
  private summaries: Map<string, Map<string, { sum: number; count: number; values: number[] }>> = new Map();
  private history: MetricValue[] = [];
  private snapshots: MetricsSnapshot[] = [];
  private maxHistorySize: number = 10000;
  private snapshotInterval?: NodeJS.Timeout;

  constructor(options: { snapshotIntervalMs?: number; maxHistorySize?: number } = {}) {
    if (options.maxHistorySize) {
      this.maxHistorySize = options.maxHistorySize;
    }

    if (options.snapshotIntervalMs) {
      this.snapshotInterval = setInterval(() => {
        this.takeSnapshot();
      }, options.snapshotIntervalMs);
    }
  }

  registerMetric(definition: MetricDefinition): void {
    if (this.metricDefinitions.has(definition.name)) {
      throw new Error(`Metric already registered: ${definition.name}`);
    }

    this.metricDefinitions.set(definition.name, definition);
    logger.info('Metric registered', { name: definition.name, type: definition.type });
  }

  getMetricDefinition(name: string): MetricDefinition | undefined {
    return this.metricDefinitions.get(name);
  }

  listMetricDefinitions(): MetricDefinition[] {
    return Array.from(this.metricDefinitions.values());
  }

  increment(name: string, labels: Record<string, string> = {}, value: number = 1): void {
    const definition = this.metricDefinitions.get(name);
    if (!definition) {
      throw new Error(`Metric not registered: ${name}`);
    }
    if (definition.type !== 'counter') {
      throw new Error(`Metric ${name} is not a counter`);
    }

    const key = this.labelsToKey(labels);
    const counterMap = this.counters.get(name) || new Map();
    const current = counterMap.get(key) || 0;
    counterMap.set(key, current + value);
    this.counters.set(name, counterMap);

    this.recordHistory(name, current + value, labels);
  }

  decrement(name: string, labels: Record<string, string> = {}, value: number = 1): void {
    this.increment(name, labels, -value);
  }

  setGauge(name: string, labels: Record<string, string> = {}, value: number): void {
    const definition = this.metricDefinitions.get(name);
    if (!definition) {
      throw new Error(`Metric not registered: ${name}`);
    }
    if (definition.type !== 'gauge') {
      throw new Error(`Metric ${name} is not a gauge`);
    }

    const key = this.labelsToKey(labels);
    const gaugeMap = this.gauges.get(name) || new Map();
    gaugeMap.set(key, value);
    this.gauges.set(name, gaugeMap);

    this.recordHistory(name, value, labels);
  }

  observeHistogram(name: string, labels: Record<string, string> = {}, value: number): void {
    const definition = this.metricDefinitions.get(name);
    if (!definition) {
      throw new Error(`Metric not registered: ${name}`);
    }
    if (definition.type !== 'histogram') {
      throw new Error(`Metric ${name} is not a histogram`);
    }

    const key = this.labelsToKey(labels);
    const histogramMap = this.histograms.get(name) || new Map();
    let histogram = histogramMap.get(key);

    if (!histogram) {
      histogram = {
        sum: 0,
        count: 0,
        buckets: this.createDefaultBuckets().map(upperBound => ({ upperBound, count: 0 }))
      };
    }

    histogram.sum += value;
    histogram.count += 1;

    for (const bucket of histogram.buckets) {
      if (value <= bucket.upperBound) {
        bucket.count += 1;
      }
    }

    histogramMap.set(key, histogram);
    this.histograms.set(name, histogramMap);

    this.recordHistory(name, value, labels);
  }

  observeSummary(name: string, labels: Record<string, string> = {}, value: number): void {
    const definition = this.metricDefinitions.get(name);
    if (!definition) {
      throw new Error(`Metric not registered: ${name}`);
    }
    if (definition.type !== 'summary') {
      throw new Error(`Metric ${name} is not a summary`);
    }

    const key = this.labelsToKey(labels);
    const summaryMap = this.summaries.get(name) || new Map();
    let summary = summaryMap.get(key);

    if (!summary) {
      summary = { sum: 0, count: 0, values: [] };
    }

    summary.sum += value;
    summary.count += 1;
    summary.values.push(value);

    if (summary.values.length > 1000) {
      summary.values = summary.values.slice(-1000);
    }

    summaryMap.set(key, summary);
    this.summaries.set(name, summaryMap);

    this.recordHistory(name, value, labels);
  }

  private createDefaultBuckets(): number[] {
    return [0.005, 0.01, 0.025, 0.05, 0.1, 0.25, 0.5, 1, 2.5, 5, 10, Infinity];
  }

  private labelsToKey(labels: Record<string, string>): string {
    return Object.entries(labels)
      .sort(([a], [b]) => a.localeCompare(b))
      .map(([k, v]) => `${k}=${v}`)
      .join(',');
  }

  private recordHistory(name: string, value: number, labels: Record<string, string>): void {
    const metricValue: MetricValue = {
      name,
      value,
      labels,
      timestamp: Date.now()
    };

    this.history.push(metricValue);

    if (this.history.length > this.maxHistorySize) {
      this.history = this.history.slice(-this.maxHistorySize);
    }
  }

  getCounterValue(name: string, labels: Record<string, string> = {}): number {
    const counterMap = this.counters.get(name);
    if (!counterMap) return 0;
    const key = this.labelsToKey(labels);
    return counterMap.get(key) || 0;
  }

  getGaugeValue(name: string, labels: Record<string, string> = {}): number {
    const gaugeMap = this.gauges.get(name);
    if (!gaugeMap) return 0;
    const key = this.labelsToKey(labels);
    return gaugeMap.get(key) || 0;
  }

  getHistogramValue(name: string, labels: Record<string, string> = {}): { sum: number; count: number; buckets: HistogramBucket[] } | null {
    const histogramMap = this.histograms.get(name);
    if (!histogramMap) return null;
    const key = this.labelsToKey(labels);
    return histogramMap.get(key) || null;
  }

  getSummaryValue(name: string, labels: Record<string, string> = {}): { sum: number; count: number; quantiles: { quantile: number; value: number }[] } | null {
    const summaryMap = this.summaries.get(name);
    if (!summaryMap) return null;
    const key = this.labelsToKey(labels);
    const summary = summaryMap.get(key);
    if (!summary) return null;

    const sortedValues = [...summary.values].sort((a, b) => a - b);
    const quantiles = [0.5, 0.9, 0.95, 0.99].map(q => ({
      quantile: q,
      value: this.quantile(sortedValues, q)
    }));

    return {
      sum: summary.sum,
      count: summary.count,
      quantiles
    };
  }

  private quantile(sortedValues: number[], q: number): number {
    if (sortedValues.length === 0) return 0;
    const pos = (sortedValues.length - 1) * q;
    const lower = Math.floor(pos);
    const upper = Math.ceil(pos);
    if (lower === upper) return sortedValues[lower];
    return sortedValues[lower] + (sortedValues[upper] - sortedValues[lower]) * (pos - lower);
  }

  queryMetrics(query: MetricsQuery): MetricValue[] {
    let results = [...this.history];

    if (query.name) {
      results = results.filter(m => m.name === query.name);
    }

    if (query.labels) {
      results = results.filter(m => {
        return Object.entries(query.labels!).every(([k, v]) => m.labels[k] === v);
      });
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

  takeSnapshot(dimensions: Record<string, string> = {}): MetricsSnapshot {
    const metrics: Record<string, number> = {};

    for (const [name] of this.metricDefinitions) {
      const counterMap = this.counters.get(name);
      if (counterMap) {
        for (const [labelsKey, value] of counterMap) {
          metrics[`${name}{${labelsKey}}`] = value;
        }
      }

      const gaugeMap = this.gauges.get(name);
      if (gaugeMap) {
        for (const [labelsKey, value] of gaugeMap) {
          metrics[`${name}{${labelsKey}}`] = value;
        }
      }
    }

    const snapshot: MetricsSnapshot = {
      snapshot_id: generateId('snapshot'),
      timestamp: new Date().toISOString(),
      metrics: {
        throughput: metrics['requests_total'] || 0,
        latency_p99: metrics['latency_p99'] || 0,
        error_rate: metrics['errors_total'] || 0,
        ...metrics
      },
      dimensions
    };

    this.snapshots.push(snapshot);

    if (this.snapshots.length > 1000) {
      this.snapshots = this.snapshots.slice(-1000);
    }

    logger.debug('Metrics snapshot taken', { snapshotId: snapshot.snapshot_id });
    return snapshot;
  }

  getSnapshots(limit: number = 100): MetricsSnapshot[] {
    return this.snapshots.slice(-limit);
  }

  getSnapshot(snapshotId: string): MetricsSnapshot | undefined {
    return this.snapshots.find(s => s.snapshot_id === snapshotId);
  }

  exportPrometheusFormat(): string {
    let output = '';

    for (const definition of this.metricDefinitions.values()) {
      output += `# HELP ${definition.name} ${definition.description}\n`;
      output += `# TYPE ${definition.name} ${definition.type}\n`;

      switch (definition.type) {
        case 'counter': {
          const counterMap = this.counters.get(definition.name);
          if (counterMap) {
            for (const [labelsKey, value] of counterMap) {
              const labelsStr = labelsKey ? `{${labelsKey}}` : '';
              output += `${definition.name}${labelsStr} ${value}\n`;
            }
          }
          break;
        }
        case 'gauge': {
          const gaugeMap = this.gauges.get(definition.name);
          if (gaugeMap) {
            for (const [labelsKey, value] of gaugeMap) {
              const labelsStr = labelsKey ? `{${labelsKey}}` : '';
              output += `${definition.name}${labelsStr} ${value}\n`;
            }
          }
          break;
        }
      }
    }

    return output;
  }

  reset(): void {
    this.counters.clear();
    this.gauges.clear();
    this.histograms.clear();
    this.summaries.clear();
    this.history = [];
    logger.info('All metrics reset');
  }

  async stop(): Promise<void> {
    if (this.snapshotInterval) {
      clearInterval(this.snapshotInterval);
      this.snapshotInterval = undefined;
    }
  }
}

export const defaultMonitoring = new MonitoringService();

defaultMonitoring.registerMetric({
  name: 'requests_total',
  type: 'counter',
  description: 'Total number of requests',
  labels: ['method', 'path', 'status']
});

defaultMonitoring.registerMetric({
  name: 'request_duration_seconds',
  type: 'histogram',
  description: 'Request duration in seconds',
  labels: ['method', 'path']
});

defaultMonitoring.registerMetric({
  name: 'errors_total',
  type: 'counter',
  description: 'Total number of errors',
  labels: ['type']
});

defaultMonitoring.registerMetric({
  name: 'active_tasks',
  type: 'gauge',
  description: 'Number of active tasks',
  labels: ['type']
});
