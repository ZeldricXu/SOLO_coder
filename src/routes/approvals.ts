import { Router, Request, Response } from 'express';
import { asyncHandler, requireTenant, validateRequest, validateQuery, validateParams, RequestContext } from '../common/middleware';
import { paginationSchema, approvalRuleSchema, idSchema } from '../common/validation/schemas';
import * as approvalModule from '../modules/approval-engine';
import { ApiResponse, PaginationParams, ApprovalRuleInput, ApprovalStrategy, ProcessingContext } from '../common/types';

const router = Router();

router.post('/rules',
  requireTenant,
  validateRequest(approvalRuleSchema),
  asyncHandler(async (req: Request, res: Response) => {
    const ctx = req as RequestContext;
    const data = req.body as ApprovalRuleInput;
    const result = await approvalModule.createApprovalRule(ctx.tenantId!, data, ctx.traceId);
    res.status(201).json({
      code: 201,
      data: result,
      traceId: ctx.traceId
    } as ApiResponse);
  })
);

router.get('/rules/:id',
  requireTenant,
  validateParams(idSchema),
  asyncHandler(async (req: Request, res: Response) => {
    const ctx = req as RequestContext;
    const { id } = req.params;
    const result = await approvalModule.getApprovalRule(ctx.tenantId!, id, ctx.traceId);
    res.json({
      code: 200,
      data: result,
      traceId: ctx.traceId
    } as ApiResponse);
  })
);

router.get('/rules',
  requireTenant,
  validateQuery(paginationSchema),
  asyncHandler(async (req: Request, res: Response) => {
    const ctx = req as RequestContext;
    const params = req.query as unknown as PaginationParams & { type?: string };
    const result = await approvalModule.listApprovalRules(ctx.tenantId!, params);
    res.json({
      code: 200,
      data: result,
      traceId: ctx.traceId
    } as ApiResponse);
  })
);

router.put('/rules/:id',
  requireTenant,
  validateParams(idSchema),
  validateRequest(approvalRuleSchema.partial()),
  asyncHandler(async (req: Request, res: Response) => {
    const ctx = req as RequestContext;
    const { id } = req.params;
    const data = req.body as Partial<ApprovalRuleInput>;
    const result = await approvalModule.updateApprovalRule(ctx.tenantId!, id, data, ctx.traceId);
    res.json({
      code: 200,
      data: result,
      traceId: ctx.traceId
    } as ApiResponse);
  })
);

router.delete('/rules/:id',
  requireTenant,
  validateParams(idSchema),
  asyncHandler(async (req: Request, res: Response) => {
    const ctx = req as RequestContext;
    const { id } = req.params;
    await approvalModule.deleteApprovalRule(ctx.tenantId!, id, ctx.traceId);
    res.json({
      code: 200,
      message: 'Approval rule deleted successfully',
      traceId: ctx.traceId
    } as ApiResponse);
  })
);

router.post('/instances',
  requireTenant,
  asyncHandler(async (req: Request, res: Response) => {
    const ctx = req as RequestContext;
    const { ticketId, ruleId, strategy, approverIds } = req.body as {
      ticketId: string;
      ruleId: string;
      strategy?: ApprovalStrategy;
      approverIds: string[];
    };
    const context: Partial<ProcessingContext> = { traceId: ctx.traceId, tenantId: ctx.tenantId };
    const result = await approvalModule.createApprovalInstance(
      ctx.tenantId!,
      ticketId,
      ruleId,
      strategy || 'ANY',
      approverIds,
      context
    );
    res.status(201).json({
      code: 201,
      data: result,
      traceId: ctx.traceId
    } as ApiResponse);
  })
);

router.get('/instances/:id',
  requireTenant,
  validateParams(idSchema),
  asyncHandler(async (req: Request, res: Response) => {
    const ctx = req as RequestContext;
    const { id } = req.params;
    const result = await approvalModule.getApprovalInstance(ctx.tenantId!, id, ctx.traceId);
    res.json({
      code: 200,
      data: result,
      traceId: ctx.traceId
    } as ApiResponse);
  })
);

router.post('/instances/:id/resolve',
  requireTenant,
  validateParams(idSchema),
  asyncHandler(async (req: Request, res: Response) => {
    const ctx = req as RequestContext;
    const { id } = req.params;
    const { approverId, decision, comment } = req.body as {
      approverId: string;
      decision: 'approved' | 'rejected';
      comment?: string;
    };
    const context: Partial<ProcessingContext> = { traceId: ctx.traceId, tenantId: ctx.tenantId };
    const result = await approvalModule.resolveApproval(
      ctx.tenantId!,
      id,
      approverId,
      decision,
      comment,
      context
    );
    res.json({
      code: 200,
      data: result,
      traceId: ctx.traceId
    } as ApiResponse);
  })
);

router.post('/instances/:id/delegate',
  requireTenant,
  validateParams(idSchema),
  asyncHandler(async (req: Request, res: Response) => {
    const ctx = req as RequestContext;
    const { id } = req.params;
    const { fromApproverId, toApproverId } = req.body as {
      fromApproverId: string;
      toApproverId: string;
    };
    const context: Partial<ProcessingContext> = { traceId: ctx.traceId, tenantId: ctx.tenantId };
    const result = await approvalModule.delegateApproval(
      ctx.tenantId!,
      id,
      fromApproverId,
      toApproverId,
      context
    );
    res.json({
      code: 200,
      data: result,
      traceId: ctx.traceId
    } as ApiResponse);
  })
);

router.post('/instances/:id/resolve-dynamic',
  requireTenant,
  validateParams(idSchema),
  asyncHandler(async (req: Request, res: Response) => {
    const ctx = req as RequestContext;
    const { id } = req.params;
    const result = await approvalModule.resolveDynamicApprovers(ctx.tenantId!, id, ctx.traceId);
    res.json({
      code: 200,
      data: result,
      traceId: ctx.traceId
    } as ApiResponse);
  })
);

router.get('/tickets/:ticketId',
  requireTenant,
  validateParams(idSchema),
  asyncHandler(async (req: Request, res: Response) => {
    const ctx = req as RequestContext;
    const { ticketId } = req.params;
    const result = await approvalModule.listApprovalsForTicket(ctx.tenantId!, ticketId, ctx.traceId);
    res.json({
      code: 200,
      data: result,
      traceId: ctx.traceId
    } as ApiResponse);
  })
);

router.get('/approvers/:approverId',
  requireTenant,
  validateParams(idSchema),
  validateQuery(paginationSchema),
  asyncHandler(async (req: Request, res: Response) => {
    const ctx = req as RequestContext;
    const { approverId } = req.params;
    const params = req.query as unknown as PaginationParams & { status?: string };
    const result = await approvalModule.listApprovalsForApprover(ctx.tenantId!, approverId, params);
    res.json({
      code: 200,
      data: result,
      traceId: ctx.traceId
    } as ApiResponse);
  })
);

router.get('/analytics',
  requireTenant,
  asyncHandler(async (req: Request, res: Response) => {
    const ctx = req as RequestContext;
    const { startDate, endDate } = req.query;
    const result = await approvalModule.getApprovalAnalytics(
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

export default router;
