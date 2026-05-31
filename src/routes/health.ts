import { Router, Request, Response } from 'express';
import { monitoring } from '../monitoring';
import { coreProcessor } from '../core';
import { logger } from '../logging';

const router = Router();

router.get('/', (req: Request, res: Response) => {
  const health = {
    status: 'healthy',
    timestamp: new Date().toISOString(),
    uptime: process.uptime(),
    memory: process.memoryUsage(),
    services: {
      monitoring: 'healthy',
      core_processor: 'healthy',
      logging: logger.getLevel(),
    },
    metrics: {
      active_tasks: coreProcessor.getActiveTaskCount(),
      queue_size: coreProcessor.getQueueSize(),
      registered_handlers: coreProcessor.getHandlerNames(),
    },
  };

  monitoring.incrementCounter('health_checks', 1);
  res.json({ code: 200, data: health });
});

router.get('/metrics', (req: Request, res: Response) => {
  const startTime = Date.now() - 3600000;
  const endTime = Date.now();

  const report = monitoring.generateReport(startTime, endTime);
  res.json({ code: 200, data: report });
});

router.get('/snapshots', (req: Request, res: Response) => {
  const limit = req.query.limit ? parseInt(req.query.limit as string, 10) : 10;
  const snapshots = monitoring.getSnapshots(limit);
  res.json({ code: 200, data: snapshots });
});

export default router;
