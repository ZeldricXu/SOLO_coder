import { EventEmitter } from 'events';
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
declare class ModelEvaluationService extends EventEmitter {
    private evaluations;
    private driftResults;
    private metricDefinitions;
    private onlineConfigs;
    private baselineData;
    private currentData;
    private maxDataPoints;
    constructor();
    private registerDefaultMetrics;
    registerMetric(definition: MetricDefinition): void;
    createOfflineEvaluation(modelId: string, modelName: string, version: string, datasetId: string, metrics: Record<string, number>, baselineMetrics?: Record<string, number>): ModelEvaluation;
    startOnlineEvaluation(modelId: string, modelName: string, version: string): ModelEvaluation;
    completeOnlineEvaluation(evalId: string, metrics: Record<string, number>): ModelEvaluation | null;
    recordPrediction(modelId: string, prediction: number, groundTruth?: number, timestamp?: number): void;
    setBaseline(modelId: string, featureName: string, values: number[]): void;
    detectDrift(modelId: string, featureName: string, currentValues: number[], threshold?: number): DriftDetectionResult;
    detectAllDrifts(modelId: string, threshold?: number): DriftDetectionResult[];
    private calculateStats;
    compareEvaluations(evalIdA: string, evalIdB: string): Record<string, {
        a: number;
        b: number;
        delta: number;
        deltaPercent: number;
    }> | null;
    configureOnlineMonitoring(config: OnlineMonitoringConfig): void;
    getOnlineMonitoringConfig(modelId: string): OnlineMonitoringConfig | null;
    getEvaluation(evalId: string): ModelEvaluation | null;
    listEvaluations(modelId?: string, evalType?: 'offline' | 'online'): ModelEvaluation[];
    getDriftHistory(modelId: string, limit?: number): DriftDetectionResult[];
    getMetricDefinitions(): MetricDefinition[];
    calculateAccuracy(predictions: number[], groundTruth: number[]): number;
    calculatePrecisionRecallF1(predictions: number[], groundTruth: number[], positiveClass?: number): {
        precision: number;
        recall: number;
        f1: number;
    };
    generateEvaluationReport(evalId: string): Record<string, unknown> | null;
}
export declare const evaluationService: ModelEvaluationService;
export { ModelEvaluationService, ModelEvaluation, DriftDetectionResult, MetricDefinition, OnlineMonitoringConfig };
//# sourceMappingURL=index.d.ts.map