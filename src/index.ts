import express, { Request, Response, NextFunction } from 'express';
import { generateId, getCurrentTimestamp } from './utils';
import logger from './utils/logger';
import requestHandler from './core';
import performanceMonitor from './monitoring';
import metricsService from './metrics';
import sloManager, { routedSLOManager } from './slo';
import alertEngine from './alerting';
import anomalyDetector from './anomaly-detection';
import traceCollector from './tracing';
import configManager from './config';
import cacheManager, { EventBus, globalEventBus, CacheEventData, CacheOperationEvent, CacheInvalidationEvent } from './data-access';
import { createStaticConfigLoader } from './config';
import { MetricsPlugin, PluginMetadata } from './metrics';

const app = express();
const PORT = process.env.PORT || 3000;

app.use(express.json({ limit: '10mb' }));

app.use((req: Request, res: Response, next: NextFunction) => {
  const traceId = req.headers['x-trace-id'] as string || generateId('trace');
  req.headers['x-trace-id'] = traceId;
  res.setHeader('x-trace-id', traceId);
  next();
});

app.use(performanceMonitor.getMiddleware());

app.get('/health', async (req: Request, res: Response) => {
  const status = await performanceMonitor.runAllHealthChecks();
  res.json({
    status: status.overall_status,
    timestamp: getCurrentTimestamp(),
    components: status.components,
  });
});

app.get('/metrics', async (req: Request, res: Response) => {
  const metrics = performanceMonitor.exportMetrics();
  res.json({
    timestamp: getCurrentTimestamp(),
    metrics,
  });
});

app.get('/api/v1/status', async (req: Request, res: Response) => {
  const stats = await requestHandler.getStats();
  const cacheStats = cacheManager.getAllStats();
  const traceStats = traceCollector.getProcessor().getStats();

  res.json({
    code: 200,
    data: {
      timestamp: getCurrentTimestamp(),
      request_handler: stats,
      caches: Object.fromEntries(cacheStats),
      tracing: traceStats,
      active_alerts: alertEngine.getActiveAlerts().length,
      error_budgets: await sloManager.getAllErrorBudgets(),
    },
  });
});

app.post('/api/v1/resources', async (req: Request, res: Response) => {
  const traceId = req.headers['x-trace-id'] as string;
  const context = requestHandler.createContext(traceId);
  const result = await requestHandler.createResource(req.body, context);
  res.status(result.code).json(result);
});

app.get('/api/v1/resources/:id/status', async (req: Request, res: Response) => {
  const traceId = req.headers['x-trace-id'] as string;
  const context = requestHandler.createContext(traceId);
  const result = await requestHandler.getResourceStatus(req.params.id, context);
  res.status(result.code).json(result);
});

app.post('/api/v1/resources/batch', async (req: Request, res: Response) => {
  const traceId = req.headers['x-trace-id'] as string;
  const context = requestHandler.createContext(traceId);
  const result = await requestHandler.batchOperation(req.body, context);
  res.status(result.code).json(result);
});

app.post('/api/v1/slo', async (req: Request, res: Response) => {
  try {
    const slo = await sloManager.createSLO(req.body);
    res.json({ code: 201, data: slo, timestamp: getCurrentTimestamp() });
  } catch (error) {
    res.status(400).json({ code: 400, error: (error as Error).message, timestamp: getCurrentTimestamp() });
  }
});

app.post('/api/v1/sli', async (req: Request, res: Response) => {
  try {
    const sli = await sloManager.createSLI(req.body);
    res.json({ code: 201, data: sli, timestamp: getCurrentTimestamp() });
  } catch (error) {
    res.status(400).json({ code: 400, error: (error as Error).message, timestamp: getCurrentTimestamp() });
  }
});

app.get('/api/v1/slo/:id/error-budget', async (req: Request, res: Response) => {
  const budget = await sloManager.getErrorBudget(req.params.id);
  if (!budget) {
    res.status(404).json({ code: 404, error: 'SLO not found', timestamp: getCurrentTimestamp() });
    return;
  }
  res.json({ code: 200, data: budget, timestamp: getCurrentTimestamp() });
});

app.get('/api/v1/slo/error-budgets', async (req: Request, res: Response) => {
  const budgets = await sloManager.getAllErrorBudgets();
  res.json({ code: 200, data: budgets, timestamp: getCurrentTimestamp() });
});

app.post('/api/v1/sli/:id/record', async (req: Request, res: Response) => {
  const { good_events, total_events, dimensions } = req.body;
  const metric = sloManager.getCalculator().recordSLI(req.params.id, good_events, total_events, dimensions);
  if (!metric) {
    res.status(404).json({ code: 404, error: 'SLI not found', timestamp: getCurrentTimestamp() });
    return;
  }
  res.json({ code: 200, data: metric, timestamp: getCurrentTimestamp() });
});

app.post('/api/v1/alerts/rules', async (req: Request, res: Response) => {
  const success = alertEngine.addRule(req.body);
  if (!success) {
    res.status(400).json({ code: 400, error: 'Invalid alert rule', timestamp: getCurrentTimestamp() });
    return;
  }
  res.json({ code: 201, data: { rule_id: req.body.rule_id }, timestamp: getCurrentTimestamp() });
});

app.get('/api/v1/alerts/rules', async (req: Request, res: Response) => {
  const rules = alertEngine.getAllRules();
  res.json({ code: 200, data: rules, timestamp: getCurrentTimestamp() });
});

app.get('/api/v1/alerts/active', async (req: Request, res: Response) => {
  const alerts = alertEngine.getActiveAlerts();
  res.json({ code: 200, data: alerts, timestamp: getCurrentTimestamp() });
});

app.post('/api/v1/traces/spans', async (req: Request, res: Response) => {
  const spans = Array.isArray(req.body) ? req.body : [req.body];
  const sampled = traceCollector.collectBatch(spans);
  res.json({
    code: 200,
    data: { received: spans.length, sampled },
    timestamp: getCurrentTimestamp(),
  });
});

app.get('/api/v1/traces/:traceId', async (req: Request, res: Response) => {
  const trace = traceCollector.getProcessor().getTrace(req.params.traceId);
  if (!trace) {
    res.status(404).json({ code: 404, error: 'Trace not found', timestamp: getCurrentTimestamp() });
    return;
  }
  res.json({ code: 200, data: trace, timestamp: getCurrentTimestamp() });
});

app.post('/api/v1/anomaly/detect', async (req: Request, res: Response) => {
  const { metric_name, values, config } = req.body;
  for (const value of values || []) {
    anomalyDetector.addDataPoint(metric_name, value);
  }
  const result = anomalyDetector.detect(metric_name, config);
  res.json({ code: 200, data: result, timestamp: getCurrentTimestamp() });
});

app.post('/api/v1/anomaly/baseline/:metricName', async (req: Request, res: Response) => {
  const baseline = anomalyDetector.buildBaseline(req.params.metricName);
  if (!baseline) {
    res.status(400).json({ code: 400, error: 'Insufficient data for baseline', timestamp: getCurrentTimestamp() });
    return;
  }
  res.json({ code: 200, data: baseline, timestamp: getCurrentTimestamp() });
});

app.get('/api/v1/anomaly/algorithms', async (req: Request, res: Response) => {
  const algorithms = anomalyDetector.getAlgorithmNames();
  res.json({ code: 200, data: algorithms, timestamp: getCurrentTimestamp() });
});

app.get('/api/v1/slo/routing/stats', async (req: Request, res: Response) => {
  const stats = routedSLOManager.getRouterStats();
  res.json({ code: 200, data: stats, timestamp: getCurrentTimestamp() });
});

app.post('/api/v1/slo/routing/replicas', async (req: Request, res: Response) => {
  const { id, host, port, priority, tags } = req.body;
  if (!id || !host || !port) {
    res.status(400).json({ code: 400, error: 'id, host and port are required', timestamp: getCurrentTimestamp() });
    return;
  }
  routedSLOManager.addReplica({ id, host, port, priority: priority || 1, healthy: true, tags: tags || {} });
  res.json({ code: 201, data: { replica_id: id }, timestamp: getCurrentTimestamp() });
});

app.delete('/api/v1/slo/routing/replicas/:id', async (req: Request, res: Response) => {
  const removed = routedSLOManager.removeReplica(req.params.id);
  res.json({ code: 200, data: { removed }, timestamp: getCurrentTimestamp() });
});

app.get('/api/v1/slo/routing/replicas', async (req: Request, res: Response) => {
  const replicas = routedSLOManager.getAllReplicas();
  res.json({ code: 200, data: replicas, timestamp: getCurrentTimestamp() });
});

app.post('/api/v1/slo/routing/failover', async (req: Request, res: Response) => {
  const result = await routedSLOManager.triggerFailover();
  res.json({ code: 200, data: result, timestamp: getCurrentTimestamp() });
});

app.post('/api/v1/cache/events/subscribe', async (req: Request, res: Response) => {
  const { event, webhook_url } = req.body;
  if (!event || !webhook_url) {
    res.status(400).json({ code: 400, error: 'event and webhook_url are required', timestamp: getCurrentTimestamp() });
    return;
  }

  const handler = async (data: any) => {
    try {
      await fetch(webhook_url, {
        method: 'POST',
        headers: { 'Content-Type': 'application/json' },
        body: JSON.stringify({ event, data }),
      });
    } catch (error) {
      logger.error(`Webhook notification failed for event ${event}:`, error);
    }
  };

  const unsubscribe = cacheManager.on(event, handler);
  const subscriptionId = generateId('sub');

  res.json({
    code: 201,
    data: { subscription_id: subscriptionId, event, webhook_url },
    timestamp: getCurrentTimestamp(),
  });
});

app.get('/api/v1/cache/events', async (req: Request, res: Response) => {
  const events = ['cache.set', 'cache.get', 'cache.delete', 'cache.evict', 'cache.expire', 'cache.clear', 'cache.operation', 'cache.invalidation'];
  res.json({ code: 200, data: events, timestamp: getCurrentTimestamp() });
});

app.get('/api/v1/cache/eventbus/stats', async (req: Request, res: Response) => {
  const eventBus = cacheManager.getEventBus();
  const events = ['cache.set', 'cache.get', 'cache.delete', 'cache.evict', 'cache.expire', 'cache.clear', 'cache.operation', 'cache.invalidation'];
  const stats: Record<string, number> = {};
  for (const event of events) {
    stats[event] = eventBus.getSubscriberCount(event);
  }
  res.json({ code: 200, data: stats, timestamp: getCurrentTimestamp() });
});

app.post('/api/v1/metrics/plugins', async (req: Request, res: Response) => {
  try {
    const plugin = req.body as MetricsPlugin;
    if (!plugin.name || !plugin.version) {
      res.status(400).json({ code: 400, error: 'Plugin name and version are required', timestamp: getCurrentTimestamp() });
      return;
    }
    const pluginId = await metricsService.loadPlugin(plugin);
    res.json({ code: 201, data: { plugin_id: pluginId }, timestamp: getCurrentTimestamp() });
  } catch (error) {
    res.status(400).json({ code: 400, error: (error as Error).message, timestamp: getCurrentTimestamp() });
  }
});

app.get('/api/v1/metrics/plugins', async (req: Request, res: Response) => {
  const plugins = metricsService.getPluginManager().getAllMetadata();
  res.json({ code: 200, data: plugins, timestamp: getCurrentTimestamp() });
});

app.get('/api/v1/metrics/plugins/:pluginId', async (req: Request, res: Response) => {
  const plugin = metricsService.getPluginManager().getPlugin(req.params.pluginId);
  const metadata = metricsService.getPluginManager().getPluginMetadata(req.params.pluginId);
  if (!plugin || !metadata) {
    res.status(404).json({ code: 404, error: 'Plugin not found', timestamp: getCurrentTimestamp() });
    return;
  }
  res.json({ code: 200, data: { plugin, metadata }, timestamp: getCurrentTimestamp() });
});

app.delete('/api/v1/metrics/plugins/:pluginId', async (req: Request, res: Response) => {
  const result = await metricsService.unloadPlugin(req.params.pluginId);
  res.json({ code: 200, data: { unloaded: result }, timestamp: getCurrentTimestamp() });
});

app.get('/api/v1/metrics/aggregations', async (req: Request, res: Response) => {
  const aggregations = metricsService.getAggregator().getAvailableAggregations();
  res.json({ code: 200, data: aggregations, timestamp: getCurrentTimestamp() });
});

app.post('/api/v1/metrics/plugins/:pluginId/enable', async (req: Request, res: Response) => {
  const result = metricsService.getPluginManager().enablePlugin(req.params.pluginId);
  res.json({ code: 200, data: { enabled: result }, timestamp: getCurrentTimestamp() });
});

app.post('/api/v1/metrics/plugins/:pluginId/disable', async (req: Request, res: Response) => {
  const result = metricsService.getPluginManager().disablePlugin(req.params.pluginId);
  res.json({ code: 200, data: { disabled: result }, timestamp: getCurrentTimestamp() });
});

app.post('/api/v1/metrics/record', async (req: Request, res: Response) => {
  const { metric_name, value, dimensions } = req.body;
  metricsService.recordMetric(metric_name, value, dimensions);
  res.json({ code: 200, data: { recorded: true }, timestamp: getCurrentTimestamp() });
});

app.get('/api/v1/metrics/query', async (req: Request, res: Response) => {
  const { metric_name, start_time, end_time, aggregation, granularity } = req.query;
  const startTime = parseInt(start_time as string);
  const endTime = parseInt(end_time as string) || Date.now();

  if (!metric_name || !startTime) {
    res.status(400).json({ code: 400, error: 'metric_name and start_time are required', timestamp: getCurrentTimestamp() });
    return;
  }

  const data = await metricsService.getAggregatedMetric(
    metric_name as string,
    startTime,
    endTime,
    aggregation as string,
    granularity as string
  );

  res.json({ code: 200, data, timestamp: getCurrentTimestamp() });
});

app.post('/api/v1/configs', async (req: Request, res: Response) => {
  const success = configManager.addOrUpdateConfig(req.body);
  if (!success) {
    res.status(400).json({ code: 400, error: 'Invalid config', timestamp: getCurrentTimestamp() });
    return;
  }
  res.json({ code: 201, data: { config_id: req.body.config_id }, timestamp: getCurrentTimestamp() });
});

app.get('/api/v1/configs/:namespace', async (req: Request, res: Response) => {
  const configs = configManager.getConfigsByNamespace(req.params.namespace);
  res.json({ code: 200, data: configs, timestamp: getCurrentTimestamp() });
});

app.get('/api/v1/configs/diff/:ns1/:ns2', async (req: Request, res: Response) => {
  const diffs = configManager.diffNamespaces(req.params.ns1, req.params.ns2);
  res.json({ code: 200, data: Object.fromEntries(diffs), timestamp: getCurrentTimestamp() });
});

app.use((err: Error, req: Request, res: Response, next: NextFunction) => {
  logger.error('Unhandled error:', err);
  res.status(500).json({
    code: 500,
    error: 'Internal server error',
    message: err.message,
    timestamp: getCurrentTimestamp(),
  });
});

app.use((req: Request, res: Response) => {
  res.status(404).json({
    code: 404,
    error: 'Not found',
    path: req.path,
    timestamp: getCurrentTimestamp(),
  });
});

async function initializeSystem(): Promise<void> {
  logger.info('Initializing SLO Monitoring Platform...');

  await configManager.loadFromAllSources();

  metricsService.start();
  traceCollector.start();
  performanceMonitor.startHealthChecks(30000);
  cacheManager.startCleanup(60000);

  performanceMonitor.registerHealthCheck('metrics_storage', async () => {
    const isHealthy = await metricsService.getInMemoryStorage().healthCheck();
    return {
      name: 'metrics_storage',
      status: isHealthy ? 'healthy' : 'unhealthy',
      details: metricsService.getInMemoryStorage().getStats(),
      timestamp: getCurrentTimestamp(),
      duration_ms: 0,
    };
  });

  performanceMonitor.registerHealthCheck('config_manager', async () => {
    const configCount = configManager.getAllConfigs().length;
    return {
      name: 'config_manager',
      status: 'healthy',
      details: { config_count: configCount },
      timestamp: getCurrentTimestamp(),
      duration_ms: 0,
    };
  });

  logger.info('SLO Monitoring Platform initialized successfully');
}

process.on('SIGINT', async () => {
  logger.info('Shutting down gracefully...');
  metricsService.stop();
  traceCollector.stop();
  performanceMonitor.stopHealthChecks();
  cacheManager.stopCleanup();
  process.exit(0);
});

process.on('uncaughtException', (error) => {
  logger.error('Uncaught exception:', error);
});

process.on('unhandledRejection', (reason, promise) => {
  logger.error('Unhandled rejection at:', promise, 'reason:', reason);
});

if (require.main === module) {
  initializeSystem()
    .then(() => {
      app.listen(PORT, () => {
        logger.info(`Server is running on port ${PORT}`);
        logger.info(`Health check: http://localhost:${PORT}/health`);
        logger.info(`Metrics: http://localhost:${PORT}/metrics`);
        logger.info(`API Status: http://localhost:${PORT}/api/v1/status`);
      });
    })
    .catch((error) => {
      logger.error('Failed to initialize system:', error);
      process.exit(1);
    });
}

export { app, initializeSystem };
