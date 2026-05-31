import 'dotenv/config';
import { logger } from './logging';
import { createAPIGateway, APIGateway } from './api-gateway';
import { createCommandAuditModule, CommandAuditModule } from './command-audit';
import { createCoreProcessor, CoreProcessor } from './core-processing';
import { createConnectionPool, ConnectionPool, DatabaseConfig } from './data-access';
import { createDNSProxy, DNSProxy } from './dns-proxy';
import { createEventStore, EventStore } from './event-store';
import { createImageDistributionModule, ImageDistributionModule } from './image-distribution';
import { createSidecarLifecycleManager, SidecarLifecycleManager } from './sidecar';
import { createStorageManager, StorageManager, StorageConfig } from './storage';
import { Resource, RouteConfig } from './types';
import { v4 as uuidv4 } from 'uuid';
import express, { Express, Request, Response } from 'express';

export interface MiddlewareConfig {
  port?: number;
  database?: DatabaseConfig;
  storage?: StorageConfig;
}

export class EnterpriseMiddleware {
  private config: MiddlewareConfig;
  private apiGateway: APIGateway;
  private commandAudit: CommandAuditModule;
  private coreProcessor: CoreProcessor;
  private dnsProxy: DNSProxy;
  private eventStore: EventStore;
  private imageDistribution: ImageDistributionModule;
  private sidecarManager: SidecarLifecycleManager;
  private storageManager?: StorageManager;
  private dbPool?: ConnectionPool;
  private app: Express;
  private resources: Map<string, Resource> = new Map();

  constructor(config: MiddlewareConfig = {}) {
    this.config = { port: 3000, ...config };
    this.apiGateway = createAPIGateway();
    this.commandAudit = createCommandAuditModule();
    this.coreProcessor = createCoreProcessor();
    this.dnsProxy = createDNSProxy();
    this.eventStore = createEventStore();
    this.imageDistribution = createImageDistributionModule();
    this.sidecarManager = createSidecarLifecycleManager();
    this.app = express();
    
    if (config.database) {
      this.dbPool = createConnectionPool(config.database);
    }
    if (config.storage) {
      this.storageManager = createStorageManager(config.storage);
    }

    this.setupExpressApp();
  }

  private setupExpressApp(): void {
    this.app.use(express.json());

    this.app.post('/api/v1/resources', async (req: Request, res: Response) => {
      try {
        const { type, config = {}, labels = {} } = req.body;
        const resource: Resource = {
          id: `rsc_${uuidv4()}`,
          type,
          config,
          labels,
          status: 'provisioning',
          createdAt: new Date().toISOString()
        };
        this.resources.set(resource.id, resource);

        const command = await this.commandAudit.createCommand(
          'resource.create',
          { type, config, labels },
          req.headers['x-user-id'] as string || 'system'
        );

        await this.commandAudit.executeCommand(command.command_id, async () => {
          await this.coreProcessor.process({ type, config, labels });
          return resource;
        });

        resource.status = 'ready';
        res.status(201).json({ code: 201, data: { id: resource.id, status: resource.status } });
      } catch (error) {
        logger.error('Failed to create resource', error as Error);
        res.status(500).json({ code: 500, error: (error as Error).message });
      }
    });

    this.app.get('/api/v1/resources/:id/status', async (req: Request, res: Response) => {
      const resource = this.resources.get(req.params.id);
      if (!resource) {
        return res.status(404).json({ code: 404, error: 'Resource not found' });
      }
      res.json({ code: 200, data: { id: resource.id, status: resource.status, progress: 1.0 } });
    });

    this.app.post('/api/v1/resources/batch', async (req: Request, res: Response) => {
      const { operations } = req.body;
      const results: any[] = [];
      const batchId = `batch_${uuidv4()}`;

      for (const op of operations) {
        try {
          const resource = this.resources.get(op.id);
          if (resource) {
            resource.status = op.action === 'start' ? 'running' : resource.status;
            results.push({ id: op.id, success: true, action: op.action });
          } else {
            results.push({ id: op.id, success: false, error: 'Not found' });
          }
        } catch (error) {
          results.push({ id: op.id, success: false, error: (error as Error).message });
        }
      }

      res.json({ code: 200, data: { batch_id: batchId, results } });
    });

    this.app.post('/api/v1/gateway/route', async (req: Request, res: Response) => {
      const response = await this.apiGateway.routeRequest({
        method: req.body.method || 'GET',
        path: req.body.path || '/',
        headers: req.body.headers || {},
        body: req.body.body,
        query: req.body.query || {}
      });
      res.status(response.statusCode).json(response);
    });

    this.app.get('/api/v1/gateway/routes', (req: Request, res: Response) => {
      res.json({ code: 200, data: this.apiGateway.getRoutes() });
    });

    this.app.post('/api/v1/gateway/routes', (req: Request, res: Response) => {
      const route: RouteConfig = req.body;
      this.apiGateway.addRoute(route);
      res.status(201).json({ code: 201, data: route });
    });

    this.app.get('/api/v1/dns/query', async (req: Request, res: Response) => {
      const { domain, type = 'A' } = req.query;
      const result = await this.dnsProxy.query(domain as string, type as any);
      res.json({ code: 200, data: result });
    });

    this.app.get('/api/v1/audit/report', async (req: Request, res: Response) => {
      const { start, end } = req.query;
      const report = await this.commandAudit.generateComplianceReport(
        start as string || new Date(Date.now() - 24 * 60 * 60 * 1000).toISOString(),
        end as string || new Date().toISOString()
      );
      res.json({ code: 200, data: report });
    });

    this.app.get('/api/v1/metrics', (req: Request, res: Response) => {
      res.json({
        code: 200,
        data: {
          gateway: this.apiGateway.getMetrics(),
          sidecars: this.sidecarManager.getStats(),
          events: this.eventStore.getStats(),
          dns: this.dnsProxy.getStats(),
          commandAudit: this.commandAudit.getCacheStats(),
          imageDistribution: this.imageDistribution.getBatchStats()
        }
      });
    });

    this.app.get('/metrics', (req: Request, res: Response) => {
      res.set('Content-Type', 'text/plain; version=0.0.4');
      res.send(this.dnsProxy.getPrometheusMetrics());
    });

    this.app.get('/api/v1/dns/metrics', (req: Request, res: Response) => {
      res.json({ code: 200, data: this.dnsProxy.getMetrics() });
    });

    this.app.get('/api/v1/dns/health', async (req: Request, res: Response) => {
      const health = await this.dnsProxy.checkAllUpstreamsHealth();
      res.json({ code: 200, data: Object.fromEntries(health) });
    });

    this.app.post('/api/v1/dns/upstreams', (req: Request, res: Response) => {
      const upstream = this.dnsProxy.addUpstream(req.body);
      res.status(201).json({ code: 201, data: upstream });
    });

    this.app.post('/api/v1/command/batch', async (req: Request, res: Response) => {
      const { commandIds } = req.body;
      const result = await this.commandAudit.batchGetCommands(commandIds);
      res.json({
        code: 200,
        data: {
          found: Object.fromEntries(result.found),
          missing: result.missing,
          cachedFrom: Object.fromEntries(result.cachedFrom)
        }
      });
    });

    this.app.post('/api/v1/command/cache/invalidate', async (req: Request, res: Response) => {
      const { commandId } = req.body;
      const deleted = await this.commandAudit.invalidateCommandCache(commandId);
      res.json({ code: 200, data: { deletedCount: deleted } });
    });

    this.app.post('/api/v1/images/batch/pull', async (req: Request, res: Response) => {
      const { requests, options } = req.body;
      const result = await this.imageDistribution.batchPull(requests, options);
      res.json({
        code: 200,
        data: {
          success: Object.fromEntries(result.success),
          failed: Object.fromEntries(result.failed),
          totalDuration: result.totalDuration,
          networkRequests: result.networkRequests,
          bytesTransferred: result.bytesTransferred
        }
      });
    });

    this.app.post('/api/v1/images/batch/sync', async (req: Request, res: Response) => {
      const { requests, options } = req.body;
      const result = await this.imageDistribution.batchSync(requests, options);
      res.json({ code: 200, data: result });
    });

    this.app.post('/api/v1/images/batch/delete', async (req: Request, res: Response) => {
      const { requests, options } = req.body;
      const result = await this.imageDistribution.batchDelete(requests, options);
      res.json({ code: 200, data: result });
    });
  }

  async start(): Promise<void> {
    if (this.dbPool) {
      await this.dbPool.initialize();
    }

    this.app.listen(this.config.port, () => {
      logger.info('Enterprise Middleware started', { port: this.config.port });
    });
  }

  async stop(): Promise<void> {
    if (this.dbPool) {
      await this.dbPool.close();
    }
    logger.info('Enterprise Middleware stopped');
  }

  getAPIGateway(): APIGateway { return this.apiGateway; }
  getCommandAudit(): CommandAuditModule { return this.commandAudit; }
  getCoreProcessor(): CoreProcessor { return this.coreProcessor; }
  getDNSProxy(): DNSProxy { return this.dnsProxy; }
  getEventStore(): EventStore { return this.eventStore; }
  getImageDistribution(): ImageDistributionModule { return this.imageDistribution; }
  getSidecarManager(): SidecarLifecycleManager { return this.sidecarManager; }
  getStorageManager(): StorageManager | undefined { return this.storageManager; }
  getDbPool(): ConnectionPool | undefined { return this.dbPool; }
}

export const createMiddleware = (config?: MiddlewareConfig): EnterpriseMiddleware => {
  return new EnterpriseMiddleware(config);
};

if (require.main === module) {
  const middleware = createMiddleware({ port: 3000 });
  middleware.start().catch(error => {
    logger.fatal('Failed to start middleware', error as Error);
  });
}

export { logger };
export * from './types';
export * from './logging';
export * from './api-gateway';
export * from './command-audit';
export * from './core-processing';
export * from './data-access';
export * from './dns-proxy';
export * from './event-store';
export * from './image-distribution';
export * from './sidecar';
export * from './storage';
