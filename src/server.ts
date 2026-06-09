import { createApp } from './app';
import { config } from '@config/index';
import { logger } from '@utils/logger';
import { connectionPool } from '@modules/tenant/connection-pool';
import { redisManager } from '@modules/tenant/redis-manager';
import { elasticsearchClient } from '@modules/search/elasticsearch-client';
import { cdnService } from '@modules/cdn/cdn-service';
import { webhookService } from '@modules/webhook/webhook-service';
import { usageService } from '@modules/usage/usage-service';

const startServer = async () => {
  try {
    logger.info('Starting Multi-Tenant CMS API server...');

    await connectionPool.initialize();
    logger.info('Database connection pool initialized');

    await redisManager.initialize();
    logger.info('Redis connection initialized');

    const app = createApp();

    await app.listen({
      port: config.port,
      host: config.host,
    });

    logger.info(
      { port: config.port, host: config.host, environment: config.nodeEnv },
      'Server started successfully'
    );

    const gracefulShutdown = async (signal: string) => {
      logger.info({ signal }, 'Received shutdown signal, gracefully stopping...');

      try {
        await app.close();
        logger.info('HTTP server closed');

        await Promise.all([
          cdnService.close(),
          webhookService.close(),
          usageService.close(),
        ]);
        logger.info('All services closed');

        await elasticsearchClient.closeAll();
        logger.info('Elasticsearch clients closed');

        await redisManager.closeAll();
        logger.info('Redis clients closed');

        await connectionPool.closeAll();
        logger.info('Database connections closed');

        process.exit(0);
      } catch (error) {
        logger.error({ error }, 'Error during graceful shutdown');
        process.exit(1);
      }
    };

    process.on('SIGTERM', () => gracefulShutdown('SIGTERM'));
    process.on('SIGINT', () => gracefulShutdown('SIGINT'));

    process.on('unhandledRejection', (reason, promise) => {
      logger.error({ reason, promise }, 'Unhandled Rejection');
    });

    process.on('uncaughtException', (error) => {
      logger.error({ error }, 'Uncaught Exception');
      process.exit(1);
    });
  } catch (error) {
    logger.fatal({ error }, 'Failed to start server');
    process.exit(1);
  }
};

startServer();
