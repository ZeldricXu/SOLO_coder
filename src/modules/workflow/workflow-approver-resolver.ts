import { WorkflowNode } from '@types/index';
import { logger } from '@utils/logger';
import { approverResolver, ApproverResolutionResult } from './approver-resolver';

export interface ResolvedApprover {
  nodeId: string;
  approvers: string[];
  resolution: ApproverResolutionResult;
}

export class WorkflowApproverResolver {
  async resolveApprovalNodes(
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

  invalidateCache(tenantId: string, userId?: string): void {
    approverResolver.invalidateCache(tenantId, userId);
  }
}

export const workflowApproverResolver = new WorkflowApproverResolver();
