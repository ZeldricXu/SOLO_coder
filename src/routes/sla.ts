import { Router, Request, Response } from 'express';
import { asyncHandler, requireTenant, validateRequest, validateQuery, validateParams, RequestContext } from '../common/middleware';
import { paginationSchema, slaPolicySchema, idSchema } from '../common/validation/schemas';
import * as slaModule from '../modules/sla-monitor';
import { ApiResponse, PaginationParams, SLAPolicyInput } from '../common/types';

const router = Router();

router.post('/policies',
  requireTenant,
  validateRequest(slaPolicySchema),
  asyncHandler(async (req: Request, res: Response) => {
    const ctx = req as RequestContext;
    const data = req.body as SLAPolicyInput;
    const result = await slaModule.createSLAPolicy(ctx.tenantId!, data, ctx.traceId);
    res.status(201).json({
      code: 201,
      data: result,
      traceId: ctx.traceId
    } as ApiResponse);
  })
);

router.get('/policies/:id',
  requireTenant,
  validateParams(idSchema),
  asyncHandler(async (req: Request, res: Response) => {
    const ctx = req as RequestContext;
    const { id } = req.params;
    const result = await slaModule.getSLAPolicy(ctx.tenantId!, id, ctx.traceId);
    res.json({
      code: 200,
      data: result,
      traceId: ctx.traceId
    } as ApiResponse);
  })
);

router.get('/policies',
  requireTenant,
  validateQuery(paginationSchema),
  asyncHandler(async (req: Request, res: Response) => {
    const ctx = req as RequestContext;
    const params = req.query as unknown as PaginationParams;
    const result = await slaModule.listSLAPolicies(ctx.tenantId!, params);
    res.json({
      code: 200,
      data: result,
      traceId: ctx.traceId
    } as ApiResponse);
  })
);

router.get('/policies/match',
  requireTenant,
  asyncHandler(async (req: Request, res: Response) => {
    const ctx = req as RequestContext;
    const { ticketType, priority } = req.query;
    const result = await slaModule.getMatchingSLAPolicy(
      ctx.tenantId!,
      ticketType as string,
      priority as string,
      ctx.traceId
    );
    res.json({
      code: 200,
      data: result,
      traceId: ctx.traceId
    } as ApiResponse);
  })
);

router.get('/monitor/status',
  requireTenant,
  asyncHandler(async (req: Request, res: Response) => {
    const ctx = req as RequestContext;
    const result = await slaModule.monitorActiveSLAs(ctx.tenantId!);
    res.json({
      code: 200,
      data: result,
      traceId: ctx.traceId
    } as ApiResponse);
  })
);

router.get('/report',
  requireTenant,
  asyncHandler(async (req: Request, res: Response) => {
    const ctx = req as RequestContext;
    const { startDate, endDate } = req.query;
    const result = await slaModule.getSLAReport(
      ctx.tenantId!,
      new Date(startDate as string),
      new Date(endDate as string)
    );
    res.json({
      code: 200,
      data: result,
      traceId: ctx.traceId
    } as ApiResponse);
  })
);

router.post('/monitor/start',
  requireTenant,
  asyncHandler(async (req: Request, res: Response) => {
    const ctx = req as RequestContext;
    const { intervalMs } = req.body as { intervalMs?: number };
    const stopFn = slaModule.startSLAMonitor(ctx.tenantId!, intervalMs || 60000);
    res.json({
      code: 200,
      message: 'SLA monitor started',
      data: { intervalMs: intervalMs || 60000 },
      traceId: ctx.traceId
    } as ApiResponse);
  })
);

export default router;
