import { Router } from 'express';
import tenantRoutes from './tenant';
import billingRoutes from './billing';
import ticketRoutes from './tickets';
import skillRoutes from './skills';
import slaRoutes from './sla';
import processRoutes from './processes';
import approvalRoutes from './approvals';
import documentRoutes from './documents';
import { getMetricsCollector } from '../common/metrics';
import { RequestContext } from '../common/middleware';
import { Request, Response } from 'express';

const router = Router();

router.get('/health', (_req: Request, res: Response) => {
  res.json({
    code: 200,
    data: {
      status: 'healthy',
      timestamp: new Date().toISOString(),
      uptime: process.uptime()
    }
  });
});

router.get('/metrics', (_req: Request, res: Response) => {
  const metrics = getMetricsCollector();
  res.json({
    code: 200,
    data: metrics.getSnapshot()
  });
});

router.post('/api/v1/resources', (req: Request, res: Response) => {
  const ctx = req as RequestContext;
  res.status(201).json({
    code: 201,
    data: {
      id: `rsc_${Date.now()}`,
      status: 'provisioning'
    },
    traceId: ctx.traceId
  });
});

router.get('/api/v1/resources/:id/status', (req: Request, res: Response) => {
  const ctx = req as RequestContext;
  res.json({
    code: 200,
    data: {
      id: req.params.id,
      status: 'completed',
      progress: 0.8
    },
    traceId: ctx.traceId
  });
});

router.post('/api/v1/resources/batch', (req: Request, res: Response) => {
  const ctx = req as RequestContext;
  res.json({
    code: 200,
    data: {
      batch_id: `batch_${Date.now()}`,
      results: req.body.operations || []
    },
    traceId: ctx.traceId
  });
});

router.use('/api/v1/tenants', tenantRoutes);
router.use('/api/v1/billing', billingRoutes);
router.use('/api/v1/tickets', ticketRoutes);
router.use('/api/v1', skillRoutes);
router.use('/api/v1/sla', slaRoutes);
router.use('/api/v1/processes', processRoutes);
router.use('/api/v1/approvals', approvalRoutes);
router.use('/api/v1/documents', documentRoutes);

export default router;
