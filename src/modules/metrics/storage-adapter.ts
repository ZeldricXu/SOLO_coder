import { IMetricsAggregator } from '@ports/index';
import { MetricPoint } from '@apptypes/index';
import { rootLogger } from '@modules/logging';
import { calculateP95, calculateP99, calculateAvg } from '@utils/index';
import { config } from '@config/index';

export interface IMetricsStorage {
  ingest(point: MetricPoint): Promise<void>;
  ingestBatch(points: MetricPoint[]): Promise<void>;
  query(
    metricName: string,
    tags: Record<string, string>,
    startTime: number,
    endTime: number
  ): Promise<MetricPoint[]>;
}

export class MemoryMetricsStorage implements IMetricsStorage {
  private logger = rootLogger.child({ module: 'MemoryMetricsStorage' });
  private data: Map<string, MetricPoint[]> = new Map();
  private maxDataPoints: number;

  constructor(maxDataPoints: number = 10000) {
    this.maxDataPoints = maxDataPoints;
  }

  private getKey(metricName: string, tags: Record<string, string>): string {
    const sortedTags = Object.entries(tags)
      .sort(([a], [b]) => a.localeCompare(b))
      .map(([k, v]) => `${k}=${v}`)
      .join(',');
    return `${metricName}?${sortedTags}`;
  }

  async ingest(point: MetricPoint): Promise<void> {
    const key = this.getKey(point.metric_name, point.tags);
    if (!this.data.has(key)) {
      this.data.set(key, []);
    }
    const points = this.data.get(key)!;
    points.push(point);

    if (points.length > this.maxDataPoints) {
      points.splice(0, points.length - this.maxDataPoints);
    }
  }

  async ingestBatch(points: MetricPoint[]): Promise<void> {
    for (const point of points) {
      await this.ingest(point);
    }
  }

  async query(
    metricName: string,
    tags: Record<string, string>,
    startTime: number,
    endTime: number
  ): Promise<MetricPoint[]> {
    const results: MetricPoint[] = [];

    for (const [key, points] of this.data) {
      const [name, tagsStr] = key.split('?');
      if (name !== metricName) continue;

      const pointTags: Record<string, string> = {};
      if (tagsStr) {
        tagsStr.split(',').forEach((kv) => {
          const [k, v] = kv.split('=');
          if (k && v) pointTags[k] = v;
        });
      }

      const tagsMatch = Object.entries(tags).every(
        ([k, v]) => pointTags[k] === v
      );

      if (tagsMatch) {
        const filtered = points.filter(
          (p) => p.timestamp >= startTime && p.timestamp <= endTime
        );
        results.push(...filtered);
      }
    }

    return results.sort((a, b) => a.timestamp - b.timestamp);
  }

  getKeys(): string[] {
    return Array.from(this.data.keys());
  }

  clear(): void {
    this.data.clear();
    this.logger.info('Memory metrics storage cleared');
  }
}

export class MetricsAggregator implements IMetricsAggregator {
  private logger = rootLogger.child({ module: 'MetricsAggregator' });
  private storage: IMetricsStorage;
  private preAggregationWindows: Map<string, { values: number[]; windowStart: number }> = new Map();
  private aggregationWindow: number;
  private aggregationTimer: NodeJS.Timeout | null = null;

  constructor(storage?: IMetricsStorage) {
    this.storage = storage || new MemoryMetricsStorage(config.metrics.maxDataPoints);
    this.aggregationWindow = config.metrics.aggregationWindow;
    this.startAggregationLoop();
  }

  private startAggregationLoop(): void {
    this.aggregationTimer = setInterval(() => {
      this.processPreAggregation();
    }, this.aggregationWindow);
  }

  private processPreAggregation(): void {
    const now = Date.now();

    for (const [key, window] of this.preAggregationWindows) {
      if (now - window.windowStart >= this.aggregationWindow && window.values.length > 0) {
        const values = window.values;
        const aggregatedPoint: MetricPoint = {
          timestamp: window.windowStart + this.aggregationWindow,
          metric_name: `${key.split('|')[0]}.aggregated`,
          value: calculateAvg(values),
          tags: {
            aggregation_window: `${this.aggregationWindow}ms`,
            ...this.parseTagsFromKey(key),
          },
        };

        this.storage.ingest(aggregatedPoint).catch((err) => {
          this.logger.error('Failed to ingest aggregated metric', {
            error: err.message,
            metric: aggregatedPoint.metric_name,
          });
        });

        window.values = [];
        window.windowStart = now;
      }
    }
  }

  private parseTagsFromKey(key: string): Record<string, string> {
    const parts = key.split('|');
    const tags: Record<string, string> = {};
    if (parts.length > 1) {
      parts.slice(1).forEach((kv) => {
        const [k, v] = kv.split('=');
        if (k && v) tags[k] = v;
      });
    }
    return tags;
  }

  private getAggregationKey(point: MetricPoint): string {
    const sortedTags = Object.entries(point.tags)
      .sort(([a], [b]) => a.localeCompare(b))
      .map(([k, v]) => `${k}=${v}`)
      .join('|');
    return `${point.metric_name}|${sortedTags}`;
  }

  async ingest(point: MetricPoint): Promise<void> {
    const key = this.getAggregationKey(point);

    if (!this.preAggregationWindows.has(key)) {
      this.preAggregationWindows.set(key, {
        values: [],
        windowStart: Date.now(),
      });
    }

    const window = this.preAggregationWindows.get(key)!;
    window.values.push(point.value);

    await this.storage.ingest(point);
  }

  async ingestBatch(points: MetricPoint[]): Promise<void> {
    for (const point of points) {
      await this.ingest(point);
    }
    this.logger.info('Batch metrics ingested', { count: points.length });
  }

  async query(
    metricName: string,
    tags: Record<string, string>,
    startTime: number,
    endTime: number
  ): Promise<MetricPoint[]> {
    return this.storage.query(metricName, tags, startTime, endTime);
  }

  async getAggregated(
    metricName: string,
    tags: Record<string, string>,
    window: string
  ): Promise<{
    avg: number;
    sum: number;
    min: number;
    max: number;
    count: number;
    p95: number;
    p99: number;
  }> {
    const now = Date.now();
    let startTime: number;

    switch (window) {
      case '1m':
        startTime = now - 60 * 1000;
        break;
      case '5m':
        startTime = now - 5 * 60 * 1000;
        break;
      case '1h':
        startTime = now - 60 * 60 * 1000;
        break;
      case '1d':
        startTime = now - 24 * 60 * 60 * 1000;
        break;
      default:
        startTime = now - 60 * 1000;
    }

    const points = await this.storage.query(metricName, tags, startTime, now);
    const values = points.map((p) => p.value);

    if (values.length === 0) {
      return {
        avg: 0,
        sum: 0,
        min: 0,
        max: 0,
        count: 0,
        p95: 0,
        p99: 0,
      };
    }

    return {
      avg: calculateAvg(values),
      sum: values.reduce((a, b) => a + b, 0),
      min: Math.min(...values),
      max: Math.max(...values),
      count: values.length,
      p95: calculateP95(values),
      p99: calculateP99(values),
    };
  }

  stop(): void {
    if (this.aggregationTimer) {
      clearInterval(this.aggregationTimer);
      this.aggregationTimer = null;
    }
    this.logger.info('Metrics aggregator stopped');
  }
}

export const createMetricsAggregator = (
  storage?: IMetricsStorage
): IMetricsAggregator => {
  return new MetricsAggregator(storage);
};
