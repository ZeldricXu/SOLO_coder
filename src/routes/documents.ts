import { Router, Request, Response } from 'express';
import { asyncHandler, requireTenant, validateRequest, validateQuery, validateParams, RequestContext } from '../common/middleware';
import { paginationSchema, documentSchema, idSchema } from '../common/validation/schemas';
import * as documentModule from '../modules/document-compare';
import { ApiResponse, PaginationParams, DocumentInput, ProcessingContext } from '../common/types';

const router = Router();

router.post('/',
  requireTenant,
  validateRequest(documentSchema),
  asyncHandler(async (req: Request, res: Response) => {
    const ctx = req as RequestContext;
    const data = req.body as DocumentInput;
    const result = await documentModule.createDocument(ctx.tenantId!, data, ctx.traceId);
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
    const result = await documentModule.getDocumentById(ctx.tenantId!, id, ctx.traceId);
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
    const params = req.query as unknown as PaginationParams;
    const result = await documentModule.listDocuments(ctx.tenantId!, params);
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
    await documentModule.deleteDocument(ctx.tenantId!, id, ctx.traceId);
    res.json({
      code: 200,
      message: 'Document deleted successfully',
      traceId: ctx.traceId
    } as ApiResponse);
  })
);

router.post('/:id/versions',
  requireTenant,
  validateParams(idSchema),
  asyncHandler(async (req: Request, res: Response) => {
    const ctx = req as RequestContext;
    const { id } = req.params;
    const { content, createdBy } = req.body as { content: string; createdBy?: string };
    const result = await documentModule.createDocumentVersion(
      ctx.tenantId!,
      id,
      content,
      createdBy,
      ctx.traceId
    );
    res.status(201).json({
      code: 201,
      data: result,
      traceId: ctx.traceId
    } as ApiResponse);
  })
);

router.get('/:id/versions/:version',
  requireTenant,
  validateParams(idSchema),
  asyncHandler(async (req: Request, res: Response) => {
    const ctx = req as RequestContext;
    const { id, version } = req.params;
    const result = await documentModule.getDocumentVersion(
      ctx.tenantId!,
      id,
      Number(version),
      ctx.traceId
    );
    res.json({
      code: 200,
      data: result,
      traceId: ctx.traceId
    } as ApiResponse);
  })
);

router.get('/:id/compare',
  requireTenant,
  validateParams(idSchema),
  asyncHandler(async (req: Request, res: Response) => {
    const ctx = req as RequestContext;
    const { id } = req.params;
    const { fromVersion, toVersion } = req.query;
    const context: Partial<ProcessingContext> = { traceId: ctx.traceId, tenantId: ctx.tenantId };
    const result = await documentModule.compareDocuments(
      ctx.tenantId!,
      id,
      Number(fromVersion),
      Number(toVersion),
      context
    );
    res.json({
      code: 200,
      data: result,
      traceId: ctx.traceId
    } as ApiResponse);
  })
);

router.post('/compare/direct',
  requireTenant,
  asyncHandler(async (req: Request, res: Response) => {
    const ctx = req as RequestContext;
    const { oldText, newText } = req.body as { oldText: string; newText: string };
    const result = documentModule.compareTextDirect(oldText, newText);
    res.json({
      code: 200,
      data: result,
      traceId: ctx.traceId
    } as ApiResponse);
  })
);

router.post('/batch/compare',
  requireTenant,
  asyncHandler(async (req: Request, res: Response) => {
    const ctx = req as RequestContext;
    const { comparisons } = req.body as {
      comparisons: Array<{ documentId: string; fromVersion: number; toVersion: number }>;
    };
    const context: Partial<ProcessingContext> = { traceId: ctx.traceId, tenantId: ctx.tenantId };
    const result = await documentModule.batchCompare(ctx.tenantId!, comparisons, context);
    res.json({
      code: 200,
      data: result,
      traceId: ctx.traceId
    } as ApiResponse);
  })
);

router.get('/:id/comparisons',
  requireTenant,
  validateParams(idSchema),
  validateQuery(paginationSchema),
  asyncHandler(async (req: Request, res: Response) => {
    const ctx = req as RequestContext;
    const { id } = req.params;
    const params = req.query as unknown as PaginationParams;
    const result = await documentModule.getDocumentComparisons(
      ctx.tenantId!,
      id,
      params,
      ctx.traceId
    );
    res.json({
      code: 200,
      data: result,
      traceId: ctx.traceId
    } as ApiResponse);
  })
);

router.get('/comparisons/:id',
  requireTenant,
  validateParams(idSchema),
  asyncHandler(async (req: Request, res: Response) => {
    const ctx = req as RequestContext;
    const { id } = req.params;
    const result = await documentModule.getComparisonById(ctx.tenantId!, id, ctx.traceId);
    res.json({
      code: 200,
      data: result,
      traceId: ctx.traceId
    } as ApiResponse);
  })
);

router.get('/stats/summary',
  requireTenant,
  asyncHandler(async (req: Request, res: Response) => {
    const ctx = req as RequestContext;
    const result = await documentModule.getDocumentStats(ctx.tenantId!);
    res.json({
      code: 200,
      data: result,
      traceId: ctx.traceId
    } as ApiResponse);
  })
);

export default router;
