import { Router, Request, Response } from 'express';
import { asyncHandler, requireTenant, validateRequest, validateQuery, validateParams, RequestContext } from '../common/middleware';
import { paginationSchema, skillSchema, agentSchema, skillAssessmentSchema, idSchema } from '../common/validation/schemas';
import * as skillModule from '../modules/skill-graph';
import { ApiResponse, PaginationParams, SkillInput, AgentInput, SkillAssessmentInput } from '../common/types';

const router = Router();

router.post('/skills',
  requireTenant,
  validateRequest(skillSchema),
  asyncHandler(async (req: Request, res: Response) => {
    const ctx = req as RequestContext;
    const data = req.body as SkillInput;
    const result = await skillModule.createSkill(ctx.tenantId!, data, ctx.traceId);
    res.status(201).json({
      code: 201,
      data: result,
      traceId: ctx.traceId
    } as ApiResponse);
  })
);

router.get('/skills/tree',
  requireTenant,
  asyncHandler(async (req: Request, res: Response) => {
    const ctx = req as RequestContext;
    const result = await skillModule.getSkillTree(ctx.tenantId!);
    res.json({
      code: 200,
      data: result,
      traceId: ctx.traceId
    } as ApiResponse);
  })
);

router.get('/skills/:id',
  requireTenant,
  validateParams(idSchema),
  asyncHandler(async (req: Request, res: Response) => {
    const ctx = req as RequestContext;
    const { id } = req.params;
    const result = await skillModule.getSkillById(ctx.tenantId!, id, ctx.traceId);
    res.json({
      code: 200,
      data: result,
      traceId: ctx.traceId
    } as ApiResponse);
  })
);

router.get('/skills',
  requireTenant,
  validateQuery(paginationSchema),
  asyncHandler(async (req: Request, res: Response) => {
    const ctx = req as RequestContext;
    const params = req.query as unknown as PaginationParams & { category?: string; parentId?: string };
    const result = await skillModule.listSkills(ctx.tenantId!, params);
    res.json({
      code: 200,
      data: result,
      traceId: ctx.traceId
    } as ApiResponse);
  })
);

router.put('/skills/:id',
  requireTenant,
  validateParams(idSchema),
  validateRequest(skillSchema.partial()),
  asyncHandler(async (req: Request, res: Response) => {
    const ctx = req as RequestContext;
    const { id } = req.params;
    const data = req.body as Partial<SkillInput>;
    const result = await skillModule.updateSkill(ctx.tenantId!, id, data, ctx.traceId);
    res.json({
      code: 200,
      data: result,
      traceId: ctx.traceId
    } as ApiResponse);
  })
);

router.delete('/skills/:id',
  requireTenant,
  validateParams(idSchema),
  asyncHandler(async (req: Request, res: Response) => {
    const ctx = req as RequestContext;
    const { id } = req.params;
    await skillModule.deleteSkill(ctx.tenantId!, id, ctx.traceId);
    res.json({
      code: 200,
      message: 'Skill deleted successfully',
      traceId: ctx.traceId
    } as ApiResponse);
  })
);

router.post('/agents',
  requireTenant,
  validateRequest(agentSchema),
  asyncHandler(async (req: Request, res: Response) => {
    const ctx = req as RequestContext;
    const data = req.body as AgentInput;
    const result = await skillModule.createAgent(ctx.tenantId!, data, ctx.traceId);
    res.status(201).json({
      code: 201,
      data: result,
      traceId: ctx.traceId
    } as ApiResponse);
  })
);

router.get('/agents/:id',
  requireTenant,
  validateParams(idSchema),
  asyncHandler(async (req: Request, res: Response) => {
    const ctx = req as RequestContext;
    const { id } = req.params;
    const result = await skillModule.getAgentById(ctx.tenantId!, id, ctx.traceId);
    res.json({
      code: 200,
      data: result,
      traceId: ctx.traceId
    } as ApiResponse);
  })
);

router.get('/agents',
  requireTenant,
  validateQuery(paginationSchema),
  asyncHandler(async (req: Request, res: Response) => {
    const ctx = req as RequestContext;
    const params = req.query as unknown as PaginationParams & { status?: string; skillId?: string };
    const result = await skillModule.listAgents(ctx.tenantId!, params);
    res.json({
      code: 200,
      data: result,
      traceId: ctx.traceId
    } as ApiResponse);
  })
);

router.put('/agents/:id',
  requireTenant,
  validateParams(idSchema),
  validateRequest(agentSchema.partial()),
  asyncHandler(async (req: Request, res: Response) => {
    const ctx = req as RequestContext;
    const { id } = req.params;
    const data = req.body as Partial<AgentInput> & { skillIds?: string[] };
    const result = await skillModule.updateAgent(ctx.tenantId!, id, data, ctx.traceId);
    res.json({
      code: 200,
      data: result,
      traceId: ctx.traceId
    } as ApiResponse);
  })
);

router.delete('/agents/:id',
  requireTenant,
  validateParams(idSchema),
  asyncHandler(async (req: Request, res: Response) => {
    const ctx = req as RequestContext;
    const { id } = req.params;
    await skillModule.deleteAgent(ctx.tenantId!, id, ctx.traceId);
    res.json({
      code: 200,
      message: 'Agent deleted successfully',
      traceId: ctx.traceId
    } as ApiResponse);
  })
);

router.get('/agents/:id/skills',
  requireTenant,
  validateParams(idSchema),
  asyncHandler(async (req: Request, res: Response) => {
    const ctx = req as RequestContext;
    const { id } = req.params;
    const result = await skillModule.getAgentSkills(ctx.tenantId!, id, ctx.traceId);
    res.json({
      code: 200,
      data: result,
      traceId: ctx.traceId
    } as ApiResponse);
  })
);

router.post('/assessments',
  requireTenant,
  validateRequest(skillAssessmentSchema),
  asyncHandler(async (req: Request, res: Response) => {
    const ctx = req as RequestContext;
    const data = req.body as SkillAssessmentInput;
    const result = await skillModule.createSkillAssessment(ctx.tenantId!, data, ctx.traceId);
    res.status(201).json({
      code: 201,
      data: result,
      traceId: ctx.traceId
    } as ApiResponse);
  })
);

router.post('/agents/:id/learning-path',
  requireTenant,
  validateParams(idSchema),
  asyncHandler(async (req: Request, res: Response) => {
    const ctx = req as RequestContext;
    const { id } = req.params;
    const { targetSkillIds } = req.body as { targetSkillIds: string[] };
    const result = await skillModule.generateLearningPath(
      ctx.tenantId!,
      id,
      targetSkillIds,
      ctx.traceId
    );
    res.status(201).json({
      code: 201,
      data: result,
      traceId: ctx.traceId
    } as ApiResponse);
  })
);

router.get('/agents/:id/learning-paths',
  requireTenant,
  validateParams(idSchema),
  asyncHandler(async (req: Request, res: Response) => {
    const ctx = req as RequestContext;
    const { id } = req.params;
    const result = await skillModule.getLearningPaths(ctx.tenantId!, id, ctx.traceId);
    res.json({
      code: 200,
      data: result,
      traceId: ctx.traceId
    } as ApiResponse);
  })
);

router.get('/agents/:id/gap-analysis',
  requireTenant,
  validateParams(idSchema),
  asyncHandler(async (req: Request, res: Response) => {
    const ctx = req as RequestContext;
    const { id } = req.params;
    const { requiredSkillIds } = req.query;
    const skillIds = Array.isArray(requiredSkillIds)
      ? requiredSkillIds as string[]
      : [requiredSkillIds as string];
    const result = await skillModule.getSkillGapAnalysis(
      ctx.tenantId!,
      id,
      skillIds,
      ctx.traceId
    );
    res.json({
      code: 200,
      data: result,
      traceId: ctx.traceId
    } as ApiResponse);
  })
);

router.get('/skills/:id/recommend-agents',
  requireTenant,
  validateParams(idSchema),
  asyncHandler(async (req: Request, res: Response) => {
    const ctx = req as RequestContext;
    const { id } = req.params;
    const { minLevel, limit } = req.query;
    const result = await skillModule.recommendAgentsForSkill(
      ctx.tenantId!,
      id,
      minLevel ? Number(minLevel) : 0,
      limit ? Number(limit) : 10
    );
    res.json({
      code: 200,
      data: result,
      traceId: ctx.traceId
    } as ApiResponse);
  })
);

export default router;
