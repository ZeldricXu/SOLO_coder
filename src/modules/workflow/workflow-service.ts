import { WorkflowDefinition, WorkflowInstance } from '@prisma/client';
import { TenantContext } from '@types/index';
import { logger } from '@utils/logger';
import { workflowConfigLoader, CreateWorkflowInput, UpdateWorkflowInput } from './workflow-config-loader';
import { workflowStateMachine, ApproveNodeInput } from './workflow-state-machine';
import { workflowQueryService } from './workflow-query-service';
import { workflowApproverResolver, ResolvedApprover } from './workflow-approver-resolver';

export { CreateWorkflowInput, UpdateWorkflowInput, ApproveNodeInput };

export interface StartWorkflowInput {
  workflowId: string;
  contentId: string;
  startedBy: string;
  contentData?: Record<string, unknown>;
}

export class WorkflowService {
  async createWorkflow(
    tenant: TenantContext,
    input: CreateWorkflowInput
  ): Promise<WorkflowDefinition> {
    return workflowConfigLoader.createWorkflow(tenant, input);
  }

  async getWorkflow(tenantId: string, workflowId: string): Promise<WorkflowDefinition | null> {
    return workflowConfigLoader.getWorkflow(tenantId, workflowId);
  }

  async listWorkflows(
    tenantId: string,
    modelId?: string,
    page = 1,
    pageSize = 50
  ): Promise<{ workflows: WorkflowDefinition[]; total: number }> {
    return workflowConfigLoader.listWorkflows(tenantId, modelId, page, pageSize);
  }

  async updateWorkflow(
    tenant: TenantContext,
    workflowId: string,
    input: UpdateWorkflowInput
  ): Promise<WorkflowDefinition> {
    return workflowConfigLoader.updateWorkflow(tenant, workflowId, input);
  }

  async deleteWorkflow(tenantId: string, workflowId: string): Promise<void> {
    return workflowConfigLoader.deleteWorkflow(tenantId, workflowId);
  }

  validateWorkflowNodes(
    nodes: Parameters<typeof workflowConfigLoader.validateWorkflowNodes>[0],
    startNodeId: string,
    endNodeId: string
  ): void {
    workflowConfigLoader.validateWorkflowNodes(nodes, startNodeId, endNodeId);
  }

  async startWorkflow(
    tenant: TenantContext,
    input: StartWorkflowInput
  ): Promise<WorkflowInstance> {
    if (!tenant.limits.enableWorkflow) {
      throw new Error('Workflow is not enabled for this tenant');
    }

    const workflow = await this.getWorkflow(tenant.tenantId, input.workflowId);
    if (!workflow) throw new Error('Workflow not found');

    return workflowStateMachine.startWorkflow(
      tenant.tenantId,
      workflow,
      input.contentId,
      input.startedBy,
      input.contentData
    );
  }

  async approveNode(
    tenant: TenantContext,
    input: ApproveNodeInput
  ): Promise<WorkflowInstance> {
    return workflowStateMachine.approveNode(tenant.tenantId, input);
  }

  async cancelWorkflow(
    tenantId: string,
    instanceId: string,
    cancelledBy: string
  ): Promise<void> {
    return workflowStateMachine.cancelWorkflow(tenantId, instanceId, cancelledBy);
  }

  async getInstance(
    tenantId: string,
    instanceId: string
  ): Promise<WorkflowInstance | null> {
    return workflowQueryService.getInstance(tenantId, instanceId);
  }

  async listInstances(
    tenantId: string,
    contentId?: string,
    workflowId?: string,
    status?: string,
    page = 1,
    pageSize = 50
  ): Promise<{ instances: WorkflowInstance[]; total: number }> {
    return workflowQueryService.listInstances(tenantId, contentId, workflowId, status, page, pageSize);
  }

  async verifyApprovalSignature(
    instanceId: string,
    approvalIndex: number
  ): Promise<boolean> {
    return workflowQueryService.verifyApprovalSignature(instanceId, approvalIndex);
  }

  async previewApprovers(
    tenant: TenantContext,
    input: StartWorkflowInput
  ): Promise<{
    workflowId: string;
    nodes: Array<{
      nodeId: string;
      approvers: string[];
      resolvedFrom: string;
      warnings?: string[];
    }>;
  }> {
    const workflow = await this.getWorkflow(tenant.tenantId, input.workflowId);
    if (!workflow) {
      return { workflowId: input.workflowId, nodes: [] };
    }

    const nodes = workflow.nodes as unknown as Parameters<typeof workflowApproverResolver.resolveApprovalNodes>[1];
    const resolved = await workflowApproverResolver.resolveApprovalNodes(
      tenant.tenantId,
      nodes,
      input.contentId,
      input.startedBy,
      input.contentData
    );

    return {
      workflowId: input.workflowId,
      nodes: resolved.map((r: ResolvedApprover) => ({
        nodeId: r.nodeId,
        approvers: r.approvers,
        resolvedFrom: r.resolution.resolvedFrom,
        warnings: r.resolution.warnings,
      })),
    };
  }

  invalidateApproverCache(tenantId: string, userId?: string): void {
    workflowApproverResolver.invalidateCache(tenantId, userId);
    logger.info({ tenantId, userId }, 'Approver cache invalidated');
  }
}

export const workflowService = new WorkflowService();
