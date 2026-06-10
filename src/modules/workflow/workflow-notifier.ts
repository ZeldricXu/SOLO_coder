import { WorkflowInstance } from '@prisma/client';
import { WorkflowNode } from '@types/index';
import { webhookService } from '../webhook/webhook-service';
import { logger } from '@utils/logger';
import { ResolvedApprover } from './workflow-approver-resolver';

export class WorkflowNotifier {
  async notifyWorkflowStarted(
    instance: WorkflowInstance,
    resolvedApprovers: ResolvedApprover[]
  ): Promise<void> {
    try {
      logger.debug(
        { instanceId: instance.id, contentId: instance.contentId },
        'Notifying workflow started'
      );

      await webhookService.dispatchEvent(
        instance.tenantId,
        'content.workflow.started',
        {
          workflowInstanceId: instance.id,
          workflowId: instance.definitionId,
          contentId: instance.contentId,
          startedBy: instance.startedBy,
          currentNodeId: instance.currentNodeId,
          approvers: resolvedApprovers.map(r => ({
            nodeId: r.nodeId,
            approvers: r.approvers,
            resolvedFrom: r.resolution.resolvedFrom,
          })),
        }
      );
    } catch (error) {
      logger.error(
        { error, instanceId: instance.id },
        'Failed to notify workflow started'
      );
    }
  }

  async notifyNodeApproved(
    instance: WorkflowInstance,
    nextNode: WorkflowNode
  ): Promise<void> {
    try {
      logger.debug(
        { instanceId: instance.id, nextNodeId: nextNode.id },
        'Notifying node approved'
      );

      await webhookService.dispatchEvent(
        instance.tenantId,
        'content.workflow.approved',
        {
          workflowInstanceId: instance.id,
          contentId: instance.contentId,
          previousNodeId: instance.currentNodeId,
          nextNodeId: nextNode.id,
          nextNodeName: nextNode.name,
        }
      );
    } catch (error) {
      logger.error(
        { error, instanceId: instance.id },
        'Failed to notify node approved'
      );
    }
  }

  async notifyWorkflowCompleted(
    instance: WorkflowInstance,
    status: 'approved' | 'rejected'
  ): Promise<void> {
    try {
      logger.debug(
        { instanceId: instance.id, contentId: instance.contentId, status },
        'Notifying workflow completed'
      );

      const event = status === 'approved'
        ? 'content.workflow.approved'
        : 'content.workflow.rejected';

      await webhookService.dispatchEvent(
        instance.tenantId,
        event,
        {
          workflowInstanceId: instance.id,
          contentId: instance.contentId,
          status,
          completedAt: instance.completedAt,
        }
      );
    } catch (error) {
      logger.error(
        { error, instanceId: instance.id },
        'Failed to notify workflow completed'
      );
    }
  }

  async notifyWorkflowCancelled(
    instance: WorkflowInstance,
    cancelledBy: string
  ): Promise<void> {
    try {
      logger.debug(
        { instanceId: instance.id, contentId: instance.contentId, cancelledBy },
        'Notifying workflow cancelled'
      );

      await webhookService.dispatchEvent(
        instance.tenantId,
        'content.workflow.rejected',
        {
          workflowInstanceId: instance.id,
          contentId: instance.contentId,
          status: 'cancelled',
          cancelledBy,
          cancelledAt: new Date(),
        }
      );
    } catch (error) {
      logger.error(
        { error, instanceId: instance.id },
        'Failed to notify workflow cancelled'
      );
    }
  }

  async notifyApproverAssigned(
    instance: WorkflowInstance,
    nodeId: string,
    approvers: string[]
  ): Promise<void> {
    try {
      logger.debug(
        { instanceId: instance.id, nodeId, approvers },
        'Notifying approver assigned'
      );

      for (const approver of approvers) {
        await webhookService.dispatchEvent(
          instance.tenantId,
          'content.workflow.approved',
          {
            workflowInstanceId: instance.id,
            contentId: instance.contentId,
            nodeId,
            approverId: approver,
          }
        );
      }
    } catch (error) {
      logger.error(
        { error, instanceId: instance.id, nodeId },
        'Failed to notify approver assigned'
      );
    }
  }
}

export const workflowNotifier = new WorkflowNotifier();
