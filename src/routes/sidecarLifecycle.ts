import { Router, Request, Response } from 'express';
import { asyncHandler } from '../middleware/errorHandler';
import * as service from '../modules/sidecarLifecycle/service';
import { SidecarTemplateSchema, SidecarInjectionSchema } from '../modules/sidecarLifecycle/types';

const router = Router();

const getPaginationParams = (req: Request) => ({
  page: parseInt(req.query.page as string) || 1,
  pageSize: parseInt(req.query.pageSize as string) || 20,
});

router.post('/templates', asyncHandler(async (req: Request, res: Response) => {
  const data = SidecarTemplateSchema.parse(req.body);
  const result = await service.createTemplate(data);
  res.status(201).json({ code: 201, data: result });
}));

router.get('/templates', asyncHandler(async (req: Request, res: Response) => {
  const params = getPaginationParams(req);
  const result = await service.listTemplates(params);
  res.json({ code: 200, data: result });
}));

router.get('/templates/:templateId', asyncHandler(async (req: Request, res: Response) => {
  const result = await service.getTemplate(req.params.templateId);
  res.json({ code: 200, data: result });
}));

router.put('/templates/:templateId', asyncHandler(async (req: Request, res: Response) => {
  const data = SidecarTemplateSchema.partial().parse(req.body);
  const result = await service.updateTemplate(req.params.templateId, data);
  res.json({ code: 200, data: result });
}));

router.delete('/templates/:templateId', asyncHandler(async (req: Request, res: Response) => {
  await service.deleteTemplate(req.params.templateId);
  res.json({ code: 200, message: 'Template deleted' });
}));

router.post('/injections', asyncHandler(async (req: Request, res: Response) => {
  const data = SidecarInjectionSchema.parse(req.body);
  const result = await service.createInjection(data);
  res.status(201).json({ code: 201, data: result });
}));

router.get('/injections', asyncHandler(async (req: Request, res: Response) => {
  const params = getPaginationParams(req);
  const templateId = req.query.templateId as string | undefined;
  const enabledOnly = req.query.enabledOnly === 'true';
  const result = await service.listInjections(params, templateId, enabledOnly);
  res.json({ code: 200, data: result });
}));

router.get('/injections/active', asyncHandler(async (_req: Request, res: Response) => {
  const result = await service.getActiveInjections();
  res.json({ code: 200, data: result });
}));

router.get('/injections/:injectionId', asyncHandler(async (req: Request, res: Response) => {
  const result = await service.getInjection(req.params.injectionId);
  res.json({ code: 200, data: result });
}));

router.put('/injections/:injectionId', asyncHandler(async (req: Request, res: Response) => {
  const data = SidecarInjectionSchema.partial().parse(req.body);
  const result = await service.updateInjection(req.params.injectionId, data);
  res.json({ code: 200, data: result });
}));

router.delete('/injections/:injectionId', asyncHandler(async (req: Request, res: Response) => {
  await service.deleteInjection(req.params.injectionId);
  res.json({ code: 200, message: 'Injection deleted' });
}));

router.post('/injections/:injectionId/toggle', asyncHandler(async (req: Request, res: Response) => {
  const { enabled } = req.body as { enabled: boolean };
  const result = await service.toggleInjection(req.params.injectionId, enabled);
  res.json({ code: 200, data: result });
}));

router.post('/injections/:injectionId/config', asyncHandler(async (req: Request, res: Response) => {
  const { key, value } = req.body as { key: string; value: unknown };
  const result = await service.updateConfig(req.params.injectionId, key, value);
  res.json({ code: 200, data: result });
}));

router.post('/injections/:injectionId/config/batch', asyncHandler(async (req: Request, res: Response) => {
  const { updates } = req.body as { updates: Array<{ key: string; value: unknown }> };
  const result = await service.batchUpdateConfig(req.params.injectionId, updates);
  res.json({ code: 200, data: result });
}));

router.get('/pods/match', asyncHandler(async (req: Request, res: Response) => {
  const namespace = req.query.namespace as string;
  const labels = req.query.labels as unknown as Record<string, string> || {};
  const result = await service.getInjectionsForPod(namespace, labels);
  res.json({ code: 200, data: result });
}));

router.post('/instances', asyncHandler(async (req: Request, res: Response) => {
  const data = req.body as {
    templateId: string;
    namespace: string;
    podName: string;
    nodeName: string;
    status: 'running' | 'pending' | 'failed' | 'terminated';
    config: Record<string, unknown>;
  };
  const result = await service.registerSidecarInstance(data);
  res.status(201).json({ code: 201, data: result });
}));

router.get('/instances', asyncHandler(async (req: Request, res: Response) => {
  const templateId = req.query.templateId as string | undefined;
  const namespace = req.query.namespace as string | undefined;
  const result = await service.listInstances(templateId, namespace);
  res.json({ code: 200, data: result });
}));

router.get('/instances/:instanceId', asyncHandler(async (req: Request, res: Response) => {
  const result = await service.getInstance(req.params.instanceId);
  res.json({ code: 200, data: result });
}));

router.post('/instances/:instanceId/heartbeat', asyncHandler(async (req: Request, res: Response) => {
  const result = await service.heartbeat(req.params.instanceId);
  res.json({ code: 200, data: result });
}));

router.put('/instances/:instanceId/resources', asyncHandler(async (req: Request, res: Response) => {
  const result = await service.updateInstanceResources(req.params.instanceId, req.body);
  res.json({ code: 200, data: result });
}));

router.delete('/instances/:instanceId', asyncHandler(async (req: Request, res: Response) => {
  await service.deregisterSidecarInstance(req.params.instanceId);
  res.json({ code: 200, message: 'Instance deregistered' });
}));

export default router;
