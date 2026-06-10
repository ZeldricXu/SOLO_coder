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

  async notifyWorkflowRebuilt(
    oldInstance: WorkflowInstance,
    newInstance: WorkflowInstance,
    affectedApprovers: Array<{ userId: string; action: 'added' | 'removed' | 'changed' }>,
    triggeredBy: string
  ): Promise<void> {
    try {
      logger.info(
        {
          oldInstanceId: oldInstance.id,
          newInstanceId: newInstance.id,
          contentId: oldInstance.contentId,
          affectedApproverCount: affectedApprovers.length,
          triggeredBy,
        },
        'Notifying workflow rebuilt due to definition change'
      );

      await webhookService.dispatchEvent(
        oldInstance.tenantId,
        'content.workflow.updated',
        {
          eventType: 'workflow.rebuilt',
          oldWorkflowInstanceId: oldInstance.id,
          newWorkflowInstanceId: newInstance.id,
          contentId: oldInstance.contentId,
          workflowId: oldInstance.definitionId,
          triggeredBy,
          rebuiltAt: new Date(),
          affectedApprovers: affectedApprovers.map(a => ({
            userId: a.userId,
            action: a.action,
          })),
          summary: `Workflow was automatically rebuilt due to approval chain changes. ${affectedApprovers.filter(a => a.action === 'added').length} approver(s) added, ${affectedApprovers.filter(a => a.action === 'removed').length} approver(s) removed.`,
        }
      );

      const addedApprovers = affectedApprovers.filter(a => a.action === 'added').map(a => a.userId);
      if (addedApprovers.length > 0) {
        logger.info(
          { newInstanceId: newInstance.id, addedApprovers },
          'Notifying newly added approvers of pending approval task'
        );
      }

      const removedApprovers = affectedApprovers.filter(a => a.action === 'removed').map(a => a.userId);
      if (removedApprovers.length > 0) {
        logger.info(
          { oldInstanceId: oldInstance.id, removedApprovers },
          'Notifying removed approvers of task cancellation'
        );

        for (const userId of removedApprovers) {
          await webhookService.dispatchEvent(
            oldInstance.tenantId,
            'content.workflow.rejected',
            {
              eventType: 'approver.removed',
              workflowInstanceId: oldInstance.id,
              newWorkflowInstanceId: newInstance.id,
              contentId: oldInstance.contentId,
              removedApproverId: userId,
              reason: 'Approval chain updated by administrator',
              notifiedAt: new Date(),
            }
          );
        }
      }
    } catch (error) {
      logger.error(
        { error, oldInstanceId: oldInstance.id, newInstanceId: newInstance.id },
        'Failed to notify workflow rebuilt'
      );
    }
  }
}

export const workflowNotifier = new WorkflowNotifier();
