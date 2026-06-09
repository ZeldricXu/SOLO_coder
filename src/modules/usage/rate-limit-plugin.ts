import { FastifyPluginAsync, FastifyRequest, FastifyReply } from 'fastify';
import { usageService } from './usage-service';
import { TenantContext } from '@types/index';
import { logger } from '@utils/logger';

declare module 'fastify' {
  interface FastifyRequest {
    tenant?: TenantContext;
  }
}

export const rateLimitPlugin: FastifyPluginAsync = async (fastify) => {
  fastify.addHook('onRequest', async (request: FastifyRequest, reply: FastifyReply) => {
    if (!request.tenant) {
      return;
    }

    const endpoint = request.routerPath || request.url;
    const [minuteResult, dailyResult] = await Promise.all([
      usageService.checkRateLimit(request.tenant, endpoint),
      usageService.checkDailyRateLimit(request.tenant),
    ]);

    reply.header('X-RateLimit-Limit', minuteResult.limit.toString());
    reply.header('X-RateLimit-Remaining', minuteResult.remaining.toString());
    reply.header('X-RateLimit-Reset', Math.floor(minuteResult.resetAt.getTime() / 1000).toString());
    reply.header('X-RateLimit-Daily-Limit', dailyResult.limit.toString());
    reply.header('X-RateLimit-Daily-Remaining', dailyResult.remaining.toString());

    if (!minuteResult.allowed) {
      logger.warn(
        { tenantId: request.tenant.tenantId, endpoint },
        'Minute rate limit exceeded'
      );
      reply.status(429).send({
        error: 'Too Many Requests',
        message: `Rate limit exceeded. Try again at ${minuteResult.resetAt.toISOString()}`,
        retryAfter: Math.ceil((minuteResult.resetAt.getTime() - Date.now()) / 1000),
      });
      return;
    }

    if (!dailyResult.allowed) {
      logger.warn(
        { tenantId: request.tenant.tenantId },
        'Daily rate limit exceeded'
      );
      reply.status(429).send({
        error: 'Too Many Requests',
        message: `Daily API limit exceeded. Try again at ${dailyResult.resetAt.toISOString()}`,
        retryAfter: Math.ceil((dailyResult.resetAt.getTime() - Date.now()) / 1000),
      });
      return;
    }
  });

  fastify.addHook('onResponse', async (request: FastifyRequest, reply: FastifyReply) => {
    if (request.tenant) {
      const endpoint = request.routerPath || request.url;
      await usageService.trackApiCall(
        request.tenant.tenantId,
        endpoint,
        reply.statusCode
      );
    }
  });
};

export default rateLimitPlugin;
