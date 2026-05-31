import { Router, Request, Response } from 'express';
import { PrismaClient } from '@prisma/client';
import { asyncHandler } from '../middleware/errorHandler';
import { generateResourceId, generateRunId } from '../utils/idGenerator';
import { z } from 'zod';

const prisma = new PrismaClient();
const router = Router();

const ResourceSchema = z.object({
  type: z.string().min(1),
  config: z.record(z.unknown()).optional(),
  labels: z.record(z.string()).optional(),
  attributes: z.record(z.unknown()).default({}),
});

const BatchOperationSchema = z.object({
  operations: z.array(z.object({
    action: z.enum(['start', 'stop', 'restart', 'delete']),
    id: z.string().min(1),
  })),
});

const getPaginationParams = (req: Request) => ({
  page: parseInt(req.query.page as string) || 1,
  pageSize: parseInt(req.query.pageSize as string) || 20,
});

router.post('/', asyncHandler(async (req: Request, res: Response) => {
  const data = ResourceSchema.parse(req.body);
  const resource = await prisma.resource.create({
    data: {
      id: generateResourceId(),
      type: data.type,
      status: 'provisioning',
      attributes: data.attributes,
      config: data.config,
      labels: data.labels,
    },
  });

  await prisma.run.create({
    data: {
      runId: generateRunId(),
      entityId: resource.id,
      phase: 'initializing',
      progress: 0,
      resourceId: resource.id,
    },
  });

  res.status(201).json({
    code: 201,
    data: {
      id: resource.id,
      status: resource.status,
    },
  });
}));

router.get('/', asyncHandler(async (req: Request, res: Response) => {
  const params = getPaginationParams(req);
  const type = req.query.type as string | undefined;
  const status = req.query.status as string | undefined;

  const where: Record<string, unknown> = {};
  if (type) where.type = type;
  if (status) where.status = status;

  const [items, total] = await Promise.all([
    prisma.resource.findMany({
      where,
      skip: (params.page - 1) * params.pageSize,
      take: params.pageSize,
      orderBy: { createdAt: 'desc' },
    }),
    prisma.resource.count({ where }),
  ]);

  res.json({
    code: 200,
    data: {
      items,
      total,
      page: params.page,
      pageSize: params.pageSize,
      totalPages: Math.ceil(total / params.pageSize),
    },
  });
}));

router.get('/:id', asyncHandler(async (req: Request, res: Response) => {
  const resource = await prisma.resource.findUnique({
    where: { id: req.params.id },
    include: { runs: { take: 10, orderBy: { createdAt: 'desc' } } },
  });
  if (!resource) {
    res.status(404).json({ code: 404, error: 'Resource not found' });
    return;
  }
  res.json({ code: 200, data: resource });
}));

router.get('/:id/status', asyncHandler(async (req: Request, res: Response) => {
  const resource = await prisma.resource.findUnique({
    where: { id: req.params.id },
    include: { runs: { take: 1, orderBy: { createdAt: 'desc' } } },
  });
  if (!resource) {
    res.status(404).json({ code: 404, error: 'Resource not found' });
    return;
  }
  const latestRun = resource.runs[0];
  res.json({
    code: 200,
    data: {
      id: resource.id,
      status: resource.status,
      progress: latestRun?.progress ?? 0,
      phase: latestRun?.phase,
      updatedAt: resource.updatedAt,
    },
  });
}));

router.put('/:id', asyncHandler(async (req: Request, res: Response) => {
  const data = ResourceSchema.partial().parse(req.body);
  const resource = await prisma.resource.update({
    where: { id: req.params.id },
    data: {
      ...(data.type && { type: data.type }),
      ...(data.config && { config: data.config }),
      ...(data.labels && { labels: data.labels }),
      ...(data.attributes && { attributes: data.attributes }),
    },
  });
  res.json({ code: 200, data: resource });
}));

router.delete('/:id', asyncHandler(async (req: Request, res: Response) => {
  await prisma.resource.delete({ where: { id: req.params.id } });
  res.json({ code: 200, message: 'Resource deleted' });
}));

router.post('/batch', asyncHandler(async (req: Request, res: Response) => {
  const data = BatchOperationSchema.parse(req.body);
  const results = [];

  for (const op of data.operations) {
    try {
      switch (op.action) {
        case 'start':
          await prisma.resource.update({
            where: { id: op.id },
            data: { status: 'running' },
          });
          results.push({ id: op.id, action: op.action, success: true });
          break;
        case 'stop':
          await prisma.resource.update({
            where: { id: op.id },
            data: { status: 'stopped' },
          });
          results.push({ id: op.id, action: op.action, success: true });
          break;
        case 'restart':
          await prisma.resource.update({
            where: { id: op.id },
            data: { status: 'restarting' },
          });
          results.push({ id: op.id, action: op.action, success: true });
          break;
        case 'delete':
          await prisma.resource.delete({ where: { id: op.id } });
          results.push({ id: op.id, action: op.action, success: true });
          break;
        default:
          results.push({ id: op.id, action: op.action, success: false, error: 'Unknown action' });
      }
    } catch (error) {
      results.push({
        id: op.id,
        action: op.action,
        success: false,
        error: error instanceof Error ? error.message : 'Unknown error',
      });
    }
  }

  res.json({
    code: 200,
    data: {
      batchId: `batch_${Date.now()}`,
      results,
    },
  });
}));

export default router;
