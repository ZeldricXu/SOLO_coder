import { WorkflowDefinition } from '@prisma/client';
import { connectionPool } from '../tenant/connection-pool';
import { generateId } from '@utils/crypto';
import { TenantContext, WorkflowNode } from '@types/index';
import { logger } from '@utils/logger';
import { Prisma } from '@prisma/client';

export interface CreateWorkflowInput {
  modelId: string;
  name: string;
  description?: string;
  nodes: WorkflowNode[];
  startNodeId: string;
  endNodeId: string;
  isDefault?: boolean;
}

export interface UpdateWorkflowInput {
  name?: string;
  description?: string;
  nodes?: WorkflowNode[];
  startNodeId?: string;
  endNodeId?: string;
  isDefault?: boolean;
}

export class WorkflowConfigLoader {
  private prisma = connectionPool.getPlatformPrisma();

  async createWorkflow(
    tenant: TenantContext,
    input: CreateWorkflowInput
  ): Promise<WorkflowDefinition> {
    if (!tenant.limits.enableWorkflow) {
      throw new Error('Workflow is not enabled for this tenant');
    }

    this.validateWorkflowNodes(input.nodes, input.startNodeId, input.endNodeId);

    const existingDefault = input.isDefault
      ? await this.prisma.workflowDefinition.findFirst({
          where: {
            tenantId: tenant.tenantId,
            modelId: input.modelId,
            isDefault: true,
          },
        })
      : null;

    return this.prisma.$transaction(async (tx) => {
      if (existingDefault) {
        await tx.workflowDefinition.update({
          where: { id: existingDefault.id },
          data: { isDefault: false },
        });
      }

      const maxVersion = await tx.workflowDefinition.aggregate({
        where: {
          tenantId: tenant.tenantId,
          modelId: input.modelId,
          name: input.name,
        },
        _max: { version: true },
      });

      return tx.workflowDefinition.create({
        data: {
          id: generateId('wf'),
          tenantId: tenant.tenantId,
          modelId: input.modelId,
          name: input.name,
          description: input.description,
          nodes: input.nodes as unknown as Prisma.JsonValue,
          startNodeId: input.startNodeId,
          endNodeId: input.endNodeId,
          isDefault: input.isDefault || false,
          version: (maxVersion._max.version || 0) + 1,
        },
      });
    });
  }

  async getWorkflow(tenantId: string, workflowId: string): Promise<WorkflowDefinition | null> {
    return this.prisma.workflowDefinition.findFirst({
      where: { id: workflowId, tenantId },
    });
  }

  async listWorkflows(
    tenantId: string,
    modelId?: string,
    page = 1,
    pageSize = 50
  ): Promise<{ workflows: WorkflowDefinition[]; total: number }> {
    const where: any = { tenantId };
    if (modelId) {
      where.modelId = modelId;
    }

    const [workflows, total] = await Promise.all([
      this.prisma.workflowDefinition.findMany({
        where,
        skip: (page - 1) * pageSize,
        take: pageSize,
        orderBy: { createdAt: 'desc' },
      }),
      this.prisma.workflowDefinition.count({ where }),
    ]);

    return { workflows, total };
  }

  async updateWorkflow(
    tenant: TenantContext,
    workflowId: string,
    input: UpdateWorkflowInput
  ): Promise<WorkflowDefinition> {
    if (!tenant.limits.enableWorkflow) {
      throw new Error('Workflow is not enabled for this tenant');
    }

    const existing = await this.getWorkflow(tenant.tenantId, workflowId);
    if (!existing) {
      throw new Error('Workflow not found');
    }

    if (input.nodes) {
      this.validateWorkflowNodes(
        input.nodes,
        input.startNodeId || existing.startNodeId,
        input.endNodeId || existing.endNodeId
      );
    }

    return this.prisma.workflowDefinition.update({
      where: { id: workflowId },
      data: {
        name: input.name,
        description: input.description,
        nodes: input.nodes as unknown as Prisma.JsonValue,
        startNodeId: input.startNodeId,
        endNodeId: input.endNodeId,
        isDefault: input.isDefault,
        version: { increment: (input.nodes || input.startNodeId || input.endNodeId) ? 1 : 0 },
      },
    });
  }

  async deleteWorkflow(tenantId: string, workflowId: string): Promise<void> {
    await this.prisma.$transaction([
      this.prisma.workflowInstance.deleteMany({
        where: { definitionId: workflowId, tenantId },
      }),
      this.prisma.workflowDefinition.delete({
        where: { id: workflowId },
      }),
    ]);
  }

  validateWorkflowNodes(
    nodes: WorkflowNode[],
    startNodeId: string,
    endNodeId: string
  ): void {
    const nodeMap = new Map(nodes.map(n => [n.id, n]));

    if (!nodeMap.has(startNodeId)) {
      throw new Error('Start node not found in nodes');
    }
    if (!nodeMap.has(endNodeId)) {
      throw new Error('End node not found in nodes');
    }

    const startNode = nodeMap.get(startNodeId)!;
    if (startNode.type !== 'start') {
      throw new Error('Start node must be of type start');
    }

    const endNode = nodeMap.get(endNodeId)!;
    if (endNode.type !== 'end') {
      throw new Error('End node must be of type end');
    }

    for (const node of nodes) {
      if (node.type === 'approval') {
        const hasStaticApprovers = node.config.approvers && node.config.approvers.length > 0;
        const hasDynamicApprovers = (node.config.dynamicApprovers?.staticFallback?.length ?? 0) > 0;
        if (!hasStaticApprovers && !hasDynamicApprovers) {
          throw new Error(`Approval node ${node.id} must have either approvers or dynamicApprovers with staticFallback`);
        }
        if (node.config.dynamicApprovers && !node.config.dynamicApprovers.source) {
          throw new Error(`Approval node ${node.id} dynamicApprovers must specify source`);
        }
        if (node.config.approvalType === 'percentage' && !node.config.approvalPercentage) {
          throw new Error(`Approval node ${node.id} with percentage type must have approvalPercentage`);
        }
      }

      if (node.type === 'condition') {
        if (!node.config.branches || node.config.branches.length === 0) {
          throw new Error(`Condition node ${node.id} must have branches`);
        }
        for (const branch of node.config.branches) {
          if (!nodeMap.has(branch.nodeId)) {
            throw new Error(`Branch target ${branch.nodeId} not found`);
          }
        }
      }

      if (node.type === 'parallel') {
        if (!node.config.parallelNodes || node.config.parallelNodes.length < 2) {
          throw new Error(`Parallel node ${node.id} must have at least 2 parallel nodes`);
        }
        for (const parallelNodeId of node.config.parallelNodes) {
          if (!nodeMap.has(parallelNodeId)) {
            throw new Error(`Parallel node ${parallelNodeId} not found`);
          }
        }
      }

      if (node.type !== 'end' && node.type !== 'condition' && node.type !== 'parallel' && !node.nextNodeId) {
        throw new Error(`Node ${node.id} must have nextNodeId`);
      }
    }

    logger.debug('Workflow nodes validation passed');
  }
}

export const workflowConfigLoader = new WorkflowConfigLoader();
