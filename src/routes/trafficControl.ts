import { Router, Request, Response } from 'express';
import { asyncHandler } from '../middleware/errorHandler';
import * as service from '../modules/trafficControl/service';
import { TrafficPolicySchema } from '../modules/trafficControl/types';

const router = Router();

const getPaginationParams = (req: Request) => ({
  page: parseInt(req.query.page as string) || 1,
  pageSize: parseInt(req.query.pageSize as string) || 20,
});

router.post('/policies', asyncHandler(async (req: Request, res: Response) => {
  const data = TrafficPolicySchema.parse(req.body);
  const result = await service.createPolicy(data);
  res.status(201).json({ code: 201, data: result });
}));

router.get('/policies', asyncHandler(async (req: Request, res: Response) => {
  const params = getPaginationParams(req);
  const namespace = req.query.namespace as string | undefined;
  const policyType = req.query.policyType as string | undefined;
  const result = await service.listPolicies(params, namespace, policyType);
  res.json({ code: 200, data: result });
}));

router.get('/policies/active', asyncHandler(async (req: Request, res: Response) => {
  const namespace = req.query.namespace as string | undefined;
  const result = await service.getActivePolicies(namespace);
  res.json({ code: 200, data: result });
}));

router.get('/policies/:policyId', asyncHandler(async (req: Request, res: Response) => {
  const result = await service.getPolicy(req.params.policyId);
  res.json({ code: 200, data: result });
}));

router.put('/policies/:policyId', asyncHandler(async (req: Request, res: Response) => {
  const data = TrafficPolicySchema.partial().parse(req.body);
  const result = await service.updatePolicy(req.params.policyId, data);
  res.json({ code: 200, data: result });
}));

router.delete('/policies/:policyId', asyncHandler(async (req: Request, res: Response) => {
  await service.deletePolicy(req.params.policyId);
  res.json({ code: 200, message: 'Policy deleted' });
}));

router.post('/policies/:policyId/toggle', asyncHandler(async (req: Request, res: Response) => {
  const { enabled } = req.body as { enabled: boolean };
  const result = await service.togglePolicy(req.params.policyId, enabled);
  res.json({ code: 200, data: result });
}));

export default router;
