import { FastifyPluginAsync, FastifyRequest, FastifyReply } from 'fastify';
import fp from 'fastify-plugin';
import { tenantResolver } from './tenant-resolver';
import { TenantContext } from '@types/index';
import { logger } from '@utils/logger';

declare module 'fastify' {
  interface FastifyRequest {
    tenant: TenantContext;
    requestId: string;
  }
}

const tenantContextPlugin: FastifyPluginAsync = async (fastify) => {
  fastify.decorateRequest('tenant', null);
  fastify.decorateRequest('requestId', '');

  fastify.addHook('onRequest', async (request: FastifyRequest, reply: FastifyReply) => {
    request.requestId = generateRequestId();

    const requestLogger = logger.child({
      requestId: request.requestId,
      method: request.method,
      url: request.url,
      ip: request.ip,
    });

    request.log = requestLogger;

    if (isPublicRoute(request.url)) {
      return;
    }

    const tenant = await tenantResolver.resolveFromRequest(request);

    if (!tenant) {
      requestLogger.warn('Tenant not found or inactive');
      reply.status(401).send({
        error: 'Unauthorized',
        message: 'Invalid or missing tenant credentials',
        code: 'TENANT_NOT_FOUND',
      });
      return;
    }

    request.tenant = tenant;
    requestLogger.child({
      tenantId: tenant.tenantId,
      tenantCode: tenant.tenantCode,
    });

    requestLogger.debug('Tenant context resolved');
  });
};

function generateRequestId(): string {
  return `req_${Date.now().toString(36)}_${Math.random().toString(36).substr(2, 9)}`;
}

function isPublicRoute(url: string): boolean {
  const publicRoutes = [
    '/health',
    '/metrics',
    '/docs',
    '/swagger',
    '/favicon.ico',
  ];

  return publicRoutes.some(route => url.startsWith(route)) ||
         url.startsWith('/webhooks/') ||
         url === '/';
}

export default fp(tenantContextPlugin);
