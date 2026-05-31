import { Router, Request, Response } from 'express';
import { asyncHandler } from '../middleware/errorHandler';
import * as service from '../modules/chaosInjection/service';
import { ChaosScenarioSchema, ChaosInjectionSchema } from '../modules/chaosInjection/types';

const router = Router();

const getPaginationParams = (req: Request) => ({
  page: parseInt(req.query.page as string) || 1,
  pageSize: parseInt(req.query.pageSize as string) || 20,
});

router.post('/scenarios', asyncHandler(async (req: Request, res: Response) => {
  const data = ChaosScenarioSchema.parse(req.body);
  const result = await service.createScenario(data);
  res.status(201).json({ code: 201, data: result });
}));

router.get('/scenarios', asyncHandler(async (req: Request, res: Response) => {
  const params = getPaginationParams(req);
  const result = await service.listScenarios(params);
  res.json({ code: 200, data: result });
}));

router.get('/scenarios/:scenarioId', asyncHandler(async (req: Request, res: Response) => {
  const result = await service.getScenario(req.params.scenarioId);
  res.json({ code: 200, data: result });
}));

router.put('/scenarios/:scenarioId', asyncHandler(async (req: Request, res: Response) => {
  const data = ChaosScenarioSchema.partial().parse(req.body);
  const result = await service.updateScenario(req.params.scenarioId, data);
  res.json({ code: 200, data: result });
}));

router.delete('/scenarios/:scenarioId', asyncHandler(async (req: Request, res: Response) => {
  await service.deleteScenario(req.params.scenarioId);
  res.json({ code: 200, message: 'Scenario deleted' });
}));

router.post('/injections', asyncHandler(async (req: Request, res: Response) => {
  const data = ChaosInjectionSchema.parse(req.body);
  const result = await service.startInjection(data);
  res.status(201).json({ code: 201, data: result });
}));

router.get('/injections', asyncHandler(async (req: Request, res: Response) => {
  const params = getPaginationParams(req);
  const scenarioId = req.query.scenarioId as string | undefined;
  const result = await service.listInjections(params, scenarioId);
  res.json({ code: 200, data: result });
}));

router.get('/injections/:injectionId', asyncHandler(async (req: Request, res: Response) => {
  const result = await service.getInjection(req.params.injectionId);
  res.json({ code: 200, data: result });
}));

router.post('/injections/:injectionId/rollback', asyncHandler(async (req: Request, res: Response) => {
  const result = await service.rollbackInjection(req.params.injectionId);
  res.json({ code: 200, data: result });
}));

export default router;
