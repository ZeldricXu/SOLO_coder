import { EventEmitter, parseDuration, average, standardDeviation, exponentialMovingAverage, movingAverage } from '../utils';
import logger from '../utils/logger';
import { AnomalyDetectionConfig, AnomalyResult, TimeSeriesPoint } from '../types';

export interface BaselineModel {
  metric_name: string;
  mean: number;
  std_dev: number;
  min: number;
  max: number;
  trend: number[];
  seasonality?: number[];
  last_updated: string;
}

export interface DetectionAlgorithm {
  name: string;
  detect(points: number[], config: AnomalyDetectionConfig): AnomalyResult;
}

class StaticThresholdAlgorithm implements DetectionAlgorithm {
  name = 'static_threshold';

  detect(points: number[], config: AnomalyDetectionConfig): AnomalyResult {
    const threshold = config.baseline_params?.threshold ?? 0;
    const operator = config.baseline_params?.operator ?? 'gt';
    const actualValue = points[points.length - 1] ?? 0;

    let is_anomaly = false;
    switch (operator) {
      case 'gt':
        is_anomaly = actualValue > threshold;
        break;
      case 'gte':
        is_anomaly = actualValue >= threshold;
        break;
      case 'lt':
        is_anomaly = actualValue < threshold;
        break;
      case 'lte':
        is_anomaly = actualValue <= threshold;
        break;
      case 'eq':
        is_anomaly = actualValue === threshold;
        break;
      case 'neq':
        is_anomaly = actualValue !== threshold;
        break;
    }

    return {
      is_anomaly,
      score: is_anomaly ? 1.0 : 0.0,
      expected_value: threshold,
      actual_value: actualValue,
      algorithm: this.name,
      timestamp: new Date().toISOString(),
      details: { operator, threshold },
    };
  }
}

class MovingAverageAlgorithm implements DetectionAlgorithm {
  name = 'moving_average';

  detect(points: number[], config: AnomalyDetectionConfig): AnomalyResult {
    const windowSize = config.baseline_params?.window_size ?? 10;
    const sensitivity = config.sensitivity ?? 2.0;
    const actualValue = points[points.length - 1] ?? 0;

    if (points.length < windowSize + 1) {
      return {
        is_anomaly: false,
        score: 0.0,
        expected_value: actualValue,
        actual_value: actualValue,
        algorithm: this.name,
        timestamp: new Date().toISOString(),
        details: { reason: 'insufficient_data' },
      };
    }

    const historicalValues = points.slice(-windowSize - 1, -1);
    const ma = movingAverage(historicalValues, windowSize);
    const expectedValue = ma[ma.length - 1] ?? average(historicalValues);
    const stdDev = standardDeviation(historicalValues);

    const deviation = Math.abs(actualValue - expectedValue);
    const is_anomaly = deviation > sensitivity * stdDev && stdDev > 0;
    const score = stdDev > 0 ? Math.min(1.0, deviation / (sensitivity * stdDev)) : 0;

    return {
      is_anomaly,
      score,
      expected_value: expectedValue,
      actual_value: actualValue,
      algorithm: this.name,
      timestamp: new Date().toISOString(),
      details: {
        window_size: windowSize,
        sensitivity,
        std_dev: stdDev,
        moving_average: expectedValue,
      },
    };
  }
}

class ExponentialSmoothingAlgorithm implements DetectionAlgorithm {
  name = 'exponential_smoothing';

  detect(points: number[], config: AnomalyDetectionConfig): AnomalyResult {
    const alpha = config.baseline_params?.alpha ?? 0.3;
    const sensitivity = config.sensitivity ?? 2.0;
    const actualValue = points[points.length - 1] ?? 0;

    if (points.length < 2) {
      return {
        is_anomaly: false,
        score: 0.0,
        expected_value: actualValue,
        actual_value: actualValue,
        algorithm: this.name,
        timestamp: new Date().toISOString(),
        details: { reason: 'insufficient_data' },
      };
    }

    const historicalValues = points.slice(0, -1);
    const ema = exponentialMovingAverage(historicalValues, alpha);
    const expectedValue = ema[ema.length - 1] ?? average(historicalValues);
    const stdDev = standardDeviation(historicalValues);

    const deviation = Math.abs(actualValue - expectedValue);
    const is_anomaly = deviation > sensitivity * stdDev && stdDev > 0;
    const score = stdDev > 0 ? Math.min(1.0, deviation / (sensitivity * stdDev)) : 0;

    return {
      is_anomaly,
      score,
      expected_value: expectedValue,
      actual_value: actualValue,
      algorithm: this.name,
      timestamp: new Date().toISOString(),
      details: {
        alpha,
        sensitivity,
        std_dev: stdDev,
        smoothed_value: expectedValue,
      },
    };
  }
}

class ZScoreAlgorithm implements DetectionAlgorithm {
  name = 'z_score';

  detect(points: number[], config: AnomalyDetectionConfig): AnomalyResult {
    const sensitivity = config.sensitivity ?? 3.0;
    const actualValue = points[points.length - 1] ?? 0;

    if (points.length < 2) {
      return {
        is_anomaly: false,
        score: 0.0,
        expected_value: actualValue,
        actual_value: actualValue,
        algorithm: this.name,
        timestamp: new Date().toISOString(),
        details: { reason: 'insufficient_data' },
      };
    }

    const historicalValues = points.slice(0, -1);
    const mean = average(historicalValues);
    const stdDev = standardDeviation(historicalValues);

    const zScore = stdDev > 0 ? (actualValue - mean) / stdDev : 0;
    const is_anomaly = Math.abs(zScore) > sensitivity;
    const score = Math.min(1.0, Math.abs(zScore) / sensitivity);

    return {
      is_anomaly,
      score,
      expected_value: mean,
      actual_value: actualValue,
      algorithm: this.name,
      timestamp: new Date().toISOString(),
      details: {
        z_score: zScore,
        mean,
        std_dev: stdDev,
        sensitivity,
      },
    };
  }
}

class IsolationForestAlgorithm implements DetectionAlgorithm {
  name = 'isolation_forest';

  detect(points: number[], config: AnomalyDetectionConfig): AnomalyResult {
    const actualValue = points[points.length - 1] ?? 0;
    const contamination = config.baseline_params?.contamination ?? 0.1;
    const nEstimators = config.baseline_params?.n_estimators ?? 10;

    if (points.length < 10) {
      return {
        is_anomaly: false,
        score: 0.0,
        expected_value: actualValue,
        actual_value: actualValue,
        algorithm: this.name,
        timestamp: new Date().toISOString(),
        details: { reason: 'insufficient_data' },
      };
    }

    const historicalValues = points.slice(0, -1);
    const mean = average(historicalValues);
    const stdDev = standardDeviation(historicalValues);

    const pathLengths: number[] = [];
    for (let i = 0; i < nEstimators; i++) {
      const sample = this.bootstrapSample(historicalValues);
      const pathLength = this.getPathLength(actualValue, sample, 0);
      pathLengths.push(pathLength);
    }

    const avgPathLength = average(pathLengths);
    const expectedPathLength = this.getExpectedPathLength(historicalValues.length);
    const score = expectedPathLength > 0 ? Math.pow(2, -avgPathLength / expectedPathLength) : 0;

    const is_anomaly = score > 1 - contamination;

    return {
      is_anomaly,
      score,
      expected_value: mean,
      actual_value: actualValue,
      algorithm: this.name,
      timestamp: new Date().toISOString(),
      details: {
        n_estimators: nEstimators,
        contamination,
        avg_path_length: avgPathLength,
        mean,
        std_dev: stdDev,
      },
    };
  }

  private bootstrapSample(values: number[]): number[] {
    const sampleSize = Math.min(values.length, Math.floor(values.length * 0.8));
    const sample: number[] = [];
    for (let i = 0; i < sampleSize; i++) {
      const randomIndex = Math.floor(Math.random() * values.length);
      sample.push(values[randomIndex]);
    }
    return sample.sort((a, b) => a - b);
  }

  private getPathLength(value: number, sample: number[], depth: number): number {
    if (sample.length <= 1) {
      return depth;
    }

    const min = sample[0];
    const max = sample[sample.length - 1];
    const splitValue = min + Math.random() * (max - min);

    const left = sample.filter((v) => v < splitValue);
    const right = sample.filter((v) => v >= splitValue);

    if (value < splitValue) {
      return this.getPathLength(value, left, depth + 1);
    } else {
      return this.getPathLength(value, right, depth + 1);
    }
  }

  private getExpectedPathLength(n: number): number {
    if (n <= 1) return 0;
    const harmonic = Math.log(n - 1) + 0.5772156649;
    return 2 * harmonic - (2 * (n - 1)) / n;
  }
}

interface AnomalyDetectionEvents {
  'anomaly.detected': AnomalyResult;
  'baseline.updated': { metric_name: string; baseline: BaselineModel };
}

export class AnomalyDetector extends EventEmitter<AnomalyDetectionEvents> {
  private algorithms: Map<string, DetectionAlgorithm>;
  private baselines: Map<string, BaselineModel>;
  private dataPoints: Map<string, number[]>;
  private maxDataPoints: number;

  constructor(maxDataPoints: number = 10000) {
    super();
    this.algorithms = new Map();
    this.baselines = new Map();
    this.dataPoints = new Map();
    this.maxDataPoints = maxDataPoints;

    this.registerAlgorithm(new StaticThresholdAlgorithm());
    this.registerAlgorithm(new MovingAverageAlgorithm());
    this.registerAlgorithm(new ExponentialSmoothingAlgorithm());
    this.registerAlgorithm(new ZScoreAlgorithm());
    this.registerAlgorithm(new IsolationForestAlgorithm());
  }

  registerAlgorithm(algorithm: DetectionAlgorithm): void {
    this.algorithms.set(algorithm.name, algorithm);
    logger.info(`Registered anomaly detection algorithm: ${algorithm.name}`);
  }

  unregisterAlgorithm(name: string): boolean {
    return this.algorithms.delete(name);
  }

  addDataPoint(metricName: string, value: number): void {
    if (!this.dataPoints.has(metricName)) {
      this.dataPoints.set(metricName, []);
    }
    const points = this.dataPoints.get(metricName)!;
    points.push(value);
    if (points.length > this.maxDataPoints) {
      points.shift();
    }
  }

  addTimeSeriesPoints(metricName: string, points: TimeSeriesPoint[]): void {
    for (const point of points) {
      this.addDataPoint(metricName, point.value);
    }
  }

  detect(metricName: string, config: AnomalyDetectionConfig): AnomalyResult | null {
    const points = this.dataPoints.get(metricName);
    if (!points || points.length === 0) {
      return null;
    }

    const algorithm = this.algorithms.get(config.algorithm);
    if (!algorithm) {
      logger.error(`Unknown anomaly detection algorithm: ${config.algorithm}`);
      return null;
    }

    const lookbackMs = parseDuration(config.lookback_period);
    const lookbackPoints = Math.min(points.length, Math.floor(lookbackMs / 60000));
    const dataSlice = points.slice(-lookbackPoints);

    const result = algorithm.detect(dataSlice, config);

    if (result.is_anomaly) {
      logger.warn(`Anomaly detected for metric ${metricName}: score=${result.score.toFixed(3)}`);
      this.emit('anomaly.detected', result);
    }

    return result;
  }

  detectWithMultipleAlgorithms(
    metricName: string,
    configs: AnomalyDetectionConfig[]
  ): AnomalyResult[] {
    const results: AnomalyResult[] = [];
    for (const config of configs) {
      const result = this.detect(metricName, config);
      if (result) {
        results.push(result);
      }
    }
    return results;
  }

  buildBaseline(metricName: string): BaselineModel | null {
    const points = this.dataPoints.get(metricName);
    if (!points || points.length < 2) {
      return null;
    }

    const mean = average(points);
    const stdDev = standardDeviation(points);
    const trend = movingAverage(points, Math.min(10, Math.floor(points.length / 10)));

    const baseline: BaselineModel = {
      metric_name: metricName,
      mean,
      std_dev: stdDev,
      min: Math.min(...points),
      max: Math.max(...points),
      trend,
      last_updated: new Date().toISOString(),
    };

    this.baselines.set(metricName, baseline);
    this.emit('baseline.updated', { metric_name: metricName, baseline });
    logger.info(`Built baseline for metric ${metricName}: mean=${mean.toFixed(2)}, std_dev=${stdDev.toFixed(2)}`);

    return baseline;
  }

  getBaseline(metricName: string): BaselineModel | undefined {
    return this.baselines.get(metricName);
  }

  updateBaseline(metricName: string, value: number): void {
    this.addDataPoint(metricName, value);
    this.buildBaseline(metricName);
  }

  getAlgorithmNames(): string[] {
    return Array.from(this.algorithms.keys());
  }

  clear(): void {
    this.algorithms.clear();
    this.baselines.clear();
    this.dataPoints.clear();
  }
}

const anomalyDetector = new AnomalyDetector();

export default anomalyDetector;
