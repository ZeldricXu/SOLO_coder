import { Router, Request, Response } from 'express';
import { asyncHandler } from '../middleware/errorHandler';
import * as service from '../modules/imageDistribution/service';
import { ImageSyncTaskSchema } from '../modules/imageDistribution/types';

const router = Router();

const getPaginationParams = (req: Request) => ({
  page: parseInt(req.query.page as string) || 1,
  pageSize: parseInt(req.query.pageSize as string) || 20,
});

router.post('/sync-tasks', asyncHandler(async (req: Request, res: Response) => {
  const data = ImageSyncTaskSchema.parse(req.body);
  const result = await service.createSyncTask(data);
  res.status(201).json({ code: 201, data: result });
}));

router.get('/sync-tasks', asyncHandler(async (req: Request, res: Response) => {
  const params = getPaginationParams(req);
  const status = req.query.status as string | undefined;
  const result = await service.listSyncTasks(params, status as never);
  res.json({ code: 200, data: result });
}));

router.get('/sync-tasks/:taskId', asyncHandler(async (req: Request, res: Response) => {
  const result = await service.getSyncTask(req.params.taskId);
  res.json({ code: 200, data: result });
}));

router.get('/sync-tasks/:taskId/progress', asyncHandler(async (req: Request, res: Response) => {
  const result = await service.getTaskProgress(req.params.taskId);
  res.json({ code: 200, data: result });
}));

router.post('/sync-tasks/:taskId/start', asyncHandler(async (req: Request, res: Response) => {
  const { sourceAuth, targetAuth } = req.body as { sourceAuth?: unknown; targetAuth?: unknown };
  const result = await service.startSyncTask(req.params.taskId);
  service.syncImage(req.params.taskId, sourceAuth as never, targetAuth as never).catch(err => {
    console.error('Sync task failed:', err);
  });
  res.json({ code: 200, data: result });
}));

router.post('/sync-tasks/:taskId/cancel', asyncHandler(async (req: Request, res: Response) => {
  const result = await service.cancelSyncTask(req.params.taskId);
  res.json({ code: 200, data: result });
}));

router.get('/layers', asyncHandler(async (req: Request, res: Response) => {
  const params = getPaginationParams(req);
  const repository = req.query.repository as string | undefined;
  const registry = req.query.registry as string | undefined;
  const result = await service.listLayers(params, repository, registry);
  res.json({ code: 200, data: result });
}));

router.get('/layers/:layerId', asyncHandler(async (req: Request, res: Response) => {
  const result = await service.getLayer(req.params.layerId);
  res.json({ code: 200, data: result });
}));

router.get('/layers/digest/:digest', asyncHandler(async (req: Request, res: Response) => {
  const result = await service.getLayerByDigest(req.params.digest);
  res.json({ code: 200, data: result });
}));

router.delete('/layers/:layerId', asyncHandler(async (req: Request, res: Response) => {
  await service.deleteLayer(req.params.layerId);
  res.json({ code: 200, message: 'Layer deleted' });
}));

router.get('/p2p/config', asyncHandler(async (_req: Request, res: Response) => {
  const result = service.getP2PConfig();
  res.json({ code: 200, data: result });
}));

router.put('/p2p/config', asyncHandler(async (req: Request, res: Response) => {
  const result = service.updateP2PConfig(req.body);
  res.json({ code: 200, data: result });
}));

router.get('/registry/:registry/stats', asyncHandler(async (req: Request, res: Response) => {
  const result = await service.getRegistryStats(req.params.registry);
  res.json({ code: 200, data: result });
}));

export default router;
