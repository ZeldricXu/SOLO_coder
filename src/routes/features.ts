import { Router, Request, Response } from 'express';
import { featureStorageService } from '../features';
import { logger } from '../logging';

const router = Router();

router.post('/', (req: Request, res: Response) => {
  try {
    const auth = (req as unknown as { auth?: { user_id: string } }).auth;
    const { name, value_type, dimensions, description, default_value, is_online, is_offline, ttl_seconds, tags } = req.body;

    const feature = featureStorageService.registerFeature(
      name,
      value_type,
      dimensions || [],
      auth?.user_id || 'system',
      {
        description,
        default_value,
        is_online,
        is_offline,
        ttl_seconds,
        tags,
      }
    );

    res.status(201).json({ code: 201, data: feature });
  } catch (error) {
    logger.error('Feature registration failed', { error: (error as Error).message });
    res.status(400).json({ code: 400, error: (error as Error).message });
  }
});

router.get('/', (req: Request, res: Response) => {
  const tags = req.query.tags ? (req.query.tags as string).split(',') : undefined;
  const features = featureStorageService.listFeatures(tags);
  res.json({ code: 200, data: features });
});

router.get('/:id', (req: Request, res: Response) => {
  const feature = featureStorageService.getFeature(req.params.id);
  if (!feature) {
    res.status(404).json({ code: 404, error: 'Feature not found' });
    return;
  }
  res.json({ code: 200, data: feature });
});

router.put('/:id', (req: Request, res: Response) => {
  const updated = featureStorageService.updateFeature(req.params.id, req.body);
  if (!updated) {
    res.status(404).json({ code: 404, error: 'Feature not found' });
    return;
  }
  res.json({ code: 200, data: updated });
});

router.post('/online/:featureId/:entityId', (req: Request, res: Response) => {
  const { value, dimensions } = req.body;
  const stored = featureStorageService.setOnlineFeatureValue(
    req.params.featureId,
    req.params.entityId,
    value,
    dimensions || {}
  );

  if (!stored) {
    res.status(404).json({ code: 404, error: 'Feature not found or not available for online serving' });
    return;
  }

  res.json({ code: 200, data: stored });
});

router.get('/online/:featureId/:entityId', (req: Request, res: Response) => {
  const value = featureStorageService.getOnlineFeatureValue(req.params.featureId, req.params.entityId);
  res.json({ code: 200, data: value });
});

router.post('/online/batch/:entityId', (req: Request, res: Response) => {
  const { feature_ids } = req.body;
  const values = featureStorageService.getOnlineFeatures(feature_ids, req.params.entityId);
  res.json({ code: 200, data: values });
});

router.post('/offline/:featureId/:entityId', (req: Request, res: Response) => {
  const { value, timestamp, dimensions } = req.body;
  const stored = featureStorageService.recordOfflineFeatureValue(
    req.params.featureId,
    req.params.entityId,
    value,
    timestamp,
    dimensions || {}
  );

  if (!stored) {
    res.status(404).json({ code: 404, error: 'Feature not found or not available for offline storage' });
    return;
  }

  res.json({ code: 200, data: stored });
});

router.get('/offline/:featureId/:entityId', (req: Request, res: Response) => {
  const startTime = req.query.start_time ? parseInt(req.query.start_time as string, 10) : undefined;
  const endTime = req.query.end_time ? parseInt(req.query.end_time as string, 10) : undefined;
  const limit = req.query.limit ? parseInt(req.query.limit as string, 10) : undefined;

  const values = featureStorageService.getOfflineFeatureValues(
    req.params.featureId,
    req.params.entityId,
    startTime,
    endTime,
    limit
  );

  res.json({ code: 200, data: values });
});

router.post('/backfill', (req: Request, res: Response) => {
  const { feature_id, entity_ids } = req.body;

  const job = featureStorageService.backfillFeature(
    feature_id,
    entity_ids,
    async (entityId: string) => {
      return { entity_id: entityId, backfilled_at: new Date().toISOString() };
    }
  );

  res.status(201).json({ code: 201, data: job });
});

router.get('/backfill/:jobId', (req: Request, res: Response) => {
  const job = featureStorageService.getBackfillJob(req.params.jobId);
  if (!job) {
    res.status(404).json({ code: 404, error: 'Backfill job not found' });
    return;
  }
  res.json({ code: 200, data: job });
});

router.get('/backfill', (req: Request, res: Response) => {
  const featureId = req.query.feature_id as string;
  const jobs = featureStorageService.listBackfillJobs(featureId);
  res.json({ code: 200, data: jobs });
});

router.post('/groups', (req: Request, res: Response) => {
  try {
    const auth = (req as unknown as { auth?: { user_id: string } }).auth;
    const { name, feature_ids, description } = req.body;

    const group = featureStorageService.createFeatureGroup(
      name,
      feature_ids,
      auth?.user_id || 'system',
      description
    );

    res.status(201).json({ code: 201, data: group });
  } catch (error) {
    logger.error('Feature group creation failed', { error: (error as Error).message });
    res.status(400).json({ code: 400, error: (error as Error).message });
  }
});

router.get('/groups/:groupId', (req: Request, res: Response) => {
  const group = featureStorageService.getFeatureGroup(req.params.groupId);
  if (!group) {
    res.status(404).json({ code: 404, error: 'Feature group not found' });
    return;
  }
  res.json({ code: 200, data: group });
});

router.get('/groups/:groupId/online/:entityId', (req: Request, res: Response) => {
  const values = featureStorageService.getOnlineFeaturesByGroup(req.params.groupId, req.params.entityId);
  if (!values) {
    res.status(404).json({ code: 404, error: 'Feature group not found' });
    return;
  }
  res.json({ code: 200, data: values });
});

router.post('/online/config/:featureId', (req: Request, res: Response) => {
  const updated = featureStorageService.configureOnlineFeature(req.params.featureId, req.body);
  if (!updated) {
    res.status(404).json({ code: 404, error: 'Feature not found' });
    return;
  }
  res.json({ code: 200, message: 'Online feature config updated' });
});

router.get('/online/config/:featureId', (req: Request, res: Response) => {
  const config = featureStorageService.getOnlineConfig(req.params.featureId);
  if (!config) {
    res.status(404).json({ code: 404, error: 'Feature not found' });
    return;
  }
  res.json({ code: 200, data: config });
});

router.get('/consistency/:featureId/:entityId', (req: Request, res: Response) => {
  const result = featureStorageService.checkConsistency(req.params.featureId, req.params.entityId);
  res.json({ code: 200, data: result });
});

router.post('/cache/invalidate', (req: Request, res: Response) => {
  const { feature_id, entity_id } = req.body;
  featureStorageService.invalidateCache(feature_id, entity_id);
  res.json({ code: 200, message: 'Cache invalidated' });
});

router.get('/stats/summary', (req: Request, res: Response) => {
  const stats = featureStorageService.getStats();
  res.json({ code: 200, data: stats });
});

export default router;
