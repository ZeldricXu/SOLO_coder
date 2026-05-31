import { Router, Request, Response } from 'express';
import { generateId, createContext, logger, ApiResponse, ValidationError } from '../../common';
import { GpuTaskScheduler } from '../../modules/gpu-scheduler';
import { DefaultDataProcessingService } from '../../modules/data-processing';
import { DefaultNotificationService } from '../../modules/notification';
import { PromptExperimentService } from '../../modules/prompt-experiment';
import { TaskExecutionTracker } from '../../modules/scheduler';
import { DefaultFeatureStoreService } from '../../modules/feature-store';
import { MonitoringService } from '../../modules/monitoring';
import { AdversarialSampleGeneratorService } from '../../modules/adversarial';
import { DefaultCacheManager } from '../../infrastructure/cache';

const router = Router();

export interface ApiDeps {
  gpuScheduler: GpuTaskScheduler;
  dataProcessingService: DefaultDataProcessingService;
  notificationService: DefaultNotificationService;
  promptExperimentService: PromptExperimentService;
  taskTracker: TaskExecutionTracker;
  featureStore: DefaultFeatureStoreService;
  monitoringService: MonitoringService;
  adversarialService: AdversarialSampleGeneratorService;
  cacheManager: DefaultCacheManager;
}

export function createResourceRoutes(deps: ApiDeps) {
  router.post('/api/v1/resources', async (req: Request, res: Response) => {
    const ctx = createContext(req.headers['x-namespace'] as string || 'default');
    const { type, config, labels } = req.body;

    logger.info('Creating resource', { type });

    const resourceId = generateId('resource');

    const response: ApiResponse = {
      code: 201,
      data: {
        id: resourceId,
        status: 'provisioning'
      }
    };

    deps.monitoringService.increment('requests_total', { method: 'POST', path: '/api/v1/resources', status: '201' });

    res.status(201).json(response);
  });

  router.get('/api/v1/resources/:id/status', async (req: Request, res: Response) => {
    const { id } = req.params;

    const taskStatus = await deps.gpuScheduler.getStatus(id);
    const execution = await deps.taskTracker.getExecution(id);

    let status = 'unknown';
    let progress = 0;

    if (taskStatus) {
      status = taskStatus.status;
      progress = taskStatus.progress;
    } else if (execution) {
      status = execution.phase;
      progress = execution.progress;
    }

    const response: ApiResponse = {
      code: 200,
      data: {
        id,
        status,
        progress
      }
    };

    deps.monitoringService.increment('requests_total', { method: 'GET', path: '/api/v1/resources/:id/status', status: '200' });
    res.json(response);
  });

  router.post('/api/v1/resources/batch', async (req: Request, res: Response) => {
    const ctx = createContext('default');
    const { operations } = req.body;

    const batchId = generateId('batch');
    const results = [];

    for (const op of operations) {
      try {
        if (op.action === 'restart') {
          const cancelled = await deps.gpuScheduler.cancel(op.id);
          results.push({ id: op.id, success: cancelled, action: op.action });
        } else {
          results.push({ id: op.id, success: false, action: op.action, error: 'Unknown action' });
        }
      } catch (error) {
        results.push({ id: op.id, success: false, action: op.action, error: (error as Error).message });
      }
    }

    const response: ApiResponse = {
      code: 200,
      data: {
        batchId,
        results
      }
    };

    deps.monitoringService.increment('requests_total', { method: 'POST', path: '/api/v1/resources/batch', status: '200' });
    res.json(response);
  });

  router.get('/api/v1/metrics', (req: Request, res: Response) => {
    const snapshot = deps.monitoringService.takeSnapshot();
    res.json({ code: 200, data: snapshot });
  });

  router.get('/api/v1/metrics/prometheus', (req: Request, res: Response) => {
    const metrics = deps.monitoringService.exportPrometheusFormat();
    res.set('Content-Type', 'text/plain');
    res.send(metrics);
  });

  router.get('/health', (req: Request, res: Response) => {
    const gpuStats = deps.gpuScheduler.getResourceManager().getAvailableResources();
    const taskStats = deps.taskTracker.getStatistics();

    res.json({
      status: 'healthy',
      timestamp: new Date().toISOString(),
      gpu: gpuStats,
      tasks: taskStats
    });
  });

  return router;
}

export { router as resourceRouter };
