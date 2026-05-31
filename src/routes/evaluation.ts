import { Router, Request, Response } from 'express';
import { evaluationService } from '../evaluation';
import { logger } from '../logging';

const router = Router();

router.post('/offline', (req: Request, res: Response) => {
  try {
    const { model_id, model_name, version, dataset_id, metrics, baseline_metrics } = req.body;
    const evaluation = evaluationService.createOfflineEvaluation(
      model_id,
      model_name,
      version,
      dataset_id,
      metrics,
      baseline_metrics
    );
    res.status(201).json({ code: 201, data: evaluation });
  } catch (error) {
    logger.error('Offline evaluation creation failed', { error: (error as Error).message });
    res.status(400).json({ code: 400, error: (error as Error).message });
  }
});

router.post('/online/start', (req: Request, res: Response) => {
  try {
    const { model_id, model_name, version } = req.body;
    const evaluation = evaluationService.startOnlineEvaluation(model_id, model_name, version);
    res.status(201).json({ code: 201, data: evaluation });
  } catch (error) {
    logger.error('Online evaluation start failed', { error: (error as Error).message });
    res.status(400).json({ code: 400, error: (error as Error).message });
  }
});

router.post('/online/:id/complete', (req: Request, res: Response) => {
  try {
    const { metrics } = req.body;
    const evaluation = evaluationService.completeOnlineEvaluation(req.params.id, metrics);
    if (!evaluation) {
      res.status(404).json({ code: 404, error: 'Evaluation not found' });
      return;
    }
    res.json({ code: 200, data: evaluation });
  } catch (error) {
    logger.error('Online evaluation completion failed', { error: (error as Error).message });
    res.status(400).json({ code: 400, error: (error as Error).message });
  }
});

router.get('/', (req: Request, res: Response) => {
  const model_id = req.query.model_id as string;
  const eval_type = req.query.eval_type as 'offline' | 'online';
  const evaluations = evaluationService.listEvaluations(model_id, eval_type);
  res.json({ code: 200, data: evaluations });
});

router.get('/metrics', (req: Request, res: Response) => {
  const metrics = evaluationService.getMetricDefinitions();
  res.json({ code: 200, data: metrics });
});

router.get('/:id', (req: Request, res: Response) => {
  const evaluation = evaluationService.getEvaluation(req.params.id);
  if (!evaluation) {
    res.status(404).json({ code: 404, error: 'Evaluation not found' });
    return;
  }
  res.json({ code: 200, data: evaluation });
});

router.get('/:id/report', (req: Request, res: Response) => {
  const report = evaluationService.generateEvaluationReport(req.params.id);
  if (!report) {
    res.status(404).json({ code: 404, error: 'Evaluation not found' });
    return;
  }
  res.json({ code: 200, data: report });
});

router.get('/compare', (req: Request, res: Response) => {
  const eval_a = req.query.eval_a as string;
  const eval_b = req.query.eval_b as string;

  if (!eval_a || !eval_b) {
    res.status(400).json({ code: 400, error: 'Both eval_a and eval_b parameters are required' });
    return;
  }

  const comparison = evaluationService.compareEvaluations(eval_a, eval_b);
  if (!comparison) {
    res.status(404).json({ code: 404, error: 'One or both evaluations not found' });
    return;
  }

  res.json({ code: 200, data: comparison });
});

router.post('/drift/detect', (req: Request, res: Response) => {
  try {
    const { model_id, feature_name, current_values, threshold } = req.body;
    const result = evaluationService.detectDrift(model_id, feature_name, current_values, threshold);
    res.json({ code: 200, data: result });
  } catch (error) {
    logger.error('Drift detection failed', { error: (error as Error).message });
    res.status(400).json({ code: 400, error: (error as Error).message });
  }
});

router.post('/drift/baseline', (req: Request, res: Response) => {
  try {
    const { model_id, feature_name, values } = req.body;
    evaluationService.setBaseline(model_id, feature_name, values);
    res.json({ code: 200, message: 'Baseline set successfully' });
  } catch (error) {
    logger.error('Setting baseline failed', { error: (error as Error).message });
    res.status(400).json({ code: 400, error: (error as Error).message });
  }
});

router.get('/drift/history/:model_id', (req: Request, res: Response) => {
  const limit = req.query.limit ? parseInt(req.query.limit as string, 10) : undefined;
  const history = evaluationService.getDriftHistory(req.params.model_id, limit);
  res.json({ code: 200, data: history });
});

router.post('/monitoring/config', (req: Request, res: Response) => {
  try {
    evaluationService.configureOnlineMonitoring(req.body);
    res.json({ code: 200, message: 'Monitoring configuration updated' });
  } catch (error) {
    logger.error('Monitoring config update failed', { error: (error as Error).message });
    res.status(400).json({ code: 400, error: (error as Error).message });
  }
});

router.get('/monitoring/config/:model_id', (req: Request, res: Response) => {
  const config = evaluationService.getOnlineMonitoringConfig(req.params.model_id);
  if (!config) {
    res.status(404).json({ code: 404, error: 'Monitoring configuration not found' });
    return;
  }
  res.json({ code: 200, data: config });
});

router.post('/predictions', (req: Request, res: Response) => {
  try {
    const { model_id, prediction, ground_truth } = req.body;
    evaluationService.recordPrediction(model_id, prediction, ground_truth);
    res.json({ code: 200, message: 'Prediction recorded' });
  } catch (error) {
    logger.error('Recording prediction failed', { error: (error as Error).message });
    res.status(400).json({ code: 400, error: (error as Error).message });
  }
});

export default router;
