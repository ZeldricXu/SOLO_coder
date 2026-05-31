import { config } from '@config/index';
import { rootLogger } from '@modules/logging';
import { httpAdapter } from '@adapters/express-http.adapter';
import { coreHandler } from '@core/index';
import { eventBus } from '@core/event-bus';
import { profiler } from '@modules/profiling';
import { scheduler } from '@modules/scheduler';
import { metricsAggregator } from '@modules/metrics';
import { generateId } from '@utils/index';

const logger = rootLogger.child({ module: 'Application' });

async function bootstrap(): Promise<void> {
  logger.info('Starting Metric Platform...');

  try {
    coreHandler.saveConfig({
      config_id: 'cfg_default',
      namespace: 'default',
      version: 1,
      parameters: {
        timeout: 30,
        retries: 3,
        poolSize: 10,
        rules: {
          max_concurrent: 5,
          priority: 'high',
        },
      },
      enabled: true,
    });
    logger.info('Default configuration loaded');

    scheduler.registerHandler('metrics.cleanup', async (payload) => {
      logger.info('Running metrics cleanup', payload);
    });

    scheduler.registerHandler('system.healthcheck', async (payload) => {
      logger.info('Running health check', payload);
    });

    eventBus.on('resource.created', (data) => {
      logger.info('Resource created event received', data);
    });

    eventBus.on('task.completed', (data) => {
      logger.info('Task completed event received', data);
    });

    const shutdown = () => {
      logger.info('Shutting down gracefully...');

      profiler.stopAll();
      scheduler.stopAll();
      metricsAggregator.stop();

      logger.info('Application shutdown complete');
      process.exit(0);
    };

    process.on('SIGTERM', shutdown);
    process.on('SIGINT', shutdown);
    process.on('uncaughtException', (err) => {
      logger.error('Uncaught exception', { error: err.message, stack: err.stack });
      process.exit(1);
    });
    process.on('unhandledRejection', (reason, promise) => {
      logger.error('Unhandled rejection', {
        reason: reason?.toString(),
        promise: promise.toString(),
      });
    });

    await httpAdapter.start(config.server.port, config.server.host);

    logger.info('Metric Platform started successfully', {
      port: config.server.port,
      host: config.server.host,
      environment: process.env.NODE_ENV || 'development',
    });

    logger.info('API Documentation:', {
      base_url: `http://${config.server.host}:${config.server.port}/api/v1`,
      endpoints: [
        'POST /api/v1/auth/login',
        'GET /api/v1/health',
        'POST /api/v1/resources',
        'GET /api/v1/resources/:id/status',
        'POST /api/v1/resources/batch',
        'POST /api/v1/metrics/ingest',
        'GET /api/v1/metrics/query',
        'POST /api/v1/logs/ingest',
        'POST /api/v1/notifications',
        'GET /api/v1/notifications/:id/status',
        'POST /api/v1/storage',
        'GET /api/v1/storage/search',
        'GET /api/v1/topology/graph',
        'POST /api/v1/profiling/cpu/start',
        'POST /api/v1/profiling/cpu/stop',
        'POST /api/v1/profiling/memory/start',
        'POST /api/v1/profiling/memory/stop',
        'POST /api/v1/scheduler',
        'GET /api/v1/scheduler',
      ],
    });
  } catch (error) {
    logger.error('Failed to start application', {
      error: (error as Error).message,
      stack: (error as Error).stack,
    });
    process.exit(1);
  }
}

if (require.main === module) {
  bootstrap();
}

export { bootstrap, config, rootLogger, httpAdapter, coreHandler, eventBus };
