import fastify, { FastifyInstance, FastifyServerOptions } from 'fastify';
import fastifyCors from '@fastify/cors';
import fastifyHelmet from '@fastify/helmet';
import fastifySwagger from '@fastify/swagger';
import fastifySwaggerUi from '@fastify/swagger-ui';
import { fastifyZod } from 'fastify-zod';
import { config } from '@config/index';
import { logger } from '@utils/logger';

import { tenantContextPlugin } from '@modules/tenant/tenant-context';
import { rateLimitPlugin } from '@modules/usage/rate-limit-plugin';

import tenantRoutes from '@modules/tenant/routes';
import contentModelRoutes from '@modules/content-model/routes';
import versionControlRoutes from '@modules/version-control/routes';
import workflowRoutes from '@modules/workflow/routes';
import searchRoutes from '@modules/search/routes';
import cdnRoutes from '@modules/cdn/routes';
import webhookRoutes from '@modules/webhook/routes';
import usageRoutes from '@modules/usage/routes';

export function createApp(options: FastifyServerOptions = {}): FastifyInstance {
  const app = fastify({
    logger: logger as any,
    disableRequestLogging: config.nodeEnv === 'production',
    genReqId: (req) => (req.headers['x-request-id'] as string) || `req_${Date.now()}_${Math.random().toString(36).substr(2, 9)}`,
    ...options,
  });

  app.register(fastifyCors, {
    origin: config.corsOrigin,
    credentials: true,
    allowedHeaders: ['Content-Type', 'Authorization', 'X-API-Key', 'X-Request-ID'],
    exposedHeaders: ['X-RateLimit-Limit', 'X-RateLimit-Remaining', 'X-RateLimit-Reset'],
  });

  app.register(fastifyHelmet, {
    contentSecurityPolicy: config.nodeEnv === 'production',
  });

  app.register(fastifyZod);

  if (config.nodeEnv !== 'production') {
    app.register(fastifySwagger, {
      openapi: {
        info: {
          title: 'Multi-Tenant CMS API',
          description: 'SaaS Content Management System API with Multi-Tenancy',
          version: '1.0.0',
        },
        servers: [
          { url: `http://localhost:${config.port}`, description: 'Development' },
        ],
        components: {
          securitySchemes: {
            apiKey: {
              type: 'apiKey',
              name: 'X-API-Key',
              in: 'header',
            },
            bearerAuth: {
              type: 'http',
              scheme: 'bearer',
            },
          },
        },
      },
    });

    app.register(fastifySwaggerUi, {
      routePrefix: '/docs',
      uiConfig: {
        docExpansion: 'list',
        deepLinking: true,
      },
    });
  }

  app.register(tenantContextPlugin);

  app.addHook('preHandler', async (request, reply) => {
    (request as any).log = logger.withContext({
      requestId: request.id,
      tenantId: (request as any).tenant?.tenantId,
    });
  });

  app.get('/health', async () => ({
    status: 'ok',
    timestamp: new Date().toISOString(),
    uptime: process.uptime(),
  }));

  app.get('/api/v1/health', async () => ({
    status: 'ok',
    version: '1.0.0',
    timestamp: new Date().toISOString(),
  }));

  const apiV1 = async (instance: FastifyInstance) => {
    instance.register(rateLimitPlugin);

    instance.register(tenantRoutes, { prefix: '/tenants' });
    instance.register(contentModelRoutes, { prefix: '/content-models' });
    instance.register(versionControlRoutes, { prefix: '/versions' });
    instance.register(workflowRoutes, { prefix: '/workflows' });
    instance.register(searchRoutes, { prefix: '/search' });
    instance.register(cdnRoutes, { prefix: '/cdn' });
    instance.register(webhookRoutes, { prefix: '/webhooks' });
    instance.register(usageRoutes, { prefix: '/usage' });

    instance.setErrorHandler((error, request, reply) => {
      logger.error(
        {
          err: error,
          requestId: request.id,
          url: request.url,
          method: request.method,
        },
        'Request error'
      );

      const statusCode = error.statusCode || 500;
      const response = {
        error: error.name || 'InternalServerError',
        message: error.message || 'An unexpected error occurred',
        ...(config.nodeEnv !== 'production' && { stack: error.stack }),
      };

      reply.status(statusCode).send(response);
    });
  };

  app.register(apiV1, { prefix: '/api/v1' });

  app.ready((err) => {
    if (err) {
      logger.error({ err }, 'Failed to start application');
      process.exit(1);
    }

    if (config.nodeEnv !== 'production') {
      logger.info(`Swagger docs available at http://localhost:${config.port}/docs`);
    }
  });

  return app;
}

export default createApp;
