import express, { Express, Request, Response } from 'express';
import { logger, generateId, currentDateTime } from './utils/common';
import { monitoring } from './index';

export interface ServerConfig {
  port?: number;
  host?: string;
  enableMonitoring?: boolean;
}

export class InfrastructurePlatform {
  private app: Express;
  private config: Required<ServerConfig>;
  private server: any;

  constructor(config: ServerConfig = {}) {
    this.config = {
      port: config.port || 3000,
      host: config.host || '0.0.0.0',
      enableMonitoring: config.enableMonitoring !== false,
    };

    this.app = express();
    this.setupMiddleware();
    this.setupRoutes();
  }

  private setupMiddleware(): void {
    this.app.use(express.json({ limit: '10mb' }));
    this.app.use(express.urlencoded({ extended: true }));

    this.app.use((req: Request, res: Response, next) => {
      const startTime = Date.now();
      req.headers['x-request-id'] = req.headers['x-request-id'] || generateId('req_');

      res.on('finish', () => {
        const latency = Date.now() - startTime;
        const success = res.statusCode < 400;

        if (this.config.enableMonitoring) {
          monitoring.monitoringManager.recordRequest(latency, success);
        }

        logger.http(`${req.method} ${req.path}`, {
          status: res.statusCode,
          latency,
          requestId: req.headers['x-request-id'],
        });
      });

      next();
    });
  }

  private setupRoutes(): void {
    this.app.get('/health', (req: Request, res: Response) => {
      res.json({
        status: 'healthy',
        timestamp: currentDateTime(),
        version: '1.0.0',
      });
    });

    this.app.get('/api/v1/health', (req: Request, res: Response) => {
      const health = monitoring.monitoringManager.getHealth();
      res.json(health);
    });

    this.app.get('/api/v1/metrics', (req: Request, res: Response) => {
      const metrics = monitoring.monitoringManager.getMetricsCollector().getStats();
      res.json(metrics);
    });

    this.app.post('/api/v1/resources', (req: Request, res: Response) => {
      const { type, config, labels } = req.body;
      res.status(201).json({
        code: 201,
        data: {
          id: generateId('rsc_'),
          status: 'provisioning',
          type,
          labels,
        },
      });
    });

    this.app.get('/api/v1/resources/:id/status', (req: Request, res: Response) => {
      res.json({
        code: 200,
        data: {
          id: req.params.id,
          status: 'running',
          progress: 0.8,
        },
      });
    });

    this.app.post('/api/v1/resources/batch', (req: Request, res: Response) => {
      const { operations } = req.body;
      res.json({
        code: 200,
        data: {
          batchId: generateId('batch_'),
          results: operations.map((op: any) => ({
            id: op.id,
            action: op.action,
            status: 'completed',
          })),
        },
      });
    });

    this.app.use((req: Request, res: Response) => {
      res.status(404).json({
        error: 'Not Found',
        message: `Route ${req.method} ${req.path} not found`,
      });
    });
  }

  async start(): Promise<void> {
    return new Promise((resolve) => {
      this.server = this.app.listen(this.config.port, this.config.host, () => {
        logger.info(`Infrastructure Platform started`, {
          host: this.config.host,
          port: this.config.port,
        });

        if (this.config.enableMonitoring) {
          monitoring.monitoringManager.start();
          logger.info(`Monitoring enabled`);
        }

        resolve();
      });
    });
  }

  async stop(): Promise<void> {
    if (this.config.enableMonitoring) {
      monitoring.monitoringManager.stop();
    }

    if (this.server) {
      return new Promise((resolve, reject) => {
        this.server.close((err: Error) => {
          if (err) reject(err);
          else {
            logger.info(`Infrastructure Platform stopped`);
            resolve();
          }
        });
      });
    }
  }

  getApp(): Express {
    return this.app;
  }
}

if (require.main === module) {
  const platform = new InfrastructurePlatform({
    port: parseInt(process.env.PORT || '3000', 10),
  });

  platform.start().catch((error) => {
    logger.error(`Failed to start platform`, { error: error.message });
    process.exit(1);
  });

  process.on('SIGTERM', async () => {
    logger.info('SIGTERM received, shutting down...');
    await platform.stop();
    process.exit(0);
  });

  process.on('SIGINT', async () => {
    logger.info('SIGINT received, shutting down...');
    await platform.stop();
    process.exit(0);
  });
}

export default InfrastructurePlatform;
