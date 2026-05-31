import { Router, Request, Response } from 'express';
import { asyncHandler } from '../middleware/errorHandler';
import * as service from '../modules/dnsProxy/service';
import { DnsUpstreamSchema, DnsQuerySchema } from '../modules/dnsProxy/types';

const router = Router();

const getPaginationParams = (req: Request) => ({
  page: parseInt(req.query.page as string) || 1,
  pageSize: parseInt(req.query.pageSize as string) || 20,
});

router.post('/upstreams', asyncHandler(async (req: Request, res: Response) => {
  const data = DnsUpstreamSchema.parse(req.body);
  const result = await service.createUpstream(data);
  res.status(201).json({ code: 201, data: result });
}));

router.get('/upstreams', asyncHandler(async (req: Request, res: Response) => {
  const params = getPaginationParams(req);
  const enabledOnly = req.query.enabledOnly === 'true';
  const result = await service.listUpstreams(params, enabledOnly);
  res.json({ code: 200, data: result });
}));

router.get('/upstreams/:upstreamId', asyncHandler(async (req: Request, res: Response) => {
  const result = await service.getUpstream(req.params.upstreamId);
  res.json({ code: 200, data: result });
}));

router.put('/upstreams/:upstreamId', asyncHandler(async (req: Request, res: Response) => {
  const data = DnsUpstreamSchema.partial().parse(req.body);
  const result = await service.updateUpstream(req.params.upstreamId, data);
  res.json({ code: 200, data: result });
}));

router.delete('/upstreams/:upstreamId', asyncHandler(async (req: Request, res: Response) => {
  await service.deleteUpstream(req.params.upstreamId);
  res.json({ code: 200, message: 'Upstream deleted' });
}));

router.post('/resolve', asyncHandler(async (req: Request, res: Response) => {
  const data = DnsQuerySchema.parse(req.body);
  const result = await service.resolveDns(data);
  res.json({ code: 200, data: result });
}));

router.delete('/cache', asyncHandler(async (req: Request, res: Response) => {
  const domain = req.query.domain as string | undefined;
  const type = req.query.type as string | undefined;
  const count = await service.clearCache(domain, type);
  res.json({ code: 200, data: { cleared: count } });
}));

router.get('/cache/config', asyncHandler(async (_req: Request, res: Response) => {
  const result = service.getCacheConfig();
  res.json({ code: 200, data: result });
}));

router.put('/cache/config', asyncHandler(async (req: Request, res: Response) => {
  const result = service.updateCacheConfig(req.body);
  res.json({ code: 200, data: result });
}));

export default router;
