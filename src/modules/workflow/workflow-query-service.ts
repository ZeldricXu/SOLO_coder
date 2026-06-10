import { WorkflowInstance, WorkflowDefinition } from '@prisma/client';
import { connectionPool } from '../tenant/connection-pool';
import { generateApprovalSignature } from '@utils/crypto';
import { config } from '@config/index';

export class WorkflowQueryService {
  private prisma = connectionPool.getPlatformPrisma();

  async getInstance(
    tenantId: string,
    instanceId: string
  ): Promise<(WorkflowInstance & { definition: WorkflowDefinition }) | null> {
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
  ): Promise<{ instances: (WorkflowInstance & { definition: WorkflowDefinition })[]; total: number }> {
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

  async getWorkflowDefinition(
    tenantId: string,
    workflowId: string
  ): Promise<WorkflowDefinition | null> {
    return this.prisma.workflowDefinition.findFirst({
      where: { id: workflowId, tenantId },
    });
  }

  async countApprovals(
    instanceId: string,
    nodeId: string
  ): Promise<{ approved: number; total: number; rejected: number }> {
    const instance = await this.prisma.workflowInstance.findUnique({
      where: { id: instanceId },
    });

    if (!instance) {
      return { approved: 0, total: 0, rejected: 0 };
    }

    const approvals = instance.approvals as Array<{
      nodeId: string;
      decision: string;
    }>;

    const nodeApprovals = approvals.filter(a => a.nodeId === nodeId);
    const approved = nodeApprovals.filter(a => a.decision === 'approved').length;
    const rejected = nodeApprovals.filter(a => a.decision === 'rejected').length;

    return { approved, total: nodeApprovals.length, rejected };
  }
}

export const workflowQueryService = new WorkflowQueryService();
