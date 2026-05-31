import { IMetricsPort } from '../../../application/shared/ports/IMetricsPort';
import { injectable } from 'tsyringe';

interface Counter {
  value: number;
  labels: Record<string, string>;
}

interface Gauge {
  value: number;
  labels: Record<string, string>;
}

interface Histogram {
  values: number[];
  labels: Record<string, string>;
  sum: number;
  count: number;
}

@injectable()
export class PrometheusMetricsAdapter implements IMetricsPort {
  private counters: Map<string, Counter> = new Map();
  private gauges: Map<string, Gauge> = new Map();
  private histograms: Map<string, Histogram> = new Map();
  private requestLatencies: number[] = [];
  private startTime: Date = new Date();
  private requestCount = 0;
  private errorCount = 0;

  private getKey(name: string, labels: Record<string, string> = {}): string {
    const labelStr = Object.entries(labels)
      .sort(([a], [b]) => a.localeCompare(b))
      .map(([k, v]) => `${k}=${v}`)
      .join(',');
    return `${name}[${labelStr}]`;
  }

  increment(name: string, value: number = 1, labels: Record<string, string> = {}): void {
    const key = this.getKey(name, labels);
    const counter = this.counters.get(key) || { value: 0, labels };
    counter.value += value;
    this.counters.set(key, counter);
    this.requestCount += value;
  }

  decrement(name: string, value: number = 1, labels: Record<string, string> = {}): void {
    const key = this.getKey(name, labels);
    const counter = this.counters.get(key) || { value: 0, labels };
    counter.value = Math.max(0, counter.value - value);
    this.counters.set(key, counter);
  }

  gauge(name: string, value: number, labels: Record<string, string> = {}): void {
    const key = this.getKey(name, labels);
    this.gauges.set(key, { value, labels });
  }

  histogram(name: string, value: number, labels: Record<string, string> = {}): void {
    const key = this.getKey(name, labels);
    const histogram = this.histograms.get(key) || { values: [], labels, sum: 0, count: 0 };
    histogram.values.push(value);
    histogram.sum += value;
    histogram.count++;
    this.histograms.set(key, histogram);
  }

  timing(name: string, valueMs: number, labels: Record<string, string> = {}): void {
    this.histogram(name, valueMs, labels);
    this.requestLatencies.push(valueMs);
  }

  startTimer(name: string, labels: Record<string, string> = {}): () => void {
    const startTime = Date.now();
    return () => {
      const duration = Date.now() - startTime;
      this.timing(name, duration, labels);
    };
  }

  getSnapshot(): {
    counters: Record<string, number>;
    gauges: Record<string, number>;
    requestCount: number;
    errorCount: number;
    uptime: number;
  } {
    return {
      counters: Object.fromEntries(
        Array.from(this.counters.entries()).map(([k, v]) => [k, v.value])
      ),
      gauges: Object.fromEntries(
        Array.from(this.gauges.entries()).map(([k, v]) => [k, v.value])
      ),
      requestCount: this.requestCount,
      errorCount: this.errorCount,
      uptime: Date.now() - this.startTime.getTime()
    };
  }

  getSummary(): {
    requestCount: number;
    errorCount: number;
    avgResponseTime: number;
    throughput: number;
    errorRate: number;
  } {
    const uptimeMs = Date.now() - this.startTime.getTime();
    const uptimeSeconds = uptimeMs / 1000;

    return {
      requestCount: this.requestCount,
      errorCount: this.errorCount,
      avgResponseTime:
        this.requestLatencies.length > 0
          ? this.requestLatencies.reduce((a, b) => a + b, 0) / this.requestLatencies.length
          : 0,
      throughput: uptimeSeconds > 0 ? this.requestCount / uptimeSeconds : 0,
      errorRate: this.requestCount > 0 ? this.errorCount / this.requestCount : 0
    };
  }
}
