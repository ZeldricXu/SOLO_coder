import { Router, Request, Response } from 'express';
import { asyncHandler, requireTenant, validateRequest, validateQuery, validateParams, RequestContext } from '../common/middleware';
import { paginationSchema, tenantSchema, tenantIdSchema, idSchema } from '../common/validation/schemas';
import * as tenantModule from '../modules/tenant';
import { ApiResponse, PaginationParams, TenantInput } from '../common/types';

const router = Router();

router.post('/',
  validateRequest(tenantSchema),
  asyncHandler(async (req: Request, res: Response) => {
    const ctx = req as RequestContext;
    const data = req.body as TenantInput;
    const result = await tenantModule.createTenant(data, ctx.traceId);
    res.status(201).json({
      code: 201,
      data: result,
      traceId: ctx.traceId
    } as ApiResponse);
  })
);

router.get('/:id',
  validateParams(idSchema),
  asyncHandler(async (req: Request, res: Response) => {
    const ctx = req as RequestContext;
    const { id } = req.params;
    const result = await tenantModule.getTenantById(id, ctx.traceId);
    res.json({
      code: 200,
      data: result,
      traceId: ctx.traceId
    } as ApiResponse);
  })
);

router.get('/',
  validateQuery(paginationSchema),
  asyncHandler(async (req: Request, res: Response) => {
    const ctx = req as RequestContext;
    const params = req.query as unknown as PaginationParams;
    const result = await tenantModule.listTenants(params);
    res.json({
      code: 200,
      data: result,
      traceId: ctx.traceId
    } as ApiResponse);
  })
);

router.put('/:id',
  validateParams(idSchema),
  validateRequest(tenantSchema.partial()),
  asyncHandler(async (req: Request, res: Response) => {
    const ctx = req as RequestContext;
    const { id } = req.params;
    const data = req.body as Partial<TenantInput>;
    const result = await tenantModule.updateTenant(id, data, ctx.traceId);
    res.json({
      code: 200,
      data: result,
      traceId: ctx.traceId
    } as ApiResponse);
  })
);

router.delete('/:id',
  validateParams(idSchema),
  asyncHandler(async (req: Request, res: Response) => {
    const ctx = req as RequestContext;
    const { id } = req.params;
    await tenantModule.deleteTenant(id, ctx.traceId);
    res.json({
      code: 200,
      message: 'Tenant deleted successfully',
      traceId: ctx.traceId
    } as ApiResponse);
  })
);

router.get('/:id/context',
  validateParams(idSchema),
  asyncHandler(async (req: Request, res: Response) => {
    const ctx = req as RequestContext;
    const { id } = req.params;
    const result = await tenantModule.getTenantContext(id, ctx.traceId);
    res.json({
      code: 200,
      data: result,
      traceId: ctx.traceId
    } as ApiResponse);
  })
);

router.get('/:id/quotas',
  requireTenant,
  validateParams(idSchema),
  asyncHandler(async (req: Request, res: Response) => {
    const ctx = req as RequestContext;
    const { id } = req.params;
    const result = await tenantModule.getTenantQuotas(id);
    res.json({
      code: 200,
      data: result,
      traceId: ctx.traceId
    } as ApiResponse);
  })
);

router.post('/:id/config',
  requireTenant,
  validateParams(idSchema),
  asyncHandler(async (req: Request, res: Response) => {
    const ctx = req as RequestContext;
    const { id } = req.params;
    const { namespace, parameters } = req.body as { namespace: string; parameters: Record<string, unknown> };
    const result = await tenantModule.setTenantConfig(id, namespace, parameters, ctx.traceId);
    res.json({
      code: 200,
      data: result,
      traceId: ctx.traceId
    } as ApiResponse);
  })
);

router.get('/:id/config/:namespace',
  requireTenant,
  validateParams(idSchema),
  asyncHandler(async (req: Request, res: Response) => {
    const ctx = req as RequestContext;
    const { id, namespace } = req.params;
    const result = await tenantModule.getTenantConfig(id, namespace);
    res.json({
      code: 200,
      data: result,
      traceId: ctx.traceId
    } as ApiResponse);
  })
);

export default router;
