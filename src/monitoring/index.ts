import EventEmitter from 'eventemitter3';
import { v4 as uuidv4 } from 'uuid';
import { Metric, MetricsAggregate, Snapshot, Alert, MonitoringConfig } from './types';

export class MonitoringService extends EventEmitter {
  private metrics: Metric[] = [];
  private aggregates: Map<string, MetricsAggregate> = new Map();
  private alerts: Map<string, Alert> = new Map();
  private snapshotHistory: Snapshot[] = [];
  private collectionTimer?: NodeJS.Timeout;
  private metricBuffer: Map<string, number[]> = new Map();

  constructor(private config: MonitoringConfig) {
    super();
    this.startCollection();
  }

  increment(metricName: string, value: number = 1, labels: Record<string, string> = {}): void {
    this.recordMetric(metricName, 'counter', value, labels);
  }

  gauge(metricName: string, value: number, labels: Record<string, string> = {}): void {
    this.recordMetric(metricName, 'gauge', value, labels);
  }

  histogram(metricName: string, value: number, labels: Record<string, string> = {}): void {
    this.recordMetric(metricName, 'histogram', value, labels);
    const key = this.getMetricKey(metricName, labels);
    const buffer = this.metricBuffer.get(key) || [];
    buffer.push(value);
    if (buffer.length > 1000) buffer.shift();
    this.metricBuffer.set(key, buffer);
  }

  timing(metricName: string, fn: () => Promise<any>, labels: Record<string, string> = {}): Promise<any> {
    const start = Date.now();
    const result = fn();
    Promise.resolve(result).then(() => {
      const duration = Date.now() - start;
      this.histogram(metricName, duration, labels);
    });
    return result;
  }

  private recordMetric(name: string, type: Metric['type'], value: number, labels: Record<string, string>): void {
    const metric: Metric = {
      name,
      type,
      value,
      labels,
      timestamp: Date.now(),
    };

    this.metrics.push(metric);
    this.emit('metric', metric);

    if (this.metrics.length > this.config.maxMetrics) {
      this.metrics = this.metrics.slice(-this.config.maxMetrics);
    }

    this.checkAlerts(metric);
  }

  createAlert(alert: Omit<Alert, 'id' | 'triggered'>): Alert {
    const id = uuidv4();
    const newAlert: Alert = {
      ...alert,
      id,
      triggered: false,
    };
    this.alerts.set(id, newAlert);
    return newAlert;
  }

  deleteAlert(alertId: string): boolean {
    return this.alerts.delete(alertId);
  }

  getAlerts(): Alert[] {
    return Array.from(this.alerts.values());
  }

  private checkAlerts(metric: Metric): void {
    for (const alert of this.alerts.values()) {
      if (alert.metric !== metric.name) continue;

      const match = Object.entries(alert as any).every(([key, value]) => {
        if (key === 'id' || key === 'name' || key === 'metric' || key === 'condition' ||
            key === 'threshold' || key === 'duration' || key === 'triggered' ||
            key === 'triggeredAt' || key === 'resolvedAt') return true;
        return metric.labels[key] === value;
      });

      if (!match) continue;

      const thresholdMet = this.checkThreshold(metric.value, alert.condition, alert.threshold);

      if (thresholdMet && !alert.triggered) {
        alert.triggered = true;
        alert.triggeredAt = Date.now();
        this.emit('alert-triggered', alert, metric);
      } else if (!thresholdMet && alert.triggered) {
        alert.triggered = false;
        alert.resolvedAt = Date.now();
        this.emit('alert-resolved', alert, metric);
      }
    }
  }

  private checkThreshold(value: number, condition: Alert['condition'], threshold: number): boolean {
    switch (condition) {
      case 'gt': return value > threshold;
      case 'lt': return value < threshold;
      case 'gte': return value >= threshold;
      case 'lte': return value <= threshold;
      case 'eq': return value === threshold;
      default: return false;
    }
  }

  aggregate(metricName: string, labels: Record<string, string> = {}, windowMs?: number): MetricsAggregate | null {
    const window = windowMs || this.config.aggregationWindows[0] || 60000;
    const now = Date.now();
    const windowStart = now - window;

    const relevantMetrics = this.metrics.filter(
      m => m.name === metricName &&
           m.timestamp >= windowStart &&
           Object.entries(labels).every(([k, v]) => m.labels[k] === v)
    );

    if (relevantMetrics.length === 0) return null;

    const values = relevantMetrics.map(m => m.value).sort((a, b) => a - b);
    const sum = values.reduce((a, b) => a + b, 0);

    const aggregate: MetricsAggregate = {
      name: metricName,
      count: values.length,
      sum,
      min: values[0],
      max: values[values.length - 1],
      avg: sum / values.length,
      p50: this.percentile(values, 50),
      p95: this.percentile(values, 95),
      p99: this.percentile(values, 99),
      labels,
      windowStart,
      windowEnd: now,
    };

    const key = this.getMetricKey(metricName, labels);
    this.aggregates.set(key, aggregate);

    return aggregate;
  }

  private percentile(sortedValues: number[], p: number): number {
    if (sortedValues.length === 0) return 0;
    const index = Math.ceil((p / 100) * sortedValues.length) - 1;
    return sortedValues[Math.max(0, Math.min(index, sortedValues.length - 1))];
  }

  createSnapshot(dimensions: Record<string, string> = {}): Snapshot {
    const snapshot: Snapshot = {
      snapshot_id: uuidv4(),
      timestamp: new Date().toISOString(),
      metrics: {},
      dimensions,
    };

    for (const [key, aggregate] of this.aggregates.entries()) {
      snapshot.metrics[`${key}_avg`] = aggregate.avg;
      snapshot.metrics[`${key}_p99`] = aggregate.p99;
      snapshot.metrics[`${key}_count`] = aggregate.count;
    }

    this.snapshotHistory.push(snapshot);
    if (this.snapshotHistory.length > 100) {
      this.snapshotHistory = this.snapshotHistory.slice(-100);
    }

    this.emit('snapshot', snapshot);
    return snapshot;
  }

  getSnapshots(limit: number = 10): Snapshot[] {
    return this.snapshotHistory.slice(-limit);
  }

  getMetric(name: string, labels: Record<string, string> = {}): Metric[] {
    return this.metrics.filter(
      m => m.name === name &&
           Object.entries(labels).every(([k, v]) => m.labels[k] === v)
    );
  }

  getMetricNames(): string[] {
    return Array.from(new Set(this.metrics.map(m => m.name)));
  }

  private startCollection(): void {
    this.collectionTimer = setInterval(() => {
      this.cleanupOldMetrics();
      this.emit('collection-tick');
    }, this.config.collectionInterval);
  }

  private cleanupOldMetrics(): void {
    const cutoff = Date.now() - this.config.retentionPeriod;
    this.metrics = this.metrics.filter(m => m.timestamp >= cutoff);
  }

  private getMetricKey(name: string, labels: Record<string, string>): string {
    const sortedLabels = Object.entries(labels).sort((a, b) => a[0].localeCompare(b[0]));
    return `${name}:${sortedLabels.map(([k, v]) => `${k}=${v}`).join(',')}`;
  }

  exportMetrics(format: 'json' | 'prometheus' = 'json'): string {
    if (format === 'prometheus') {
      return this.exportPrometheus();
    }
    return JSON.stringify(this.metrics, null, 2);
  }

  private exportPrometheus(): string {
    const lines: string[] = [];
    const metricGroups = new Map<string, Metric[]>();

    for (const metric of this.metrics) {
      const group = metricGroups.get(metric.name) || [];
      group.push(metric);
      metricGroups.set(metric.name, group);
    }

    for (const [name, metrics] of metricGroups) {
      lines.push(`# TYPE ${name} ${metrics[0].type}`);
      for (const metric of metrics) {
        const labelStr = Object.entries(metric.labels)
          .map(([k, v]) => `${k}="${v}"`)
          .join(',');
        const labels = labelStr ? `{${labelStr}}` : '';
        lines.push(`${name}${labels} ${metric.value} ${metric.timestamp}`);
      }
    }

    return lines.join('\n');
  }

  destroy(): void {
    if (this.collectionTimer) {
      clearInterval(this.collectionTimer);
    }
    this.removeAllListeners();
  }
}

export * from './types';
