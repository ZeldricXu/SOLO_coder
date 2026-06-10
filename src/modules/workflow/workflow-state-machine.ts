import { WorkflowInstance, WorkflowNode } from '@types/index';
import { WorkflowDefinition } from '@prisma/client';
import { connectionPool } from '../tenant/connection-pool';
import { generateApprovalSignature, generateId } from '@utils/crypto';
import { logger } from '@utils/logger';
import { config } from '@config/index';
import { workflowNotifier } from './workflow-notifier';
import { workflowApproverResolver } from './workflow-approver-resolver';
import { Prisma } from '@prisma/client';
import { workflowConfigLoader } from './workflow-config-loader';

export interface ApproveNodeInput {
  instanceId: string;
  nodeId: string;
  userId: string;
  decision: 'approved' | 'rejected';
  comment?: string;
}

export interface ResolvedApprover {
  nodeId: string;
  approvers: string[];
  resolution: import('./approver-resolver').ApproverResolutionResult;
}

export class WorkflowStateMachine {
  private prisma = connectionPool.getPlatformPrisma();

  async startWorkflow(
    tenantId: string,
    workflow: WorkflowDefinition,
    contentId: string,
    startedBy: string,
    contentData?: Record<string, unknown>
  ): Promise<WorkflowInstance> {
    const nodes = workflow.nodes as unknown as WorkflowNode[];
    const firstApprovalNode = this.findNextApprovalNode(
      nodes,
      nodes.find(n => n.id === workflow.startNodeId)!
    );

    const resolvedApprovers = await workflowApproverResolver.resolveApprovalNodes(
      tenantId, nodes, contentId, startedBy, contentData
    );
    const resolvedMap = Object.fromEntries(resolvedApprovers.map(r => [r.nodeId, r]));

    const instance = await this.prisma.workflowInstance.create({
      data: {
        id: this.generateId(),
        definitionId: workflow.id,
        tenantId, contentId,
        currentNodeId: firstApprovalNode?.id || workflow.endNodeId,
        status: 'running', approvals: [], startedBy,
        resolvedApprovers: resolvedMap as unknown as Prisma.JsonValue,
        definitionVersion: workflow.version,
        definitionSnapshot: workflow.nodes as unknown as Prisma.JsonValue,
      },
    });

    await this.prisma.contentEntry.update({
      where: { id: contentId },
      data: { status: 'REVIEWING' },
    });

    workflowNotifier.notifyWorkflowStarted(instance, resolvedApprovers);
    logger.info({ tenantId, workflowId: workflow.id, contentId }, 'Workflow started');
    return instance;
  }

  async approveNode(tenantId: string, input: ApproveNodeInput): Promise<WorkflowInstance> {
    const instance = await this.prisma.workflowInstance.findFirst({
      where: { id: input.instanceId, tenantId },
      include: { definition: true },
    });

    if (!instance) throw new Error('Workflow instance not found');
    if (instance.status !== 'running') throw new Error('Workflow is not running');
    if (input.nodeId !== instance.currentNodeId) throw new Error('Not at expected approval node');

    const conflictCheck = await this.checkWorkflowDefinitionConflict(instance);
    if (conflictCheck.hasConflict) {
      logger.warn(
        {
          instanceId: instance.id,
          oldVersion: conflictCheck.oldVersion,
          newVersion: conflictCheck.newVersion,
          contentId: instance.contentId,
        },
        'Workflow definition conflict detected, rebuilding instance...'
      );
      return this.rebuildWorkflowInstance(tenantId, instance, conflictCheck.newDefinition, input);
    }

    const nodes = instance.definition.nodes as unknown as WorkflowNode[];
    const currentNode = nodes.find(n => n.id === input.nodeId);
    if (!currentNode || currentNode.type !== 'approval') {
      throw new Error('Current node is not an approval node');
    }

    const approvers = this.getEffectiveApprovers(instance, input.nodeId);
    if (!approvers.includes(input.userId)) {
      throw new Error('User is not authorized to approve this node');
    }

    const existingApprovals = instance.approvals as Array<{ nodeId: string; userId: string }>;
    if (existingApprovals.find(a => a.nodeId === input.nodeId && a.userId === input.userId)) {
      throw new Error('User has already voted on this node');
    }

    const timestamp = Date.now();
    const signature = generateApprovalSignature(
      input.userId, instance.contentId, input.decision, timestamp, config.jwtSecret
    );

    const approvals = [...existingApprovals, {
      nodeId: input.nodeId, userId: input.userId, decision: input.decision,
      comment: input.comment, timestamp: new Date(timestamp), signature,
    }];

    if (input.decision === 'rejected') {
      return this.completeWorkflow(instance.id, 'rejected', approvals, input.userId);
    }

    if (this.isNodeApproved(currentNode, approvals, input.nodeId, instance)) {
      return this.advanceWorkflow(instance, currentNode, nodes, approvals, input.userId);
    }

    return this.prisma.workflowInstance.update({
      where: { id: instance.id },
      data: { approvals: approvals as unknown as Prisma.JsonValue },
    });
  }

  async cancelWorkflow(tenantId: string, instanceId: string, cancelledBy: string): Promise<void> {
    const instance = await this.prisma.workflowInstance.findFirst({
      where: { id: instanceId, tenantId },
    });

    if (!instance) throw new Error('Workflow instance not found');
    if (instance.status !== 'running') throw new Error('Workflow is not running');

    await this.prisma.$transaction([
      this.prisma.workflowInstance.update({
        where: { id: instanceId },
        data: { status: 'cancelled', completedAt: new Date() },
      }),
      this.prisma.contentEntry.update({
        where: { id: instance.contentId },
        data: { status: 'DRAFT', updatedBy: cancelledBy },
      }),
    ]);

    workflowNotifier.notifyWorkflowCancelled(instance, cancelledBy);
  }

  private async advanceWorkflow(
    instance: WorkflowInstance & { definition: WorkflowDefinition },
    currentNode: WorkflowNode,
    allNodes: WorkflowNode[],
    approvals: unknown[],
    userId: string
  ): Promise<WorkflowInstance> {
    const nextNode = this.findNextNodeAfterApproval(allNodes, currentNode);

    if (!nextNode || nextNode.type === 'end') {
      return this.completeWorkflow(instance.id, 'approved', approvals, userId);
    }

    if (nextNode.type === 'condition') {
      const target = await this.evaluateCondition(nextNode, instance.contentId, allNodes);
      return this.moveToNode(instance.id, target?.id || instance.definition.endNodeId, approvals);
    }

    if (nextNode.type === 'parallel') {
      return this.handleParallelNode(instance.id, nextNode, allNodes, approvals);
    }

    workflowNotifier.notifyNodeApproved(instance, nextNode);
    return this.moveToNode(instance.id, nextNode.id, approvals);
  }

  private async completeWorkflow(
    instanceId: string, status: 'approved' | 'rejected', approvals: unknown[], userId: string
  ): Promise<WorkflowInstance> {
    const instance = await this.prisma.workflowInstance.update({
      where: { id: instanceId },
      data: { status, approvals: approvals as unknown as Prisma.JsonValue, completedAt: new Date() },
      include: { definition: true },
    });

    await this.prisma.contentEntry.update({
      where: { id: instance.contentId },
      data: { status: status === 'approved' ? 'APPROVED' as any : 'REJECTED' as any, updatedBy: userId },
    });

    workflowNotifier.notifyWorkflowCompleted(instance, status);
    logger.info({ instanceId, status, contentId: instance.contentId }, 'Workflow completed');
    return instance;
  }

  private async handleParallelNode(
    instanceId: string, parallelNode: WorkflowNode, allNodes: WorkflowNode[], approvals: unknown[]
  ): Promise<WorkflowInstance> {
    const parallelIds = parallelNode.config.parallelNodes || [];
    const completed = approvals as Array<{ nodeId: string; decision: string }>;

    const allApproved = parallelIds.every(nodeId =>
      completed.some(a => a.nodeId === nodeId && a.decision === 'approved')
    );

    if (allApproved && parallelNode.nextNodeId) {
      return this.moveToNode(instanceId, parallelNode.nextNodeId, approvals);
    }

    return this.prisma.workflowInstance.update({
      where: { id: instanceId },
      data: { approvals: approvals as unknown as Prisma.JsonValue },
    });
  }

  private async evaluateCondition(
    node: WorkflowNode, contentId: string, allNodes: WorkflowNode[]
  ): Promise<WorkflowNode | null> {
    const content = await this.prisma.contentEntry.findUnique({
      where: { id: contentId }, select: { data: true },
    });

    if (!content) return null;

    for (const branch of node.config.branches || []) {
      try {
        if (new Function('data', `return ${branch.condition}`)(content.data)) {
          return allNodes.find(n => n.id === branch.nodeId) || null;
        }
      } catch (error) {
        logger.error({ error, condition: branch.condition }, 'Condition evaluation error');
      }
    }
    return null;
  }

  private async moveToNode(
    instanceId: string, nextNodeId: string, approvals: unknown[]
  ): Promise<WorkflowInstance> {
    return this.prisma.workflowInstance.update({
      where: { id: instanceId },
      data: { currentNodeId: nextNodeId, approvals: approvals as unknown as Prisma.JsonValue },
    });
  }

  private isNodeApproved(
    node: WorkflowNode, approvals: Array<{ nodeId: string; decision: string }>,
    nodeId: string, instance: WorkflowInstance
  ): boolean {
    const nodeApprovals = approvals.filter(a => a.nodeId === nodeId && a.decision === 'approved');
    const approvers = this.getEffectiveApprovers(instance, nodeId);

    switch (node.config.approvalType || 'all') {
      case 'any': return nodeApprovals.length >= 1;
      case 'all': return nodeApprovals.length === approvers.length;
      case 'percentage':
        const pct = node.config.approvalPercentage || 50;
        return (nodeApprovals.length / approvers.length) * 100 >= pct;
      default: return false;
    }
  }

  private getEffectiveApprovers(instance: WorkflowInstance, nodeId: string): string[] {
    const resolved = (instance as any).resolvedApprovers as Record<string, ResolvedApprover>;
    if (resolved && resolved[nodeId]) return resolved[nodeId].approvers;
    const nodes = (instance as any).definition?.nodes as unknown as WorkflowNode[];
    return nodes?.find(n => n.id === nodeId)?.config.approvers || [];
  }

  private findNextApprovalNode(nodes: WorkflowNode[], start: WorkflowNode): WorkflowNode | null {
    let node: WorkflowNode | null = start;
    while (node) {
      if (node.type === 'approval') return node;
      if (node.type === 'end' || !node.nextNodeId) break;
      node = nodes.find(n => n.id === node.nextNodeId) || null;
    }
    return null;
  }

  private findNextNodeAfterApproval(nodes: WorkflowNode[], current: WorkflowNode): WorkflowNode | null {
    return current.nextNodeId ? nodes.find(n => n.id === current.nextNodeId) || null : null;
  }

  private generateId(): string {
    const ts = Date.now().toString(36);
    const rand = Math.random().toString(36).substring(2, 10);
    return `wfi_${ts}${rand}`;
  }

  private async checkWorkflowDefinitionConflict(
    instance: WorkflowInstance & { definition: WorkflowDefinition }
  ): Promise<{
    hasConflict: boolean;
    oldVersion: number;
    newVersion: number;
    newDefinition: WorkflowDefinition | null;
    approversChanged: boolean;
    changedNodes: string[];
  }> {
    const instanceVersion = (instance as any).definitionVersion || 1;
    const currentDefinition = instance.definition;

    if (instanceVersion !== currentDefinition.version) {
      const latestDefinition = await workflowConfigLoader.getWorkflow(
        instance.tenantId,
        instance.definitionId
      );

      if (latestDefinition && latestDefinition.version > instanceVersion) {
        const approversChanged = this.checkApproverChanges(
          (instance as any).definitionSnapshot as WorkflowNode[],
          latestDefinition.nodes as unknown as WorkflowNode[],
          instance.currentNodeId
        );

        return {
          hasConflict: approversChanged.hasChanges,
          oldVersion: instanceVersion,
          newVersion: latestDefinition.version,
          newDefinition: latestDefinition,
          approversChanged: approversChanged.hasChanges,
          changedNodes: approversChanged.changedNodes,
        };
      }
    }

    return {
      hasConflict: false,
      oldVersion: instanceVersion,
      newVersion: currentDefinition.version,
      newDefinition: null,
      approversChanged: false,
      changedNodes: [],
    };
  }

  private checkApproverChanges(
    oldNodes: WorkflowNode[],
    newNodes: WorkflowNode[],
    currentNodeId: string
  ): { hasChanges: boolean; changedNodes: string[] } {
    const changedNodes: string[] = [];
    const oldNodeMap = new Map(oldNodes.map(n => [n.id, n]));
    const newNodeMap = new Map(newNodes.map(n => [n.id, n]));

    for (const [nodeId, oldNode] of oldNodeMap) {
      if (oldNode.type !== 'approval') continue;

      const newNode = newNodeMap.get(nodeId);
      if (!newNode) {
        changedNodes.push(nodeId);
        continue;
      }

      const oldApprovers = JSON.stringify(oldNode.config.approvers || []);
      const newApprovers = JSON.stringify(newNode.config.approvers || []);
      const oldDynamic = JSON.stringify(oldNode.config.dynamicApprovers || {});
      const newDynamic = JSON.stringify(newNode.config.dynamicApprovers || {});
      const oldType = oldNode.config.approvalType;
      const newType = newNode.config.approvalType;

      if (oldApprovers !== newApprovers || oldDynamic !== newDynamic || oldType !== newType) {
        changedNodes.push(nodeId);
      }
    }

    return {
      hasChanges: changedNodes.length > 0,
      changedNodes,
    };
  }

  private async rebuildWorkflowInstance(
    tenantId: string,
    oldInstance: WorkflowInstance & { definition: WorkflowDefinition },
    newDefinition: WorkflowDefinition,
    input: ApproveNodeInput
  ): Promise<WorkflowInstance> {
    const content = await this.prisma.contentEntry.findUnique({
      where: { id: oldInstance.contentId },
      select: { data: true, status: true },
    });

    if (!content) {
      throw new Error('Content not found for workflow rebuild');
    }

    const existingApprovals = oldInstance.approvals as Array<{
      nodeId: string;
      userId: string;
      decision: string;
      comment?: string;
      timestamp: Date;
      signature: string;
    }>;

    await this.prisma.$transaction(async (tx) => {
      await tx.workflowInstance.update({
        where: { id: oldInstance.id },
        data: {
          status: 'cancelled',
          completedAt: new Date(),
          cancellationReason: `Workflow definition updated from v${oldInstance.definition?.version} to v${newDefinition.version}. Rebuilt automatically.`,
        },
      });

      const nodes = newDefinition.nodes as unknown as WorkflowNode[];
      const firstApprovalNode = this.findNextApprovalNode(
        nodes,
        nodes.find(n => n.id === newDefinition.startNodeId)!
      );

      const resolvedApprovers = await workflowApproverResolver.resolveApprovalNodes(
        tenantId, nodes, oldInstance.contentId, oldInstance.startedBy,
        content.data as Record<string, unknown>
      );
      const resolvedMap = Object.fromEntries(resolvedApprovers.map(r => [r.nodeId, r]));

      const migratedApprovals = await this.migrateExistingApprovals(
        existingApprovals,
        resolvedMap,
        nodes
      );

      const newInstanceId = generateId('wfi');
      await tx.workflowInstance.create({
        data: {
          id: newInstanceId,
          definitionId: newDefinition.id,
          tenantId,
          contentId: oldInstance.contentId,
          currentNodeId: firstApprovalNode?.id || newDefinition.endNodeId,
          status: 'running',
          approvals: migratedApprovals as unknown as Prisma.JsonValue,
          startedBy: oldInstance.startedBy,
          resolvedApprovers: resolvedMap as unknown as Prisma.JsonValue,
          definitionVersion: newDefinition.version,
          definitionSnapshot: newDefinition.nodes as unknown as Prisma.JsonValue,
          rebuiltFromInstanceId: oldInstance.id,
        },
      });

      logger.info(
        {
          oldInstanceId: oldInstance.id,
          newInstanceId,
          tenantId,
          contentId: oldInstance.contentId,
          migratedApprovalCount: migratedApprovals.length,
        },
        'Workflow instance rebuilt due to definition change'
      );
    });

    const newInstance = await this.prisma.workflowInstance.findFirst({
      where: { rebuiltFromInstanceId: oldInstance.id },
      include: { definition: true },
    });

    if (!newInstance) {
      throw new Error('Failed to find rebuilt workflow instance');
    }

    const affectedApprovers = this.getAffectedApprovers(
      oldInstance,
      newInstance
    );

    workflowNotifier.notifyWorkflowRebuilt(
      oldInstance,
      newInstance,
      affectedApprovers,
      input.userId
    );

    return this.approveNode(tenantId, {
      ...input,
      instanceId: newInstance.id,
    });
  }

  private async migrateExistingApprovals(
    existingApprovals: Array<{
      nodeId: string;
      userId: string;
      decision: string;
      comment?: string;
      timestamp: Date;
      signature: string;
    }>,
    newResolvedApprovers: Record<string, ResolvedApprover>,
    newNodes: WorkflowNode[]
  ): Promise<Array<{
    nodeId: string;
    userId: string;
    decision: string;
    comment?: string;
    timestamp: Date;
    signature: string;
    migrated?: boolean;
  }>> {
    const newNodeMap = new Map(newNodes.map(n => [n.id, n]));
    const migratedApprovals: Array<any> = [];

    for (const approval of existingApprovals) {
      const newNode = newNodeMap.get(approval.nodeId);
      if (!newNode || newNode.type !== 'approval') continue;

      const newApprovers = newResolvedApprovers[approval.nodeId]?.approvers || [];
      if (!newApprovers.includes(approval.userId)) continue;

      migratedApprovals.push({
        ...approval,
        migrated: true,
      });
    }

    return migratedApprovals;
  }

  private getAffectedApprovers(
    oldInstance: WorkflowInstance & { definition: WorkflowDefinition },
    newInstance: WorkflowInstance & { definition: WorkflowDefinition }
  ): Array<{ userId: string; action: 'added' | 'removed' | 'changed' }> {
    const oldResolved = (oldInstance as any).resolvedApprovers as Record<string, ResolvedApprover>;
    const newResolved = (newInstance as any).resolvedApprovers as Record<string, ResolvedApprover>;
    const affected = new Map<string, 'added' | 'removed' | 'changed'>();

    const allNodeIds = new Set([...Object.keys(oldResolved || {}), ...Object.keys(newResolved || {})]);

    for (const nodeId of allNodeIds) {
      const oldApprovers = new Set(oldResolved?.[nodeId]?.approvers || []);
      const newApprovers = new Set(newResolved?.[nodeId]?.approvers || []);

      for (const userId of oldApprovers) {
        if (!newApprovers.has(userId)) {
          affected.set(userId, 'removed');
        }
      }

      for (const userId of newApprovers) {
        if (!oldApprovers.has(userId)) {
          affected.set(userId, 'added');
        }
      }
    }

    return Array.from(affected.entries()).map(([userId, action]) => ({ userId, action }));
  }
}

export const workflowStateMachine = new WorkflowStateMachine();
