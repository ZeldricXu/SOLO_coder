import fastify, { type FastifyInstance } from 'fastify';
import cors from '@fastify/cors';
import helmet from '@fastify/helmet';
import multipart from '@fastify/multipart';
import rateLimit from '@fastify/rate-limit';
import { env } from './config/env';
import { logger } from './config/logger';
import { connectDatabase, disconnectDatabase } from './config/database';
import { connectRedis, disconnectRedis } from './config/redis';
import { registerModelRoutes } from './model/registry';
import { registerInferenceRoutes } from './inference/gateway';
import { registerExperimentRoutes } from './experiment/service';
import { registerFeatureStoreRoutes } from './feature-store/service';
import { registerABTestRoutes } from './abtest/engine';
import { registerMonitoringRoutes } from './monitoring/service';
import { grpcServer } from './grpc/server';

export class MLOpsServer {
  private app: FastifyInstance;
  private isRunning: boolean = false;

  constructor() {
    this.app = fastify({
      logger: false,
      trustProxy: true,
      bodyLimit: 10 * 1024 * 1024,
    });
  }

  async start(): Promise<void> {
    if (this.isRunning) return;

    logger.info('Starting MLOps Platform Server...');

    await Promise.all([connectDatabase(), connectRedis()]);

    await this.registerPlugins();
    await this.registerRoutes();
    await this.registerGracefulShutdown();

    await this.app.listen({
      host: env.SERVER_HOST,
      port: env.SERVER_PORT,
    });

    logger.info(
      { host: env.SERVER_HOST, port: env.SERVER_PORT, protocol: 'REST' },
      'REST API server started'
    );

    if (env.NODE_ENV !== 'test') {
      await grpcServer.start();
    }

    this.isRunning = true;
    logger.info('MLOps Platform Server started successfully');
  }

  async stop(): Promise<void> {
    if (!this.isRunning) return;

    logger.info('Stopping MLOps Platform Server...');

    await Promise.all([
      this.app.close(),
      grpcServer.stop(),
      disconnectDatabase(),
      disconnectRedis(),
    ]);

    this.isRunning = false;
    logger.info('MLOps Platform Server stopped successfully');
  }

  private async registerPlugins(): Promise<void> {
    await this.app.register(cors, {
      origin: true,
      credentials: true,
    });

    await this.app.register(helmet, {
      contentSecurityPolicy: env.NODE_ENV === 'production',
    });

    await this.app.register(multipart, {
      limits: {
        fileSize: 1024 * 1024 * 500,
      },
    });

    await this.app.register(rateLimit, {
      max: 1000,
      timeWindow: '1 minute',
      allowList: ['127.0.0.1', '::1'],
    });

    this.app.addHook('onRequest', (request, reply, done) => {
      const startTime = Date.now();
      request.log = logger.child({
        requestId: request.headers['x-request-id'] || Math.random().toString(36).slice(2),
        method: request.method,
        url: request.url,
      });

      reply.raw.on('finish', () => {
        const duration = Date.now() - startTime;
        request.log.info(
          { statusCode: reply.statusCode, durationMs: duration },
          'Request completed'
        );
      });

      done();
    });

    this.app.setErrorHandler((error, request, reply) => {
      request.log.error({ error }, 'Request error');
      reply.status(error.statusCode || 500).send({
        error: error.name,
        message: error.message,
        details: error.cause || undefined,
      });
    });
  }

  private async registerRoutes(): Promise<void> {
    this.app.get('/health', async () => ({
      status: 'ok',
      timestamp: Date.now(),
      uptime: process.uptime(),
      version: process.env.npm_package_version || '1.0.0',
    }));

    this.app.get('/api/v1/health', async () => ({
      status: 'ok',
      service: 'mlops-platform',
      timestamp: Date.now(),
    }));

    await registerModelRoutes(this.app);
    await registerInferenceRoutes(this.app);
    await registerExperimentRoutes(this.app);
    await registerFeatureStoreRoutes(this.app);
    await registerABTestRoutes(this.app);
    await registerMonitoringRoutes(this.app);

    this.app.get('/api/v1/routes', async () => {
      return this.app.printRoutes();
    });
  }

  private async registerGracefulShutdown(): Promise<void> {
    const signals: NodeJS.Signals[] = ['SIGTERM', 'SIGINT', 'SIGQUIT'];

    for (const signal of signals) {
      process.on(signal, async () => {
        logger.info({ signal }, 'Received shutdown signal');
        await this.stop();
        process.exit(0);
      });
    }

    process.on('uncaughtException', async (error) => {
      logger.fatal({ error }, 'Uncaught exception');
      await this.stop();
      process.exit(1);
    });

    process.on('unhandledRejection', async (reason, promise) => {
      logger.fatal({ reason, promise }, 'Unhandled rejection');
      await this.stop();
      process.exit(1);
    });
  }

  getApp(): FastifyInstance {
    return this.app;
  }
}

export const server = new MLOpsServer();

if (require.main === module) {
  server.start().catch((error) => {
    logger.fatal({ error }, 'Failed to start server');
    process.exit(1);
  });
}
