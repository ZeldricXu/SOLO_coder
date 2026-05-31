export interface MetricLabel {
  name: string;
  value: string;
}

export interface CounterMetric {
  name: string;
  help: string;
  value: number;
  labels: MetricLabel[];
}

export interface GaugeMetric {
  name: string;
  help: string;
  value: number;
  labels: MetricLabel[];
}

export interface HistogramMetric {
  name: string;
  help: string;
  buckets: number[];
  values: Map<number, number>;
  sum: number;
  count: number;
  labels: MetricLabel[];
}

export interface SummaryMetric {
  name: string;
  help: string;
  quantiles: number[];
  values: Map<number, number>;
  sum: number;
  count: number;
  labels: MetricLabel[];
}

export type MetricType = 'counter' | 'gauge' | 'histogram' | 'summary';

export interface Metric {
  type: MetricType;
  name: string;
  help: string;
  labels: MetricLabel[];
  value?: number;
  buckets?: number[];
  bucketValues?: Map<number, number>;
  sum?: number;
  count?: number;
}

export class PrometheusRegistry {
  private counters: Map<string, CounterMetric> = new Map();
  private gauges: Map<string, GaugeMetric> = new Map();
  private histograms: Map<string, HistogramMetric> = new Map();
  private summaries: Map<string, SummaryMetric> = new Map();

  registerCounter(name: string, help: string, labels: MetricLabel[] = []): void {
    if (this.counters.has(name)) return;
    this.counters.set(name, { name, help, value: 0, labels });
  }

  registerGauge(name: string, help: string, labels: MetricLabel[] = []): void {
    if (this.gauges.has(name)) return;
    this.gauges.set(name, { name, help, value: 0, labels });
  }

  registerHistogram(name: string, help: string, buckets: number[] = [0.005, 0.01, 0.025, 0.05, 0.1, 0.25, 0.5, 1, 2.5, 5, 10], labels: MetricLabel[] = []): void {
    if (this.histograms.has(name)) return;
    const bucketValues = new Map<number, number>();
    buckets.forEach(b => bucketValues.set(b, 0));
    bucketValues.set(Infinity, 0);
    this.histograms.set(name, { name, help, buckets, values: bucketValues, sum: 0, count: 0, labels });
  }

  registerSummary(name: string, help: string, quantiles: number[] = [0.5, 0.9, 0.95, 0.99], labels: MetricLabel[] = []): void {
    if (this.summaries.has(name)) return;
    const values = new Map<number, number>();
    quantiles.forEach(q => values.set(q, 0));
    this.summaries.set(name, { name, help, quantiles, values, sum: 0, count: 0, labels });
  }

  incrementCounter(name: string, amount: number = 1): void {
    const counter = this.counters.get(name);
    if (counter) counter.value += amount;
  }

  setGauge(name: string, value: number): void {
    const gauge = this.gauges.get(name);
    if (gauge) gauge.value = value;
  }

  observeHistogram(name: string, value: number): void {
    const histogram = this.histograms.get(name);
    if (!histogram) return;
    
    histogram.sum += value;
    histogram.count++;
    
    for (const bucket of histogram.buckets) {
      if (value <= bucket) {
        const current = histogram.values.get(bucket) || 0;
        histogram.values.set(bucket, current + 1);
      }
    }
    const infCount = histogram.values.get(Infinity) || 0;
    histogram.values.set(Infinity, infCount + 1);
  }

  observeSummary(name: string, value: number): void {
    const summary = this.summaries.get(name);
    if (!summary) return;
    summary.sum += value;
    summary.count++;
  }

  resetCounter(name: string): void {
    const counter = this.counters.get(name);
    if (counter) counter.value = 0;
  }

  resetHistogram(name: string): void {
    const histogram = this.histograms.get(name);
    if (histogram) {
      histogram.sum = 0;
      histogram.count = 0;
      histogram.values.forEach((_, key) => histogram.values.set(key, 0));
    }
  }

  resetSummary(name: string): void {
    const summary = this.summaries.get(name);
    if (summary) {
      summary.sum = 0;
      summary.count = 0;
    }
  }

  resetAll(): void {
    this.counters.forEach(c => c.value = 0);
    this.gauges.forEach(g => g.value = 0);
    this.histograms.forEach(h => {
      h.sum = 0;
      h.count = 0;
      h.values.forEach((_, key) => h.values.set(key, 0));
    });
    this.summaries.forEach(s => {
      s.sum = 0;
      s.count = 0;
    });
  }

  getMetric(name: string): Metric | undefined {
    if (this.counters.has(name)) {
      const c = this.counters.get(name)!;
      return { type: 'counter', name: c.name, help: c.help, labels: c.labels, value: c.value };
    }
    if (this.gauges.has(name)) {
      const g = this.gauges.get(name)!;
      return { type: 'gauge', name: g.name, help: g.help, labels: g.labels, value: g.value };
    }
    if (this.histograms.has(name)) {
      const h = this.histograms.get(name)!;
      return { type: 'histogram', name: h.name, help: h.help, labels: h.labels, buckets: h.buckets, bucketValues: h.values, sum: h.sum, count: h.count };
    }
    if (this.summaries.has(name)) {
      const s = this.summaries.get(name)!;
      return { type: 'summary', name: s.name, help: s.help, labels: s.labels, buckets: s.quantiles, bucketValues: s.values, sum: s.sum, count: s.count };
    }
    return undefined;
  }

  getAllMetrics(): Metric[] {
    const metrics: Metric[] = [];
    this.counters.forEach((c) => metrics.push({ type: 'counter', name: c.name, help: c.help, labels: c.labels, value: c.value }));
    this.gauges.forEach((g) => metrics.push({ type: 'gauge', name: g.name, help: g.help, labels: g.labels, value: g.value }));
    this.histograms.forEach((h) => metrics.push({ type: 'histogram', name: h.name, help: h.help, labels: h.labels, buckets: h.buckets, bucketValues: h.values, sum: h.sum, count: h.count }));
    this.summaries.forEach((s) => metrics.push({ type: 'summary', name: s.name, help: s.help, labels: s.labels, buckets: s.quantiles, bucketValues: s.values, sum: s.sum, count: s.count }));
    return metrics;
  }

  toPrometheusFormat(): string {
    const lines: string[] = [];

    this.counters.forEach((counter, name) => {
      lines.push(`# HELP ${name} ${counter.help}`);
      lines.push(`# TYPE ${name} counter`);
      lines.push(`${name} ${counter.value}`);
    });

    this.gauges.forEach((gauge, name) => {
      lines.push(`# HELP ${name} ${gauge.help}`);
      lines.push(`# TYPE ${name} gauge`);
      lines.push(`${name} ${gauge.value}`);
    });

    this.histograms.forEach((histogram, name) => {
      lines.push(`# HELP ${name} ${histogram.help}`);
      lines.push(`# TYPE ${name} histogram`);
      histogram.buckets.forEach(bucket => {
        lines.push(`${name}_bucket{le="${bucket}"} ${histogram.values.get(bucket) || 0}`);
      });
      lines.push(`${name}_bucket{le="+Inf"} ${histogram.values.get(Infinity) || 0}`);
      lines.push(`${name}_sum ${histogram.sum}`);
      lines.push(`${name}_count ${histogram.count}`);
    });

    this.summaries.forEach((summary, name) => {
      lines.push(`# HELP ${name} ${summary.help}`);
      lines.push(`# TYPE ${name} summary`);
      summary.quantiles.forEach(quantile => {
        lines.push(`${name}{quantile="${quantile}"} ${summary.values.get(quantile) || 0}`);
      });
      lines.push(`${name}_sum ${summary.sum}`);
      lines.push(`${name}_count ${summary.count}`);
    });

    return lines.join('\n') + '\n';
  }

  toJSON(): Record<string, any> {
    const result: Record<string, any> = {};
    this.counters.forEach((c, name) => {
      result[name] = { type: 'counter', value: c.value, labels: c.labels };
    });
    this.gauges.forEach((g, name) => {
      result[name] = { type: 'gauge', value: g.value, labels: g.labels };
    });
    this.histograms.forEach((h, name) => {
      result[name] = {
        type: 'histogram',
        count: h.count,
        sum: h.sum,
        buckets: Object.fromEntries(h.values),
        labels: h.labels
      };
    });
    return result;
  }
}

export const createPrometheusRegistry = (): PrometheusRegistry => new PrometheusRegistry();
