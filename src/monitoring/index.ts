import { v4 as uuidv4 } from 'uuid';
import logger from '../common/logger';
import { MetricsSnapshot } from '../types';
import { sleep } from '../common/utils';

export interface MetricPoint {
  name: string;
  value: number;
  timestamp: number;
  tags: Record<string, string>;
}

export interface AggregatedMetric {
  name: string;
  count: number;
  sum: number;
  avg: number;
  min: number;
  max: number;
  p50: number;
  p95: number;
  p99: number;
  tags: Record<string, string>;
  windowStart: number;
  windowEnd: number;
}

export interface BatchMetricRecord {
  name: string;
  value: number;
  tags?: Record<string, string>;
  timestamp?: number;
}

export interface MetricMergeRule {
  sourcePattern: RegExp;
  targetName: string;
  mergeType: 'sum' | 'avg' | 'max' | 'min' | 'count';
  tagAggregation?: 'keep' | 'combine' | 'drop';
}

export interface DownsamplingRule {
  metricPattern: RegExp;
  originalResolutionMs: number;
  targetResolutionMs: number;
  aggregationFunction: 'avg' | 'sum' | 'max' | 'min' | 'first' | 'last';
  retentionMs: number;
}

export interface TimeWindow {
  start: number;
  end: number;
  durationMs: number;
}

export interface BatchOperationRequest {
  operation: 'increment' | 'gauge' | 'histogram' | 'timer' | 'custom';
  name: string;
  value?: number;
  tags?: Record<string, string>;
  durationMs?: number;
  status?: 'success' | 'error';
  timestamp?: number;
}

export interface BatchOperationResult {
  operationId: string;
  successCount: number;
  failedCount: number;
  mergedCount: number;
  totalTimeMs: number;
}

export interface BatchProcessorStats {
  totalOperations: number;
  mergedOperations: number;
  batchCount: number;
  avgBatchSize: number;
  avgMergeRatio: number;
  pendingOperations: number;
}

export interface MonitoringConfig {
  retentionPeriodMs: number;
  aggregationIntervalMs: number;
  maxPointsPerMetric: number;
  enableBatching: boolean;
  batchSize: number;
  batchTimeoutMs: number;
  enableMerging: boolean;
  enableDownsampling: boolean;
  alignToWallClock: boolean;
  preAggregationCacheSize: number;
  enableBatchOperations: boolean;
  maxBatchOperations: number;
  autoMergeSimilarMetrics: boolean;
  mergeThresholdMs: number;
  asyncBatchProcessing: boolean;
  asyncBatchConcurrency: number;
}

class PriorityBatcher<T> {
  private queues: Map<string, T[]> = new Map();
  private maxSize: number;
  private maxWaitMs: number;
  private timer?: NodeJS.Timeout;
  private onFlush: (batch: T[]) => void;

  constructor(maxSize: number, maxWaitMs: number, onFlush: (batch: T[]) => void) {
    this.maxSize = maxSize;
    this.maxWaitMs = maxWaitMs;
    this.onFlush = onFlush;
  }

  add(item: T, priority: number = 1): void {
    const queueKey = `priority-${priority}`;
    if (!this.queues.has(queueKey)) {
      this.queues.set(queueKey, []);
    }
    const queue = this.queues.get(queueKey)!;
    queue.push(item);

    if (queue.length >= this.maxSize) {
      this.flushPriority(queueKey);
    }

    if (!this.timer) {
      this.startTimer();
    }
  }

  private startTimer(): void {
    this.timer = setTimeout(() => {
      this.flushAll();
      this.timer = undefined;
    }, this.maxWaitMs);
  }

  private flushPriority(queueKey: string): void {
    const queue = this.queues.get(queueKey);
    if (queue && queue.length > 0) {
      this.onFlush([...queue]);
      queue.length = 0;
    }
  }

  private flushAll(): void {
    const priorities = Array.from(this.queues.keys()).sort((a, b) => {
      const aNum = parseInt(a.replace('priority-', ''));
      const bNum = parseInt(b.replace('priority-', ''));
      return bNum - aNum;
    });

    for (const priority of priorities) {
      this.flushPriority(priority);
    }
  }

  flush(): void {
    this.flushAll();
    if (this.timer) {
      clearTimeout(this.timer);
      this.timer = undefined;
    }
  }

  size(): number {
    return Array.from(this.queues.values()).reduce((sum, q) => sum + q.length, 0);
  }

  stop(): void {
    if (this.timer) {
      clearTimeout(this.timer);
      this.timer = undefined;
    }
    this.flushAll();
  }
}

class AsyncBatchProcessor<T> {
  private queue: T[] = [];
  private processing: boolean = false;
  private maxConcurrency: number;
  private maxBatchSize: number;
  private processFn: (batch: T[]) => Promise<void>;
  private semaphore: { count: number; max: number } = { count: 0, max: 1 };

  constructor(maxConcurrency: number, maxBatchSize: number, processFn: (batch: T[]) => Promise<void>) {
    this.maxConcurrency = maxConcurrency;
    this.maxBatchSize = maxBatchSize;
    this.processFn = processFn;
    this.semaphore = { count: 0, max: maxConcurrency };
  }

  async add(items: T[]): Promise<void> {
    this.queue.push(...items);
    await this.tryProcess();
  }

  private async tryProcess(): Promise<void> {
    if (this.processing || this.queue.length === 0 || this.semaphore.count >= this.semaphore.max) {
      return;
    }

    this.processing = true;
    this.semaphore.count++;

    try {
      while (this.queue.length > 0) {
        const batch = this.queue.splice(0, this.maxBatchSize);
        await this.processFn(batch);
      }
    } finally {
      this.processing = false;
      this.semaphore.count--;
    }
  }

  size(): number {
    return this.queue.length;
  }

  async flush(): Promise<void> {
    while (this.queue.length > 0) {
      await this.tryProcess();
      if (this.queue.length > 0) {
        await sleep(10);
      }
    }
  }
}

export class MonitoringService {
  private metrics: Map<string, MetricPoint[]> = new Map();
  private aggregatedMetrics: Map<string, AggregatedMetric[]> = new Map();
  private preAggregationCache: Map<string, { values: number[]; count: number; sum: number; min: number; max: number }> = new Map();
  private batchBuffer: BatchMetricRecord[] = [];
  private batchTimer?: NodeJS.Timeout;
  private mergeRules: MetricMergeRule[] = [];
  private downsamplingRules: DownsamplingRule[] = [];
  private config: MonitoringConfig;
  private aggregationTimer?: NodeJS.Timeout;
  private onSnapshot?: (snapshot: MetricsSnapshot) => void;
  private onBatchFlushed?: (batch: BatchMetricRecord[], aggregated: Map<string, AggregatedMetric>) => void;

  private priorityBatcher?: PriorityBatcher<BatchOperationRequest>;
  private asyncProcessor?: AsyncBatchProcessor<BatchMetricRecord>;
  private batchStats: {
    totalOperations: number;
    mergedOperations: number;
    batchCount: number;
    totalBatchSize: number;
  } = {
    totalOperations: 0,
    mergedOperations: 0,
    batchCount: 0,
    totalBatchSize: 0
  };
  private operationBatches: Map<string, BatchOperationRequest[]> = new Map();
  private operationBatchTimer?: NodeJS.Timeout;

  constructor(config: Partial<MonitoringConfig> = {}) {
    this.config = {
      retentionPeriodMs: config.retentionPeriodMs ?? 3600000,
      aggregationIntervalMs: config.aggregationIntervalMs ?? 60000,
      maxPointsPerMetric: config.maxPointsPerMetric ?? 10000,
      enableBatching: config.enableBatching ?? true,
      batchSize: config.batchSize ?? 1000,
      batchTimeoutMs: config.batchTimeoutMs ?? 5000,
      enableMerging: config.enableMerging ?? true,
      enableDownsampling: config.enableDownsampling ?? true,
      alignToWallClock: config.alignToWallClock ?? true,
      preAggregationCacheSize: config.preAggregationCacheSize ?? 10000,
      enableBatchOperations: config.enableBatchOperations ?? true,
      maxBatchOperations: config.maxBatchOperations ?? 1000,
      autoMergeSimilarMetrics: config.autoMergeSimilarMetrics ?? true,
      mergeThresholdMs: config.mergeThresholdMs ?? 100,
      asyncBatchProcessing: config.asyncBatchProcessing ?? false,
      asyncBatchConcurrency: config.asyncBatchConcurrency ?? 2
    };
    this.startAggregationLoop();

    if (this.config.enableBatching) {
      this.startBatchLoop();
    }

    if (this.config.enableBatchOperations) {
      this.initializeBatchOperationProcessor();
    }

    if (this.config.asyncBatchProcessing) {
      this.asyncProcessor = new AsyncBatchProcessor<BatchMetricRecord>(
        this.config.asyncBatchConcurrency,
        this.config.batchSize,
        async (batch) => this.processBatchRecords(batch)
      );
    }
  }

  private initializeBatchOperationProcessor(): void {
    this.priorityBatcher = new PriorityBatcher<BatchOperationRequest>(
      this.config.maxBatchOperations,
      this.config.batchTimeoutMs,
      (batch) => this.processOperationBatch(batch)
    );
  }

  setSnapshotCallback(callback: (snapshot: MetricsSnapshot) => void): void {
    this.onSnapshot = callback;
  }

  setBatchFlushedCallback(callback: (batch: BatchMetricRecord[], aggregated: Map<string, AggregatedMetric>) => void): void {
    this.onBatchFlushed = callback;
  }

  addMergeRule(rule: MetricMergeRule): void {
    this.mergeRules.push(rule);
    logger.info({ sourcePattern: rule.sourcePattern.source, targetName: rule.targetName }, '添加指标合并规则');
  }

  removeMergeRule(index: number): boolean {
    if (index >= 0 && index < this.mergeRules.length) {
      this.mergeRules.splice(index, 1);
      return true;
    }
    return false;
  }

  addDownsamplingRule(rule: DownsamplingRule): void {
    this.downsamplingRules.push(rule);
    logger.info({ metricPattern: rule.metricPattern.source }, '添加降采样规则');
  }

  recordMetric(name: string, value: number, tags: Record<string, string> = {}): void {
    if (this.config.enableBatching) {
      this.batchBuffer.push({ name, value, tags, timestamp: Date.now() });
      if (this.batchBuffer.length >= this.config.batchSize) {
        this.flushBatch();
      }
    } else {
      this.processMetricPoint(name, value, tags, Date.now());
    }
  }

  recordBatch(records: BatchMetricRecord[]): void {
    if (this.config.asyncBatchProcessing && this.asyncProcessor) {
      this.asyncProcessor.add(records.map(r => ({
        ...r,
        timestamp: r.timestamp ?? Date.now()
      })));
    } else if (this.config.enableBatching) {
      this.batchBuffer.push(...records.map(r => ({
        ...r,
        timestamp: r.timestamp ?? Date.now()
      })));
      if (this.batchBuffer.length >= this.config.batchSize) {
        this.flushBatch();
      }
    } else {
      for (const record of records) {
        this.processMetricPoint(record.name, record.value, record.tags || {}, record.timestamp || Date.now());
      }
    }
    this.batchStats.totalOperations += records.length;
    logger.debug({ count: records.length }, '批量指标已接收');
  }

  async executeBatchOperations(operations: BatchOperationRequest[]): Promise<BatchOperationResult> {
    const startTime = Date.now();
    const operationId = uuidv4();
    let successCount = 0;
    let failedCount = 0;
    let mergedCount = 0;

    if (this.config.autoMergeSimilarMetrics) {
      const { merged, count } = this.mergeSimilarOperations(operations);
      operations = merged;
      mergedCount = count;
    }

    this.batchStats.totalOperations += operations.length;
    this.batchStats.mergedOperations += mergedCount;

    for (const op of operations) {
      try {
        this.executeSingleOperation(op);
        successCount++;
      } catch (e) {
        failedCount++;
        logger.error({ operation: op.operation, name: op.name, e }, '批量操作执行失败');
      }
    }

    return {
      operationId,
      successCount,
      failedCount,
      mergedCount,
      totalTimeMs: Date.now() - startTime
    };
  }

  submitBatchOperations(operations: BatchOperationRequest[], priority: number = 1): void {
    if (!this.priorityBatcher) {
      this.executeBatchOperations(operations);
      return;
    }

    for (const op of operations) {
      this.priorityBatcher.add(op, priority);
    }
  }

  private mergeSimilarOperations(operations: BatchOperationRequest[]): { merged: BatchOperationRequest[]; count: number } {
    const mergedMap = new Map<string, BatchOperationRequest>();
    let mergedCount = 0;
    const now = Date.now();

    for (const op of operations) {
      const key = this.getOperationMergeKey(op);
      const existing = mergedMap.get(key);

      if (!existing) {
        mergedMap.set(key, { ...op });
      } else {
        const timestamp = op.timestamp ?? now;
        const existingTimestamp = existing.timestamp ?? now;

        if (Math.abs(timestamp - existingTimestamp) <= this.config.mergeThresholdMs) {
          mergedCount++;
          if (op.operation === 'increment') {
            existing.value = (existing.value ?? 0) + (op.value ?? 1);
          } else if (op.operation === 'gauge') {
            existing.value = op.value;
          } else if (op.operation === 'histogram') {
            existing.value = op.value;
          }
        } else {
          mergedMap.set(uuidv4(), { ...op });
        }
      }
    }

    return {
      merged: Array.from(mergedMap.values()),
      count: mergedCount
    };
  }

  private getOperationMergeKey(op: BatchOperationRequest): string {
    const tagsStr = op.tags ? Object.entries(op.tags).sort().map(([k, v]) => `${k}=${v}`).join(',') : '';
    return `${op.operation}:${op.name}:${tagsStr}`;
  }

  private executeSingleOperation(op: BatchOperationRequest): void {
    const tags = op.tags || {};
    const timestamp = op.timestamp ?? Date.now();

    switch (op.operation) {
      case 'increment':
        this.processMetricPoint(op.name, op.value ?? 1, tags, timestamp);
        break;
      case 'gauge':
        this.processMetricPoint(op.name, op.value ?? 0, tags, timestamp);
        break;
      case 'histogram':
        this.processMetricPoint(op.name, op.value ?? 0, tags, timestamp);
        break;
      case 'timer':
        if (op.durationMs !== undefined) {
          const statusTags = op.status ? { ...tags, status: op.status } : tags;
          this.processMetricPoint(`${op.name}.duration`, op.durationMs, statusTags, timestamp);
          this.processMetricPoint(`${op.name}.count`, 1, statusTags, timestamp);
        }
        break;
      case 'custom':
        if (op.value !== undefined) {
          this.processMetricPoint(op.name, op.value, tags, timestamp);
        }
        break;
    }
  }

  private processOperationBatch(batch: BatchOperationRequest[]): void {
    this.batchStats.batchCount++;
    this.batchStats.totalBatchSize += batch.length;
    this.executeBatchOperations(batch);
  }

  private async processBatchRecords(batch: BatchMetricRecord[]): Promise<void> {
    const now = Date.now();
    for (const record of batch) {
      this.processMetricPoint(record.name, record.value, record.tags || {}, record.timestamp || now);
    }
  }

  private startBatchLoop(): void {
    this.batchTimer = setInterval(() => {
      if (this.batchBuffer.length > 0) {
        this.flushBatch();
      }
    }, this.config.batchTimeoutMs);
  }

  private flushBatch(): void {
    if (this.batchBuffer.length === 0) return;

    const batch = [...this.batchBuffer];
    this.batchBuffer = [];

    const now = Date.now();
    const aggregated = new Map<string, AggregatedMetric>();
    const tempMetrics = new Map<string, MetricPoint[]>();

    for (const record of batch) {
      const timestamp = record.timestamp || now;
      const tags = record.tags || {};
      const key = this.getMetricKey(record.name, tags);

      const point: MetricPoint = {
        name: record.name,
        value: record.value,
        timestamp,
        tags
      };

      if (!tempMetrics.has(key)) {
        tempMetrics.set(key, []);
      }
      tempMetrics.get(key)!.push(point);
      this.processMetricPoint(record.name, record.value, tags, timestamp);
    }

    for (const [key, points] of tempMetrics.entries()) {
      if (points.length > 0) {
        const values = points.map(p => p.value).sort((a, b) => a - b);
        const sum = values.reduce((acc, v) => acc + v, 0);
        const windowStart = this.alignTimestamp(points[0].timestamp);
        const windowEnd = windowStart + this.config.aggregationIntervalMs;

        aggregated.set(key, {
          name: points[0].name,
          count: values.length,
          sum,
          avg: sum / values.length,
          min: values[0],
          max: values[values.length - 1],
          p50: values[Math.floor(values.length * 0.5)],
          p95: values[Math.floor(values.length * 0.95)],
          p99: values[Math.floor(values.length * 0.99)],
          tags: points[0].tags,
          windowStart,
          windowEnd
        });
      }
    }

    if (this.config.enableMerging) {
      this.applyMergeRules(aggregated);
    }

    logger.debug({ batchSize: batch.length, aggregatedCount: aggregated.size }, '批量指标已处理');
    this.onBatchFlushed?.(batch, aggregated);
  }

  private processMetricPoint(name: string, value: number, tags: Record<string, string>, timestamp: number): void {
    const alignedTimestamp = this.config.alignToWallClock ? this.alignTimestamp(timestamp) : timestamp;
    const key = this.getMetricKey(name, tags);

    const preAggKey = `${key}:${alignedTimestamp}`;
    let preAgg = this.preAggregationCache.get(preAggKey);
    if (!preAgg) {
      preAgg = { values: [], count: 0, sum: 0, min: Infinity, max: -Infinity };
      if (this.preAggregationCache.size >= this.config.preAggregationCacheSize) {
        const firstKey = this.preAggregationCache.keys().next().value;
        if (firstKey !== undefined) {
          this.preAggregationCache.delete(firstKey);
        }
      }
      this.preAggregationCache.set(preAggKey, preAgg);
    }

    preAgg.values.push(value);
    preAgg.count++;
    preAgg.sum += value;
    preAgg.min = Math.min(preAgg.min, value);
    preAgg.max = Math.max(preAgg.max, value);

    const point: MetricPoint = {
      name,
      value,
      timestamp,
      tags
    };

    if (!this.metrics.has(key)) {
      this.metrics.set(key, []);
    }

    const points = this.metrics.get(key)!;
    points.push(point);

    if (points.length > this.config.maxPointsPerMetric) {
      const toRemove = points.length - this.config.maxPointsPerMetric;
      points.splice(0, toRemove);
    }

    logger.debug({ name, value, tags, timestamp }, '指标已记录');
  }

  private alignTimestamp(timestamp: number): number {
    return Math.floor(timestamp / this.config.aggregationIntervalMs) * this.config.aggregationIntervalMs;
  }

  private applyMergeRules(aggregated: Map<string, AggregatedMetric>): void {
    for (const rule of this.mergeRules) {
      const matchingKeys: string[] = [];
      for (const key of aggregated.keys()) {
        if (rule.sourcePattern.test(key)) {
          matchingKeys.push(key);
        }
      }

      if (matchingKeys.length === 0) continue;

      let merged: AggregatedMetric | null = null;
      const allValues: number[] = [];

      for (const key of matchingKeys) {
        const metric = aggregated.get(key)!;
        allValues.push(...this.getValuesFromAggregated(metric));

        if (!merged) {
          merged = { ...metric, tags: {} };
        } else {
          merged = this.mergeAggregatedMetrics(merged, metric, rule);
        }
      }

      if (merged && allValues.length > 0) {
        const sortedValues = allValues.sort((a, b) => a - b);
        merged.p50 = sortedValues[Math.floor(sortedValues.length * 0.5)];
        merged.p95 = sortedValues[Math.floor(sortedValues.length * 0.95)];
        merged.p99 = sortedValues[Math.floor(sortedValues.length * 0.99)];
        merged.name = rule.targetName;
        aggregated.set(this.getMetricKey(rule.targetName, {}), merged);
      }
    }
  }

  private getValuesFromAggregated(metric: AggregatedMetric): number[] {
    const values: number[] = [];
    for (let i = 0; i < metric.count; i++) {
      values.push(metric.avg);
    }
    return values;
  }

  private mergeAggregatedMetrics(a: AggregatedMetric, b: AggregatedMetric, rule: MetricMergeRule): AggregatedMetric {
    const merged: AggregatedMetric = {
      ...a,
      count: a.count + b.count,
      sum: a.sum + b.sum,
      min: Math.min(a.min, b.min),
      max: Math.max(a.max, b.max),
      windowStart: Math.min(a.windowStart, b.windowStart),
      windowEnd: Math.max(a.windowEnd, b.windowEnd)
    };

    merged.avg = merged.sum / merged.count;

    if (rule.tagAggregation === 'combine') {
      merged.tags = { ...a.tags, ...b.tags };
    } else if (rule.tagAggregation === 'keep') {
      merged.tags = a.tags;
    } else {
      merged.tags = {};
    }

    return merged;
  }

  increment(name: string, tags: Record<string, string> = {}, value: number = 1): void {
    this.recordMetric(name, value, tags);
  }

  gauge(name: string, value: number, tags: Record<string, string> = {}): void {
    this.recordMetric(name, value, tags);
  }

  histogram(name: string, value: number, tags: Record<string, string> = {}): void {
    this.recordMetric(name, value, tags);
  }

  async withTimer<T>(name: string, fn: () => Promise<T>, tags: Record<string, string> = {}): Promise<T> {
    const start = Date.now();
    try {
      const result = await fn();
      this.recordMetric(`${name}.duration`, Date.now() - start, { ...tags, status: 'success' });
      this.increment(`${name}.count`, { ...tags, status: 'success' });
      return result;
    } catch (error) {
      this.recordMetric(`${name}.duration`, Date.now() - start, { ...tags, status: 'error' });
      this.increment(`${name}.count`, { ...tags, status: 'error' });
      throw error;
    }
  }

  getMetric(name: string, tags: Record<string, string> = {}): MetricPoint[] {
    const key = this.getMetricKey(name, tags);
    return this.metrics.get(key) || [];
  }

  getAggregatedMetrics(name: string, tags: Record<string, string> = {}): AggregatedMetric[] {
    const key = this.getMetricKey(name, tags);
    return this.aggregatedMetrics.get(key) || [];
  }

  queryMetrics(
    namePattern: string,
    startTime: number,
    endTime: number,
    tags?: Record<string, string>
  ): MetricPoint[] {
    const results: MetricPoint[] = [];
    const regex = new RegExp(namePattern);

    for (const [key, points] of this.metrics.entries()) {
      if (!regex.test(key)) continue;

      if (tags) {
        const pointTags = points[0]?.tags || {};
        let match = true;
        for (const [k, v] of Object.entries(tags)) {
          if (pointTags[k] !== v) {
            match = false;
            break;
          }
        }
        if (!match) continue;
      }

      results.push(...points.filter(p => p.timestamp >= startTime && p.timestamp <= endTime));
    }

    return results.sort((a, b) => a.timestamp - b.timestamp);
  }

  queryAggregated(
    namePattern: string,
    startTime: number,
    endTime: number,
    tags?: Record<string, string>
  ): AggregatedMetric[] {
    const results: AggregatedMetric[] = [];
    const regex = new RegExp(namePattern);

    for (const [key, metrics] of this.aggregatedMetrics.entries()) {
      if (!regex.test(key)) continue;

      if (tags) {
        const metricTags = metrics[0]?.tags || {};
        let match = true;
        for (const [k, v] of Object.entries(tags)) {
          if (metricTags[k] !== v) {
            match = false;
            break;
          }
        }
        if (!match) continue;
      }

      results.push(...metrics.filter(m => m.windowStart >= startTime && m.windowEnd <= endTime));
    }

    return results.sort((a, b) => a.windowStart - b.windowStart);
  }

  createSnapshot(dimensions: Record<string, string> = {}): MetricsSnapshot {
    const now = Date.now();
    const windowStart = now - this.config.aggregationIntervalMs;

    const throughput = this.calculateRate('requests.count', windowStart, now);
    const latencyP99 = this.calculatePercentile('requests.duration', 99, windowStart, now);
    const errorRate = this.calculateErrorRate(windowStart, now);

    const snapshot: MetricsSnapshot = {
      snapshot_id: uuidv4(),
      timestamp: new Date().toISOString(),
      metrics: {
        throughput,
        latency_p99: latencyP99,
        error_rate: errorRate
      },
      dimensions
    };

    this.onSnapshot?.(snapshot);
    return snapshot;
  }

  createBatchSnapshot(windowStart: number, windowEnd: number, dimensions: Record<string, string> = {}): {
    snapshotId: string;
    windowStart: string;
    windowEnd: string;
    metrics: Array<{ name: string; aggregated: AggregatedMetric }>;
    dimensions: Record<string, string>;
  } {
    const metrics: Array<{ name: string; aggregated: AggregatedMetric }> = [];

    for (const [key, aggList] of this.aggregatedMetrics.entries()) {
      const filtered = aggList.filter(a => a.windowStart >= windowStart && a.windowEnd <= windowEnd);
      if (filtered.length > 0) {
        const merged = this.mergeMultipleAggregated(filtered);
        metrics.push({ name: key, aggregated: merged });
      }
    }

    return {
      snapshotId: uuidv4(),
      windowStart: new Date(windowStart).toISOString(),
      windowEnd: new Date(windowEnd).toISOString(),
      metrics,
      dimensions
    };
  }

  private mergeMultipleAggregated(metrics: AggregatedMetric[]): AggregatedMetric {
    if (metrics.length === 0) {
      throw new Error('Cannot merge empty metrics array');
    }

    const first = metrics[0];
    let result: AggregatedMetric = { ...first };

    for (let i = 1; i < metrics.length; i++) {
      result = {
        ...result,
        count: result.count + metrics[i].count,
        sum: result.sum + metrics[i].sum,
        min: Math.min(result.min, metrics[i].min),
        max: Math.max(result.max, metrics[i].max),
        windowStart: Math.min(result.windowStart, metrics[i].windowStart),
        windowEnd: Math.max(result.windowEnd, metrics[i].windowEnd)
      };
    }

    result.avg = result.sum / result.count;
    return result;
  }

  private calculateRate(name: string, start: number, end: number): number {
    const allPoints: MetricPoint[] = [];
    for (const [key, points] of this.metrics.entries()) {
      if (key.startsWith(name)) {
        allPoints.push(...points.filter(p => p.timestamp >= start && p.timestamp <= end));
      }
    }
    const durationSeconds = (end - start) / 1000;
    return durationSeconds > 0 ? allPoints.length / durationSeconds : 0;
  }

  private calculatePercentile(name: string, percentile: number, start: number, end: number): number {
    const allPoints: MetricPoint[] = [];
    for (const [key, points] of this.metrics.entries()) {
      if (key.startsWith(name)) {
        allPoints.push(...points.filter(p => p.timestamp >= start && p.timestamp <= end));
      }
    }
    if (allPoints.length === 0) return 0;

    const values = allPoints.map(p => p.value).sort((a, b) => a - b);
    const index = Math.ceil((percentile / 100) * values.length) - 1;
    return values[Math.max(0, index)];
  }

  private calculateErrorRate(start: number, end: number): number {
    let total = 0;
    let errors = 0;

    for (const [key, points] of this.metrics.entries()) {
      if (key.includes('.count')) {
        const filtered = points.filter(p => p.timestamp >= start && p.timestamp <= end);
        total += filtered.length;
        if (key.includes('status:error') || key.includes('status=error')) {
          errors += filtered.length;
        }
      }
    }

    return total > 0 ? errors / total : 0;
  }

  private aggregate(): void {
    const now = Date.now();
    const windowStart = now - this.config.aggregationIntervalMs;

    const newAggregations = new Map<string, AggregatedMetric>();

    for (const [key, points] of this.metrics.entries()) {
      const windowPoints = points.filter(p => p.timestamp >= windowStart);
      if (windowPoints.length === 0) continue;

      const values = windowPoints.map(p => p.value).sort((a, b) => a - b);
      const sum = values.reduce((acc, v) => acc + v, 0);

      const aggregated: AggregatedMetric = {
        name: windowPoints[0].name,
        count: values.length,
        sum,
        avg: sum / values.length,
        min: values[0],
        max: values[values.length - 1],
        p50: values[Math.floor(values.length * 0.5)],
        p95: values[Math.floor(values.length * 0.95)],
        p99: values[Math.floor(values.length * 0.99)],
        tags: windowPoints[0].tags,
        windowStart: this.alignTimestamp(windowPoints[0].timestamp),
        windowEnd: now
      };

      newAggregations.set(key, aggregated);

      if (!this.aggregatedMetrics.has(key)) {
        this.aggregatedMetrics.set(key, []);
      }
      this.aggregatedMetrics.get(key)!.push(aggregated);
    }

    if (this.config.enableMerging && newAggregations.size > 0) {
      this.applyMergeRules(newAggregations);
    }

    if (this.config.enableDownsampling) {
      this.applyDownsampling();
    }

    this.cleanupOldData();
  }

  private applyDownsampling(): void {
    const now = Date.now();

    for (const rule of this.downsamplingRules) {
      for (const [key, metrics] of this.aggregatedMetrics.entries()) {
        if (!rule.metricPattern.test(key)) continue;

        const cutoff = now - rule.retentionMs;
        const recentMetrics = metrics.filter(m => m.windowEnd >= cutoff);

        if (recentMetrics.length < 2) continue;

        const downsampled: AggregatedMetric[] = [];
        let currentBucket: AggregatedMetric[] = [];
        let bucketStart = recentMetrics[0].windowStart;

        for (const metric of recentMetrics) {
          if (metric.windowStart >= bucketStart + rule.targetResolutionMs) {
            if (currentBucket.length > 0) {
              downsampled.push(this.mergeMultipleAggregated(currentBucket));
            }
            currentBucket = [metric];
            bucketStart = metric.windowStart;
          } else {
            currentBucket.push(metric);
          }
        }

        if (currentBucket.length > 0) {
          downsampled.push(this.mergeMultipleAggregated(currentBucket));
        }

        this.aggregatedMetrics.set(key, downsampled);
      }
    }
  }

  private cleanupOldData(): void {
    const cutoff = Date.now() - this.config.retentionPeriodMs;

    for (const [key, points] of this.metrics.entries()) {
      this.metrics.set(key, points.filter(p => p.timestamp >= cutoff));
    }

    for (const [key, agg] of this.aggregatedMetrics.entries()) {
      this.aggregatedMetrics.set(key, agg.filter(a => a.windowEnd >= cutoff));
    }

    const preAggCutoff = Date.now() - this.config.aggregationIntervalMs * 2;
    for (const [key] of this.preAggregationCache) {
      const timestamp = parseInt(key.split(':').pop() || '0');
      if (timestamp < preAggCutoff) {
        this.preAggregationCache.delete(key);
      }
    }
  }

  private startAggregationLoop(): void {
    this.aggregationTimer = setInterval(() => {
      try {
        this.aggregate();
      } catch (error) {
        logger.error({ error }, '指标聚合失败');
      }
    }, this.config.aggregationIntervalMs);
  }

  getBatchProcessorStats(): BatchProcessorStats {
    return {
      totalOperations: this.batchStats.totalOperations,
      mergedOperations: this.batchStats.mergedOperations,
      batchCount: this.batchStats.batchCount,
      avgBatchSize: this.batchStats.batchCount > 0 ? this.batchStats.totalBatchSize / this.batchStats.batchCount : 0,
      avgMergeRatio: this.batchStats.totalOperations > 0 ? this.batchStats.mergedOperations / this.batchStats.totalOperations : 0,
      pendingOperations: this.batchBuffer.length + (this.asyncProcessor?.size() ?? 0)
    };
  }

  stop(): void {
    if (this.aggregationTimer) {
      clearInterval(this.aggregationTimer);
    }
    if (this.batchTimer) {
      clearInterval(this.batchTimer);
    }
    if (this.priorityBatcher) {
      this.priorityBatcher.stop();
    }
    if (this.operationBatchTimer) {
      clearTimeout(this.operationBatchTimer);
    }
  }

  getAllMetrics(): string[] {
    return Array.from(this.metrics.keys());
  }

  reset(): void {
    this.metrics.clear();
    this.aggregatedMetrics.clear();
    this.preAggregationCache.clear();
    this.batchBuffer = [];
    this.batchStats = {
      totalOperations: 0,
      mergedOperations: 0,
      batchCount: 0,
      totalBatchSize: 0
    };
  }

  getStats(): {
    totalMetrics: number;
    totalAggregated: number;
    metricNames: string[];
    batchBufferSize: number;
    preAggregationCacheSize: number;
    mergeRuleCount: number;
    downsamplingRuleCount: number;
    batchProcessor?: BatchProcessorStats;
  } {
    return {
      totalMetrics: this.metrics.size,
      totalAggregated: this.aggregatedMetrics.size,
      metricNames: Array.from(this.metrics.keys()),
      batchBufferSize: this.batchBuffer.length,
      preAggregationCacheSize: this.preAggregationCache.size,
      mergeRuleCount: this.mergeRules.length,
      downsamplingRuleCount: this.downsamplingRules.length,
      batchProcessor: this.config.enableBatchOperations ? this.getBatchProcessorStats() : undefined
    };
  }

  forceFlush(): number {
    const count = this.batchBuffer.length;
    this.flushBatch();
    if (this.priorityBatcher) {
      this.priorityBatcher.flush();
    }
    return count;
  }

  private getMetricKey(name: string, tags: Record<string, string>): string {
    const sortedTags = Object.entries(tags)
      .sort(([a], [b]) => a.localeCompare(b))
      .map(([k, v]) => `${k}=${v}`)
      .join(',');
    return sortedTags ? `${name}[${sortedTags}]` : name;
  }
}

export default MonitoringService;
