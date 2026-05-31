import { Router, Request, Response } from 'express';
import { asyncHandler, requireTenant, validateRequest, validateQuery, validateParams, RequestContext } from '../common/middleware';
import { paginationSchema, workflowProcessSchema, idSchema } from '../common/validation/schemas';
import * as processModule from '../modules/process-designer';
import { ApiResponse, PaginationParams, WorkflowProcessInput, ProcessingContext } from '../common/types';

const router = Router();

router.post('/',
  requireTenant,
  validateRequest(workflowProcessSchema),
  asyncHandler(async (req: Request, res: Response) => {
    const ctx = req as RequestContext;
    const data = req.body as WorkflowProcessInput;
    const result = await processModule.createProcess(ctx.tenantId!, data, ctx.traceId);
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
    const result = await processModule.getProcessById(ctx.tenantId!, id, ctx.traceId);
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
    const params = req.query as unknown as PaginationParams & { status?: string };
    const result = await processModule.listProcesses(ctx.tenantId!, params);
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
  validateRequest(workflowProcessSchema.partial()),
  asyncHandler(async (req: Request, res: Response) => {
    const ctx = req as RequestContext;
    const { id } = req.params;
    const data = req.body as Partial<WorkflowProcessInput>;
    const result = await processModule.updateProcess(ctx.tenantId!, id, data, ctx.traceId);
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
    await processModule.deleteProcess(ctx.tenantId!, id, ctx.traceId);
    res.json({
      code: 200,
      message: 'Process deleted successfully',
      traceId: ctx.traceId
    } as ApiResponse);
  })
);

router.get('/:id/diagram',
  requireTenant,
  validateParams(idSchema),
  asyncHandler(async (req: Request, res: Response) => {
    const ctx = req as RequestContext;
    const { id } = req.params;
    const result = await processModule.getProcessDiagram(ctx.tenantId!, id, ctx.traceId);
    res.json({
      code: 200,
      data: result,
      traceId: ctx.traceId
    } as ApiResponse);
  })
);

router.post('/:id/publish',
  requireTenant,
  validateParams(idSchema),
  asyncHandler(async (req: Request, res: Response) => {
    const ctx = req as RequestContext;
    const { id } = req.params;
    const result = await processModule.publishProcess(ctx.tenantId!, id, ctx.traceId);
    res.json({
      code: 200,
      data: result,
      traceId: ctx.traceId
    } as ApiResponse);
  })
);

router.post('/validate',
  requireTenant,
  asyncHandler(async (req: Request, res: Response) => {
    const ctx = req as RequestContext;
    const { nodes, edges } = req.body as {
      nodes: Array<{ id?: string; type: string; name: string }>;
      edges: Array<{ sourceNodeId: string; targetNodeId: string }>;
    };
    const result = processModule.validateProcessGraph(nodes, edges);
    res.json({
      code: 200,
      data: result,
      traceId: ctx.traceId
    } as ApiResponse);
  })
);

router.post('/:id/start',
  requireTenant,
  validateParams(idSchema),
  asyncHandler(async (req: Request, res: Response) => {
    const ctx = req as RequestContext;
    const { id } = req.params;
    const { entityId, variables } = req.body as { entityId: string; variables?: Record<string, unknown> };
    const context: Partial<ProcessingContext> = { traceId: ctx.traceId, tenantId: ctx.tenantId };
    const result = await processModule.startProcessInstance(
      ctx.tenantId!,
      id,
      entityId,
      variables || {},
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
    const result = await processModule.getProcessInstance(ctx.tenantId!, id, ctx.traceId);
    res.json({
      code: 200,
      data: result,
      traceId: ctx.traceId
    } as ApiResponse);
  })
);

router.get('/:id/instances',
  requireTenant,
  validateParams(idSchema),
  validateQuery(paginationSchema),
  asyncHandler(async (req: Request, res: Response) => {
    const ctx = req as RequestContext;
    const { id } = req.params;
    const params = req.query as unknown as PaginationParams;
    const result = await processModule.listProcessInstances(ctx.tenantId!, id, params);
    res.json({
      code: 200,
      data: result,
      traceId: ctx.traceId
    } as ApiResponse);
  })
);

router.post('/instances/:id/advance',
  requireTenant,
  validateParams(idSchema),
  asyncHandler(async (req: Request, res: Response) => {
    const ctx = req as RequestContext;
    const { id } = req.params;
    const { variables } = req.body as { variables?: Record<string, unknown> };
    const context: Partial<ProcessingContext> = { traceId: ctx.traceId, tenantId: ctx.tenantId };
    const result = await processModule.advanceProcessInstance(
      ctx.tenantId!,
      id,
      variables || {},
      context
    );
    res.json({
      code: 200,
      data: result,
      traceId: ctx.traceId
    } as ApiResponse);
  })
);

export default router;
