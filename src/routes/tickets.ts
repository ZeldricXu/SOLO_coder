import { Router, Request, Response } from 'express';
import { asyncHandler, requireTenant, validateRequest, validateQuery, validateParams, RequestContext } from '../common/middleware';
import { paginationSchema, ticketSchema, idSchema, batchOperationSchema } from '../common/validation/schemas';
import * as ticketModule from '../modules/ticket-assignment';
import * as slaModule from '../modules/sla-monitor';
import { ApiResponse, PaginationParams, TicketInput, ProcessingContext } from '../common/types';

const router = Router();

router.post('/',
  requireTenant,
  validateRequest(ticketSchema),
  asyncHandler(async (req: Request, res: Response) => {
    const ctx = req as RequestContext;
    const data = req.body as TicketInput;
    const context: Partial<ProcessingContext> = { traceId: ctx.traceId, tenantId: ctx.tenantId };
    const result = await ticketModule.createTicket(ctx.tenantId!, data, context);
    res.status(201).json({
      code: 201,
      data: result,
      traceId: ctx.traceId
    } as ApiResponse);
  })
);

router.get('/:id',
  requireTenant,
  validateParams(idSchema),
  asyncHandler(async (req: Request, res: Response) => {
    const ctx = req as RequestContext;
    const { id } = req.params;
    const result = await ticketModule.getTicketById(ctx.tenantId!, id, ctx.traceId);
    res.json({
      code: 200,
      data: result,
      traceId: ctx.traceId
    } as ApiResponse);
  })
);

router.get('/',
  requireTenant,
  validateQuery(paginationSchema),
  asyncHandler(async (req: Request, res: Response) => {
    const ctx = req as RequestContext;
    const params = req.query as unknown as PaginationParams & { status?: string; priority?: string; agentId?: string };
    const result = await ticketModule.listTickets(ctx.tenantId!, params);
    res.json({
      code: 200,
      data: result,
      traceId: ctx.traceId
    } as ApiResponse);
  })
);

router.put('/:id',
  requireTenant,
  validateParams(idSchema),
  validateRequest(ticketSchema.partial()),
  asyncHandler(async (req: Request, res: Response) => {
    const ctx = req as RequestContext;
    const { id } = req.params;
    const data = req.body as Partial<TicketInput> & { status?: string };
    const result = await ticketModule.updateTicket(ctx.tenantId!, id, data, ctx.traceId);
    res.json({
      code: 200,
      data: result,
      traceId: ctx.traceId
    } as ApiResponse);
  })
);

router.delete('/:id',
  requireTenant,
  validateParams(idSchema),
  asyncHandler(async (req: Request, res: Response) => {
    const ctx = req as RequestContext;
    const { id } = req.params;
    await ticketModule.deleteTicket(ctx.tenantId!, id, ctx.traceId);
    res.json({
      code: 200,
      message: 'Ticket deleted successfully',
      traceId: ctx.traceId
    } as ApiResponse);
  })
);

router.post('/:id/assign',
  requireTenant,
  validateParams(idSchema),
  asyncHandler(async (req: Request, res: Response) => {
    const ctx = req as RequestContext;
    const { id } = req.params;
    const { agentId, autoAssign } = req.body as { agentId?: string; autoAssign?: boolean };
    const context: Partial<ProcessingContext> = { traceId: ctx.traceId, tenantId: ctx.tenantId };
    const result = await ticketModule.assignTicket(
      ctx.tenantId!,
      id,
      agentId,
      autoAssign !== false,
      context
    );
    res.json({
      code: 200,
      data: result,
      traceId: ctx.traceId
    } as ApiResponse);
  })
);

router.post('/:id/reassign',
  requireTenant,
  validateParams(idSchema),
  asyncHandler(async (req: Request, res: Response) => {
    const ctx = req as RequestContext;
    const { id } = req.params;
    const { newAgentId, reason } = req.body as { newAgentId: string; reason: string };
    const context: Partial<ProcessingContext> = { traceId: ctx.traceId, tenantId: ctx.tenantId };
    const result = await ticketModule.reassignTicket(
      ctx.tenantId!,
      id,
      newAgentId,
      reason,
      context
    );
    res.json({
      code: 200,
      data: result,
      traceId: ctx.traceId
    } as ApiResponse);
  })
);

router.post('/:id/resolve',
  requireTenant,
  validateParams(idSchema),
  asyncHandler(async (req: Request, res: Response) => {
    const ctx = req as RequestContext;
    const { id } = req.params;
    const { resolution } = req.body as { resolution: string };
    const context: Partial<ProcessingContext> = { traceId: ctx.traceId, tenantId: ctx.tenantId };
    const result = await ticketModule.resolveTicket(ctx.tenantId!, id, resolution, context);
    res.json({
      code: 200,
      data: result,
      traceId: ctx.traceId
    } as ApiResponse);
  })
);

router.post('/:id/close',
  requireTenant,
  validateParams(idSchema),
  asyncHandler(async (req: Request, res: Response) => {
    const ctx = req as RequestContext;
    const { id } = req.params;
    const context: Partial<ProcessingContext> = { traceId: ctx.traceId, tenantId: ctx.tenantId };
    const result = await ticketModule.closeTicket(ctx.tenantId!, id, context);
    res.json({
      code: 200,
      data: result,
      traceId: ctx.traceId
    } as ApiResponse);
  })
);

router.post('/batch/assign',
  requireTenant,
  asyncHandler(async (req: Request, res: Response) => {
    const ctx = req as RequestContext;
    const { ticketIds } = req.body as { ticketIds: string[] };
    const context: Partial<ProcessingContext> = { traceId: ctx.traceId, tenantId: ctx.tenantId };
    const result = await ticketModule.bulkAssignTickets(ctx.tenantId!, ticketIds, context);
    res.json({
      code: 200,
      data: result,
      traceId: ctx.traceId
    } as ApiResponse);
  })
);

router.get('/:id/match',
  requireTenant,
  validateParams(idSchema),
  asyncHandler(async (req: Request, res: Response) => {
    const ctx = req as RequestContext;
    const { id } = req.params;
    const { threshold, limit, strategy } = req.query;
    const result = await ticketModule.findMatchingAgents(
      ctx.tenantId!,
      id,
      {
        threshold: threshold ? Number(threshold) : undefined,
        limit: limit ? Number(limit) : undefined,
        strategy: strategy as string | undefined
      },
      ctx.traceId
    );
    res.json({
      code: 200,
      data: result,
      traceId: ctx.traceId
    } as ApiResponse);
  })
);

router.get('/:id/match/:agentId',
  requireTenant,
  validateParams(idSchema),
  asyncHandler(async (req: Request, res: Response) => {
    const ctx = req as RequestContext;
    const { id, agentId } = req.params;
    const result = await ticketModule.calculateSkillMatch(
      ctx.tenantId!,
      id,
      agentId,
      ctx.traceId
    );
    res.json({
      code: 200,
      data: result,
      traceId: ctx.traceId
    } as ApiResponse);
  })
);

router.get('/analytics/assignments',
  requireTenant,
  asyncHandler(async (req: Request, res: Response) => {
    const ctx = req as RequestContext;
    const { startDate, endDate } = req.query;
    const result = await ticketModule.getAssignmentAnalytics(
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

router.post('/:id/sla',
  requireTenant,
  validateParams(idSchema),
  asyncHandler(async (req: Request, res: Response) => {
    const ctx = req as RequestContext;
    const { id } = req.params;
    const context: Partial<ProcessingContext> = { traceId: ctx.traceId, tenantId: ctx.tenantId };
    const result = await slaModule.createSLAInstance(ctx.tenantId!, id, context);
    res.status(201).json({
      code: 201,
      data: result,
      traceId: ctx.traceId
    } as ApiResponse);
  })
);

router.get('/:id/sla',
  requireTenant,
  validateParams(idSchema),
  asyncHandler(async (req: Request, res: Response) => {
    const ctx = req as RequestContext;
    const { id } = req.params;
    const result = await slaModule.getSLACountdown(ctx.tenantId!, id, ctx.traceId);
    res.json({
      code: 200,
      data: result,
      traceId: ctx.traceId
    } as ApiResponse);
  })
);

router.post('/:id/sla/check',
  requireTenant,
  validateParams(idSchema),
  asyncHandler(async (req: Request, res: Response) => {
    const ctx = req as RequestContext;
    const { id } = req.params;
    const result = await slaModule.checkSLAEscalation(ctx.tenantId!, id, ctx.traceId);
    res.json({
      code: 200,
      data: result,
      traceId: ctx.traceId
    } as ApiResponse);
  })
);

router.post('/batch',
  requireTenant,
  validateRequest(batchOperationSchema),
  asyncHandler(async (req: Request, res: Response) => {
    const ctx = req as RequestContext;
    const { operations } = req.body as { operations: Array<{ action: string; id: string }> };
    const results = [];
    for (const op of operations) {
      try {
        if (op.action === 'delete') {
          await ticketModule.deleteTicket(ctx.tenantId!, op.id, ctx.traceId);
          results.push({ id: op.id, action: op.action, status: 'success' });
        }
      } catch (err) {
        results.push({
          id: op.id,
          action: op.action,
          status: 'failed',
          error: err instanceof Error ? err.message : String(err)
        });
      }
    }
    res.json({
      code: 200,
      data: { batch_id: `batch_${Date.now()}`, results },
      traceId: ctx.traceId
    } as ApiResponse);
  })
);

export default router;
