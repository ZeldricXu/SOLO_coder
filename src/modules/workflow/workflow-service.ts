import { WorkflowDefinition, WorkflowInstance, WorkflowNodeType, ApprovalType } from '@prisma/client';
import { connectionPool } from '../tenant/connection-pool';
import { generateApprovalSignature, generateId } from '@utils/crypto';
import { logger } from '@utils/logger';
import { TenantContext, WorkflowNode } from '@types/index';
import { config } from '@config/index';
import { approverResolver, ApproverResolutionResult } from './approver-resolver';

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

export interface StartWorkflowInput {
  workflowId: string;
  contentId: string;
  startedBy: string;
  contentData?: Record<string, unknown>;
}

export interface ResolvedApprover {
  nodeId: string;
  approvers: string[];
  resolution: ApproverResolutionResult;
}

export interface ApproveNodeInput {
  instanceId: string;
  nodeId: string;
  userId: string;
  decision: 'approved' | 'rejected';
  comment?: string;
}

export class WorkflowService {
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

  private validateWorkflowNodes(
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
        const hasDynamicApprovers = node.config.dynamicApprovers?.staticFallback?.length ?? 0 > 0;
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

  async startWorkflow(
    tenant: TenantContext,
    input: StartWorkflowInput
  ): Promise<WorkflowInstance> {
    if (!tenant.limits.enableWorkflow) {
      throw new Error('Workflow is not enabled for this tenant');
    }

    const workflow = await this.getWorkflow(tenant.tenantId, input.workflowId);
    if (!workflow) {
      throw new Error('Workflow not found');
    }

    const nodes = workflow.nodes as unknown as WorkflowNode[];
    const startNode = nodes.find(n => n.id === workflow.startNodeId);
    if (!startNode) {
      throw new Error('Start node not found');
    }

    const firstApprovalNode = this.findNextApprovalNode(nodes, startNode);

    const resolvedApprovers = await this.resolveApprovalNodes(
      tenant.tenantId,
      nodes,
      input.contentId,
      input.startedBy,
      input.contentData
    );

    const resolvedApproversMap = Object.fromEntries(
      resolvedApprovers.map(r => [r.nodeId, r])
    );

    const instance = await this.prisma.workflowInstance.create({
      data: {
        id: generateId('wfi'),
        definitionId: input.workflowId,
        tenantId: tenant.tenantId,
        contentId: input.contentId,
        currentNodeId: firstApprovalNode?.id || workflow.endNodeId,
        status: 'running',
        approvals: [],
        startedBy: input.startedBy,
        resolvedApprovers: resolvedApproversMap as unknown as Prisma.JsonValue,
      },
    });

    await this.prisma.contentEntry.update({
      where: { id: input.contentId },
      data: { status: 'REVIEWING' },
    });

    logger.info(
      {
        tenantId: tenant.tenantId,
        workflowId: input.workflowId,
        contentId: input.contentId,
        resolvedApprovers: resolvedApprovers.length,
      },
      'Started workflow instance with dynamic approvers'
    );

    return instance;
  }

  private async resolveApprovalNodes(
    tenantId: string,
    nodes: WorkflowNode[],
    contentId: string,
    submittedBy: string,
    contentData?: Record<string, unknown>
  ): Promise<ResolvedApprover[]> {
    const approvalNodes = nodes.filter(n => n.type === 'approval');
    const results: ResolvedApprover[] = [];

    for (const node of approvalNodes) {
      let approvers: string[] = [];
      let resolution: ApproverResolutionResult | null = null;

      if (node.config.dynamicApprovers) {
        try {
          resolution = await approverResolver.resolveApprovers({
            tenantId,
            contentId,
            submittedBy,
            dynamicConfig: node.config.dynamicApprovers,
            contentData,
          });
          approvers = resolution.approvers;
        } catch (error) {
          logger.error(
            { error, nodeId: node.id, tenantId },
            'Failed to resolve dynamic approvers, using static fallback'
          );
          approvers = node.config.dynamicApprovers.staticFallback;
          resolution = {
            approvers,
            source: 'static',
            resolvedFrom: 'static_fallback',
            cacheHit: false,
            warnings: [`Dynamic resolution failed: ${(error as Error).message}`],
          };
        }
      } else {
        approvers = node.config.approvers || [];
        resolution = {
          approvers,
          source: 'static',
          resolvedFrom: 'static_config',
          cacheHit: false,
        };
      }

      if (node.config.approvers && node.config.approvers.length > 0) {
        approvers = [...new Set([...approvers, ...node.config.approvers])];
      }

      results.push({
        nodeId: node.id,
        approvers,
        resolution,
      });

      logger.debug(
        {
          nodeId: node.id,
          approvers,
          resolvedFrom: resolution?.resolvedFrom,
        },
        'Approvers resolved for node'
      );
    }

    return results;
  }

  private getEffectiveApprovers(
    instance: WorkflowInstance,
    nodeId: string
  ): string[] {
    const resolvedApprovers = (instance as any).resolvedApprovers as Record<string, ResolvedApprover>;
    if (resolvedApprovers && resolvedApprovers[nodeId]) {
      return resolvedApprovers[nodeId].approvers;
    }

    const nodes = (instance as any).definition?.nodes as unknown as WorkflowNode[];
    const node = nodes?.find(n => n.id === nodeId);
    if (node?.config.approvers) {
      return node.config.approvers;
    }

    return [];
  }

  private findNextApprovalNode(nodes: WorkflowNode[], currentNode: WorkflowNode): WorkflowNode | null {
    let node: WorkflowNode | null = currentNode;

    while (node) {
      if (node.type === 'approval') {
        return node;
      }

      if (node.type === 'end') {
        return null;
      }

      if (node.nextNodeId) {
        node = nodes.find(n => n.id === node.nextNodeId) || null;
      } else {
        break;
      }
    }

    return null;
  }

  async approveNode(
    tenant: TenantContext,
    input: ApproveNodeInput
  ): Promise<WorkflowInstance> {
    const instance = await this.prisma.workflowInstance.findFirst({
      where: { id: input.instanceId, tenantId: tenant.tenantId },
      include: { definition: true },
    });

    if (!instance) {
      throw new Error('Workflow instance not found');
    }

    if (instance.status !== 'running') {
      throw new Error('Workflow is not running');
    }

    if (input.nodeId !== instance.currentNodeId) {
      throw new Error('Not at the expected approval node');
    }

    const nodes = instance.definition.nodes as unknown as WorkflowNode[];
    const currentNode = nodes.find(n => n.id === input.nodeId);

    if (!currentNode || currentNode.type !== 'approval') {
      throw new Error('Current node is not an approval node');
    }

    const effectiveApprovers = this.getEffectiveApprovers(instance, input.nodeId);
    if (!effectiveApprovers.includes(input.userId)) {
      throw new Error('User is not authorized to approve this node');
    }

    const timestamp = Date.now();
    const signature = generateApprovalSignature(
      input.userId,
      instance.contentId,
      input.decision,
      timestamp,
      config.jwtSecret
    );

    const approvals = instance.approvals as Array<{
      nodeId: string;
      userId: string;
      decision: string;
      comment?: string;
      timestamp: Date;
      signature: string;
    }>;

    const existingApproval = approvals.find(
      a => a.nodeId === input.nodeId && a.userId === input.userId
    );

    if (existingApproval) {
      throw new Error('User has already voted on this node');
    }

    approvals.push({
      nodeId: input.nodeId,
      userId: input.userId,
      decision: input.decision,
      comment: input.comment,
      timestamp: new Date(timestamp),
      signature,
    });

    if (input.decision === 'rejected') {
      return this.completeWorkflow(instance.id, 'rejected', approvals, input.userId);
    }

    const nodeApprovals = approvals.filter(a => a.nodeId === input.nodeId && a.decision === 'approved');
    const approvalType = currentNode.config.approvalType || 'all';
    const approvers = effectiveApprovers;

    let approved = false;

    switch (approvalType) {
      case 'any':
        approved = nodeApprovals.length >= 1;
        break;
      case 'all':
        approved = nodeApprovals.length === approvers.length;
        break;
      case 'percentage':
        const percentage = currentNode.config.approvalPercentage || 50;
        approved = (nodeApprovals.length / approvers.length) * 100 >= percentage;
        break;
    }

    if (approved) {
      const nextNode = this.findNextNodeAfterApproval(nodes, currentNode);
      
      if (!nextNode || nextNode.type === 'end') {
        return this.completeWorkflow(instance.id, 'approved', approvals, input.userId);
      }

      if (nextNode.type === 'condition') {
        const targetNode = await this.evaluateCondition(
          nextNode,
          instance.contentId,
          nodes
        );
        return this.moveToNextNode(instance.id, targetNode?.id || instance.definition.endNodeId, approvals);
      }

      if (nextNode.type === 'parallel') {
        return this.handleParallelNode(instance.id, nextNode, nodes, approvals);
      }

      return this.moveToNextNode(instance.id, nextNode.id, approvals);
    }

    return this.prisma.workflowInstance.update({
      where: { id: instance.id },
      data: { approvals: approvals as unknown as Prisma.JsonValue },
    });
  }

  private async evaluateCondition(
    node: WorkflowNode,
    contentId: string,
    allNodes: WorkflowNode[]
  ): Promise<WorkflowNode | null> {
    const content = await this.prisma.contentEntry.findUnique({
      where: { id: contentId },
      select: { data: true },
    });

    if (!content) return null;

    const contentData = content.data as Record<string, unknown>;

    for (const branch of node.config.branches || []) {
      try {
        const conditionFn = new Function('data', `return ${branch.condition}`);
        if (conditionFn(contentData)) {
          return allNodes.find(n => n.id === branch.nodeId) || null;
        }
      } catch (error) {
        logger.error({ error, condition: branch.condition }, 'Error evaluating condition');
      }
    }

    return null;
  }

  private async handleParallelNode(
    instanceId: string,
    parallelNode: WorkflowNode,
    allNodes: WorkflowNode[],
    approvals: unknown[]
  ): Promise<WorkflowInstance> {
    const parallelNodeIds = parallelNode.config.parallelNodes || [];
    const completedApprovals = approvals as Array<{ nodeId: string; decision: string }>;

    const allParallelApproved = parallelNodeIds.every(nodeId =>
      completedApprovals.some(a => a.nodeId === nodeId && a.decision === 'approved')
    );

    if (allParallelApproved && parallelNode.nextNodeId) {
      const nextNode = allNodes.find(n => n.id === parallelNode.nextNodeId);
      return this.moveToNextNode(instanceId, nextNode?.id || parallelNode.nextNodeId, approvals);
    }

    return this.prisma.workflowInstance.update({
      where: { id: instanceId },
      data: { approvals: approvals as unknown as Prisma.JsonValue },
    });
  }

  private findNextNodeAfterApproval(nodes: WorkflowNode[], currentNode: WorkflowNode): WorkflowNode | null {
    if (!currentNode.nextNodeId) return null;
    return nodes.find(n => n.id === currentNode.nextNodeId) || null;
  }

  private async moveToNextNode(
    instanceId: string,
    nextNodeId: string,
    approvals: unknown[]
  ): Promise<WorkflowInstance> {
    return this.prisma.workflowInstance.update({
      where: { id: instanceId },
      data: {
        currentNodeId: nextNodeId,
        approvals: approvals as unknown as Prisma.JsonValue,
      },
    });
  }

  private async completeWorkflow(
    instanceId: string,
    status: 'approved' | 'rejected',
    approvals: unknown[],
    userId: string
  ): Promise<WorkflowInstance> {
    const instance = await this.prisma.workflowInstance.update({
      where: { id: instanceId },
      data: {
        status,
        approvals: approvals as unknown as Prisma.JsonValue,
        completedAt: new Date(),
      },
      include: { definition: true },
    });

    const newStatus = status === 'approved' ? 'APPROVED' : 'REJECTED';

    await this.prisma.contentEntry.update({
      where: { id: instance.contentId },
      data: {
        status: newStatus as any,
        updatedBy: userId,
      },
    });

    logger.info(
      { instanceId, status, contentId: instance.contentId },
      'Workflow completed'
    );

    return instance;
  }

  async getInstance(tenantId: string, instanceId: string): Promise<WorkflowInstance | null> {
    return this.prisma.workflowInstance.findFirst({
      where: { id: instanceId, tenantId },
      include: { definition: true },
    });
  }

  async listInstances(
    tenantId: string,
    contentId?: string,
    workflowId?: string,
    status?: string,
    page = 1,
    pageSize = 50
  ): Promise<{ instances: WorkflowInstance[]; total: number }> {
    const where: any = { tenantId };
    if (contentId) where.contentId = contentId;
    if (workflowId) where.definitionId = workflowId;
    if (status) where.status = status;

    const [instances, total] = await Promise.all([
      this.prisma.workflowInstance.findMany({
        where,
        skip: (page - 1) * pageSize,
        take: pageSize,
        orderBy: { startedAt: 'desc' },
        include: { definition: true },
      }),
      this.prisma.workflowInstance.count({ where }),
    ]);

    return { instances, total };
  }

  async cancelWorkflow(tenantId: string, instanceId: string, cancelledBy: string): Promise<void> {
    const instance = await this.getInstance(tenantId, instanceId);
    if (!instance) {
      throw new Error('Workflow instance not found');
    }

    if (instance.status !== 'running') {
      throw new Error('Workflow is not running');
    }

    await this.prisma.$transaction([
      this.prisma.workflowInstance.update({
        where: { id: instanceId },
        data: {
          status: 'cancelled',
          completedAt: new Date(),
        },
      }),
      this.prisma.contentEntry.update({
        where: { id: instance.contentId },
        data: {
          status: 'DRAFT',
          updatedBy: cancelledBy,
        },
      }),
    ]);
  }

  async verifyApprovalSignature(
    instanceId: string,
    approvalIndex: number
  ): Promise<boolean> {
    const instance = await this.prisma.workflowInstance.findUnique({
      where: { id: instanceId },
    });

    if (!instance) return false;

    const approvals = instance.approvals as Array<{
      userId: string;
      decision: string;
      timestamp: Date;
      signature: string;
    }>;

    const approval = approvals[approvalIndex];
    if (!approval) return false;

    const expectedSignature = generateApprovalSignature(
      approval.userId,
      instance.contentId,
      approval.decision,
      new Date(approval.timestamp).getTime(),
      config.jwtSecret
    );

    return approval.signature === expectedSignature;
  }
}

export const workflowService = new WorkflowService();

import { Prisma } from '@prisma/client';
