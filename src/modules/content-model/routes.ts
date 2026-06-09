import { FastifyPluginAsync, FastifyRequest, FastifyReply } from 'fastify';
import { contentModelService, CreateContentModelInput, UpdateContentModelInput, CreateContentInput, UpdateContentInput } from './content-model-service';
import { ContentSchema, TenantContext } from '@types/index';
import { ContentStatus } from '@prisma/client';
import { z } from 'zod';

const contentModelRoutes: FastifyPluginAsync = async (fastify) => {
  fastify.addHook('onRequest', async (request, reply) => {
    if (!request.tenant) {
      reply.status(401).send({ error: 'Unauthorized' });
      return;
    }
  });

  fastify.post(
    '/models',
    {
      schema: {
        tags: ['Content Models'],
        summary: 'Create a new content model',
      },
    },
    async (
      request: FastifyRequest<{ Body: CreateContentModelInput }> & { tenant: TenantContext },
      reply: FastifyReply
    ) => {
      const model = await contentModelService.createContentModel(request.tenant, request.body);
      reply.status(201).send(model);
    }
  );

  fastify.get(
    '/models',
    {
      schema: {
        querystring: z.object({
          page: z.coerce.number().int().positive().default(1),
          pageSize: z.coerce.number().int().positive().max(100).default(50),
        }),
        tags: ['Content Models'],
        summary: 'List content models',
      },
    },
    async (
      request: FastifyRequest<{ Querystring: { page: number; pageSize: number } }> & { tenant: TenantContext }
    ) => {
      const { models, total } = await contentModelService.listContentModels(
        request.tenant.tenantId,
        request.query.page,
        request.query.pageSize
      );
      return {
        data: models,
        pagination: {
          page: request.query.page,
          pageSize: request.query.pageSize,
          total,
          pages: Math.ceil(total / request.query.pageSize),
        },
      };
    }
  );

  fastify.get(
    '/models/:modelId',
    {
      schema: {
        params: z.object({ modelId: z.string() }),
        tags: ['Content Models'],
        summary: 'Get a content model',
      },
    },
    async (
      request: FastifyRequest<{ Params: { modelId: string } }> & { tenant: TenantContext },
      reply: FastifyReply
    ) => {
      const model = await contentModelService.getContentModel(
        request.tenant.tenantId,
        request.params.modelId
      );
      if (!model) {
        reply.status(404).send({ error: 'Content model not found' });
        return;
      }
      return model;
    }
  );

  fastify.put(
    '/models/:modelId',
    {
      schema: {
        params: z.object({ modelId: z.string() }),
        tags: ['Content Models'],
        summary: 'Update a content model',
      },
    },
    async (
      request: FastifyRequest<{ Params: { modelId: string }; Body: UpdateContentModelInput }> & { tenant: TenantContext }
    ) => {
      return contentModelService.updateContentModel(
        request.tenant,
        request.params.modelId,
        request.body
      );
    }
  );

  fastify.delete(
    '/models/:modelId',
    {
      schema: {
        params: z.object({ modelId: z.string() }),
        tags: ['Content Models'],
        summary: 'Delete a content model',
      },
    },
    async (
      request: FastifyRequest<{ Params: { modelId: string } }> & { tenant: TenantContext },
      reply: FastifyReply
    ) => {
      await contentModelService.deleteContentModel(
        request.tenant.tenantId,
        request.params.modelId
      );
      reply.status(204).send();
    }
  );

  fastify.post(
    '/models/:modelId/content',
    {
      schema: {
        params: z.object({ modelId: z.string() }),
        tags: ['Content Entries'],
        summary: 'Create a content entry',
      },
    },
    async (
      request: FastifyRequest<{ Params: { modelId: string }; Body: CreateContentInput }> & { tenant: TenantContext },
      reply: FastifyReply
    ) => {
      const content = await contentModelService.createContent(
        request.tenant,
        request.params.modelId,
        request.body
      );
      reply.status(201).send(content);
    }
  );

  fastify.get(
    '/models/:modelId/content',
    {
      schema: {
        params: z.object({ modelId: z.string() }),
        querystring: z.object({
          page: z.coerce.number().int().positive().default(1),
          pageSize: z.coerce.number().int().positive().max(100).default(50),
          status: z.nativeEnum(ContentStatus).optional(),
        }),
        tags: ['Content Entries'],
        summary: 'List content entries',
      },
    },
    async (
      request: FastifyRequest<{
        Params: { modelId: string };
        Querystring: { page: number; pageSize: number; status?: ContentStatus };
      }> & { tenant: TenantContext }
    ) => {
      const { content, total } = await contentModelService.listContent(
        request.tenant.tenantId,
        request.params.modelId,
        request.query.page,
        request.query.pageSize,
        request.query.status
      );
      return {
        data: content,
        pagination: {
          page: request.query.page,
          pageSize: request.query.pageSize,
          total,
          pages: Math.ceil(total / request.query.pageSize),
        },
      };
    }
  );

  fastify.get(
    '/models/:modelId/content/:contentId',
    {
      schema: {
        params: z.object({ modelId: z.string(), contentId: z.string() }),
        tags: ['Content Entries'],
        summary: 'Get a content entry',
      },
    },
    async (
      request: FastifyRequest<{ Params: { modelId: string; contentId: string } }> & { tenant: TenantContext },
      reply: FastifyReply
    ) => {
      const content = await contentModelService.getContent(
        request.tenant.tenantId,
        request.params.modelId,
        request.params.contentId
      );
      if (!content) {
        reply.status(404).send({ error: 'Content not found' });
        return;
      }
      return content;
    }
  );

  fastify.put(
    '/models/:modelId/content/:contentId',
    {
      schema: {
        params: z.object({ modelId: z.string(), contentId: z.string() }),
        tags: ['Content Entries'],
        summary: 'Update a content entry',
      },
    },
    async (
      request: FastifyRequest<{
        Params: { modelId: string; contentId: string };
        Body: UpdateContentInput;
      }> & { tenant: TenantContext }
    ) => {
      return contentModelService.updateContent(
        request.tenant,
        request.params.modelId,
        request.params.contentId,
        request.body
      );
    }
  );

  fastify.delete(
    '/models/:modelId/content/:contentId',
    {
      schema: {
        params: z.object({ modelId: z.string(), contentId: z.string() }),
        tags: ['Content Entries'],
        summary: 'Delete a content entry',
      },
    },
    async (
      request: FastifyRequest<{ Params: { modelId: string; contentId: string } }> & { tenant: TenantContext },
      reply: FastifyReply
    ) => {
      await contentModelService.deleteContent(
        request.tenant.tenantId,
        request.params.modelId,
        request.params.contentId
      );
      reply.status(204).send();
    }
  );

  fastify.post(
    '/models/:modelId/content/:contentId/publish',
    {
      schema: {
        params: z.object({ modelId: z.string(), contentId: z.string() }),
        body: z.object({ publishedBy: z.string() }),
        tags: ['Content Entries'],
        summary: 'Publish a content entry',
      },
    },
    async (
      request: FastifyRequest<{
        Params: { modelId: string; contentId: string };
        Body: { publishedBy: string };
      }> & { tenant: TenantContext }
    ) => {
      return contentModelService.publishContent(
        request.tenant.tenantId,
        request.params.modelId,
        request.params.contentId,
        request.body.publishedBy
      );
    }
  );

  fastify.post(
    '/models/:modelId/content/:contentId/unpublish',
    {
      schema: {
        params: z.object({ modelId: z.string(), contentId: z.string() }),
        body: z.object({ updatedBy: z.string() }),
        tags: ['Content Entries'],
        summary: 'Unpublish a content entry',
      },
    },
    async (
      request: FastifyRequest<{
        Params: { modelId: string; contentId: string };
        Body: { updatedBy: string };
      }> & { tenant: TenantContext }
    ) => {
      return contentModelService.unpublishContent(
        request.tenant.tenantId,
        request.params.modelId,
        request.params.contentId,
        request.body.updatedBy
      );
    }
  );

  fastify.get(
    '/models/:modelId/published',
    {
      schema: {
        params: z.object({ modelId: z.string() }),
        querystring: z.object({
          page: z.coerce.number().int().positive().default(1),
          pageSize: z.coerce.number().int().positive().max(100).default(50),
        }),
        tags: ['Published Content'],
        summary: 'List published content',
      },
    },
    async (
      request: FastifyRequest<{
        Params: { modelId: string };
        Querystring: { page: number; pageSize: number };
      }> & { tenant: TenantContext }
    ) => {
      return contentModelService.listPublishedContent(
        request.tenant.tenantId,
        request.params.modelId,
        request.query.page,
        request.query.pageSize
      );
    }
  );

  fastify.get(
    '/models/:modelId/published/:contentId',
    {
      schema: {
        params: z.object({ modelId: string(), contentId: z.string() }),
        tags: ['Published Content'],
        summary: 'Get published content',
      },
    },
    async (
      request: FastifyRequest<{ Params: { modelId: string; contentId: string } }> & { tenant: TenantContext },
      reply: FastifyReply
    ) => {
      const data = await contentModelService.getPublishedContent(
        request.tenant.tenantId,
        request.params.modelId,
        request.params.contentId
      );
      if (!data) {
        reply.status(404).send({ error: 'Published content not found' });
        return;
      }
      return { id: request.params.contentId, data };
    }
  );
};

export default contentModelRoutes;
