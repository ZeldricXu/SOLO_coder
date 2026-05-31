"use strict";
Object.defineProperty(exports, "__esModule", { value: true });
const express_1 = require("express");
const evaluation_1 = require("../evaluation");
const logging_1 = require("../logging");
const router = (0, express_1.Router)();
router.post('/offline', (req, res) => {
    try {
        const { model_id, model_name, version, dataset_id, metrics, baseline_metrics } = req.body;
        const evaluation = evaluation_1.evaluationService.createOfflineEvaluation(model_id, model_name, version, dataset_id, metrics, baseline_metrics);
        res.status(201).json({ code: 201, data: evaluation });
    }
    catch (error) {
        logging_1.logger.error('Offline evaluation creation failed', { error: error.message });
        res.status(400).json({ code: 400, error: error.message });
    }
});
router.post('/online/start', (req, res) => {
    try {
        const { model_id, model_name, version } = req.body;
        const evaluation = evaluation_1.evaluationService.startOnlineEvaluation(model_id, model_name, version);
        res.status(201).json({ code: 201, data: evaluation });
    }
    catch (error) {
        logging_1.logger.error('Online evaluation start failed', { error: error.message });
        res.status(400).json({ code: 400, error: error.message });
    }
});
router.post('/online/:id/complete', (req, res) => {
    try {
        const { metrics } = req.body;
        const evaluation = evaluation_1.evaluationService.completeOnlineEvaluation(req.params.id, metrics);
        if (!evaluation) {
            res.status(404).json({ code: 404, error: 'Evaluation not found' });
            return;
        }
        res.json({ code: 200, data: evaluation });
    }
    catch (error) {
        logging_1.logger.error('Online evaluation completion failed', { error: error.message });
        res.status(400).json({ code: 400, error: error.message });
    }
});
router.get('/', (req, res) => {
    const model_id = req.query.model_id;
    const eval_type = req.query.eval_type;
    const evaluations = evaluation_1.evaluationService.listEvaluations(model_id, eval_type);
    res.json({ code: 200, data: evaluations });
});
router.get('/metrics', (req, res) => {
    const metrics = evaluation_1.evaluationService.getMetricDefinitions();
    res.json({ code: 200, data: metrics });
});
router.get('/:id', (req, res) => {
    const evaluation = evaluation_1.evaluationService.getEvaluation(req.params.id);
    if (!evaluation) {
        res.status(404).json({ code: 404, error: 'Evaluation not found' });
        return;
    }
    res.json({ code: 200, data: evaluation });
});
router.get('/:id/report', (req, res) => {
    const report = evaluation_1.evaluationService.generateEvaluationReport(req.params.id);
    if (!report) {
        res.status(404).json({ code: 404, error: 'Evaluation not found' });
        return;
    }
    res.json({ code: 200, data: report });
});
router.get('/compare', (req, res) => {
    const eval_a = req.query.eval_a;
    const eval_b = req.query.eval_b;
    if (!eval_a || !eval_b) {
        res.status(400).json({ code: 400, error: 'Both eval_a and eval_b parameters are required' });
        return;
    }
    const comparison = evaluation_1.evaluationService.compareEvaluations(eval_a, eval_b);
    if (!comparison) {
        res.status(404).json({ code: 404, error: 'One or both evaluations not found' });
        return;
    }
    res.json({ code: 200, data: comparison });
});
router.post('/drift/detect', (req, res) => {
    try {
        const { model_id, feature_name, current_values, threshold } = req.body;
        const result = evaluation_1.evaluationService.detectDrift(model_id, feature_name, current_values, threshold);
        res.json({ code: 200, data: result });
    }
    catch (error) {
        logging_1.logger.error('Drift detection failed', { error: error.message });
        res.status(400).json({ code: 400, error: error.message });
    }
});
router.post('/drift/baseline', (req, res) => {
    try {
        const { model_id, feature_name, values } = req.body;
        evaluation_1.evaluationService.setBaseline(model_id, feature_name, values);
        res.json({ code: 200, message: 'Baseline set successfully' });
    }
    catch (error) {
        logging_1.logger.error('Setting baseline failed', { error: error.message });
        res.status(400).json({ code: 400, error: error.message });
    }
});
router.get('/drift/history/:model_id', (req, res) => {
    const limit = req.query.limit ? parseInt(req.query.limit, 10) : undefined;
    const history = evaluation_1.evaluationService.getDriftHistory(req.params.model_id, limit);
    res.json({ code: 200, data: history });
});
router.post('/monitoring/config', (req, res) => {
    try {
        evaluation_1.evaluationService.configureOnlineMonitoring(req.body);
        res.json({ code: 200, message: 'Monitoring configuration updated' });
    }
    catch (error) {
        logging_1.logger.error('Monitoring config update failed', { error: error.message });
        res.status(400).json({ code: 400, error: error.message });
    }
});
router.get('/monitoring/config/:model_id', (req, res) => {
    const config = evaluation_1.evaluationService.getOnlineMonitoringConfig(req.params.model_id);
    if (!config) {
        res.status(404).json({ code: 404, error: 'Monitoring configuration not found' });
        return;
    }
    res.json({ code: 200, data: config });
});
router.post('/predictions', (req, res) => {
    try {
        const { model_id, prediction, ground_truth } = req.body;
        evaluation_1.evaluationService.recordPrediction(model_id, prediction, ground_truth);
        res.json({ code: 200, message: 'Prediction recorded' });
    }
    catch (error) {
        logging_1.logger.error('Recording prediction failed', { error: error.message });
        res.status(400).json({ code: 400, error: error.message });
    }
});
exports.default = router;
//# sourceMappingURL=evaluation.js.map