import express, { Request, Response, NextFunction, Express } from 'express';
import * as http from 'http';
import { ConfigManager, EnvConfigSource, FileConfigSource } from './modules/config';
import { Logger, getLogger } from './modules/logging';
import { ApiGateway, AuthService, RateLimiter, MultiTenantRateLimiter } from './modules/gateway';
import { CoreProcessor } from './modules/core';
import { TaskScheduler } from './modules/scheduler';
import { BillingManager } from './modules/billing';
import { SkillGraphManager } from './modules/skill-graph';
import { FlowDesigner } from './modules/flow-designer';
import { StorageManager } from './modules/storage';
import { ConnectionPool, QueryOptimizer, BaseRepository } from './modules/data-access';
import { AppError, handleError, isAppError } from './common/errors';
import { getCurrentTimestamp, generateUUID } from './common/utils';
import { AppConfig } from './types/config';

export interface AppDependencies {
  configManager: ConfigManager;
  logger: Logger;
  authService: AuthService;
  rateLimiter: RateLimiter;
  multiTenantRateLimiter: MultiTenantRateLimiter;
  apiGateway: ApiGateway;
  coreProcessor: CoreProcessor;
  taskScheduler: TaskScheduler;
  billingManager: BillingManager;
  skillGraphManager: SkillGraphManager;
  flowDesigner: FlowDesigner;
  storageManager: StorageManager;
  connectionPool?: ConnectionPool;
  queryOptimizer?: QueryOptimizer;
}

export class PlatformApplication {
  private app: Express;
  private server?: http.Server;
  private deps: AppDependencies;
  private isRunning: boolean;

  constructor(deps: AppDependencies) {
    this.deps = deps;
    this.app = express();
    this.isRunning = false;

    this.configureMiddleware();
    this.configureRoutes();
    this.configureErrorHandler();
  }

  private configureMiddleware(): void {
    const { logger, apiGateway } = this.deps;

    this.app.use(express.json({ limit: '10mb' }));
    this.app.use(express.urlencoded({ extended: true }));

    this.app.use((req: Request, res: Response, next: NextFunction) => {
      (req as any).requestId = generateUUID();
      (req as any).startTime = Date.now();
      res.setHeader('X-Request-ID', (req as any).requestId);
      next();
    });

    this.app.use((req: Request, res: Response, next: NextFunction) => {
      logger.info(`请求开始: ${req.method} ${req.path}`, {
        module: 'http',
        traceId: (req as any).requestId,
        metadata: {
          method: req.method,
          path: req.path,
          ip: req.ip,
          userAgent: req.get('user-agent')
        }
      });
      next();
    });

    this.app.use(apiGateway.rateLimit('ip'));
  }

  private configureRoutes(): void {
    const { coreProcessor, billingManager, taskScheduler, skillGraphManager, flowDesigner, storageManager, logger } = this.deps;

    coreProcessor.registerHandler({
      name: 'health.check',
      handler: () => ({ status: 'healthy', timestamp: getCurrentTimestamp() })
    });

    coreProcessor.registerHandler({
      name: 'echo',
      handler: (payload: unknown) => payload
    });

    this.app.get('/health', async (req: Request, res: Response) => {
      const result = await coreProcessor.processRequest('health.check', {});
      res.json(coreProcessor.buildSuccessResponse(result.data));
    });

    this.app.get('/api/v1/stats', (req: Request, res: Response) => {
      res.json(coreProcessor.buildSuccessResponse({
        core: coreProcessor.getStats(),
        scheduler: taskScheduler.getStats(),
        billing: billingManager.getStats(),
        skillGraph: skillGraphManager.getStats(),
        flowDesigner: flowDesigner.getStats(),
        storage: storageManager.getStats(),
        logger: logger.getStats()
      }));
    });

    this.app.post('/api/v1/resources', async (req: Request, res: Response) => {
      try {
        const { type, config, labels } = req.body;
        const tenantId = (req as any).tenantId;
        const userId = (req as any).userId;

        const resource = coreProcessor.createResource(type, config, labels, { tenantId, userId });
        res.status(201).json(coreProcessor.buildSuccessResponse(resource));
      } catch (error) {
        res.json(coreProcessor.buildErrorResponse(error));
      }
    });

    this.app.get('/api/v1/resources/:id/status', (req: Request, res: Response) => {
      try {
        const resource = coreProcessor.getResource(req.params.id);
        res.json(coreProcessor.buildSuccessResponse({
          id: resource.id,
          status: resource.status,
          progress: resource.attributes.progress || 0
        }));
      } catch (error) {
        res.json(coreProcessor.buildErrorResponse(error));
      }
    });

    this.app.get('/api/v1/resources', (req: Request, res: Response) => {
      try {
        const { type, status, tenantId } = req.query;
        const resources = coreProcessor.listResources({
          type: type as any,
          status: status as any,
          tenantId: tenantId as string
        });
        res.json(coreProcessor.buildSuccessResponse(resources));
      } catch (error) {
        res.json(coreProcessor.buildErrorResponse(error));
      }
    });

    this.app.post('/api/v1/resources/batch', async (req: Request, res: Response) => {
      try {
        const { operations } = req.body;
        const results = await Promise.all(
          operations.map(async (op: { action: string; id: string }) => {
            if (op.action === 'start') {
              const instance = coreProcessor.startRunInstance(op.id);
              return { id: op.id, action: op.action, status: 'started', runId: instance.run_id };
            }
            return { id: op.id, action: op.action, status: 'unknown' };
          })
        );
        res.json(coreProcessor.buildSuccessResponse({ batchId: generateUUID(), results }));
      } catch (error) {
        res.json(coreProcessor.buildErrorResponse(error));
      }
    });

    this.app.post('/api/v1/tasks', (req: Request, res: Response) => {
      try {
        const { type, payload, schedule, priority } = req.body;
        const task = taskScheduler.createTask(type, payload, {
          schedule,
          priority,
          tenantId: (req as any).tenantId,
          createdBy: (req as any).userId
        });
        res.status(201).json(coreProcessor.buildSuccessResponse(task));
      } catch (error) {
        res.json(coreProcessor.buildErrorResponse(error));
      }
    });

    this.app.get('/api/v1/tasks', (req: Request, res: Response) => {
      try {
        const { status, type } = req.query;
        const tasks = taskScheduler.listTasks({
          status: status as any,
          type: type as string,
          tenantId: (req as any).tenantId
        });
        res.json(coreProcessor.buildSuccessResponse(tasks));
      } catch (error) {
        res.json(coreProcessor.buildErrorResponse(error));
      }
    });

    this.app.post('/api/v1/tasks/:id/execute', async (req: Request, res: Response) => {
      try {
        const execution = await taskScheduler.executeTask(req.params.id);
        res.json(coreProcessor.buildSuccessResponse(execution));
      } catch (error) {
        res.json(coreProcessor.buildErrorResponse(error));
      }
    });

    this.app.post('/api/v1/logs/level', (req: Request, res: Response) => {
      try {
        const { level, module } = req.body;
        logger.setLevel(level, module);
        res.json(coreProcessor.buildSuccessResponse({ level, module, updated: true }));
      } catch (error) {
        res.json(coreProcessor.buildErrorResponse(error));
      }
    });

    this.app.get('/api/v1/logs', (req: Request, res: Response) => {
      try {
        const { level, module, limit } = req.query;
        const logs = logger.getLogs({
          level: level as any,
          module: module as string,
          limit: limit ? parseInt(limit as string, 10) : 100
        });
        res.json(coreProcessor.buildSuccessResponse(logs));
      } catch (error) {
        res.json(coreProcessor.buildErrorResponse(error));
      }
    });

    this.app.post('/api/v1/skills', (req: Request, res: Response) => {
      try {
        const { name, description, category, levels, prerequisites, tags } = req.body;
        const skill = skillGraphManager.createSkill(name, description, category, levels, prerequisites, tags);
        res.status(201).json(coreProcessor.buildSuccessResponse(skill));
      } catch (error) {
        res.json(coreProcessor.buildErrorResponse(error));
      }
    });

    this.app.get('/api/v1/skills', (req: Request, res: Response) => {
      try {
        const { category } = req.query;
        const skills = skillGraphManager.listSkills({ category: category as any });
        res.json(coreProcessor.buildSuccessResponse(skills));
      } catch (error) {
        res.json(coreProcessor.buildErrorResponse(error));
      }
    });

    this.app.post('/api/v1/employees', (req: Request, res: Response) => {
      try {
        const { name, email, department, position } = req.body;
        const employee = skillGraphManager.addEmployee(name, email, department, position);
        res.status(201).json(coreProcessor.buildSuccessResponse(employee));
      } catch (error) {
        res.json(coreProcessor.buildErrorResponse(error));
      }
    });

    this.app.get('/api/v1/employees', (req: Request, res: Response) => {
      try {
        const { department } = req.query;
        const employees = skillGraphManager.listEmployees(department as string);
        res.json(coreProcessor.buildSuccessResponse(employees));
      } catch (error) {
        res.json(coreProcessor.buildErrorResponse(error));
      }
    });

    this.app.post('/api/v1/employees/:id/skills', (req: Request, res: Response) => {
      try {
        const { skillId, level, assessor, notes } = req.body;
        const employeeSkill = skillGraphManager.setEmployeeSkill(req.params.id, skillId, level, assessor, undefined, notes);
        res.json(coreProcessor.buildSuccessResponse(employeeSkill));
      } catch (error) {
        res.json(coreProcessor.buildErrorResponse(error));
      }
    });

    this.app.get('/api/v1/employees/:id/skills', (req: Request, res: Response) => {
      try {
        const skills = skillGraphManager.getEmployeeSkillsWithDetails(req.params.id);
        res.json(coreProcessor.buildSuccessResponse(skills));
      } catch (error) {
        res.json(coreProcessor.buildErrorResponse(error));
      }
    });

    this.app.post('/api/v1/billing/usage', (req: Request, res: Response) => {
      try {
        const { tenantId, resourceType, quantity } = req.body;
        const record = billingManager.recordUsage(tenantId, resourceType, quantity);
        res.status(201).json(coreProcessor.buildSuccessResponse(record));
      } catch (error) {
        res.json(coreProcessor.buildErrorResponse(error));
      }
    });

    this.app.get('/api/v1/billing/invoices', (req: Request, res: Response) => {
      try {
        const { tenantId, status } = req.query;
        const invoices = billingManager.listInvoices(tenantId as string, status as any);
        res.json(coreProcessor.buildSuccessResponse(invoices));
      } catch (error) {
        res.json(coreProcessor.buildErrorResponse(error));
      }
    });

    this.app.post('/api/v1/billing/invoices/:id/issue', (req: Request, res: Response) => {
      try {
        const invoice = billingManager.issueInvoice(req.params.id);
        res.json(coreProcessor.buildSuccessResponse(invoice));
      } catch (error) {
        res.json(coreProcessor.buildErrorResponse(error));
      }
    });

    this.app.get('/api/v1/billing/tenants/:id/summary', (req: Request, res: Response) => {
      try {
        const summary = billingManager.getTenantBillingSummary(req.params.id);
        res.json(coreProcessor.buildSuccessResponse(summary));
      } catch (error) {
        res.json(coreProcessor.buildErrorResponse(error));
      }
    });

    this.app.post('/api/v1/flows', (req: Request, res: Response) => {
      try {
        const { name, createdBy } = req.body;
        const flow = flowDesigner.createFlow(name, createdBy);
        res.status(201).json(coreProcessor.buildSuccessResponse(flow));
      } catch (error) {
        res.json(coreProcessor.buildErrorResponse(error));
      }
    });

    this.app.get('/api/v1/flows', (req: Request, res: Response) => {
      try {
        const flows = flowDesigner.listFlows();
        res.json(coreProcessor.buildSuccessResponse(flows));
      } catch (error) {
        res.json(coreProcessor.buildErrorResponse(error));
      }
    });

    this.app.post('/api/v1/flows/:id/nodes', (req: Request, res: Response) => {
      try {
        const node = flowDesigner.addNode(req.params.id, req.body);
        res.status(201).json(coreProcessor.buildSuccessResponse(node));
      } catch (error) {
        res.json(coreProcessor.buildErrorResponse(error));
      }
    });

    this.app.post('/api/v1/flows/:id/connections', (req: Request, res: Response) => {
      try {
        const connection = flowDesigner.addConnection(req.params.id, req.body);
        res.status(201).json(coreProcessor.buildSuccessResponse(connection));
      } catch (error) {
        res.json(coreProcessor.buildErrorResponse(error));
      }
    });

    this.app.post('/api/v1/flows/:id/validate', (req: Request, res: Response) => {
      try {
        const validation = flowDesigner.validateFlow(req.params.id);
        res.json(coreProcessor.buildSuccessResponse(validation));
      } catch (error) {
        res.json(coreProcessor.buildErrorResponse(error));
      }
    });

    this.app.post('/api/v1/storage/backup', async (req: Request, res: Response) => {
      try {
        const { type, source } = req.body;
        const backup = await storageManager.createBackup(type, source);
        res.status(201).json(coreProcessor.buildSuccessResponse(backup));
      } catch (error) {
        res.json(coreProcessor.buildErrorResponse(error));
      }
    });

    this.app.get('/api/v1/storage/backups', (req: Request, res: Response) => {
      try {
        const backups = storageManager.listBackups();
        res.json(coreProcessor.buildSuccessResponse(backups));
      } catch (error) {
        res.json(coreProcessor.buildErrorResponse(error));
      }
    });

    this.app.post('/api/v1/storage/backups/:id/restore', async (req: Request, res: Response) => {
      try {
        const restore = await storageManager.restoreBackup(req.params.id);
        res.json(coreProcessor.buildSuccessResponse(restore));
      } catch (error) {
        res.json(coreProcessor.buildErrorResponse(error));
      }
    });

    this.app.get('*', (req: Request, res: Response) => {
      res.status(404).json(coreProcessor.buildErrorResponse(new AppError('路由不存在', 'NOT_FOUND', 404)));
    });
  }

  private configureErrorHandler(): void {
    const { logger } = this.deps;

    this.app.use((err: unknown, req: Request, res: Response, _next: NextFunction) => {
      const appError = handleError(err);
      const duration = Date.now() - ((req as any).startTime || Date.now());

      logger.error(`请求错误: ${req.method} ${req.path}`, {
        module: 'http',
        traceId: (req as any).requestId,
        error: err,
        metadata: {
          method: req.method,
          path: req.path,
          statusCode: appError.statusCode,
          durationMs: duration
        }
      });

      res.status(appError.statusCode).json({
        code: appError.statusCode,
        error: appError.code,
        message: appError.message,
        details: appError.details,
        traceId: (req as any).requestId
      });
    });
  }

  async start(port: number = 3000): Promise<void> {
    if (this.isRunning) return;

    const { logger, taskScheduler, configManager } = this.deps;

    taskScheduler.registerHandler('cleanup.logs', async () => {
      logger.info('执行日志清理任务', { module: 'scheduler' });
    });

    taskScheduler.registerHandler('billing.generate_invoice', async (task) => {
      const { tenantId } = task.payload as { tenantId: string };
      logger.info(`为租户 ${tenantId} 生成账单`, { module: 'billing' });
      billingManager.generateInvoice(tenantId);
    });

    taskScheduler.start();

    this.server = this.app.listen(port, () => {
      logger.info(`平台服务已启动，端口: ${port}`, { module: 'app' });
      logger.info(`配置已加载: ${JSON.stringify(configManager.getAppConfig())}`, { module: 'app' });
    });

    this.isRunning = true;
  }

  async stop(): Promise<void> {
    if (!this.isRunning) return;

    const { logger, taskScheduler } = this.deps;

    taskScheduler.stop();

    if (this.server) {
      this.server.close(() => {
        logger.info('HTTP 服务器已关闭', { module: 'app' });
      });
    }

    this.isRunning = false;
    logger.info('平台服务已停止', { module: 'app' });
  }

  getExpressApp(): Express {
    return this.app;
  }

  getDependencies(): AppDependencies {
    return this.deps;
  }

  isServerRunning(): boolean {
    return this.isRunning;
  }
}

export async function createApplication(): Promise<PlatformApplication> {
  const configManager = new ConfigManager();
  configManager.addSource(new EnvConfigSource());

  try {
    configManager.addSource(new FileConfigSource('.env', 'env'));
  } catch {
    // 忽略 .env 文件不存在的情况
  }

  await configManager.initialize();

  const logger = getLogger({
    defaultLevel: (process.env.LOG_LEVEL as any) || 'info',
    jsonFormat: process.env.LOG_JSON === 'true',
    levelOverrides: {
      'http': 'info',
      'app': 'info',
      'scheduler': 'info'
    }
  });

  const authService = new AuthService({
    jwtSecret: process.env.JWT_SECRET || 'default-secret-key',
    jwtExpiresIn: process.env.JWT_EXPIRES_IN || '24h'
  });

  const rateLimiter = new RateLimiter({
    maxRequests: parseInt(process.env.RATE_LIMIT_MAX || '100', 10),
    windowMs: parseInt(process.env.RATE_LIMIT_WINDOW || '60000', 10)
  });

  const multiTenantRateLimiter = new MultiTenantRateLimiter({
    maxRequestsPerTenant: parseInt(process.env.TENANT_RATE_LIMIT_MAX || '1000', 10),
    windowMs: parseInt(process.env.TENANT_RATE_LIMIT_WINDOW || '60000', 10)
  });

  const apiGateway = new ApiGateway(authService, rateLimiter, multiTenantRateLimiter);
  const coreProcessor = new CoreProcessor({
    maxConcurrentRequests: parseInt(process.env.MAX_CONCURRENT || '100', 10)
  });

  const taskScheduler = new TaskScheduler({
    maxConcurrentTasks: parseInt(process.env.SCHEDULER_MAX_CONCURRENT || '10', 10),
    retryAttempts: parseInt(process.env.SCHEDULER_RETRY || '3', 10)
  });

  const billingManager = new BillingManager({
    pricePerApiCall: parseFloat(process.env.PRICE_API_CALL || '0.001'),
    pricePerStorageGb: parseFloat(process.env.PRICE_STORAGE || '0.05'),
    pricePerComputeUnit: parseFloat(process.env.PRICE_COMPUTE || '0.01'),
    pricePerBandwidthGb: parseFloat(process.env.PRICE_BANDWIDTH || '0.02'),
    currency: process.env.BILLING_CURRENCY || 'USD',
    cycleDays: parseInt(process.env.BILLING_CYCLE_DAYS || '30', 10)
  });

  const skillGraphManager = new SkillGraphManager();
  const flowDesigner = new FlowDesigner();

  const storageManager = new StorageManager({
    backupDir: process.env.BACKUP_DIR || './backups',
    compressionLevel: parseInt(process.env.BACKUP_COMPRESSION || '6', 10),
    encryption: process.env.BACKUP_ENCRYPTION === 'true'
  });

  let connectionPool: ConnectionPool | undefined;
  let queryOptimizer: QueryOptimizer | undefined;

  if (process.env.DATABASE_URL) {
    try {
      connectionPool = new ConnectionPool({
        connectionString: process.env.DATABASE_URL,
        maxConnections: parseInt(process.env.DB_MAX_CONN || '20', 10)
      });
      queryOptimizer = new QueryOptimizer();
      logger.info('数据库连接池已初始化', { module: 'data-access' });
    } catch (error) {
      logger.warn('数据库连接初始化失败', { module: 'data-access', error });
    }
  }

  const app = new PlatformApplication({
    configManager,
    logger,
    authService,
    rateLimiter,
    multiTenantRateLimiter,
    apiGateway,
    coreProcessor,
    taskScheduler,
    billingManager,
    skillGraphManager,
    flowDesigner,
    storageManager,
    connectionPool,
    queryOptimizer
  });

  return app;
}

if (require.main === module) {
  const port = parseInt(process.env.PORT || '3000', 10);

  createApplication()
    .then(app => app.start(port))
    .catch(error => {
      console.error('应用启动失败:', error);
      process.exit(1);
    });
}
