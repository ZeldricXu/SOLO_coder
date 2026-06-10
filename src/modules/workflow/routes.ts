import { FastifyPluginAsync, FastifyRequest } from 'fastify';
import { workflowService, CreateWorkflowInput, UpdateWorkflowInput, StartWorkflowInput, ApproveNodeInput } from './workflow-service';
import { TenantContext } from '@types/index';
import { z } from 'zod';

const workflowRoutes: FastifyPluginAsync = async (fastify) => {
  fastify.addHook('onRequest', async (request, reply) => {
    if (!request.tenant) {
      reply.status(401).send({ error: 'Unauthorized' });
      return;
    }
    if (!request.tenant.limits.enableWorkflow) {
      reply.status(403).send({ error: 'Workflow not enabled' });
      return;
    }
  });

  fastify.post(
    '/workflows',
    {
      schema: {
        tags: ['Workflow'],
        summary: 'Create a new workflow definition',
      },
    },
    async (
      request: FastifyRequest<{ Body: CreateWorkflowInput }> & { tenant: TenantContext },
      reply
    ) => {
      const workflow = await workflowService.createWorkflow(request.tenant, request.body);
      reply.status(201).send(workflow);
    }
  );

  fastify.get(
    '/workflows',
    {
      schema: {
        querystring: z.object({
          modelId: z.string().optional(),
          page: z.coerce.number().int().positive().default(1),
          pageSize: z.coerce.number().int().positive().max(100).default(50),
        }),
        tags: ['Workflow'],
        summary: 'List workflow definitions',
      },
    },
    async (
      request: FastifyRequest<{
        Querystring: { modelId?: string; page: number; pageSize: number };
      }> & { tenant: TenantContext }
    ) => {
      return workflowService.listWorkflows(
        request.tenant.tenantId,
        request.query.modelId,
        request.query.page,
        request.query.pageSize
      );
    }
  );

  fastify.get(
    '/workflows/:workflowId',
    {
      schema: {
        params: z.object({ workflowId: z.string() }),
        tags: ['Workflow'],
        summary: 'Get a workflow definition',
      },
    },
    async (
      request: FastifyRequest<{ Params: { workflowId: string } }> & { tenant: TenantContext },
      reply
    ) => {
      const workflow = await workflowService.getWorkflow(
        request.tenant.tenantId,
        request.params.workflowId
      );
      if (!workflow) {
        reply.status(404).send({ error: 'Workflow not found' });
        return;
      }
      return workflow;
    }
  );

  fastify.put(
    '/workflows/:workflowId',
    {
      schema: {
        params: z.object({ workflowId: z.string() }),
        tags: ['Workflow'],
        summary: 'Update a workflow definition',
      },
    },
    async (
      request: FastifyRequest<{
        Params: { workflowId: string };
        Body: UpdateWorkflowInput;
      }> & { tenant: TenantContext }
    ) => {
      return workflowService.updateWorkflow(
        request.tenant,
        request.params.workflowId,
        request.body
      );
    }
  );

  fastify.delete(
    '/workflows/:workflowId',
    {
      schema: {
        params: z.object({ workflowId: z.string() }),
        tags: ['Workflow'],
        summary: 'Delete a workflow definition',
      },
    },
    async (
      request: FastifyRequest<{ Params: { workflowId: string } }> & { tenant: TenantContext },
      reply
    ) => {
      await workflowService.deleteWorkflow(
        request.tenant.tenantId,
        request.params.workflowId
      );
      reply.status(204).send();
    }
  );

  fastify.post(
    '/workflows/start',
    {
      schema: {
        body: z.object({
          workflowId: z.string(),
          contentId: z.string(),
          startedBy: z.string(),
          contentData: z.record(z.unknown()).optional(),
        }),
        tags: ['Workflow'],
        summary: 'Start a workflow instance',
      },
    },
    async (
      request: FastifyRequest<{ Body: StartWorkflowInput }> & { tenant: TenantContext },
      reply
    ) => {
      const instance = await workflowService.startWorkflow(request.tenant, request.body);
      reply.status(201).send(instance);
    }
  );

  fastify.post(
    '/workflows/preview-approvers',
    {
      schema: {
        body: z.object({
          workflowId: z.string(),
          contentId: z.string(),
          startedBy: z.string(),
          contentData: z.record(z.unknown()).optional(),
        }),
        tags: ['Workflow'],
        summary: 'Preview dynamic approvers before starting workflow',
      },
    },
    async (
      request: FastifyRequest<{ Body: StartWorkflowInput }> & { tenant: TenantContext }
    ) => {
      const workflow = await workflowService.getWorkflow(
        request.tenant.tenantId,
        request.body.workflowId
      );
      if (!workflow) {
        return { error: 'Workflow not found' };
      }

      const nodes = workflow.nodes as unknown as import('@types/index').WorkflowNode[];
      const resolved = await (workflowService as any).resolveApprovalNodes(
        request.tenant.tenantId,
        nodes,
        request.body.contentId,
        request.body.startedBy,
        request.body.contentData
      );

      return {
        workflowId: request.body.workflowId,
        nodes: resolved.map(r => ({
          nodeId: r.nodeId,
          approvers: r.approvers,
          resolvedFrom: r.resolution.resolvedFrom,
          warnings: r.resolution.warnings,
        })),
      };
    }
  );

  fastify.post(
    '/workflows/instances/:instanceId/approve',
    {
      schema: {
        params: z.object({ instanceId: z.string() }),
        body: z.object({
          nodeId: z.string(),
          userId: z.string(),
          decision: z.enum(['approved', 'rejected']),
          comment: z.string().optional(),
        }),
        tags: ['Workflow'],
        summary: 'Approve or reject a workflow node',
      },
    },
    async (
      request: FastifyRequest<{
        Params: { instanceId: string };
        Body: ApproveNodeInput;
      }> & { tenant: TenantContext }
    ) => {
      return workflowService.approveNode(request.tenant, {
        instanceId: request.params.instanceId,
        ...request.body,
      });
    }
  );

  fastify.get(
    '/workflows/instances/:instanceId',
    {
      schema: {
        params: z.object({ instanceId: z.string() }),
        tags: ['Workflow'],
        summary: 'Get a workflow instance',
      },
    },
    async (
      request: FastifyRequest<{ Params: { instanceId: string } }> & { tenant: TenantContext },
      reply
    ) => {
      const instance = await workflowService.getInstance(
        request.tenant.tenantId,
        request.params.instanceId
      );
      if (!instance) {
        reply.status(404).send({ error: 'Workflow instance not found' });
        return;
      }
      return instance;
    }
  );

  fastify.get(
    '/workflows/instances',
    {
      schema: {
        querystring: z.object({
          contentId: z.string().optional(),
          workflowId: z.string().optional(),
          status: z.string().optional(),
          page: z.coerce.number().int().positive().default(1),
          pageSize: z.coerce.number().int().positive().max(100).default(50),
        }),
        tags: ['Workflow'],
        summary: 'List workflow instances',
      },
    },
    async (
      request: FastifyRequest<{
        Querystring: {
          contentId?: string;
          workflowId?: string;
          status?: string;
          page: number;
          pageSize: number;
        };
      }> & { tenant: TenantContext }
    ) => {
      return workflowService.listInstances(
        request.tenant.tenantId,
        request.query.contentId,
        request.query.workflowId,
        request.query.status,
        request.query.page,
        request.query.pageSize
      );
    }
  );

  fastify.post(
    '/workflows/instances/:instanceId/cancel',
    {
      schema: {
        params: z.object({ instanceId: z.string() }),
        body: z.object({ cancelledBy: z.string() }),
        tags: ['Workflow'],
        summary: 'Cancel a workflow instance',
      },
    },
    async (
      request: FastifyRequest<{
        Params: { instanceId: string };
        Body: { cancelledBy: string };
      }> & { tenant: TenantContext }
    ) => {
      await workflowService.cancelWorkflow(
        request.tenant.tenantId,
        request.params.instanceId,
        request.body.cancelledBy
      );
      return { success: true };
    }
  );

  fastify.get(
    '/workflows/instances/:instanceId/verify/:approvalIndex',
    {
      schema: {
        params: z.object({
          instanceId: z.string(),
          approvalIndex: z.coerce.number().int().nonnegative(),
        }),
        tags: ['Workflow'],
        summary: 'Verify approval signature tamper-proof',
      },
    },
    async (
      request: FastifyRequest<{
        Params: { instanceId: string; approvalIndex: number };
      }> & { tenant: TenantContext }
    ) => {
      const valid = await workflowService.verifyApprovalSignature(
        request.params.instanceId,
        request.params.approvalIndex
      );
      return { valid };
    }
  );
};

export default workflowRoutes;
