import { Router, Request, Response } from 'express';
import { z } from 'zod';
import { configManager } from '../config';
import { logger } from '../logging';

const router = Router();

const CreateConfigSchema = z.object({
  namespace: z.string(),
  parameters: z.record(z.unknown()),
  enabled: z.boolean().default(true),
});

const UpdateConfigSchema = z.object({
  parameters: z.record(z.unknown()),
  enabled: z.boolean().optional(),
});

const RollbackSchema = z.object({
  target_version: z.number().int().min(1),
  reason: z.string(),
});

router.post('/', (req: Request, res: Response) => {
  try {
    const auth = (req as unknown as { auth?: { user_id: string } }).auth;
    const body = CreateConfigSchema.parse(req.body);

    const config = configManager.createConfig(
      body.namespace,
      body.parameters,
      auth?.user_id || 'system',
      body.enabled
    );

    res.status(201).json({ code: 201, data: config });
  } catch (error) {
    logger.error('Config creation failed', { error: (error as Error).message });
    res.status(400).json({ code: 400, error: (error as Error).message });
  }
});

router.get('/', (req: Request, res: Response) => {
  const namespace = req.query.namespace as string;
  const configs = configManager.listConfigs(namespace);
  res.json({ code: 200, data: configs });
});

router.get('/:id', (req: Request, res: Response) => {
  const config = configManager.getConfig(req.params.id);
  if (!config) {
    res.status(404).json({ code: 404, error: 'Config not found' });
    return;
  }
  res.json({ code: 200, data: config });
});

router.put('/:id', (req: Request, res: Response) => {
  try {
    const auth = (req as unknown as { auth?: { user_id: string } }).auth;
    const body = UpdateConfigSchema.parse(req.body);

    const config = configManager.updateConfig(
      req.params.id,
      body.parameters,
      auth?.user_id || 'system',
      body.enabled
    );

    if (!config) {
      res.status(404).json({ code: 404, error: 'Config not found' });
      return;
    }

    res.json({ code: 200, data: config });
  } catch (error) {
    logger.error('Config update failed', { error: (error as Error).message });
    res.status(400).json({ code: 400, error: (error as Error).message });
  }
});

router.post('/:id/rollback', (req: Request, res: Response) => {
  try {
    const auth = (req as unknown as { auth?: { user_id: string } }).auth;
    const body = RollbackSchema.parse(req.body);

    const config = configManager.rollbackConfig(
      req.params.id,
      body.target_version,
      body.reason,
      auth?.user_id || 'system'
    );

    if (!config) {
      res.status(404).json({ code: 404, error: 'Config or version not found' });
      return;
    }

    res.json({ code: 200, data: config });
  } catch (error) {
    logger.error('Config rollback failed', { error: (error as Error).message });
    res.status(400).json({ code: 400, error: (error as Error).message });
  }
});

router.get('/:id/versions', (req: Request, res: Response) => {
  const versions = configManager.listConfigVersions(req.params.id);
  if (versions.length === 0) {
    res.status(404).json({ code: 404, error: 'Config not found' });
    return;
  }
  res.json({ code: 200, data: versions });
});

router.get('/:id/versions/:version', (req: Request, res: Response) => {
  const version = parseInt(req.params.version, 10);
  const config = configManager.getConfigVersion(req.params.id, version);
  if (!config) {
    res.status(404).json({ code: 404, error: 'Config version not found' });
    return;
  }
  res.json({ code: 200, data: config });
});

router.get('/:id/diff', (req: Request, res: Response) => {
  const versionA = parseInt(req.query.from as string, 10);
  const versionB = parseInt(req.query.to as string, 10);

  if (isNaN(versionA) || isNaN(versionB)) {
    res.status(400).json({ code: 400, error: 'Both from and to version parameters are required' });
    return;
  }

  const diff = configManager.diffConfigs(req.params.id, versionA, versionB);
  res.json({ code: 200, data: diff });
});

router.get('/:id/rollback-history', (req: Request, res: Response) => {
  const history = configManager.getRollbackHistory(req.params.id);
  res.json({ code: 200, data: history });
});

router.delete('/:id', (req: Request, res: Response) => {
  const deleted = configManager.deleteConfig(req.params.id);
  if (!deleted) {
    res.status(404).json({ code: 404, error: 'Config not found' });
    return;
  }
  res.json({ code: 200, message: 'Config deleted successfully' });
});

export default router;
