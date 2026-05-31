import { Router, Request, Response } from 'express';
import { z } from 'zod';
import { coreProcessor } from '../core';
import { logger } from '../logging';
import { TaskCreateSchema, BatchOperationSchema } from '../shared/schemas';

const router = Router();

router.post('/', (req: Request, res: Response) => {
  try {
    const body = TaskCreateSchema.parse(req.body);
    const task = coreProcessor.createTask(body.type, body.config, body.labels);

    const traceId = req.headers['x-trace-id'] as string;
    if (req.query.async === 'true') {
      coreProcessor.queueTask(task.id, body.config, 0, traceId);
      res.status(202).json({ code: 202, data: { id: task.id, status: 'queued' } });
    } else {
      res.status(201).json({ code: 201, data: { id: task.id, status: 'provisioning' } });
    }
  } catch (error) {
    logger.error('Task creation failed', { error: (error as Error).message });
    res.status(400).json({ code: 400, error: (error as Error).message });
  }
});

router.post('/execute/:id', async (req: Request, res: Response) => {
  const traceId = req.headers['x-trace-id'] as string;
  const result = await coreProcessor.executeTask(req.params.id, req.body, traceId);

  if (!result.success) {
    res.status(500).json({ code: 500, error: result.error });
    return;
  }

  res.json({ code: 200, data: result });
});

router.get('/', (req: Request, res: Response) => {
  const status = req.query.status as any;
  const tasks = coreProcessor.listTasks(status);
  res.json({ code: 200, data: tasks });
});

router.get('/scheduled', (req: Request, res: Response) => {
  const tasks = coreProcessor.listScheduledTasks();
  res.json({ code: 200, data: tasks });
});

router.post('/schedule', (req: Request, res: Response) => {
  try {
    const { task_type, cron_expression, config, timezone } = req.body;
    const scheduled = coreProcessor.scheduleTask(task_type, cron_expression, config, timezone);
    res.status(201).json({ code: 201, data: scheduled });
  } catch (error) {
    logger.error('Task scheduling failed', { error: (error as Error).message });
    res.status(400).json({ code: 400, error: (error as Error).message });
  }
});

router.delete('/schedule/:id', (req: Request, res: Response) => {
  const cancelled = coreProcessor.cancelScheduledTask(req.params.id);
  if (!cancelled) {
    res.status(404).json({ code: 404, error: 'Scheduled task not found' });
    return;
  }
  res.json({ code: 200, message: 'Scheduled task cancelled' });
});

router.get('/:id', (req: Request, res: Response) => {
  const task = coreProcessor.getTask(req.params.id);
  if (!task) {
    res.status(404).json({ code: 404, error: 'Task not found' });
    return;
  }
  res.json({ code: 200, data: task });
});

router.get('/:id/status', (req: Request, res: Response) => {
  const status = coreProcessor.getTaskStatus(req.params.id);
  if (status === null) {
    res.status(404).json({ code: 404, error: 'Task not found' });
    return;
  }
  res.json({ code: 200, data: { id: req.params.id, status, progress: null } });
});

router.get('/:id/runs', (req: Request, res: Response) => {
  const runs = coreProcessor.getTaskRuns(req.params.id);
  res.json({ code: 200, data: runs });
});

router.get('/runs/:runId', (req: Request, res: Response) => {
  const run = coreProcessor.getRun(req.params.runId);
  if (!run) {
    res.status(404).json({ code: 404, error: 'Run not found' });
    return;
  }
  res.json({ code: 200, data: run });
});

router.delete('/:id', (req: Request, res: Response) => {
  const cancelled = coreProcessor.cancelTask(req.params.id);
  if (!cancelled) {
    res.status(404).json({ code: 404, error: 'Task not found or cannot be cancelled' });
    return;
  }
  res.json({ code: 200, message: 'Task cancelled' });
});

router.post('/batch', (req: Request, res: Response) => {
  try {
    const body = BatchOperationSchema.parse(req.body);
    const results: Array<{ id: string; action: string; success: boolean; error?: string }> = [];

    for (const op of body.operations) {
      try {
        let success = false;
        switch (op.action) {
          case 'start':
            success = coreProcessor.queueTask(op.id, {}, 0);
            break;
          case 'stop':
          case 'delete':
            success = coreProcessor.cancelTask(op.id);
            break;
          case 'restart':
            coreProcessor.cancelTask(op.id);
            success = coreProcessor.queueTask(op.id, {}, 0);
            break;
          default:
            success = false;
        }
        results.push({ id: op.id, action: op.action, success });
      } catch (error) {
        results.push({ id: op.id, action: op.action, success: false, error: (error as Error).message });
      }
    }

    res.json({
      code: 200,
      data: {
        batch_id: `batch_${Date.now()}`,
        results,
      },
    });
  } catch (error) {
    logger.error('Batch operation failed', { error: (error as Error).message });
    res.status(400).json({ code: 400, error: (error as Error).message });
  }
});

router.get('/queue/stats', (req: Request, res: Response) => {
  const stats = {
    queue_size: coreProcessor.getQueueSize(),
    active_tasks: coreProcessor.getActiveTaskCount(),
    handlers: coreProcessor.getHandlerNames(),
  };
  res.json({ code: 200, data: stats });
});

export default router;
