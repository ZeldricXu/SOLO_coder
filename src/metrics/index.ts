import { v4 as uuidv4 } from 'uuid';
import { MetricPoint, AggregationRule } from '../types';
import { ProcessingPipeline, DataTransformer } from '../core';
import { LRUCache } from 'lru-cache';

export interface AggregatedMetric {
  timestamp: number;
  metric: string;
  tags: Record<string, string>;
  value: number;
  function: AggregationRule['function'];
}

export interface TimeSeries {
  metric: string;
  tags: Record<string, string>;
  points: { timestamp: number; value: number }[];
}

export interface StorageAdapter {
  write(points: MetricPoint[]): Promise<void>;
  query(metric: string, tags: Record<string, string>, startTime: number, endTime: number): Promise<MetricPoint[]>;
  queryAggregated(
    metric: string,
    tags: Record<string, string>,
    startTime: number,
    endTime: number,
    aggregation: AggregationRule['function'],
    interval: number
  ): Promise<AggregatedMetric[]>;
}

export class InMemoryStorageAdapter implements StorageAdapter {
  private points: MetricPoint[] = [];
  private maxPoints: number = 100000;

  async write(points: MetricPoint[]): Promise<void> {
    this.points.push(...points);
    if (this.points.length > this.maxPoints) {
      this.points = this.points.slice(-this.maxPoints);
    }
  }

  async query(
    metric: string,
    tags: Record<string, string>,
    startTime: number,
    endTime: number
  ): Promise<MetricPoint[]> {
    return this.points.filter(p =>
      p.metric === metric &&
      p.timestamp >= startTime &&
      p.timestamp <= endTime &&
      this.matchTags(p.tags, tags)
    );
  }

  async queryAggregated(
    metric: string,
    tags: Record<string, string>,
    startTime: number,
    endTime: number,
    aggregation: AggregationRule['function'],
    interval: number
  ): Promise<AggregatedMetric[]> {
    const points = await this.query(metric, tags, startTime, endTime);
    const buckets: Map<number, number[]> = new Map();

    for (const point of points) {
      const bucketStart = Math.floor(point.timestamp / interval) * interval;
      if (!buckets.has(bucketStart)) {
        buckets.set(bucketStart, []);
      }
      buckets.get(bucketStart)!.push(point.value);
    }

    const results: AggregatedMetric[] = [];
    for (const [timestamp, values] of buckets.entries()) {
      results.push({
        timestamp,
        metric,
        tags,
        value: this.aggregate(values, aggregation),
        function: aggregation,
      });
    }

    return results.sort((a, b) => a.timestamp - b.timestamp);
  }

  private matchTags(pointTags: Record<string, string>, queryTags: Record<string, string>): boolean {
    for (const [key, value] of Object.entries(queryTags)) {
      if (pointTags[key] !== value) {
        return false;
      }
    }
    return true;
  }

  private aggregate(values: number[], fn: AggregationRule['function']): number {
    if (values.length === 0) return 0;

    switch (fn) {
      case 'sum':
        return values.reduce((a, b) => a + b, 0);
      case 'avg':
        return values.reduce((a, b) => a + b, 0) / values.length;
      case 'count':
        return values.length;
      case 'min':
        return Math.min(...values);
      case 'max':
        return Math.max(...values);
      case 'p50':
        return this.percentile(values, 50);
      case 'p95':
        return this.percentile(values, 95);
      case 'p99':
        return this.percentile(values, 99);
      default:
        return 0;
    }
  }

  private percentile(values: number[], p: number): number {
    const sorted = [...values].sort((a, b) => a - b);
    const index = Math.ceil((p / 100) * sorted.length) - 1;
    return sorted[Math.max(0, Math.min(index, sorted.length - 1))];
  }
}

export class MetricCollector {
  private points: MetricPoint[] = [];
  private maxBatchSize: number = 1000;
  private flushInterval: number = 10000;
  private flushTimer: NodeJS.Timeout | null = null;
  private storageAdapter: StorageAdapter;
  private onFlush: ((points: MetricPoint[]) => void) | null = null;

  constructor(storageAdapter: StorageAdapter) {
    this.storageAdapter = storageAdapter;
  }

  setOnFlush(callback: (points: MetricPoint[]) => void): void {
    this.onFlush = callback;
  }

  startAutoFlush(): void {
    if (this.flushTimer) return;
    this.flushTimer = setInterval(() => this.flush(), this.flushInterval);
  }

  stopAutoFlush(): void {
    if (this.flushTimer) {
      clearInterval(this.flushTimer);
      this.flushTimer = null;
    }
  }

  collect(point: MetricPoint): void {
    const normalized = this.normalizePoint(point);
    this.points.push(normalized);
    if (this.points.length >= this.maxBatchSize) {
      this.flush();
    }
  }

  collectMany(points: MetricPoint[]): void {
    for (const point of points) {
      this.collect(point);
    }
  }

  private normalizePoint(point: MetricPoint): MetricPoint {
    return {
      ...point,
      timestamp: point.timestamp || Date.now(),
      tags: point.tags || {},
    };
  }

  async flush(): Promise<void> {
    if (this.points.length === 0) return;

    const batch = [...this.points];
    this.points = [];

    try {
      await this.storageAdapter.write(batch);
      if (this.onFlush) {
        this.onFlush(batch);
      }
    } catch (error) {
      this.points.unshift(...batch);
      throw error;
    }
  }

  getPendingCount(): number {
    return this.points.length;
  }
}

export class AggregationEngine {
  private rules: Map<string, AggregationRule> = new Map();
  private results: LRUCache<string, AggregatedMetric[]>;
  private storageAdapter: StorageAdapter;

  constructor(storageAdapter: StorageAdapter) {
    this.storageAdapter = storageAdapter;
    this.results = new LRUCache({ max: 1000 });
  }

  addRule(rule: AggregationRule): void {
    this.rules.set(rule.id, rule);
  }

  removeRule(id: string): boolean {
    return this.rules.delete(id);
  }

  getRules(): AggregationRule[] {
    return Array.from(this.rules.values());
  }

  async aggregate(
    metric: string,
    tags: Record<string, string>,
    startTime: number,
    endTime: number
  ): Promise<AggregatedMetric[]> {
    const rule = this.findMatchingRule(metric, tags);
    if (!rule) {
      return [];
    }

    const cacheKey = `${metric}:${JSON.stringify(tags)}:${startTime}:${endTime}:${rule.function}:${rule.interval}`;
    const cached = this.results.get(cacheKey);
    if (cached) {
      return cached;
    }

    const results = await this.storageAdapter.queryAggregated(
      metric,
      tags,
      startTime,
      endTime,
      rule.function,
      rule.interval
    );

    this.results.set(cacheKey, results);
    return results;
  }

  private findMatchingRule(metric: string, tags: Record<string, string>): AggregationRule | null {
    for (const rule of this.rules.values()) {
      if (rule.metric !== metric) continue;
      const hasAllGroupBy = rule.groupBy.every(key => tags[key] !== undefined);
      if (hasAllGroupBy) {
        return rule;
      }
    }
    return null;
  }

  async computeRate(
    metric: string,
    tags: Record<string, string>,
    startTime: number,
    endTime: number
  ): Promise<number> {
    const points = await this.storageAdapter.query(metric, tags, startTime, endTime);
    if (points.length < 2) return 0;

    const first = points[0];
    const last = points[points.length - 1];
    const deltaTime = (last.timestamp - first.timestamp) / 1000;

    if (deltaTime <= 0) return 0;
    return (last.value - first.value) / deltaTime;
  }
}

export class MetricsQueryService {
  private storageAdapter: StorageAdapter;
  private aggregationEngine: AggregationEngine;

  constructor(storageAdapter: StorageAdapter, aggregationEngine: AggregationEngine) {
    this.storageAdapter = storageAdapter;
    this.aggregationEngine = aggregationEngine;
  }

  async queryRaw(
    metric: string,
    tags: Record<string, string>,
    startTime: number,
    endTime: number
  ): Promise<TimeSeries> {
    const points = await this.storageAdapter.query(metric, tags, startTime, endTime);
    return {
      metric,
      tags,
      points: points.map(p => ({ timestamp: p.timestamp, value: p.value })),
    };
  }

  async queryAggregated(
    metric: string,
    tags: Record<string, string>,
    startTime: number,
    endTime: number
  ): Promise<TimeSeries> {
    const aggregated = await this.aggregationEngine.aggregate(metric, tags, startTime, endTime);
    return {
      metric,
      tags,
      points: aggregated.map(a => ({ timestamp: a.timestamp, value: a.value })),
    };
  }

  async queryRate(
    metric: string,
    tags: Record<string, string>,
    startTime: number,
    endTime: number
  ): Promise<number> {
    return this.aggregationEngine.computeRate(metric, tags, startTime, endTime);
  }

  async getMetricNames(): Promise<string[]> {
    const allPoints = await this.storageAdapter.query('', {}, 0, Date.now());
    return [...new Set(allPoints.map(p => p.metric))];
  }

  async getTagValues(metric: string, tagName: string): Promise<string[]> {
    const allPoints = await this.storageAdapter.query(metric, {}, 0, Date.now());
    return [...new Set(allPoints.map(p => p.tags[tagName]).filter(v => v !== undefined))];
  }
}

export class MetricsPipeline {
  private collector: MetricCollector;
  private aggregationEngine: AggregationEngine;
  private queryService: MetricsQueryService;
  private pipeline: ProcessingPipeline<MetricPoint, MetricPoint>;

  constructor(
    collector: MetricCollector,
    aggregationEngine: AggregationEngine,
    queryService: MetricsQueryService
  ) {
    this.collector = collector;
    this.aggregationEngine = aggregationEngine;
    this.queryService = queryService;
    this.pipeline = this.buildPipeline();
  }

  private buildPipeline(): ProcessingPipeline<MetricPoint, MetricPoint> {
    return new ProcessingPipeline<MetricPoint, MetricPoint>()
      .addStage({
        name: 'validation',
        process: async (point) => this.validatePoint(point),
      })
      .addStage({
        name: 'normalization',
        process: async (point) => this.normalizePoint(point),
      })
      .addStage({
        name: 'collection',
        process: async (point) => {
          this.collector.collect(point);
          return point;
        },
      });
  }

  private validatePoint(point: MetricPoint): MetricPoint {
    if (!point.metric) {
      throw new Error('Metric name is required');
    }
    if (typeof point.value !== 'number') {
      throw new Error('Metric value must be a number');
    }
    return point;
  }

  private normalizePoint(point: MetricPoint): MetricPoint {
    const schema: Record<string, string> = {
      metric: 'string',
      value: 'number',
      timestamp: 'integer',
    };
    const normalized = DataTransformer.normalize(point as unknown as Record<string, unknown>, schema);
    return normalized as unknown as MetricPoint;
  }

  async ingest(point: MetricPoint): Promise<MetricPoint> {
    const result = await this.pipeline.execute(point);
    if (!result.success || !result.data) {
      throw new Error(result.error || 'Failed to ingest metric');
    }
    return result.data;
  }

  async ingestMany(points: MetricPoint[]): Promise<void> {
    await Promise.all(points.map(p => this.ingest(p)));
  }
}

export function createMetricsModule(): {
  storageAdapter: StorageAdapter;
  collector: MetricCollector;
  aggregationEngine: AggregationEngine;
  queryService: MetricsQueryService;
  pipeline: MetricsPipeline;
} {
  const storageAdapter = new InMemoryStorageAdapter();
  const collector = new MetricCollector(storageAdapter);
  const aggregationEngine = new AggregationEngine(storageAdapter);
  const queryService = new MetricsQueryService(storageAdapter, aggregationEngine);
  const pipeline = new MetricsPipeline(collector, aggregationEngine, queryService);

  return {
    storageAdapter,
    collector,
    aggregationEngine,
    queryService,
    pipeline,
  };
}
