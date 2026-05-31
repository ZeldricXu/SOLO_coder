"use strict";
Object.defineProperty(exports, "__esModule", { value: true });
exports.ModelEvaluationService = exports.evaluationService = void 0;
const events_1 = require("events");
const utils_1 = require("../shared/utils");
const logging_1 = require("../logging");
const monitoring_1 = require("../monitoring");
class ModelEvaluationService extends events_1.EventEmitter {
    evaluations = new Map();
    driftResults = new Map();
    metricDefinitions = new Map();
    onlineConfigs = new Map();
    baselineData = new Map();
    currentData = new Map();
    maxDataPoints = 1000;
    constructor() {
        super();
        this.registerDefaultMetrics();
    }
    registerDefaultMetrics() {
        const defaults = [
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
    registerMetric(definition) {
        this.metricDefinitions.set(definition.name, definition);
        logging_1.logger.info('Metric registered', { metric_name: definition.name });
        this.emit('metric.registered', definition);
    }
    createOfflineEvaluation(modelId, modelName, version, datasetId, metrics, baselineMetrics) {
        const evalId = (0, utils_1.generateId)('eval');
        const evaluation = {
            eval_id: evalId,
            model_id: modelId,
            model_name: modelName,
            version,
            eval_type: 'offline',
            dataset_id: datasetId,
            metrics,
            baseline_metrics: baselineMetrics,
            status: 'completed',
            started_at: (0, utils_1.nowISO)(),
            completed_at: (0, utils_1.nowISO)(),
            metadata: {},
        };
        this.evaluations.set(evalId, evaluation);
        logging_1.logger.info('Offline evaluation created', { eval_id: evalId, model_id: modelId, version });
        this.emit('evaluation.created', evaluation);
        return evaluation;
    }
    startOnlineEvaluation(modelId, modelName, version) {
        const evalId = (0, utils_1.generateId)('eval');
        const evaluation = {
            eval_id: evalId,
            model_id: modelId,
            model_name: modelName,
            version,
            eval_type: 'online',
            metrics: {},
            status: 'running',
            started_at: (0, utils_1.nowISO)(),
            metadata: {},
        };
        this.evaluations.set(evalId, evaluation);
        logging_1.logger.info('Online evaluation started', { eval_id: evalId, model_id: modelId, version });
        this.emit('evaluation.started', evaluation);
        return evaluation;
    }
    completeOnlineEvaluation(evalId, metrics) {
        const evaluation = this.evaluations.get(evalId);
        if (!evaluation || evaluation.eval_type !== 'online') {
            return null;
        }
        evaluation.metrics = metrics;
        evaluation.status = 'completed';
        evaluation.completed_at = (0, utils_1.nowISO)();
        logging_1.logger.info('Online evaluation completed', { eval_id: evalId, model_id: evaluation.model_id });
        this.emit('evaluation.completed', evaluation);
        return evaluation;
    }
    recordPrediction(modelId, prediction, groundTruth, timestamp) {
        if (!this.currentData.has(modelId)) {
            this.currentData.set(modelId, new Map());
        }
        const modelData = this.currentData.get(modelId);
        if (!modelData.has('predictions')) {
            modelData.set('predictions', []);
        }
        const predictions = modelData.get('predictions');
        predictions.push(prediction);
        if (predictions.length > this.maxDataPoints) {
            predictions.shift();
        }
        if (groundTruth !== undefined) {
            if (!modelData.has('ground_truth')) {
                modelData.set('ground_truth', []);
            }
            const groundTruths = modelData.get('ground_truth');
            groundTruths.push(groundTruth);
            if (groundTruths.length > this.maxDataPoints) {
                groundTruths.shift();
            }
        }
        monitoring_1.monitoring.incrementCounter('predictions_recorded', 1, { model_id: modelId });
    }
    setBaseline(modelId, featureName, values) {
        if (!this.baselineData.has(modelId)) {
            this.baselineData.set(modelId, new Map());
        }
        this.baselineData.get(modelId).set(featureName, [...values]);
        logging_1.logger.info('Baseline data set', { model_id: modelId, feature: featureName, sample_count: values.length });
    }
    detectDrift(modelId, featureName, currentValues, threshold = 0.5) {
        const baselineValues = this.baselineData.get(modelId)?.get(featureName) || [];
        const driftScore = (0, utils_1.calculateDrift)(baselineValues, currentValues);
        const baselineStats = this.calculateStats(baselineValues);
        const currentStats = this.calculateStats(currentValues);
        let significance = 'low';
        if (driftScore >= threshold * 2) {
            significance = 'high';
        }
        else if (driftScore >= threshold) {
            significance = 'medium';
        }
        const result = {
            drift_id: (0, utils_1.generateId)('drift'),
            model_id: modelId,
            feature_name: featureName,
            drift_score: driftScore,
            significance,
            threshold,
            timestamp: (0, utils_1.nowISO)(),
            baseline_stats: baselineStats,
            current_stats: currentStats,
            is_alert: driftScore >= threshold,
        };
        if (!this.driftResults.has(modelId)) {
            this.driftResults.set(modelId, []);
        }
        this.driftResults.get(modelId).push(result);
        if (result.is_alert) {
            logging_1.logger.warn('Drift detected', {
                model_id: modelId,
                feature: featureName,
                drift_score: driftScore,
                significance,
            });
            this.emit('drift.alert', result);
        }
        monitoring_1.monitoring.recordLatency('drift_score', driftScore * 1000, { model_id: modelId, feature: featureName });
        this.emit('drift.detected', result);
        return result;
    }
    detectAllDrifts(modelId, threshold = 0.5) {
        const baselineFeatures = this.baselineData.get(modelId);
        if (!baselineFeatures)
            return [];
        const results = [];
        for (const [featureName] of baselineFeatures.entries()) {
            const currentValues = this.currentData.get(modelId)?.get(featureName) || [];
            if (currentValues.length > 0) {
                results.push(this.detectDrift(modelId, featureName, currentValues, threshold));
            }
        }
        return results;
    }
    calculateStats(values) {
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
    compareEvaluations(evalIdA, evalIdB) {
        const evalA = this.evaluations.get(evalIdA);
        const evalB = this.evaluations.get(evalIdB);
        if (!evalA || !evalB)
            return null;
        const allMetrics = new Set([...Object.keys(evalA.metrics), ...Object.keys(evalB.metrics)]);
        const comparison = {};
        for (const metric of allMetrics) {
            const a = evalA.metrics[metric] || 0;
            const b = evalB.metrics[metric] || 0;
            const delta = b - a;
            const deltaPercent = a !== 0 ? (delta / a) * 100 : b !== 0 ? 100 : 0;
            comparison[metric] = { a, b, delta, deltaPercent };
        }
        return comparison;
    }
    configureOnlineMonitoring(config) {
        this.onlineConfigs.set(config.model_id, config);
        logging_1.logger.info('Online monitoring configured', { model_id: config.model_id, enabled: config.enabled });
    }
    getOnlineMonitoringConfig(modelId) {
        return this.onlineConfigs.get(modelId) || null;
    }
    getEvaluation(evalId) {
        return this.evaluations.get(evalId) || null;
    }
    listEvaluations(modelId, evalType) {
        let evals = Array.from(this.evaluations.values());
        if (modelId) {
            evals = evals.filter((e) => e.model_id === modelId);
        }
        if (evalType) {
            evals = evals.filter((e) => e.eval_type === evalType);
        }
        return evals.sort((a, b) => new Date(b.started_at).getTime() - new Date(a.started_at).getTime());
    }
    getDriftHistory(modelId, limit) {
        const history = this.driftResults.get(modelId) || [];
        const sorted = [...history].sort((a, b) => new Date(b.timestamp).getTime() - new Date(a.timestamp).getTime());
        return limit ? sorted.slice(0, limit) : sorted;
    }
    getMetricDefinitions() {
        return Array.from(this.metricDefinitions.values());
    }
    calculateAccuracy(predictions, groundTruth) {
        if (predictions.length === 0 || predictions.length !== groundTruth.length)
            return 0;
        let correct = 0;
        for (let i = 0; i < predictions.length; i++) {
            if (Math.round(predictions[i]) === Math.round(groundTruth[i])) {
                correct++;
            }
        }
        return correct / predictions.length;
    }
    calculatePrecisionRecallF1(predictions, groundTruth, positiveClass = 1) {
        let tp = 0, fp = 0, fn = 0;
        for (let i = 0; i < predictions.length; i++) {
            const pred = Math.round(predictions[i]);
            const actual = Math.round(groundTruth[i]);
            if (pred === positiveClass && actual === positiveClass)
                tp++;
            else if (pred === positiveClass && actual !== positiveClass)
                fp++;
            else if (pred !== positiveClass && actual === positiveClass)
                fn++;
        }
        const precision = tp + fp > 0 ? tp / (tp + fp) : 0;
        const recall = tp + fn > 0 ? tp / (tp + fn) : 0;
        const f1 = precision + recall > 0 ? 2 * (precision * recall) / (precision + recall) : 0;
        return { precision, recall, f1 };
    }
    generateEvaluationReport(evalId) {
        const evaluation = this.evaluations.get(evalId);
        if (!evaluation)
            return null;
        const report = {
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
            report.metrics_summary[name] = {
                value,
                description: def?.description || name,
                higher_is_better: def?.higher_is_better ?? true,
            };
        }
        if (evaluation.baseline_metrics) {
            report.baseline_comparison = {};
            for (const [name, value] of Object.entries(evaluation.metrics)) {
                const baseline = evaluation.baseline_metrics[name] || 0;
                report.baseline_comparison[name] = {
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
exports.ModelEvaluationService = ModelEvaluationService;
exports.evaluationService = new ModelEvaluationService();
//# sourceMappingURL=index.js.map