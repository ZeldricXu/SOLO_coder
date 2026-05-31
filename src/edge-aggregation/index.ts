import { v4 as uuidv4 } from 'uuid';
import logger from '../common/logger';

export interface DataPoint {
  timestamp: number;
  value: number;
  tags: Record<string, string>;
}

export interface AggregatedData {
  aggregationId: string;
  startTime: number;
  endTime: number;
  metrics: {
    count: number;
    sum: number;
    avg: number;
    min: number;
    max: number;
    first: number;
    last: number;
    variance?: number;
    stdDev?: number;
  };
  tags: Record<string, string>;
  rawDataPoints: number;
}

export interface WindowConfig {
  type: 'tumbling' | 'sliding' | 'session';
  durationMs: number;
  slideMs?: number;
  sessionTimeoutMs?: number;
}

export interface AggregationRule {
  ruleId: string;
  name: string;
  metricName: string;
  window: WindowConfig;
  aggregationFunctions: Array<'count' | 'sum' | 'avg' | 'min' | 'max' | 'first' | 'last'>;
  tags: string[];
  enabled: boolean;
  uploadThreshold?: {
    minDataPoints?: number;
    maxDelayMs?: number;
  };
}

export interface AggregationResult {
  ruleId: string;
  metricName: string;
  windowStart: number;
  windowEnd: number;
  data: AggregatedData;
  shouldUpload: boolean;
}

export class EdgeDataAggregator {
  private rules: Map<string, AggregationRule> = new Map();
  private dataBuffers: Map<string, DataPoint[]> = new Map();
  private windowTimers: Map<string, NodeJS.Timeout> = new Map();
  private onAggregationComplete?: (result: AggregationResult) => void;
  private onUploadReady?: (aggregatedData: AggregatedData[]) => void;

  setAggregationCallback(callback: (result: AggregationResult) => void): void {
    this.onAggregationComplete = callback;
  }

  setUploadCallback(callback: (aggregatedData: AggregatedData[]) => void): void {
    this.onUploadReady = callback;
  }

  registerRule(rule: AggregationRule): void {
    this.rules.set(rule.ruleId, rule);
    const bufferKey = this.getBufferKey(rule);
    this.dataBuffers.set(bufferKey, []);
    this.startWindowTimer(rule);
    logger.info({ ruleId: rule.ruleId, name: rule.name, metricName: rule.metricName }, '注册聚合规则');
  }

  unregisterRule(ruleId: string): void {
    const rule = this.rules.get(ruleId);
    if (rule) {
      const bufferKey = this.getBufferKey(rule);
      this.dataBuffers.delete(bufferKey);
      const timer = this.windowTimers.get(ruleId);
      if (timer) clearInterval(timer);
      this.windowTimers.delete(ruleId);
      this.rules.delete(ruleId);
      logger.info({ ruleId }, '注销聚合规则');
    }
  }

  ingestDataPoint(metricName: string, value: number, tags: Record<string, string> = {}): void {
    const point: DataPoint = {
      timestamp: Date.now(),
      value,
      tags
    };

    for (const rule of this.rules.values()) {
      if (rule.enabled && rule.metricName === metricName) {
        if (this.matchesTags(rule, tags)) {
          const bufferKey = this.getBufferKey(rule, tags);
          if (!this.dataBuffers.has(bufferKey)) {
            this.dataBuffers.set(bufferKey, []);
          }
          this.dataBuffers.get(bufferKey)!.push(point);
          logger.debug({ ruleId: rule.ruleId, metricName, value }, '数据点已接收');
        }
      }
    }
  }

  ingestBatch(metricName: string, values: Array<{ value: number; tags: Record<string, string>; timestamp?: number }>): void {
    for (const v of values) {
      const point: DataPoint = {
        timestamp: v.timestamp ?? Date.now(),
        value: v.value,
        tags: v.tags
      };

      for (const rule of this.rules.values()) {
        if (rule.enabled && rule.metricName === metricName) {
          if (this.matchesTags(rule, v.tags)) {
            const bufferKey = this.getBufferKey(rule, v.tags);
            if (!this.dataBuffers.has(bufferKey)) {
              this.dataBuffers.set(bufferKey, []);
            }
            this.dataBuffers.get(bufferKey)!.push(point);
          }
        }
      }
    }
    logger.debug({ metricName, count: values.length }, '批量数据点已接收');
  }

  forceAggregate(ruleId: string): AggregationResult[] {
    const rule = this.rules.get(ruleId);
    if (!rule) return [];

    const results: AggregationResult[] = [];
    for (const [bufferKey, points] of this.dataBuffers.entries()) {
      if (bufferKey.startsWith(`${rule.ruleId}:`) && points.length > 0) {
        const result = this.aggregatePoints(rule, points);
        results.push(result);
        this.dataBuffers.set(bufferKey, []);
      }
    }

    return results;
  }

  triggerUpload(): void {
    const allAggregated: AggregatedData[] = [];
    for (const rule of this.rules.values()) {
      const results = this.forceAggregate(rule.ruleId);
      for (const r of results) {
        allAggregated.push(r.data);
      }
    }

    if (allAggregated.length > 0 && this.onUploadReady) {
      logger.info({ count: allAggregated.length }, '触发聚合数据上传');
      this.onUploadReady(allAggregated);
    }
  }

  private aggregatePoints(rule: AggregationRule, points: DataPoint[]): AggregationResult {
    const sortedPoints = [...points].sort((a, b) => a.timestamp - b.timestamp);
    const values = sortedPoints.map(p => p.value);
    const sum = values.reduce((acc, v) => acc + v, 0);
    const avg = sum / values.length;
    const variance = values.reduce((acc, v) => acc + Math.pow(v - avg, 2), 0) / values.length;
    const stdDev = Math.sqrt(variance);

    const tags: Record<string, string> = {};
    for (const tagKey of rule.tags) {
      const tagValue = sortedPoints[0]?.tags[tagKey];
      if (tagValue) tags[tagKey] = tagValue;
    }

    const data: AggregatedData = {
      aggregationId: uuidv4(),
      startTime: sortedPoints[0].timestamp,
      endTime: sortedPoints[sortedPoints.length - 1].timestamp,
      metrics: {
        count: values.length,
        sum,
        avg,
        min: Math.min(...values),
        max: Math.max(...values),
        first: values[0],
        last: values[values.length - 1],
        variance,
        stdDev
      },
      tags,
      rawDataPoints: points.length
    };

    const shouldUpload = this.checkUploadThreshold(rule, data);
    const result: AggregationResult = {
      ruleId: rule.ruleId,
      metricName: rule.metricName,
      windowStart: sortedPoints[0].timestamp,
      windowEnd: sortedPoints[sortedPoints.length - 1].timestamp,
      data,
      shouldUpload
    };

    this.onAggregationComplete?.(result);
    logger.debug({ ruleId: rule.ruleId, points: points.length, shouldUpload }, '聚合完成');

    return result;
  }

  private checkUploadThreshold(rule: AggregationRule, data: AggregatedData): boolean {
    if (!rule.uploadThreshold) return true;

    const { minDataPoints = 0, maxDelayMs } = rule.uploadThreshold;
    if (data.rawDataPoints >= minDataPoints) return true;

    if (maxDelayMs) {
      const age = Date.now() - data.startTime;
      if (age >= maxDelayMs) return true;
    }

    return false;
  }

  private startWindowTimer(rule: AggregationRule): void {
    if (this.windowTimers.has(rule.ruleId)) return;

    const timer = setInterval(() => {
      this.processWindow(rule).catch(error => {
        logger.error({ ruleId: rule.ruleId, error }, '窗口处理失败');
      });
    }, rule.window.durationMs);

    this.windowTimers.set(rule.ruleId, timer);
  }

  private async processWindow(rule: AggregationRule): Promise<void> {
    for (const [bufferKey, points] of this.dataBuffers.entries()) {
      if (bufferKey.startsWith(`${rule.ruleId}:`) && points.length > 0) {
        const result = this.aggregatePoints(rule, points);
        this.dataBuffers.set(bufferKey, []);

        if (result.shouldUpload && this.onUploadReady) {
          this.onUploadReady([result.data]);
        }
      }
    }
  }

  private matchesTags(rule: AggregationRule, tags: Record<string, string>): boolean {
    for (const requiredTag of rule.tags) {
      if (tags[requiredTag] === undefined) return false;
    }
    return true;
  }

  private getBufferKey(rule: AggregationRule, tags: Record<string, string> = {}): string {
    const tagValues = rule.tags.map(t => tags[t] || '').join(',');
    return `${rule.ruleId}:${tagValues}`;
  }

  getBufferStats(): Array<{ ruleId: string; bufferSize: number }> {
    const stats: Array<{ ruleId: string; bufferSize: number }> = [];
    for (const rule of this.rules.values()) {
      let totalSize = 0;
      for (const [key, points] of this.dataBuffers.entries()) {
        if (key.startsWith(`${rule.ruleId}:`)) {
          totalSize += points.length;
        }
      }
      stats.push({ ruleId: rule.ruleId, bufferSize: totalSize });
    }
    return stats;
  }

  stop(): void {
    for (const [ruleId, timer] of this.windowTimers) {
      clearInterval(timer);
    }
    this.windowTimers.clear();
  }

  clearBuffers(): void {
    this.dataBuffers.clear();
  }

  getRules(): AggregationRule[] {
    return Array.from(this.rules.values());
  }
}
