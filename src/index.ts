import express, { Express, Request, Response } from 'express';
import { createTracingModule, createTracingModuleWithConfig, TracingConfigManager, DynamicSpanCollector, DynamicSamplingStrategyManager } from './tracing';
import { createNotificationModule, NotificationStrategyRegistry, PriorityStrategy, SuppressionStrategy, RoutingStrategy } from './notification';
import { createProfilingModule, ProfileEventBus, ProfileEvent, AsyncProfilingSession } from './profiling';
import { createMetricsModule } from './metrics';
import { createStorageModule } from './storage';
import { createAlertingModule } from './alerting';
import { createAnomalyModule } from './anomaly';
import { createConfigModule } from './config';
import { createDataAccessModule } from './data-access';
import { TraceSpan, SamplingStrategy, Notification, NotificationChannel, SuppressionRule, AlertRule, MetricPoint, LifecyclePolicy, Config, SchemaMigration, TracingConfig, ScenarioType } from './types';

export interface AppModules {
  tracing: ReturnType<typeof createTracingModule>;
  notification: ReturnType<typeof createNotificationModule>;
  profiling: ReturnType<typeof createProfilingModule>;
  metrics: ReturnType<typeof createMetricsModule>;
  storage: ReturnType<typeof createStorageModule>;
  alerting: ReturnType<typeof createAlertingModule>;
  anomaly: ReturnType<typeof createAnomalyModule>;
  config: ReturnType<typeof createConfigModule>;
  dataAccess: ReturnType<typeof createDataAccessModule>;
  tracingConfigManager?: TracingConfigManager;
  strategyRegistry?: NotificationStrategyRegistry;
  profileEventBus?: ProfileEventBus;
}

export function createApp(storageBaseDir: string = './data'): {
  modules: AppModules;
  app: Express;
  start: (port?: number) => Promise<void>;
  stop: () => Promise<void>;
} {
  const tracing = createTracingModule();
  const notification = createNotificationModule();
  const profiling = createProfilingModule();
  const metrics = createMetricsModule();
  const storage = createStorageModule(storageBaseDir);
  const alerting = createAlertingModule(metrics.queryService, notification.router);
  const anomaly = createAnomalyModule(metrics.queryService);
  const config = createConfigModule();
  const dataAccess = createDataAccessModule(storage.storageManager);

  const modules: AppModules = {
    tracing,
    notification,
    profiling,
    metrics,
    storage,
    alerting,
    anomaly,
    config,
    dataAccess,
  };

  const app = express();
  app.use(express.json({ limit: '10mb' }));

  app.get('/health', (req: Request, res: Response) => {
    res.json({
      status: 'ok',
      timestamp: new Date().toISOString(),
      modules: {
        tracing: 'ok',
        notification: 'ok',
        profiling: 'ok',
        metrics: 'ok',
        storage: 'ok',
        alerting: 'ok',
        anomaly: 'ok',
        config: 'ok',
        dataAccess: 'ok',
      },
    });
  });

  app.post('/api/v1/traces/spans', async (req: Request, res: Response) => {
    try {
      const spans: TraceSpan[] = Array.isArray(req.body) ? req.body : [req.body];
      const processed = await Promise.all(spans.map(s => tracing.pipeline.processSpan(s)));
      res.status(201).json({ code: 201, data: { count: processed.length, spans: processed } });
    } catch (error) {
      res.status(400).json({ code: 400, error: (error as Error).message });
    }
  });

  app.get('/api/v1/traces/:traceId', async (req: Request, res: Response) => {
    const spans = tracing.collector.getTrace(req.params.traceId);
    res.json({ code: 200, data: spans });
  });

  app.post('/api/v1/traces/:traceId/finalize', async (req: Request, res: Response) => {
    try {
      const result = await tracing.pipeline.finalizeTrace(req.params.traceId);
      res.json({ code: 200, data: result });
    } catch (error) {
      res.status(400).json({ code: 400, error: (error as Error).message });
    }
  });

  app.post('/api/v1/sampling/strategies', async (req: Request, res: Response) => {
    const strategy: SamplingStrategy = req.body;
    tracing.strategyManager.addStrategy(strategy);
    res.status(201).json({ code: 201, data: strategy });
  });

  app.get('/api/v1/sampling/strategies', (req: Request, res: Response) => {
    res.json({ code: 200, data: tracing.strategyManager.getStrategies() });
  });

  app.post('/api/v1/tracing/config', async (req: Request, res: Response) => {
    try {
      if (!modules.tracingConfigManager) {
        return res.status(404).json({ code: 404, error: 'Dynamic config not available' });
      }
      modules.tracingConfigManager.updateConfig(req.body);
      res.json({ code: 200, data: modules.tracingConfigManager.getConfig() });
    } catch (error) {
      res.status(400).json({ code: 400, error: (error as Error).message });
    }
  });

  app.get('/api/v1/tracing/config', (req: Request, res: Response) => {
    if (!modules.tracingConfigManager) {
      return res.status(404).json({ code: 404, error: 'Dynamic config not available' });
    }
    res.json({ code: 200, data: modules.tracingConfigManager.getConfig() });
  });

  app.post('/api/v1/tracing/scenario/:scenario', async (req: Request, res: Response) => {
    try {
      if (!modules.tracingConfigManager) {
        return res.status(404).json({ code: 404, error: 'Dynamic config not available' });
      }
      modules.tracingConfigManager.setCurrentScenario(req.params.scenario as ScenarioType);
      res.json({ code: 200, data: { scenario: req.params.scenario } });
    } catch (error) {
      res.status(400).json({ code: 400, error: (error as Error).message });
    }
  });

  app.post('/api/v1/notifications/send', async (req: Request, res: Response) => {
    try {
      const result = await notification.router.send(req.body);
      res.json({ code: 200, data: result });
    } catch (error) {
      res.status(400).json({ code: 400, error: (error as Error).message });
    }
  });

  app.post('/api/v1/notifications/channels', async (req: Request, res: Response) => {
    const channel: NotificationChannel = req.body;
    notification.channelManager.addChannel(channel);
    res.status(201).json({ code: 201, data: channel });
  });

  app.get('/api/v1/notifications/channels', (req: Request, res: Response) => {
    res.json({ code: 200, data: notification.channelManager.getChannels() });
  });

  app.post('/api/v1/notifications/suppression-rules', async (req: Request, res: Response) => {
    const rule: SuppressionRule = req.body;
    notification.suppressionManager.addRule(rule);
    res.status(201).json({ code: 201, data: rule });
  });

  app.get('/api/v1/notifications/suppression-rules', (req: Request, res: Response) => {
    res.json({ code: 200, data: notification.suppressionManager.getRules() });
  });

  app.get('/api/v1/notifications/strategies', (req: Request, res: Response) => {
    if (!modules.strategyRegistry) {
      return res.status(404).json({ code: 404, error: 'Strategy registry not available' });
    }
    res.json({
      code: 200,
      data: {
        priorityStrategies: modules.strategyRegistry.listPriorityStrategies(),
        suppressionStrategies: modules.strategyRegistry.listSuppressionStrategies(),
        routingStrategies: modules.strategyRegistry.listRoutingStrategies(),
        active: {
          priority: modules.strategyRegistry.getActivePriorityStrategy()?.id,
          suppression: modules.strategyRegistry.getActiveSuppressionStrategy()?.id,
          routing: modules.strategyRegistry.getActiveRoutingStrategy()?.id,
        },
      },
    });
  });

  app.post('/api/v1/notifications/strategies/priority/:id', async (req: Request, res: Response) => {
    try {
      if (!modules.strategyRegistry) {
        return res.status(404).json({ code: 404, error: 'Strategy registry not available' });
      }
      const success = modules.strategyRegistry.setActivePriorityStrategy(req.params.id);
      if (!success) {
        return res.status(404).json({ code: 404, error: 'Strategy not found' });
      }
      res.json({ code: 200, data: { id: req.params.id } });
    } catch (error) {
      res.status(400).json({ code: 400, error: (error as Error).message });
    }
  });

  app.post('/api/v1/notifications/strategies/suppression/:id', async (req: Request, res: Response) => {
    try {
      if (!modules.strategyRegistry) {
        return res.status(404).json({ code: 404, error: 'Strategy registry not available' });
      }
      const success = modules.strategyRegistry.setActiveSuppressionStrategy(req.params.id);
      if (!success) {
        return res.status(404).json({ code: 404, error: 'Strategy not found' });
      }
      res.json({ code: 200, data: { id: req.params.id } });
    } catch (error) {
      res.status(400).json({ code: 400, error: (error as Error).message });
    }
  });

  app.post('/api/v1/notifications/strategies/routing/:id', async (req: Request, res: Response) => {
    try {
      if (!modules.strategyRegistry) {
        return res.status(404).json({ code: 404, error: 'Strategy registry not available' });
      }
      const success = modules.strategyRegistry.setActiveRoutingStrategy(req.params.id);
      if (!success) {
        return res.status(404).json({ code: 404, error: 'Strategy not found' });
      }
      res.json({ code: 200, data: { id: req.params.id } });
    } catch (error) {
      res.status(400).json({ code: 400, error: (error as Error).message });
    }
  });

  app.post('/api/v1/profiling/sessions', async (req: Request, res: Response) => {
    try {
      const { type, duration, wait } = req.body;
      if (wait === true) {
        const session = await profiling.manager.startSessionAsync(type, duration);
        res.status(200).json({ code: 200, data: session });
      } else {
        const session = await profiling.manager.startSession(type, duration);
        res.status(202).json({ code: 202, data: { sessionId: session.id, status: 'running' } });
      }
    } catch (error) {
      res.status(400).json({ code: 400, error: (error as Error).message });
    }
  });

  app.get('/api/v1/profiling/sessions', (req: Request, res: Response) => {
    res.json({ code: 200, data: profiling.manager.listSessions() });
  });

  app.get('/api/v1/profiling/sessions/:id', (req: Request, res: Response) => {
    const session = profiling.manager.getSession(req.params.id);
    if (!session) {
      res.status(404).json({ code: 404, error: 'Session not found' });
      return;
    }
    res.json({ code: 200, data: session });
  });

  app.get('/api/v1/profiling/sessions/:id/flamegraph', (req: Request, res: Response) => {
    const svg = profiling.manager.generateFlameGraphSVG(req.params.id);
    if (!svg) {
      res.status(404).json({ code: 404, error: 'Session not found' });
      return;
    }
    res.type('image/svg+xml').send(svg);
  });

  app.get('/api/v1/profiling/sessions/:id/compare/:otherId', (req: Request, res: Response) => {
    const report = profiling.manager.generateDiffReport(req.params.id, req.params.otherId);
    if (!report) {
      res.status(404).json({ code: 404, error: 'Sessions not found' });
      return;
    }
    res.type('text/plain').send(report);
  });

  app.delete('/api/v1/profiling/sessions/:id', (req: Request, res: Response) => {
    const cancelled = profiling.manager.cancelSession(req.params.id);
    res.json({ code: 200, data: { cancelled } });
  });

  app.post('/api/v1/metrics/ingest', async (req: Request, res: Response) => {
    try {
      const points: MetricPoint[] = Array.isArray(req.body) ? req.body : [req.body];
      await metrics.pipeline.ingestMany(points);
      res.status(201).json({ code: 201, data: { count: points.length } });
    } catch (error) {
      res.status(400).json({ code: 400, error: (error as Error).message });
    }
  });

  app.get('/api/v1/metrics/query', async (req: Request, res: Response) => {
    try {
      const { metric, tags, startTime, endTime, aggregated } = req.query;
      const tagsObj = typeof tags === 'string' ? JSON.parse(tags) : {};
      const start = parseInt(startTime as string) || Date.now() - 3600000;
      const end = parseInt(endTime as string) || Date.now();

      const result = aggregated === 'true'
        ? await metrics.queryService.queryAggregated(metric as string, tagsObj, start, end)
        : await metrics.queryService.queryRaw(metric as string, tagsObj, start, end);

      res.json({ code: 200, data: result });
    } catch (error) {
      res.status(400).json({ code: 400, error: (error as Error).message });
    }
  });

  app.post('/api/v1/storage/objects', async (req: Request, res: Response) => {
    try {
      const { key, data, metadata } = req.body;
      const buffer = Buffer.isBuffer(data) ? data : Buffer.from(JSON.stringify(data));
      const obj = await storage.storageManager.put(key, buffer, metadata);
      res.status(201).json({ code: 201, data: obj });
    } catch (error) {
      res.status(400).json({ code: 400, error: (error as Error).message });
    }
  });

  app.get('/api/v1/storage/objects', async (req: Request, res: Response) => {
    const prefix = req.query.prefix as string || '';
    const objects = await storage.storageManager.list(prefix);
    res.json({ code: 200, data: objects });
  });

  app.get('/api/v1/storage/objects/:key', async (req: Request, res: Response) => {
    const result = await storage.storageManager.get(req.params.key);
    if (!result) {
      res.status(404).json({ code: 404, error: 'Object not found' });
      return;
    }
    res.json({ code: 200, data: { object: result.object, data: result.data.toString('base64') } });
  });

  app.delete('/api/v1/storage/objects/:key', async (req: Request, res: Response) => {
    const deleted = await storage.storageManager.delete(req.params.key);
    res.json({ code: 200, data: { deleted } });
  });

  app.post('/api/v1/storage/lifecycle-policies', async (req: Request, res: Response) => {
    const policy: LifecyclePolicy = req.body;
    storage.lifecycleManager.addPolicy(policy);
    res.status(201).json({ code: 201, data: policy });
  });

  app.post('/api/v1/alerts/rules', async (req: Request, res: Response) => {
    try {
      const rule = await alerting.pipeline.createRule(req.body);
      res.status(201).json({ code: 201, data: rule });
    } catch (error) {
      res.status(400).json({ code: 400, error: (error as Error).message });
    }
  });

  app.get('/api/v1/alerts/rules', (req: Request, res: Response) => {
    res.json({ code: 200, data: alerting.ruleManager.getRules() });
  });

  app.get('/api/v1/alerts/firing', (req: Request, res: Response) => {
    res.json({ code: 200, data: alerting.pipeline.getFiringAlerts() });
  });

  app.post('/api/v1/alerts/evaluate', async (req: Request, res: Response) => {
    const results = await alerting.pipeline.evaluate();
    res.json({ code: 200, data: results });
  });

  app.post('/api/v1/anomaly/detect', async (req: Request, res: Response) => {
    try {
      const { metric, tags, value, algorithms } = req.body;
      const results = await anomaly.pipeline.process(metric, tags || {}, value, algorithms);
      res.json({ code: 200, data: results });
    } catch (error) {
      res.status(400).json({ code: 400, error: (error as Error).message });
    }
  });

  app.get('/api/v1/anomaly/algorithms', (req: Request, res: Response) => {
    res.json({ code: 200, data: anomaly.pipeline.getAvailableAlgorithms() });
  });

  app.post('/api/v1/config', async (req: Request, res: Response) => {
    try {
      const { namespace, parameters, description } = req.body;
      const cfg = await config.manager.set(namespace, parameters, description);
      res.status(201).json({ code: 201, data: cfg });
    } catch (error) {
      res.status(400).json({ code: 400, error: (error as Error).message });
    }
  });

  app.get('/api/v1/config/:namespace', (req: Request, res: Response) => {
    const version = req.query.version ? parseInt(req.query.version as string) : undefined;
    const cfg = config.manager.get(req.params.namespace, version);
    if (!cfg) {
      res.status(404).json({ code: 404, error: 'Config not found' });
      return;
    }
    res.json({ code: 200, data: cfg });
  });

  app.get('/api/v1/config/:namespace/versions', (req: Request, res: Response) => {
    const versions = config.manager.listVersions(req.params.namespace);
    res.json({ code: 200, data: versions });
  });

  app.post('/api/v1/config/:namespace/rollback', async (req: Request, res: Response) => {
    try {
      const { version } = req.body;
      const cfg = config.manager.rollback(req.params.namespace, version);
      if (!cfg) {
        res.status(404).json({ code: 404, error: 'Config not found' });
        return;
      }
      res.json({ code: 200, data: cfg });
    } catch (error) {
      res.status(400).json({ code: 400, error: (error as Error).message });
    }
  });

  app.get('/api/v1/config/:namespace/diff', (req: Request, res: Response) => {
    const v1 = parseInt(req.query.v1 as string);
    const v2 = parseInt(req.query.v2 as string);
    const diff = config.manager.diff(req.params.namespace, v1, v2);
    if (!diff) {
      res.status(404).json({ code: 404, error: 'Versions not found' });
      return;
    }
    res.json({ code: 200, data: diff });
  });

  app.post('/api/v1/data/migrations', async (req: Request, res: Response) => {
    try {
      const migrations: SchemaMigration[] = Array.isArray(req.body) ? req.body : [req.body];
      dataAccess.migrationManager.registerMigrations(migrations);
      const applied = await dataAccess.migrationManager.migrate();
      res.status(201).json({ code: 201, data: { applied } });
    } catch (error) {
      res.status(400).json({ code: 400, error: (error as Error).message });
    }
  });

  app.get('/api/v1/data/migrations', (req: Request, res: Response) => {
    res.json({
      code: 200,
      data: {
        currentVersion: dataAccess.migrationManager.getCurrentVersion(),
        migrations: dataAccess.migrationManager.getMigrations(),
      },
    });
  });

  app.post('/api/v1/resources', (req: Request, res: Response) => {
    res.status(201).json({
      code: 201,
      data: {
        id: 'rsc_' + Date.now(),
        status: 'provisioning',
      },
    });
  });

  app.get('/api/v1/resources/:id/status', (req: Request, res: Response) => {
    res.json({
      code: 200,
      data: {
        id: req.params.id,
        status: 'completed',
        progress: 1,
      },
    });
  });

  app.post('/api/v1/resources/batch', (req: Request, res: Response) => {
    res.json({
      code: 200,
      data: {
        batch_id: 'batch_' + Date.now(),
        results: [],
      },
    });
  });

  let server: ReturnType<typeof app.listen> | null = null;

  const start = async (port: number = 3000): Promise<void> => {
    metrics.collector.startAutoFlush();
    alerting.evaluator.startAutoEvaluation(30000);
    storage.lifecycleManager.startAutoCheck();

    server = app.listen(port, () => {
      console.log(`Observability Platform running on port ${port}`);
      console.log(`Health check: http://localhost:${port}/health`);
    });
  };

  const stop = async (): Promise<void> => {
    metrics.collector.stopAutoFlush();
    alerting.evaluator.stopAutoEvaluation();
    storage.lifecycleManager.stopAutoCheck();

    if (server) {
      server.close();
      server = null;
    }
  };

  return { modules, app, start, stop };
}

export function createAppWithEnhancements(storageBaseDir: string = './data', tracingConfig?: TracingConfig): {
  modules: AppModules;
  app: Express;
  start: (port?: number) => Promise<void>;
  stop: () => Promise<void>;
} {
  const appInstance = createApp(storageBaseDir);

  if (tracingConfig) {
    const tracingWithConfig = createTracingModuleWithConfig(tracingConfig);
    appInstance.modules.tracing = tracingWithConfig;
    appInstance.modules.tracingConfigManager = tracingWithConfig.configManager;
  }

  appInstance.modules.strategyRegistry = new NotificationStrategyRegistry();
  appInstance.modules.profileEventBus = appInstance.modules.profiling.eventBus;

  return appInstance;
}

if (require.main === module) {
  const { start } = createApp('./data');
  start(3000).catch(console.error);
}

export { createTracingModule, createTracingModuleWithConfig } from './tracing';
export { createNotificationModule } from './notification';
export { createProfilingModule } from './profiling';
export { createMetricsModule } from './metrics';
export { createStorageModule } from './storage';
export { createAlertingModule } from './alerting';
export { createAnomalyModule } from './anomaly';
export { createConfigModule } from './config';
export { createDataAccessModule } from './data-access';
export * from './types';
export * from './core';
