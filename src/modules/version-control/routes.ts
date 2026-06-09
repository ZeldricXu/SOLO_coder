import { FastifyPluginAsync, FastifyRequest } from 'fastify';
import { versionControlService, RestoreVersionInput } from './version-service';
import { TenantContext } from '@types/index';
import { z } from 'zod';

const versionControlRoutes: FastifyPluginAsync = async (fastify) => {
  fastify.addHook('onRequest', async (request, reply) => {
    if (!request.tenant) {
      reply.status(401).send({ error: 'Unauthorized' });
      return;
    }
    if (!request.tenant.limits.enableVersioning) {
      reply.status(403).send({ error: 'Versioning not enabled' });
      return;
    }
  });

  fastify.get(
    '/models/:modelId/content/:contentId/versions',
    {
      schema: {
        params: z.object({ modelId: z.string(), contentId: z.string() }),
        querystring: z.object({
          page: z.coerce.number().int().positive().default(1),
          pageSize: z.coerce.number().int().positive().max(100).default(20),
        }),
        tags: ['Version Control'],
        summary: 'List content versions',
      },
    },
    async (
      request: FastifyRequest<{
        Params: { modelId: string; contentId: string };
        Querystring: { page: number; pageSize: number };
      }> & { tenant: TenantContext }
    ) => {
      return versionControlService.listVersions(
        request.tenant.tenantId,
        request.params.contentId,
        request.query.page,
        request.query.pageSize
      );
    }
  );

  fastify.get(
    '/versions/:versionId',
    {
      schema: {
        params: z.object({ versionId: z.string() }),
        tags: ['Version Control'],
        summary: 'Get a specific version',
      },
    },
    async (
      request: FastifyRequest<{ Params: { versionId: string } }> & { tenant: TenantContext },
      reply
    ) => {
      const version = await versionControlService.getVersion(
        request.tenant.tenantId,
        request.params.versionId
      );
      if (!version) {
        reply.status(404).send({ error: 'Version not found' });
        return;
      }
      return version;
    }
  );

  fastify.get(
    '/versions/:versionId/diff',
    {
      schema: {
        params: z.object({ versionId: z.string() }),
        tags: ['Version Control'],
        summary: 'Get version diff from previous',
      },
    },
    async (
      request: FastifyRequest<{ Params: { versionId: string } }> & { tenant: TenantContext }
    ) => {
      return versionControlService.getVersionDiff(
        request.tenant.tenantId,
        request.params.versionId
      );
    }
  );

  fastify.get(
    '/models/:modelId/content/:contentId/versions/compare',
    {
      schema: {
        params: z.object({ modelId: z.string(), contentId: z.string() }),
        querystring: z.object({
          versionA: z.coerce.number().int().positive(),
          versionB: z.coerce.number().int().positive(),
        }),
        tags: ['Version Control'],
        summary: 'Compare two versions',
      },
    },
    async (
      request: FastifyRequest<{
        Params: { modelId: string; contentId: string };
        Querystring: { versionA: number; versionB: number };
      }> & { tenant: TenantContext }
    ) => {
      return versionControlService.compareVersions(
        request.tenant.tenantId,
        request.params.contentId,
        request.query.versionA,
        request.query.versionB
      );
    }
  );

  fastify.post(
    '/versions/:versionId/restore',
    {
      schema: {
        params: z.object({ versionId: z.string() }),
        body: z.object({ restoredBy: z.string() }),
        tags: ['Version Control'],
        summary: 'Restore to a previous version',
      },
    },
    async (
      request: FastifyRequest<{
        Params: { versionId: string };
        Body: { restoredBy: string };
      }> & { tenant: TenantContext }
    ) => {
      return versionControlService.restoreVersion(request.tenant, {
        versionId: request.params.versionId,
        restoredBy: request.body.restoredBy,
      });
    }
  );

  fastify.delete(
    '/models/:modelId/content/:contentId/versions/cleanup',
    {
      schema: {
        params: z.object({ modelId: z.string(), contentId: z.string() }),
        querystring: z.object({ keep: z.coerce.number().int().positive().default(50) }),
        tags: ['Version Control'],
        summary: 'Cleanup old versions',
      },
    },
    async (
      request: FastifyRequest<{
        Params: { modelId: string; contentId: string };
        Querystring: { keep: number };
      }> & { tenant: TenantContext }
    ) => {
      const deleted = await versionControlService.deleteOldVersions(
        request.tenant.tenantId,
        request.params.contentId,
        request.query.keep
      );
      return { deletedCount: deleted };
    }
  );
};

export default versionControlRoutes;
