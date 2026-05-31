import { Router, Request, Response } from 'express';
import { asyncHandler, requireTenant, validateRequest, validateQuery, validateParams, RequestContext } from '../common/middleware';
import { paginationSchema, usageRecordSchema, idSchema } from '../common/validation/schemas';
import * as billingModule from '../modules/billing';
import { ApiResponse, PaginationParams, UsageRecordInput, ProcessingContext } from '../common/types';

const router = Router();

router.post('/usage',
  requireTenant,
  validateRequest(usageRecordSchema),
  asyncHandler(async (req: Request, res: Response) => {
    const ctx = req as RequestContext;
    const data = req.body as UsageRecordInput;
    const context: Partial<ProcessingContext> = { traceId: ctx.traceId, tenantId: ctx.tenantId };
    const result = await billingModule.recordUsage(ctx.tenantId!, data, context);
    res.status(201).json({
      code: 201,
      data: result,
      traceId: ctx.traceId
    } as ApiResponse);
  })
);

router.post('/usage/batch',
  requireTenant,
  asyncHandler(async (req: Request, res: Response) => {
    const ctx = req as RequestContext;
    const { records } = req.body as { records: Array<{ tenantId: string; data: UsageRecordInput }> };
    const context: Partial<ProcessingContext> = { traceId: ctx.traceId, tenantId: ctx.tenantId };
    const result = await billingModule.processUsageBatch(records, context);
    res.json({
      code: 200,
      data: result,
      traceId: ctx.traceId
    } as ApiResponse);
  })
);

router.get('/usage',
  requireTenant,
  asyncHandler(async (req: Request, res: Response) => {
    const ctx = req as RequestContext;
    const { billingPeriod, resourceType } = req.query;
    const result = await billingModule.getUsageForPeriod(
      ctx.tenantId!,
      billingPeriod as string | undefined,
      resourceType as string | undefined
    );
    res.json({
      code: 200,
      data: result,
      traceId: ctx.traceId
    } as ApiResponse);
  })
);

router.get('/usage/estimate',
  requireTenant,
  asyncHandler(async (req: Request, res: Response) => {
    const ctx = req as RequestContext;
    const { resourceType, quantity } = req.query;
    const result = await billingModule.estimateCost(
      ctx.tenantId!,
      resourceType as string,
      Number(quantity)
    );
    res.json({
      code: 200,
      data: result,
      traceId: ctx.traceId
    } as ApiResponse);
  })
);

router.post('/invoices/generate',
  requireTenant,
  asyncHandler(async (req: Request, res: Response) => {
    const ctx = req as RequestContext;
    const { billingPeriod } = req.body as { billingPeriod?: string };
    const result = await billingModule.generateInvoice(ctx.tenantId!, billingPeriod, ctx.traceId);
    res.status(201).json({
      code: 201,
      data: result,
      traceId: ctx.traceId
    } as ApiResponse);
  })
);

router.get('/invoices/:id',
  requireTenant,
  validateParams(idSchema),
  asyncHandler(async (req: Request, res: Response) => {
    const ctx = req as RequestContext;
    const { id } = req.params;
    const result = await billingModule.getInvoice(id, ctx.traceId);
    res.json({
      code: 200,
      data: result,
      traceId: ctx.traceId
    } as ApiResponse);
  })
);

router.get('/invoices',
  requireTenant,
  validateQuery(paginationSchema),
  asyncHandler(async (req: Request, res: Response) => {
    const ctx = req as RequestContext;
    const params = req.query as unknown as PaginationParams;
    const result = await billingModule.listInvoices(ctx.tenantId!, params);
    res.json({
      code: 200,
      data: result,
      traceId: ctx.traceId
    } as ApiResponse);
  })
);

router.post('/invoices/:id/pay',
  requireTenant,
  validateParams(idSchema),
  asyncHandler(async (req: Request, res: Response) => {
    const ctx = req as RequestContext;
    const { id } = req.params;
    const { paymentMethod } = req.body as { paymentMethod: string };
    const result = await billingModule.payInvoice(id, paymentMethod, ctx.traceId);
    res.json({
      code: 200,
      data: result,
      traceId: ctx.traceId
    } as ApiResponse);
  })
);

router.get('/summary',
  requireTenant,
  asyncHandler(async (req: Request, res: Response) => {
    const ctx = req as RequestContext;
    const result = await billingModule.getBillingSummary(ctx.tenantId!, ctx.traceId);
    res.json({
      code: 200,
      data: result,
      traceId: ctx.traceId
    } as ApiResponse);
  })
);

router.get('/plans',
  asyncHandler(async (req: Request, res: Response) => {
    const ctx = req as RequestContext;
    const { activeOnly } = req.query;
    const result = await billingModule.listBillingPlans(activeOnly !== 'false');
    res.json({
      code: 200,
      data: result,
      traceId: ctx.traceId
    } as ApiResponse);
  })
);

router.post('/plans',
  asyncHandler(async (req: Request, res: Response) => {
    const ctx = req as RequestContext;
    const { name, type, price, features } = req.body as {
      name: string;
      type: string;
      price: number;
      features: Record<string, unknown>;
    };
    const result = await billingModule.createBillingPlan(name, type, price, features);
    res.status(201).json({
      code: 201,
      data: result,
      traceId: ctx.traceId
    } as ApiResponse);
  })
);

export default router;
