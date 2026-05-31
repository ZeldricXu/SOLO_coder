import { Router, Request, Response } from 'express';
import { promptExperimentService } from '../prompt';
import { logger } from '../logging';

const router = Router();

router.post('/', (req: Request, res: Response) => {
  try {
    const auth = (req as unknown as { auth?: { user_id: string } }).auth;
    const { name, content, variables, description, tags, metadata } = req.body;

    const result = promptExperimentService.createPrompt(
      name,
      content,
      variables || [],
      auth?.user_id || 'system',
      description,
      tags,
      metadata
    );

    res.status(201).json({ code: 201, data: result });
  } catch (error) {
    logger.error('Prompt creation failed', { error: (error as Error).message });
    res.status(400).json({ code: 400, error: (error as Error).message });
  }
});

router.get('/', (req: Request, res: Response) => {
  const include_inactive = req.query.include_inactive === 'true';
  const prompts = promptExperimentService.listPrompts(include_inactive);
  res.json({ code: 200, data: prompts });
});

router.get('/search', (req: Request, res: Response) => {
  const query = req.query.q as string;
  const tags = req.query.tags ? (req.query.tags as string).split(',') : undefined;

  if (!query) {
    res.status(400).json({ code: 400, error: 'Query parameter q is required' });
    return;
  }

  const prompts = promptExperimentService.searchPrompts(query, tags);
  res.json({ code: 200, data: prompts });
});

router.get('/:id', (req: Request, res: Response) => {
  const prompt = promptExperimentService.getPrompt(req.params.id);
  if (!prompt) {
    res.status(404).json({ code: 404, error: 'Prompt not found' });
    return;
  }
  res.json({ code: 200, data: prompt });
});

router.post('/:id/versions', (req: Request, res: Response) => {
  try {
    const auth = (req as unknown as { auth?: { user_id: string } }).auth;
    const { content, variables, description, metadata } = req.body;

    const version = promptExperimentService.createVersion(
      req.params.id,
      content,
      variables || [],
      auth?.user_id || 'system',
      description,
      metadata
    );

    if (!version) {
      res.status(404).json({ code: 404, error: 'Prompt not found' });
      return;
    }

    res.status(201).json({ code: 201, data: version });
  } catch (error) {
    logger.error('Prompt version creation failed', { error: (error as Error).message });
    res.status(400).json({ code: 400, error: (error as Error).message });
  }
});

router.get('/:id/versions', (req: Request, res: Response) => {
  const versions = promptExperimentService.getPromptVersions(req.params.id);
  if (versions.length === 0) {
    res.status(404).json({ code: 404, error: 'Prompt not found' });
    return;
  }
  res.json({ code: 200, data: versions });
});

router.get('/versions/:versionId', (req: Request, res: Response) => {
  const version = promptExperimentService.getPromptVersion(req.params.versionId);
  if (!version) {
    res.status(404).json({ code: 404, error: 'Prompt version not found' });
    return;
  }
  res.json({ code: 200, data: version });
});

router.post('/versions/:versionId/render', (req: Request, res: Response) => {
  const rendered = promptExperimentService.renderPrompt(req.params.versionId, req.body || {});
  if (rendered === null) {
    res.status(404).json({ code: 404, error: 'Prompt version not found' });
    return;
  }
  res.json({ code: 200, data: { rendered } });
});

router.post('/:id/archive', (req: Request, res: Response) => {
  const archived = promptExperimentService.archivePrompt(req.params.id);
  if (!archived) {
    res.status(404).json({ code: 404, error: 'Prompt not found' });
    return;
  }
  res.json({ code: 200, message: 'Prompt archived' });
});

router.post('/:id/unarchive', (req: Request, res: Response) => {
  const unarchived = promptExperimentService.unarchivePrompt(req.params.id);
  if (!unarchived) {
    res.status(404).json({ code: 404, error: 'Prompt not found' });
    return;
  }
  res.json({ code: 200, message: 'Prompt unarchived' });
});

router.post('/experiments', (req: Request, res: Response) => {
  try {
    const auth = (req as unknown as { auth?: { user_id: string } }).auth;
    const { name, prompt_id, variants, description, traffic_percentage } = req.body;

    const experiment = promptExperimentService.createExperiment(
      name,
      prompt_id,
      variants,
      auth?.user_id || 'system',
      description,
      traffic_percentage
    );

    if (!experiment) {
      res.status(404).json({ code: 404, error: 'Prompt not found' });
      return;
    }

    res.status(201).json({ code: 201, data: experiment });
  } catch (error) {
    logger.error('Experiment creation failed', { error: (error as Error).message });
    res.status(400).json({ code: 400, error: (error as Error).message });
  }
});

router.get('/experiments', (req: Request, res: Response) => {
  const status = req.query.status as any;
  const experiments = promptExperimentService.listExperiments(status);
  res.json({ code: 200, data: experiments });
});

router.get('/experiments/:id', (req: Request, res: Response) => {
  const experiment = promptExperimentService.getExperiment(req.params.id);
  if (!experiment) {
    res.status(404).json({ code: 404, error: 'Experiment not found' });
    return;
  }
  res.json({ code: 200, data: experiment });
});

router.post('/experiments/:id/start', (req: Request, res: Response) => {
  const started = promptExperimentService.startExperiment(req.params.id);
  if (!started) {
    res.status(404).json({ code: 404, error: 'Experiment not found or already running' });
    return;
  }
  res.json({ code: 200, message: 'Experiment started' });
});

router.post('/experiments/:id/pause', (req: Request, res: Response) => {
  const paused = promptExperimentService.pauseExperiment(req.params.id);
  if (!paused) {
    res.status(404).json({ code: 404, error: 'Experiment not found or not running' });
    return;
  }
  res.json({ code: 200, message: 'Experiment paused' });
});

router.post('/experiments/:id/resume', (req: Request, res: Response) => {
  const resumed = promptExperimentService.resumeExperiment(req.params.id);
  if (!resumed) {
    res.status(404).json({ code: 404, error: 'Experiment not found or not paused' });
    return;
  }
  res.json({ code: 200, message: 'Experiment resumed' });
});

router.post('/experiments/:id/end', (req: Request, res: Response) => {
  const { winner } = req.body;
  const ended = promptExperimentService.endExperiment(req.params.id, winner);
  if (!ended) {
    res.status(404).json({ code: 404, error: 'Experiment not found or already completed' });
    return;
  }
  res.json({ code: 200, message: 'Experiment ended' });
});

router.get('/experiments/:id/stats', (req: Request, res: Response) => {
  const stats = promptExperimentService.getExperimentStats(req.params.id);
  if (!stats) {
    res.status(404).json({ code: 404, error: 'Experiment not found' });
    return;
  }
  res.json({ code: 200, data: stats });
});

router.get('/experiments/:id/trials', (req: Request, res: Response) => {
  const variant_id = req.query.variant_id as string;
  const limit = req.query.limit ? parseInt(req.query.limit as string, 10) : undefined;
  const trials = promptExperimentService.getTrials(req.params.id, variant_id, limit);
  res.json({ code: 200, data: trials });
});

router.post('/experiments/:id/trials', (req: Request, res: Response) => {
  try {
    const { variant_id, input, output, metrics, latency_ms, success, error } = req.body;
    const trial = promptExperimentService.recordTrial(
      req.params.id,
      variant_id,
      input || {},
      output || '',
      metrics || {},
      latency_ms || 0,
      success !== false,
      error
    );

    if (!trial) {
      res.status(404).json({ code: 404, error: 'Experiment not found' });
      return;
    }

    res.status(201).json({ code: 201, data: trial });
  } catch (error) {
    logger.error('Trial recording failed', { error: (error as Error).message });
    res.status(400).json({ code: 400, error: (error as Error).message });
  }
});

router.get('/experiments/compare', (req: Request, res: Response) => {
  const exp_a = req.query.exp_a as string;
  const exp_b = req.query.exp_b as string;

  if (!exp_a || !exp_b) {
    res.status(400).json({ code: 400, error: 'Both exp_a and exp_b parameters are required' });
    return;
  }

  const comparison = promptExperimentService.compareExperiments(exp_a, exp_b);
  if (!comparison) {
    res.status(404).json({ code: 404, error: 'One or both experiments not found' });
    return;
  }

  res.json({ code: 200, data: comparison });
});

export default router;
