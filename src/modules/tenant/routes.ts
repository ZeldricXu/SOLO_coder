import { FastifyPluginAsync, FastifyRequest, FastifyReply } from 'fastify';
import { tenantService, CreateTenantInput, UpdateTenantInput } from './tenant-service';
import { TenantStatus } from '@prisma/client';
import { z } from 'zod';

const createTenantSchema = z.object({
  code: z.string().min(2).max(50),
  name: z.string().min(2).max(100),
  plan: z.enum(['free', 'starter', 'professional', 'enterprise']),
  hostPattern: z.string().max(200).optional(),
  customDomain: z.string().max(100).optional(),
});

const updateTenantSchema = z.object({
  name: z.string().min(2).max(100).optional(),
  status: z.enum(['ACTIVE', 'SUSPENDED', 'PENDING', 'CANCELLED']).optional(),
  plan: z.enum(['free', 'starter', 'professional', 'enterprise']).optional(),
  hostPattern: z.string().max(200).optional().nullable(),
  customDomain: z.string().max(100).optional().nullable(),
  maxApiCallsPerDay: z.number().int().positive().optional(),
  maxStorageGb: z.number().int().positive().optional(),
  maxContentModels: z.number().int().positive().optional(),
  maxUsers: z.number().int().positive().optional(),
  maxWebhooks: z.number().int().positive().optional(),
  enableVersioning: z.boolean().optional(),
  enableWorkflow: z.boolean().optional(),
  enableElasticsearch: z.boolean().optional(),
  enableCDN: z.boolean().optional(),
});

const listTenantsSchema = z.object({
  page: z.coerce.number().int().positive().default(1),
  pageSize: z.coerce.number().int().positive().max(100).default(50),
  status: z.enum(['ACTIVE', 'SUSPENDED', 'PENDING', 'CANCELLED']).optional(),
});

const getTenantSchema = z.object({
  id: z.string().uuid(),
});

const tenantRoutes: FastifyPluginAsync = async (fastify) => {
  fastify.post(
    '/tenants',
    {
      schema: {
        body: createTenantSchema,
        tags: ['Tenant Management'],
        summary: 'Create a new tenant',
      },
    },
    async (request: FastifyRequest<{ Body: CreateTenantInput }>, reply: FastifyReply) => {
      const tenant = await tenantService.createTenant(request.body);
      reply.status(201).send(tenant);
    }
  );

  fastify.get(
    '/tenants',
    {
      schema: {
        querystring: listTenantsSchema,
        tags: ['Tenant Management'],
        summary: 'List all tenants',
      },
    },
    async (
      request: FastifyRequest<{ Querystring: { page: number; pageSize: number; status?: TenantStatus } }>
    ) => {
      const { tenants, total } = await tenantService.listTenants(
        request.query.page,
        request.query.pageSize,
        request.query.status
      );
      return {
        data: tenants,
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
    '/tenants/:id',
    {
      schema: {
        params: getTenantSchema,
        tags: ['Tenant Management'],
        summary: 'Get a tenant by ID',
      },
    },
    async (
      request: FastifyRequest<{ Params: { id: string } }>,
      reply: FastifyReply
    ) => {
      const tenant = await tenantService.getTenant(request.params.id);
      if (!tenant) {
        reply.status(404).send({ error: 'Tenant not found' });
        return;
      }
      return tenant;
    }
  );

  fastify.put(
    '/tenants/:id',
    {
      schema: {
        params: getTenantSchema,
        body: updateTenantSchema,
        tags: ['Tenant Management'],
        summary: 'Update a tenant',
      },
    },
    async (
      request: FastifyRequest<{ Params: { id: string }; Body: UpdateTenantInput }>
    ) => {
      return tenantService.updateTenant(request.params.id, request.body);
    }
  );

  fastify.post(
    '/tenants/:id/suspend',
    {
      schema: {
        params: getTenantSchema,
        tags: ['Tenant Management'],
        summary: 'Suspend a tenant',
      },
    },
    async (
      request: FastifyRequest<{ Params: { id: string } }>
    ) => {
      return tenantService.suspendTenant(request.params.id);
    }
  );

  fastify.post(
    '/tenants/:id/activate',
    {
      schema: {
        params: getTenantSchema,
        tags: ['Tenant Management'],
        summary: 'Activate a tenant',
      },
    },
    async (
      request: FastifyRequest<{ Params: { id: string } }>
    ) => {
      return tenantService.activateTenant(request.params.id);
    }
  );

  fastify.post(
    '/tenants/:id/regenerate-api-key',
    {
      schema: {
        params: getTenantSchema,
        tags: ['Tenant Management'],
        summary: 'Regenerate tenant API key',
      },
    },
    async (
      request: FastifyRequest<{ Params: { id: string } }>
    ) => {
      const apiKey = await tenantService.regenerateApiKey(request.params.id);
      return { apiKey };
    }
  );

  fastify.delete(
    '/tenants/:id',
    {
      schema: {
        params: getTenantSchema,
        tags: ['Tenant Management'],
        summary: 'Delete (soft) a tenant',
      },
    },
    async (
      request: FastifyRequest<{ Params: { id: string } }>,
      reply: FastifyReply
    ) => {
      await tenantService.deleteTenant(request.params.id);
      reply.status(204).send();
    }
  );

  fastify.get(
    '/tenants/:id/limits',
    {
      schema: {
        params: getTenantSchema,
        tags: ['Tenant Management'],
        summary: 'Check tenant limits',
      },
    },
    async (
      request: FastifyRequest<{ Params: { id: string } }>
    ) => {
      return tenantService.checkTenantLimits(request.params.id);
    }
  );
};

export default tenantRoutes;
