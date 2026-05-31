import { EventEmitter } from 'events';
import { generateId, nowISO, calculateDrift, calculatePercentiles } from '../shared/utils';
import { logger } from '../logging';
import { monitoring } from '../monitoring';

interface ModelEvaluation {
  eval_id: string;
  model_id: string;
  model_name: string;
  version: string;
  eval_type: 'offline' | 'online';
  dataset_id?: string;
  metrics: Record<string, number>;
  baseline_metrics?: Record<string, number>;
  status: 'running' | 'completed' | 'failed';
  started_at: string;
  completed_at?: string;
  error_detail?: string;
  metadata: Record<string, unknown>;
}

interface DriftDetectionResult {
  drift_id: string;
  model_id: string;
  feature_name: string;
  drift_score: number;
  significance: 'low' | 'medium' | 'high';
  threshold: number;
  timestamp: string;
  baseline_stats: {
    mean: number;
    std: number;
    sample_count: number;
  };
  current_stats: {
    mean: number;
    std: number;
    sample_count: number;
  };
  is_alert: boolean;
}

interface MetricDefinition {
  name: string;
  description: string;
  type: 'accuracy' | 'precision' | 'recall' | 'f1' | 'auc' | 'mae' | 'mse' | 'rmse' | 'custom';
  higher_is_better: boolean;
  threshold?: {
    warning: number;
    critical: number;
  };
}

interface OnlineMonitoringConfig {
  model_id: string;
  enabled: boolean;
  metrics: string[];
  sampling_rate: number;
  drift_threshold: number;
  alert_channels: string[];
}

class ModelEvaluationService extends EventEmitter {
  private evaluations: Map<string, ModelEvaluation> = new Map();
  private driftResults: Map<string, DriftDetectionResult[]> = new Map();
  private metricDefinitions: Map<string, MetricDefinition> = new Map();
  private onlineConfigs: Map<string, OnlineMonitoringConfig> = new Map();
  private baselineData: Map<string, Map<string, number[]>> = new Map();
  private currentData: Map<string, Map<string, number[]>> = new Map();
  private maxDataPoints = 1000;

  constructor() {
    super();
    this.registerDefaultMetrics();
  }

  private registerDefaultMetrics(): void {
    const defaults: MetricDefinition[] = [
      { name: 'accuracy', description: 'Overall classification accuracy', type: 'accuracy', higher_is_better: true },
      { name: 'precision', description: 'Precision score', type: 'precision', higher_is_better: true },
      { name: 'recall', description: 'Recall score', type: 'recall', higher_is_better: true },
      { name: 'f1', description: 'F1 score', type: 'f1', higher_is_better: true },
      { name: 'auc', description: 'Area under ROC curve', type: 'auc', higher_is_better: true },
      { name: 'mae', description: 'Mean absolute error', type: 'mae', higher_is_better: false },
      { name: 'mse', description: 'Mean squared error', type: 'mse', higher_is_better: false },
      { name: 'rmse', description: 'Root mean squared error', type: 'rmse', higher_is_better: false },
    ];

    for (const def of defaults) {
      this.metricDefinitions.set(def.name, def);
    }
  }

  registerMetric(definition: MetricDefinition): void {
    this.metricDefinitions.set(definition.name, definition);
    logger.info('Metric registered', { metric_name: definition.name });
    this.emit('metric.registered', definition);
  }

  createOfflineEvaluation(
    modelId: string,
    modelName: string,
    version: string,
    datasetId: string,
    metrics: Record<string, number>,
    baselineMetrics?: Record<string, number>
  ): ModelEvaluation {
    const evalId = generateId('eval');
    const evaluation: ModelEvaluation = {
      eval_id: evalId,
      model_id: modelId,
      model_name: modelName,
      version,
      eval_type: 'offline',
      dataset_id: datasetId,
      metrics,
      baseline_metrics: baselineMetrics,
      status: 'completed',
      started_at: nowISO(),
      completed_at: nowISO(),
      metadata: {},
    };

    this.evaluations.set(evalId, evaluation);
    logger.info('Offline evaluation created', { eval_id: evalId, model_id: modelId, version });
    this.emit('evaluation.created', evaluation);

    return evaluation;
  }

  startOnlineEvaluation(
    modelId: string,
    modelName: string,
    version: string
  ): ModelEvaluation {
    const evalId = generateId('eval');
    const evaluation: ModelEvaluation = {
      eval_id: evalId,
      model_id: modelId,
      model_name: modelName,
      version,
      eval_type: 'online',
      metrics: {},
      status: 'running',
      started_at: nowISO(),
      metadata: {},
    };

    this.evaluations.set(evalId, evaluation);
    logger.info('Online evaluation started', { eval_id: evalId, model_id: modelId, version });
    this.emit('evaluation.started', evaluation);

    return evaluation;
  }

  completeOnlineEvaluation(
    evalId: string,
    metrics: Record<string, number>
  ): ModelEvaluation | null {
    const evaluation = this.evaluations.get(evalId);
    if (!evaluation || evaluation.eval_type !== 'online') {
      return null;
    }

    evaluation.metrics = metrics;
    evaluation.status = 'completed';
    evaluation.completed_at = nowISO();

    logger.info('Online evaluation completed', { eval_id: evalId, model_id: evaluation.model_id });
    this.emit('evaluation.completed', evaluation);

    return evaluation;
  }

  recordPrediction(
    modelId: string,
    prediction: number,
    groundTruth?: number,
    timestamp?: number
  ): void {
    if (!this.currentData.has(modelId)) {
      this.currentData.set(modelId, new Map());
    }
    const modelData = this.currentData.get(modelId)!;

    if (!modelData.has('predictions')) {
      modelData.set('predictions', []);
    }
    const predictions = modelData.get('predictions')!;
    predictions.push(prediction);
    if (predictions.length > this.maxDataPoints) {
      predictions.shift();
    }

    if (groundTruth !== undefined) {
      if (!modelData.has('ground_truth')) {
        modelData.set('ground_truth', []);
      }
      const groundTruths = modelData.get('ground_truth')!;
      groundTruths.push(groundTruth);
      if (groundTruths.length > this.maxDataPoints) {
        groundTruths.shift();
      }
    }

    monitoring.incrementCounter('predictions_recorded', 1, { model_id: modelId });
  }

  setBaseline(modelId: string, featureName: string, values: number[]): void {
    if (!this.baselineData.has(modelId)) {
      this.baselineData.set(modelId, new Map());
    }
    this.baselineData.get(modelId)!.set(featureName, [...values]);
    logger.info('Baseline data set', { model_id: modelId, feature: featureName, sample_count: values.length });
  }

  detectDrift(
    modelId: string,
    featureName: string,
    currentValues: number[],
    threshold: number = 0.5
  ): DriftDetectionResult {
    const baselineValues = this.baselineData.get(modelId)?.get(featureName) || [];
    const driftScore = calculateDrift(baselineValues, currentValues);

    const baselineStats = this.calculateStats(baselineValues);
    const currentStats = this.calculateStats(currentValues);

    let significance: 'low' | 'medium' | 'high' = 'low';
    if (driftScore >= threshold * 2) {
      significance = 'high';
    } else if (driftScore >= threshold) {
      significance = 'medium';
    }

    const result: DriftDetectionResult = {
      drift_id: generateId('drift'),
      model_id: modelId,
      feature_name: featureName,
      drift_score: driftScore,
      significance,
      threshold,
      timestamp: nowISO(),
      baseline_stats: baselineStats,
      current_stats: currentStats,
      is_alert: driftScore >= threshold,
    };

    if (!this.driftResults.has(modelId)) {
      this.driftResults.set(modelId, []);
    }
    this.driftResults.get(modelId)!.push(result);

    if (result.is_alert) {
      logger.warn('Drift detected', {
        model_id: modelId,
        feature: featureName,
        drift_score: driftScore,
        significance,
      });
      this.emit('drift.alert', result);
    }

    monitoring.recordLatency('drift_score', driftScore * 1000, { model_id: modelId, feature: featureName });
    this.emit('drift.detected', result);

    return result;
  }

  detectAllDrifts(modelId: string, threshold: number = 0.5): DriftDetectionResult[] {
    const baselineFeatures = this.baselineData.get(modelId);
    if (!baselineFeatures) return [];

    const results: DriftDetectionResult[] = [];
    for (const [featureName] of baselineFeatures.entries()) {
      const currentValues = this.currentData.get(modelId)?.get(featureName) || [];
      if (currentValues.length > 0) {
        results.push(this.detectDrift(modelId, featureName, currentValues, threshold));
      }
    }
    return results;
  }

  private calculateStats(values: number[]): { mean: number; std: number; sample_count: number } {
    if (values.length === 0) {
      return { mean: 0, std: 0, sample_count: 0 };
    }
    const mean = values.reduce((a, b) => a + b, 0) / values.length;
    const variance = values.reduce((a, b) => a + Math.pow(b - mean, 2), 0) / values.length;
    return {
      mean,
      std: Math.sqrt(variance),
      sample_count: values.length,
    };
  }

  compareEvaluations(evalIdA: string, evalIdB: string): Record<string, { a: number; b: number; delta: number; deltaPercent: number }> | null {
    const evalA = this.evaluations.get(evalIdA);
    const evalB = this.evaluations.get(evalIdB);

    if (!evalA || !evalB) return null;

    const allMetrics = new Set([...Object.keys(evalA.metrics), ...Object.keys(evalB.metrics)]);
    const comparison: Record<string, { a: number; b: number; delta: number; deltaPercent: number }> = {};

    for (const metric of allMetrics) {
      const a = evalA.metrics[metric] || 0;
      const b = evalB.metrics[metric] || 0;
      const delta = b - a;
      const deltaPercent = a !== 0 ? (delta / a) * 100 : b !== 0 ? 100 : 0;
      comparison[metric] = { a, b, delta, deltaPercent };
    }

    return comparison;
  }

  configureOnlineMonitoring(config: OnlineMonitoringConfig): void {
    this.onlineConfigs.set(config.model_id, config);
    logger.info('Online monitoring configured', { model_id: config.model_id, enabled: config.enabled });
  }

  getOnlineMonitoringConfig(modelId: string): OnlineMonitoringConfig | null {
    return this.onlineConfigs.get(modelId) || null;
  }

  getEvaluation(evalId: string): ModelEvaluation | null {
    return this.evaluations.get(evalId) || null;
  }

  listEvaluations(modelId?: string, evalType?: 'offline' | 'online'): ModelEvaluation[] {
    let evals = Array.from(this.evaluations.values());
    if (modelId) {
      evals = evals.filter((e) => e.model_id === modelId);
    }
    if (evalType) {
      evals = evals.filter((e) => e.eval_type === evalType);
    }
    return evals.sort((a, b) => new Date(b.started_at).getTime() - new Date(a.started_at).getTime());
  }

  getDriftHistory(modelId: string, limit?: number): DriftDetectionResult[] {
    const history = this.driftResults.get(modelId) || [];
    const sorted = [...history].sort((a, b) => new Date(b.timestamp).getTime() - new Date(a.timestamp).getTime());
    return limit ? sorted.slice(0, limit) : sorted;
  }

  getMetricDefinitions(): MetricDefinition[] {
    return Array.from(this.metricDefinitions.values());
  }

  calculateAccuracy(predictions: number[], groundTruth: number[]): number {
    if (predictions.length === 0 || predictions.length !== groundTruth.length) return 0;
    let correct = 0;
    for (let i = 0; i < predictions.length; i++) {
      if (Math.round(predictions[i]) === Math.round(groundTruth[i])) {
        correct++;
      }
    }
    return correct / predictions.length;
  }

  calculatePrecisionRecallF1(
    predictions: number[],
    groundTruth: number[],
    positiveClass: number = 1
  ): { precision: number; recall: number; f1: number } {
    let tp = 0, fp = 0, fn = 0;
    for (let i = 0; i < predictions.length; i++) {
      const pred = Math.round(predictions[i]);
      const actual = Math.round(groundTruth[i]);
      if (pred === positiveClass && actual === positiveClass) tp++;
      else if (pred === positiveClass && actual !== positiveClass) fp++;
      else if (pred !== positiveClass && actual === positiveClass) fn++;
    }

    const precision = tp + fp > 0 ? tp / (tp + fp) : 0;
    const recall = tp + fn > 0 ? tp / (tp + fn) : 0;
    const f1 = precision + recall > 0 ? 2 * (precision * recall) / (precision + recall) : 0;

    return { precision, recall, f1 };
  }

  generateEvaluationReport(evalId: string): Record<string, unknown> | null {
    const evaluation = this.evaluations.get(evalId);
    if (!evaluation) return null;

    const report: Record<string, unknown> = {
      eval_id: evaluation.eval_id,
      model_id: evaluation.model_id,
      model_name: evaluation.model_name,
      version: evaluation.version,
      eval_type: evaluation.eval_type,
      status: evaluation.status,
      started_at: evaluation.started_at,
      completed_at: evaluation.completed_at,
      metrics_summary: {},
    };

    for (const [name, value] of Object.entries(evaluation.metrics)) {
      const def = this.metricDefinitions.get(name);
      (report.metrics_summary as Record<string, unknown>)[name] = {
        value,
        description: def?.description || name,
        higher_is_better: def?.higher_is_better ?? true,
      };
    }

    if (evaluation.baseline_metrics) {
      report.baseline_comparison = {};
      for (const [name, value] of Object.entries(evaluation.metrics)) {
        const baseline = evaluation.baseline_metrics[name] || 0;
        (report.baseline_comparison as Record<string, unknown>)[name] = {
          current: value,
          baseline,
          improvement: value - baseline,
          improvement_percent: baseline !== 0 ? ((value - baseline) / baseline) * 100 : 0,
        };
      }
    }

    return report;
  }
}

export const evaluationService = new ModelEvaluationService();
export { ModelEvaluationService, ModelEvaluation, DriftDetectionResult, MetricDefinition, OnlineMonitoringConfig };
