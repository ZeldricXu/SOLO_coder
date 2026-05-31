import express, { Express, Request, Response, NextFunction } from 'express';
import { GpuTaskScheduler } from './modules/gpu-scheduler';
import { DefaultDataProcessingService } from './modules/data-processing';
import { DefaultNotificationService } from './modules/notification';
import { PromptExperimentService } from './modules/prompt-experiment';
import { TaskExecutionTracker } from './modules/scheduler';
import { DefaultFeatureStoreService } from './modules/feature-store';
import { MonitoringService, defaultMonitoring } from './modules/monitoring';
import { AdversarialSampleGeneratorService } from './modules/adversarial';
import { DefaultCacheManager } from './infrastructure/cache';
import { createResourceRoutes, ApiDeps } from './api/routes/resources';
import { logger, BaseError, ApiResponse } from './common';

export interface AppConfig {
  port?: number;
  redisConfig?: { host: string; port: number; cluster?: boolean; nodes?: Array<{ host: string; port: number }> };
  gpuNodeConfigs?: Array<{ nodeId: string; gpus: Array<{ id: number; totalMemoryMb: number }> }>;
}

export class Application {
  private app: Express;
  private config: Required<Omit<AppConfig, 'redisConfig'>> & { redisConfig?: AppConfig['redisConfig'] };
  private deps: ApiDeps;
  private isRunning = false;

  constructor(config: AppConfig = {}) {
    this.config = {
      port: config.port || 3000,
      gpuNodeConfigs: config.gpuNodeConfigs || [
        { nodeId: 'node-1', gpus: [{ id: 0, totalMemoryMb: 8192 }, { id: 1, totalMemoryMb: 8192 }] }
      ],
      redisConfig: config.redisConfig
    };

    this.deps = this.initializeDependencies();
    this.app = this.initializeExpress();
  }

  private initializeDependencies(): ApiDeps {
    logger.info('Initializing dependencies...');

    const gpuScheduler = new GpuTaskScheduler({
      nodeConfigs: this.config.gpuNodeConfigs,
      maxConcurrentTasks: 10,
      preemptionStrategy: 'priority',
      enablePreemption: true
    });

    const dataProcessingService = new DefaultDataProcessingService();
    const notificationService = new DefaultNotificationService();
    const promptExperimentService = new PromptExperimentService();
    const taskTracker = new TaskExecutionTracker();
    const featureStore = new DefaultFeatureStoreService();
    const monitoringService = defaultMonitoring;
    const adversarialService = new AdversarialSampleGeneratorService();
    const cacheManager = new DefaultCacheManager(this.config.redisConfig);

    logger.info('Dependencies initialized successfully');

    return {
      gpuScheduler,
      dataProcessingService,
      notificationService,
      promptExperimentService,
      taskTracker,
      featureStore,
      monitoringService,
      adversarialService,
      cacheManager
    };
  }

  private initializeExpress(): Express {
    const app = express();

    app.use(express.json({ limit: '10mb' }));
    app.use(express.urlencoded({ extended: true }));

    app.use((req: Request, res: Response, next: NextFunction) => {
      const startTime = Date.now();
      res.on('finish', () => {
        const duration = (Date.now() - startTime) / 1000;
        this.deps.monitoringService.observeHistogram(
          'request_duration_seconds',
          { method: req.method, path: req.route?.path || req.path },
          duration
        );
      });
      next();
    });

    app.use(createResourceRoutes(this.deps));

    app.use((error: Error, req: Request, res: Response, next: NextFunction) => {
      logger.error('Unhandled error', { error: error.message, stack: error.stack });

      let response: ApiResponse;

      if (error instanceof BaseError) {
        response = {
          code: error.code,
          message: error.message,
          data: error.details
        };
        res.status(error.code).json(response);
      } else {
        response = {
          code: 500,
          message: 'Internal Server Error'
        };
        res.status(500).json(response);
      }

      this.deps.monitoringService.increment('errors_total', { type: error.name });
    });

    return app;
  }

  async start(): Promise<void> {
    if (this.isRunning) {
      return;
    }

    logger.info('Starting application...');

    await this.deps.gpuScheduler.start();

    this.app.listen(this.config.port, () => {
      logger.info(`Server is running on port ${this.config.port}`);
      logger.info(`Health check: http://localhost:${this.config.port}/health`);
      logger.info(`Metrics: http://localhost:${this.config.port}/api/v1/metrics`);
    });

    this.isRunning = true;
  }

  async stop(): Promise<void> {
    if (!this.isRunning) {
      return;
    }

    logger.info('Stopping application...');

    await this.deps.gpuScheduler.stop();
    await this.deps.monitoringService.stop();

    this.isRunning = false;
    logger.info('Application stopped');
  }

  getApp(): Express {
    return this.app;
  }

  getDependencies(): ApiDeps {
    return this.deps;
  }
}

export const createApp = (config?: AppConfig): Application => {
  return new Application(config);
};
