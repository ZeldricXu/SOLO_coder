import { v4 as uuidv4 } from 'uuid';
import { AnomalyResult, DetectionAlgorithm, MetricPoint } from '../types';
import { ProcessingPipeline } from '../core';
import { MetricsQueryService } from '../metrics';

export interface BaselineConfig {
  windowSize: number;
  minDataPoints: number;
  sensitivity: number;
}

export class StatisticalUtils {
  static mean(values: number[]): number {
    if (values.length === 0) return 0;
    return values.reduce((a, b) => a + b, 0) / values.length;
  }

  static stdDev(values: number[]): number {
    if (values.length < 2) return 0;
    const mean = this.mean(values);
    const squaredDiffs = values.map(v => Math.pow(v - mean, 2));
    return Math.sqrt(this.mean(squaredDiffs));
  }

  static median(values: number[]): number {
    if (values.length === 0) return 0;
    const sorted = [...values].sort((a, b) => a - b);
    const mid = Math.floor(sorted.length / 2);
    return sorted.length % 2 !== 0 ? sorted[mid] : (sorted[mid - 1] + sorted[mid]) / 2;
  }

  static percentile(values: number[], p: number): number {
    if (values.length === 0) return 0;
    const sorted = [...values].sort((a, b) => a - b);
    const index = Math.ceil((p / 100) * sorted.length) - 1;
    return sorted[Math.max(0, Math.min(index, sorted.length - 1))];
  }

  static mad(values: number[]): number {
    if (values.length === 0) return 0;
    const median = this.median(values);
    const absoluteDiffs = values.map(v => Math.abs(v - median));
    return this.median(absoluteDiffs);
  }

  static zScore(value: number, mean: number, stdDev: number): number {
    if (stdDev === 0) return 0;
    return (value - mean) / stdDev;
  }

  static exponentialSmoothing(values: number[], alpha: number = 0.3): number[] {
    if (values.length === 0) return [];
    const smoothed: number[] = [values[0]];
    for (let i = 1; i < values.length; i++) {
      smoothed.push(alpha * values[i] + (1 - alpha) * smoothed[i - 1]);
    }
    return smoothed;
  }
}

export class ZScoreAlgorithm implements DetectionAlgorithm {
  name = 'zscore';
  private threshold: number;

  constructor(threshold: number = 3) {
    this.threshold = threshold;
  }

  detect(history: number[], current: number): AnomalyResult | null {
    if (history.length < 5) return null;

    const mean = StatisticalUtils.mean(history);
    const stdDev = StatisticalUtils.stdDev(history);
    const zScore = StatisticalUtils.zScore(current, mean, stdDev);

    if (Math.abs(zScore) > this.threshold) {
      return {
        timestamp: new Date().toISOString(),
        metric: '',
        tags: {},
        value: current,
        expected: mean,
        deviation: Math.abs(zScore),
        severity: Math.abs(zScore) > this.threshold * 1.5 ? 'high' : 'medium',
        algorithm: this.name,
      };
    }

    return null;
  }
}

export class MADAlgorithm implements DetectionAlgorithm {
  name = 'mad';
  private threshold: number;

  constructor(threshold: number = 3) {
    this.threshold = threshold;
  }

  detect(history: number[], current: number): AnomalyResult | null {
    if (history.length < 5) return null;

    const median = StatisticalUtils.median(history);
    const mad = StatisticalUtils.mad(history);
    const modifiedZScore = mad === 0 ? 0 : 0.6745 * (current - median) / mad;

    if (Math.abs(modifiedZScore) > this.threshold) {
      return {
        timestamp: new Date().toISOString(),
        metric: '',
        tags: {},
        value: current,
        expected: median,
        deviation: Math.abs(modifiedZScore),
        severity: Math.abs(modifiedZScore) > this.threshold * 1.5 ? 'high' : 'medium',
        algorithm: this.name,
      };
    }

    return null;
  }
}

export class IQRAlgorithm implements DetectionAlgorithm {
  name = 'iqr';
  private threshold: number;

  constructor(threshold: number = 1.5) {
    this.threshold = threshold;
  }

  detect(history: number[], current: number): AnomalyResult | null {
    if (history.length < 5) return null;

    const q25 = StatisticalUtils.percentile(history, 25);
    const q75 = StatisticalUtils.percentile(history, 75);
    const iqr = q75 - q25;
    const lowerBound = q25 - this.threshold * iqr;
    const upperBound = q75 + this.threshold * iqr;

    if (current < lowerBound || current > upperBound) {
      const median = StatisticalUtils.median(history);
      const deviation = Math.abs(current - median) / (iqr || 1);

      return {
        timestamp: new Date().toISOString(),
        metric: '',
        tags: {},
        value: current,
        expected: median,
        deviation,
        severity: deviation > this.threshold * 2 ? 'high' : 'medium',
        algorithm: this.name,
      };
    }

    return null;
  }
}

export class ESDAlgorithm implements DetectionAlgorithm {
  name = 'esd';
  private maxOutliers: number;
  private significance: number;

  constructor(maxOutliers: number = 5, significance: number = 0.05) {
    this.maxOutliers = maxOutliers;
    this.significance = significance;
  }

  detect(history: number[], current: number): AnomalyResult | null {
    if (history.length < 10) return null;

    const data = [...history, current];
    const n = data.length;
    const mean = StatisticalUtils.mean(data);
    const stdDev = StatisticalUtils.stdDev(data);

    if (stdDev === 0) return null;

    const zScores = data.map(v => Math.abs(v - mean) / stdDev);
    const maxZScore = Math.max(...zScores);
    const maxIndex = zScores.indexOf(maxZScore);

    const criticalValue = this.getCriticalValue(n, this.significance);

    if (maxZScore > criticalValue && maxIndex === data.length - 1) {
      return {
        timestamp: new Date().toISOString(),
        metric: '',
        tags: {},
        value: current,
        expected: mean,
        deviation: maxZScore,
        severity: maxZScore > criticalValue * 1.5 ? 'high' : 'medium',
        algorithm: this.name,
      };
    }

    return null;
  }

  private getCriticalValue(n: number, alpha: number): number {
    const t = this.inverseT(1 - alpha / (2 * n), n - 2);
    return ((n - 1) / Math.sqrt(n)) * Math.sqrt(t * t / (n - 2 + t * t));
  }

  private inverseT(p: number, df: number): number {
    if (p <= 0 || p >= 1) return Infinity;
    if (df <= 0) return NaN;

    const a = 1 / (df - 0.5);
    const b = 48 / (a * a);
    const c = ((20700 * a / b - 98) * a - 16) * a + 96.36;
    const d = ((94.5 / (b + c) - 3) / b + 1) * Math.sqrt(a * Math.PI / 2) * df;

    let x = d * (2 * p - 1);
    x = Math.log(x * x);
    x = a * (((c * x + b) * x - 103) * x - 447.5);
    x = (2 * p - 1) * Math.exp(x);

    return x;
  }
}

export class SeasonalAlgorithm implements DetectionAlgorithm {
  name = 'seasonal';
  private period: number;
  private threshold: number;

  constructor(period: number = 24, threshold: number = 2) {
    this.period = period;
    this.threshold = threshold;
  }

  detect(history: number[], current: number): AnomalyResult | null {
    if (history.length < this.period * 2) return null;

    const seasonalValues: number[] = [];
    for (let i = history.length % this.period; i < history.length; i += this.period) {
      seasonalValues.push(history[i]);
    }

    if (seasonalValues.length < 3) return null;

    const mean = StatisticalUtils.mean(seasonalValues);
    const stdDev = StatisticalUtils.stdDev(seasonalValues);
    const zScore = StatisticalUtils.zScore(current, mean, stdDev);

    if (Math.abs(zScore) > this.threshold) {
      return {
        timestamp: new Date().toISOString(),
        metric: '',
        tags: {},
        value: current,
        expected: mean,
        deviation: Math.abs(zScore),
        severity: Math.abs(zScore) > this.threshold * 1.5 ? 'high' : 'medium',
        algorithm: this.name,
      };
    }

    return null;
  }
}

export class AlgorithmRegistry {
  private algorithms: Map<string, DetectionAlgorithm> = new Map();

  constructor() {
    this.register(new ZScoreAlgorithm());
    this.register(new MADAlgorithm());
    this.register(new IQRAlgorithm());
    this.register(new ESDAlgorithm());
    this.register(new SeasonalAlgorithm());
  }

  register(algorithm: DetectionAlgorithm): void {
    this.algorithms.set(algorithm.name, algorithm);
  }

  get(name: string): DetectionAlgorithm | undefined {
    return this.algorithms.get(name);
  }

  list(): DetectionAlgorithm[] {
    return Array.from(this.algorithms.values());
  }

  remove(name: string): boolean {
    return this.algorithms.delete(name);
  }
}

export class AnomalyDetector {
  private registry: AlgorithmRegistry;
  private historyStore: Map<string, number[]> = new Map();
  private maxHistorySize: number = 1000;
  private metricsQuery: MetricsQueryService;

  constructor(registry: AlgorithmRegistry, metricsQuery: MetricsQueryService) {
    this.registry = registry;
    this.metricsQuery = metricsQuery;
  }

  addToHistory(metric: string, tags: Record<string, string>, value: number): void {
    const key = `${metric}:${JSON.stringify(tags)}`;
    const history = this.historyStore.get(key) || [];
    history.push(value);
    if (history.length > this.maxHistorySize) {
      history.splice(0, history.length - this.maxHistorySize);
    }
    this.historyStore.set(key, history);
  }

  getHistory(metric: string, tags: Record<string, string>): number[] {
    const key = `${metric}:${JSON.stringify(tags)}`;
    return this.historyStore.get(key) || [];
  }

  detect(
    metric: string,
    tags: Record<string, string>,
    value: number,
    algorithmNames: string[] = ['zscore', 'mad']
  ): AnomalyResult[] {
    const history = this.getHistory(metric, tags);
    const results: AnomalyResult[] = [];

    for (const name of algorithmNames) {
      const algorithm = this.registry.get(name);
      if (!algorithm) continue;

      const result = algorithm.detect(history, value);
      if (result) {
        result.metric = metric;
        result.tags = tags;
        results.push(result);
      }
    }

    return results;
  }

  async detectFromMetric(
    metric: string,
    tags: Record<string, string>,
    algorithmNames: string[] = ['zscore', 'mad'],
    lookbackMinutes: number = 60
  ): Promise<AnomalyResult[]> {
    const now = Date.now();
    const startTime = now - lookbackMinutes * 60 * 1000;

    const timeSeries = await this.metricsQuery.queryRaw(metric, tags, startTime, now);

    if (timeSeries.points.length === 0) {
      return [];
    }

    const history = timeSeries.points.slice(0, -1).map(p => p.value);
    const current = timeSeries.points[timeSeries.points.length - 1].value;

    for (const h of history) {
      this.addToHistory(metric, tags, h);
    }

    const results = this.detect(metric, tags, current, algorithmNames);
    this.addToHistory(metric, tags, current);

    return results;
  }

  async detectBatch(
    metrics: { metric: string; tags: Record<string, string> }[],
    algorithmNames: string[] = ['zscore', 'mad']
  ): Promise<AnomalyResult[]> {
    const results: AnomalyResult[] = [];
    for (const m of metrics) {
      const anomalies = await this.detectFromMetric(m.metric, m.tags, algorithmNames);
      results.push(...anomalies);
    }
    return results;
  }
}

export class AnomalyPipeline {
  private detector: AnomalyDetector;
  private registry: AlgorithmRegistry;
  private pipeline: ProcessingPipeline<{ metric: string; tags: Record<string, string>; value: number }, AnomalyResult[]>;

  constructor(detector: AnomalyDetector, registry: AlgorithmRegistry) {
    this.detector = detector;
    this.registry = registry;
    this.pipeline = this.buildPipeline();
  }

  private buildPipeline(): ProcessingPipeline<{ metric: string; tags: Record<string, string>; value: number }, AnomalyResult[]> {
    return new ProcessingPipeline<{ metric: string; tags: Record<string, string>; value: number }, AnomalyResult[]>()
      .addStage({
        name: 'history_update',
        process: async (input) => {
          this.detector.addToHistory(input.metric, input.tags, input.value);
          return input;
        },
      })
      .addStage({
        name: 'detection',
        process: async (input) => this.detector.detect(input.metric, input.tags, input.value),
      });
  }

  async process(metric: string, tags: Record<string, string>, value: number): Promise<AnomalyResult[]> {
    const result = await this.pipeline.execute({ metric, tags, value });
    if (!result.success || !result.data) {
      throw new Error(result.error || 'Failed to process anomaly detection');
    }
    return result.data;
  }

  getAvailableAlgorithms(): string[] {
    return this.registry.list().map(a => a.name);
  }
}

export function createAnomalyModule(
  metricsQuery: MetricsQueryService
): {
  registry: AlgorithmRegistry;
  detector: AnomalyDetector;
  pipeline: AnomalyPipeline;
} {
  const registry = new AlgorithmRegistry();
  const detector = new AnomalyDetector(registry, metricsQuery);
  const pipeline = new AnomalyPipeline(detector, registry);

  return {
    registry,
    detector,
    pipeline,
  };
}
