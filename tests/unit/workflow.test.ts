import { describe, it, expect, beforeAll, afterAll, beforeEach, afterEach, vi } from 'vitest';
import {
  setupTestApp,
  closeTestApp,
  flushRedis,
  simulateConcurrentRequests,
  TestContext,
} from '../helpers';
import {
  createTenant,
  createContentModel,
  createContentEntry,
  createWorkflowDefinition,
  createSerialWorkflowFactory,
  createParallelWorkflowFactory,
  getTenantLimits,
} from '../factories';
import { workflowService } from '@/modules/workflow/workflow-service';
import { generateApprovalSignature } from '@utils/crypto';
import { TenantContext, WorkflowNode, WorkflowNodeType, ApprovalType } from '@types/index';
import { WorkflowDefinition } from '@prisma/client';
import { config } from '@config/index';

describe('Workflow Service', () => {
  let ctx: TestContext;
  let tenant: any;
  let tenantContext: TenantContext;
  let articleModel: any;

  beforeAll(async () => {
    ctx = await setupTestApp();
  }, 60000);

  afterAll(async () => {
    await closeTestApp();
  }, 60000);

  beforeEach(async () => {
    await flushRedis();

    tenant = await createTenant({
      code: 'workflow-test-tenant',
      elasticIndexPrefix: 'workflow_test',
      plan: 'professional',
    });

    const limits = getTenantLimits('professional');
    tenantContext = {
      tenantId: tenant.id,
      tenantCode: tenant.code,
      plan: tenant.plan as any,
      dbSchema: tenant.dbSchema,
      elasticIndexPrefix: tenant.elasticIndexPrefix,
      limits,
    };

    articleModel = await createContentModel(tenant.id);
  });

  afterEach(() => {
    vi.restoreAllMocks();
  });

  const createSerialApprovalNodes = (): { nodes: WorkflowNode[]; startNodeId: string; endNodeId: string } => {
    const nodes: WorkflowNode[] = [
      {
        id: 'start',
        type: 'start',
        name: 'Start',
        config: {},
        nextNodeId: 'review',
      },
      {
        id: 'review',
        type: 'approval',
        name: 'Content Review',
        config: {
          approvalType: 'all',
          approvers: ['user-reviewer-1'],
        },
        nextNodeId: 'editor',
      },
      {
        id: 'editor',
        type: 'approval',
        name: 'Editor Approval',
        config: {
          approvalType: 'all',
          approvers: ['user-editor-1'],
        },
        nextNodeId: 'publish',
      },
      {
        id: 'publish',
        type: 'approval',
        name: 'Final Publish',
        config: {
          approvalType: 'all',
          approvers: ['user-publisher-1'],
        },
        nextNodeId: 'end',
      },
      {
        id: 'end',
        type: 'end',
        name: 'End',
        config: {},
      },
    ];
    return { nodes, startNodeId: 'start', endNodeId: 'end' };
  };

  const createParallelApprovalNodes = (): { nodes: WorkflowNode[]; startNodeId: string; endNodeId: string } => {
    const nodes: WorkflowNode[] = [
      {
        id: 'start',
        type: 'start',
        name: 'Start',
        config: {},
        nextNodeId: 'parallel-gate',
      },
      {
        id: 'parallel-gate',
        type: 'parallel',
        name: 'Parallel Review',
        config: {
          parallelNodes: ['legal-review', 'tech-review', 'business-review'],
        },
        nextNodeId: 'join',
      },
      {
        id: 'legal-review',
        type: 'approval',
        name: 'Legal Review',
        config: {
          approvalType: 'all',
          approvers: ['user-legal'],
        },
        nextNodeId: 'join',
      },
      {
        id: 'tech-review',
        type: 'approval',
        name: 'Technical Review',
        config: {
          approvalType: 'all',
          approvers: ['user-tech-1', 'user-tech-2'],
        },
        nextNodeId: 'join',
      },
      {
        id: 'business-review',
        type: 'approval',
        name: 'Business Review',
        config: {
          approvalType: 'all',
          approvers: ['user-biz-1'],
        },
        nextNodeId: 'join',
      },
      {
        id: 'join',
        type: 'parallel',
        name: 'Join Parallel',
        config: {
          parallelNodes: ['legal-review', 'tech-review', 'business-review'],
        },
        nextNodeId: 'end',
      },
      {
        id: 'end',
        type: 'end',
        name: 'End',
        config: {},
      },
    ];
    return { nodes, startNodeId: 'start', endNodeId: 'end' };
  };

  const createConditionalWorkflowNodes = (): { nodes: WorkflowNode[]; startNodeId: string; endNodeId: string } => {
    const nodes: WorkflowNode[] = [
      {
        id: 'start',
        type: 'start',
        name: 'Start',
        config: {},
        nextNodeId: 'review',
      },
      {
        id: 'review',
        type: 'approval',
        name: 'Initial Review',
        config: {
          approvalType: 'all',
          approvers: ['user-reviewer'],
        },
        nextNodeId: 'condition',
      },
      {
        id: 'condition',
        type: 'condition',
        name: 'Urgent Check',
        config: {
          branches: [
            { condition: 'data.urgent === true', nodeId: 'publish-fast' },
            { condition: 'data.urgent === false', nodeId: 'publish-normal' },
          ],
        },
      },
      {
        id: 'publish-fast',
        type: 'approval',
        name: 'Urgent Publish',
        config: {
          approvalType: 'all',
          approvers: ['user-manager'],
        },
        nextNodeId: 'end',
      },
      {
        id: 'publish-normal',
        type: 'approval',
        name: 'Normal Publish',
        config: {
          approvalType: 'all',
          approvers: ['user-manager', 'user-director'],
        },
        nextNodeId: 'end',
      },
      {
        id: 'end',
        type: 'end',
        name: 'End',
        config: {},
      },
    ];
    return { nodes, startNodeId: 'start', endNodeId: 'end' };
  };

  const createPercentageApprovalNodes = (): { nodes: WorkflowNode[]; startNodeId: string; endNodeId: string } => {
    const nodes: WorkflowNode[] = [
      {
        id: 'start',
        type: 'start',
        name: 'Start',
        config: {},
        nextNodeId: 'committee-approval',
      },
      {
        id: 'committee-approval',
        type: 'approval',
        name: 'Committee Approval',
        config: {
          approvalType: 'percentage',
          approvalPercentage: 50,
          approvers: ['user-1', 'user-2', 'user-3', 'user-4', 'user-5', 'user-6'],
        },
        nextNodeId: 'end',
      },
      {
        id: 'end',
        type: 'end',
        name: 'End',
        config: {},
      },
    ];
    return { nodes, startNodeId: 'start', endNodeId: 'end' };
  };

  const createAnyApprovalNodes = (): { nodes: WorkflowNode[]; startNodeId: string; endNodeId: string } => {
    const nodes: WorkflowNode[] = [
      {
        id: 'start',
        type: 'start',
        name: 'Start',
        config: {},
        nextNodeId: 'any-approval',
      },
      {
        id: 'any-approval',
        type: 'approval',
        name: 'Any Manager Approval',
        config: {
          approvalType: 'any',
          approvers: ['user-manager-1', 'user-manager-2', 'user-manager-3'],
        },
        nextNodeId: 'end',
      },
      {
        id: 'end',
        type: 'end',
        name: 'End',
        config: {},
      },
    ];
    return { nodes, startNodeId: 'start', endNodeId: 'end' };
  };

  describe('正常路径', () => {
    it('串签：审批链review→editor→publish按顺序逐个通过，全部通过后状态为approved', async () => {
      const { nodes, startNodeId, endNodeId } = createSerialApprovalNodes();

      const workflow = await workflowService.createWorkflow(tenantContext, {
        modelId: articleModel.id,
        name: 'Serial Approval Workflow',
        nodes,
        startNodeId,
        endNodeId,
        isDefault: true,
      });

      const contentEntry = await createContentEntry(tenant.id, articleModel.id);

      const instance = await workflowService.startWorkflow(tenantContext, {
        workflowId: workflow.id,
        contentId: contentEntry.id,
        startedBy: 'user-initiator',
      });

      expect(instance.status).toBe('running');
      expect(instance.currentNodeId).toBe('review');

      const reviewApproval = await workflowService.approveNode(tenantContext, {
        instanceId: instance.id,
        nodeId: 'review',
        userId: 'user-reviewer-1',
        decision: 'approved',
        comment: 'Content looks good',
      });

      expect(reviewApproval.currentNodeId).toBe('editor');

      const editorApproval = await workflowService.approveNode(tenantContext, {
        instanceId: instance.id,
        nodeId: 'editor',
        userId: 'user-editor-1',
        decision: 'approved',
        comment: 'Edits approved',
      });

      expect(editorApproval.currentNodeId).toBe('publish');

      const publishApproval = await workflowService.approveNode(tenantContext, {
        instanceId: instance.id,
        nodeId: 'publish',
        userId: 'user-publisher-1',
        decision: 'approved',
        comment: 'Ready to publish',
      });

      expect(publishApproval.status).toBe('approved');
      expect(publishApproval.completedAt).toBeDefined();

      const approvals = publishApproval.approvals as Array<any>;
      expect(approvals.length).toBe(3);
      expect(approvals[0].userId).toBe('user-reviewer-1');
      expect(approvals[1].userId).toBe('user-editor-1');
      expect(approvals[2].userId).toBe('user-publisher-1');

      const updatedContent = await ctx.prisma.contentEntry.findUnique({
        where: { id: contentEntry.id },
      });
      expect(updatedContent?.status).toBe('APPROVED');
    });

    it('并签：legal/tech/business三个并行审批，等待所有人审批后汇总进入下一节点', async () => {
      const { nodes, startNodeId, endNodeId } = createParallelApprovalNodes();

      const workflow = await workflowService.createWorkflow(tenantContext, {
        modelId: articleModel.id,
        name: 'Parallel Approval Workflow',
        nodes,
        startNodeId,
        endNodeId,
        isDefault: true,
      });

      const contentEntry = await createContentEntry(tenant.id, articleModel.id);

      const instance = await workflowService.startWorkflow(tenantContext, {
        workflowId: workflow.id,
        contentId: contentEntry.id,
        startedBy: 'user-initiator',
      });

      expect(instance.status).toBe('running');

      await workflowService.approveNode(tenantContext, {
        instanceId: instance.id,
        nodeId: 'legal-review',
        userId: 'user-legal',
        decision: 'approved',
        comment: 'Legally compliant',
      });

      let instanceAfterLegal = await workflowService.getInstance(tenant.id, instance.id);
      expect(instanceAfterLegal?.status).toBe('running');
      expect((instanceAfterLegal?.approvals as Array<any>).length).toBe(1);

      await workflowService.approveNode(tenantContext, {
        instanceId: instance.id,
        nodeId: 'tech-review',
        userId: 'user-tech-1',
        decision: 'approved',
        comment: 'Technically sound',
      });

      await workflowService.approveNode(tenantContext, {
        instanceId: instance.id,
        nodeId: 'tech-review',
        userId: 'user-tech-2',
        decision: 'approved',
        comment: 'Tech approved',
      });

      let instanceAfterTech = await workflowService.getInstance(tenant.id, instance.id);
      expect(instanceAfterTech?.status).toBe('running');
      expect((instanceAfterTech?.approvals as Array<any>).length).toBe(3);

      const finalApproval = await workflowService.approveNode(tenantContext, {
        instanceId: instance.id,
        nodeId: 'business-review',
        userId: 'user-biz-1',
        decision: 'approved',
        comment: 'Business approved',
      });

      expect(finalApproval.status).toBe('approved');
      expect(finalApproval.currentNodeId).toBe('end');
      expect((finalApproval.approvals as Array<any>).length).toBe(4);
    });

    it('条件分支：根据content.urgent值走不同分支（true走fast流程，false走normal流程）', async () => {
      const { nodes, startNodeId, endNodeId } = createConditionalWorkflowNodes();

      const workflow = await workflowService.createWorkflow(tenantContext, {
        modelId: articleModel.id,
        name: 'Conditional Workflow',
        nodes,
        startNodeId,
        endNodeId,
        isDefault: true,
      });

      const urgentContent = await createContentEntry(tenant.id, articleModel.id, {
        data: {
          title: 'Urgent Article',
          content: 'This is urgent content',
          urgent: true,
          status: 'draft',
        },
      });

      const normalContent = await createContentEntry(tenant.id, articleModel.id, {
        data: {
          title: 'Normal Article',
          content: 'This is normal content',
          urgent: false,
          status: 'draft',
        },
      });

      const urgentInstance = await workflowService.startWorkflow(tenantContext, {
        workflowId: workflow.id,
        contentId: urgentContent.id,
        startedBy: 'user-initiator',
      });

      await workflowService.approveNode(tenantContext, {
        instanceId: urgentInstance.id,
        nodeId: 'review',
        userId: 'user-reviewer',
        decision: 'approved',
      });

      const urgentAfterReview = await workflowService.getInstance(tenant.id, urgentInstance.id);
      expect(urgentAfterReview?.currentNodeId).toBe('publish-fast');

      const urgentFinal = await workflowService.approveNode(tenantContext, {
        instanceId: urgentInstance.id,
        nodeId: 'publish-fast',
        userId: 'user-manager',
        decision: 'approved',
      });

      expect(urgentFinal.status).toBe('approved');

      const normalInstance = await workflowService.startWorkflow(tenantContext, {
        workflowId: workflow.id,
        contentId: normalContent.id,
        startedBy: 'user-initiator',
      });

      await workflowService.approveNode(tenantContext, {
        instanceId: normalInstance.id,
        nodeId: 'review',
        userId: 'user-reviewer',
        decision: 'approved',
      });

      const normalAfterReview = await workflowService.getInstance(tenant.id, normalInstance.id);
      expect(normalAfterReview?.currentNodeId).toBe('publish-normal');

      await workflowService.approveNode(tenantContext, {
        instanceId: normalInstance.id,
        nodeId: 'publish-normal',
        userId: 'user-manager',
        decision: 'approved',
      });

      const normalFinal = await workflowService.approveNode(tenantContext, {
        instanceId: normalInstance.id,
        nodeId: 'publish-normal',
        userId: 'user-director',
        decision: 'approved',
      });

      expect(normalFinal.status).toBe('approved');
    });

    it('审批记录HMAC签名验证，篡改后验证失败', async () => {
      const { nodes, startNodeId, endNodeId } = createSerialApprovalNodes();

      const workflow = await workflowService.createWorkflow(tenantContext, {
        modelId: articleModel.id,
        name: 'Signature Test Workflow',
        nodes,
        startNodeId,
        endNodeId,
      });

      const contentEntry = await createContentEntry(tenant.id, articleModel.id);

      const instance = await workflowService.startWorkflow(tenantContext, {
        workflowId: workflow.id,
        contentId: contentEntry.id,
        startedBy: 'user-initiator',
      });

      await workflowService.approveNode(tenantContext, {
        instanceId: instance.id,
        nodeId: 'review',
        userId: 'user-reviewer-1',
        decision: 'approved',
        comment: 'Good content',
      });

      const isValid = await workflowService.verifyApprovalSignature(instance.id, 0);
      expect(isValid).toBe(true);

      const instanceData = await ctx.prisma.workflowInstance.findUnique({
        where: { id: instance.id },
      });

      const approvals = instanceData?.approvals as Array<any>;
      approvals[0].decision = 'rejected';

      await ctx.prisma.workflowInstance.update({
        where: { id: instance.id },
        data: { approvals: approvals as any },
      });

      const isTamperedValid = await workflowService.verifyApprovalSignature(instance.id, 0);
      expect(isTamperedValid).toBe(false);
    });
  });

  describe('异常路径', () => {
    it('审批链中某一步审批人不存在（非授权用户），抛出错误', async () => {
      const { nodes, startNodeId, endNodeId } = createSerialApprovalNodes();

      const workflow = await workflowService.createWorkflow(tenantContext, {
        modelId: articleModel.id,
        name: 'Approver Error Workflow',
        nodes,
        startNodeId,
        endNodeId,
      });

      const contentEntry = await createContentEntry(tenant.id, articleModel.id);

      const instance = await workflowService.startWorkflow(tenantContext, {
        workflowId: workflow.id,
        contentId: contentEntry.id,
        startedBy: 'user-initiator',
      });

      await expect(
        workflowService.approveNode(tenantContext, {
          instanceId: instance.id,
          nodeId: 'review',
          userId: 'user-unauthorized',
          decision: 'approved',
        })
      ).rejects.toThrow('User is not authorized to approve this node');
    });

    it('审批人拒绝后流程状态变为rejected，内容回退到REJECTED状态', async () => {
      const { nodes, startNodeId, endNodeId } = createSerialApprovalNodes();

      const workflow = await workflowService.createWorkflow(tenantContext, {
        modelId: articleModel.id,
        name: 'Rejection Test Workflow',
        nodes,
        startNodeId,
        endNodeId,
      });

      const contentEntry = await createContentEntry(tenant.id, articleModel.id);

      const instance = await workflowService.startWorkflow(tenantContext, {
        workflowId: workflow.id,
        contentId: contentEntry.id,
        startedBy: 'user-initiator',
      });

      const rejectedInstance = await workflowService.approveNode(tenantContext, {
        instanceId: instance.id,
        nodeId: 'review',
        userId: 'user-reviewer-1',
        decision: 'rejected',
        comment: 'Content quality is poor, needs major revisions',
      });

      expect(rejectedInstance.status).toBe('rejected');
      expect(rejectedInstance.completedAt).toBeDefined();

      const approvals = rejectedInstance.approvals as Array<any>;
      expect(approvals.length).toBe(1);
      expect(approvals[0].decision).toBe('rejected');
      expect(approvals[0].comment).toBe('Content quality is poor, needs major revisions');

      const updatedContent = await ctx.prisma.contentEntry.findUnique({
        where: { id: contentEntry.id },
      });
      expect(updatedContent?.status).toBe('REJECTED');
    });

    it('百分比审批（50%）：6个审批人3个通过即通过', async () => {
      const { nodes, startNodeId, endNodeId } = createPercentageApprovalNodes();

      const workflow = await workflowService.createWorkflow(tenantContext, {
        modelId: articleModel.id,
        name: 'Percentage Approval Workflow',
        nodes,
        startNodeId,
        endNodeId,
      });

      const contentEntry = await createContentEntry(tenant.id, articleModel.id);

      const instance = await workflowService.startWorkflow(tenantContext, {
        workflowId: workflow.id,
        contentId: contentEntry.id,
        startedBy: 'user-initiator',
      });

      await workflowService.approveNode(tenantContext, {
        instanceId: instance.id,
        nodeId: 'committee-approval',
        userId: 'user-1',
        decision: 'approved',
      });

      let afterFirst = await workflowService.getInstance(tenant.id, instance.id);
      expect(afterFirst?.status).toBe('running');

      await workflowService.approveNode(tenantContext, {
        instanceId: instance.id,
        nodeId: 'committee-approval',
        userId: 'user-2',
        decision: 'approved',
      });

      let afterSecond = await workflowService.getInstance(tenant.id, instance.id);
      expect(afterSecond?.status).toBe('running');

      const finalApproval = await workflowService.approveNode(tenantContext, {
        instanceId: instance.id,
        nodeId: 'committee-approval',
        userId: 'user-3',
        decision: 'approved',
      });

      expect(finalApproval.status).toBe('approved');
      expect((finalApproval.approvals as Array<any>).length).toBe(3);
    });

    it('或签（any）：多个审批人中任意一人通过即可', async () => {
      const { nodes, startNodeId, endNodeId } = createAnyApprovalNodes();

      const workflow = await workflowService.createWorkflow(tenantContext, {
        modelId: articleModel.id,
        name: 'Any Approval Workflow',
        nodes,
        startNodeId,
        endNodeId,
      });

      const contentEntry = await createContentEntry(tenant.id, articleModel.id);

      const instance = await workflowService.startWorkflow(tenantContext, {
        workflowId: workflow.id,
        contentId: contentEntry.id,
        startedBy: 'user-initiator',
      });

      const approvedInstance = await workflowService.approveNode(tenantContext, {
        instanceId: instance.id,
        nodeId: 'any-approval',
        userId: 'user-manager-2',
        decision: 'approved',
        comment: 'I approve this',
      });

      expect(approvedInstance.status).toBe('approved');
      expect((approvedInstance.approvals as Array<any>).length).toBe(1);
      expect((approvedInstance.approvals as Array<any>)[0].userId).toBe('user-manager-2');
    });

    it('同一用户不能重复投票', async () => {
      const { nodes, startNodeId, endNodeId } = createSerialApprovalNodes();

      const workflow = await workflowService.createWorkflow(tenantContext, {
        modelId: articleModel.id,
        name: 'Duplicate Vote Workflow',
        nodes,
        startNodeId,
        endNodeId,
      });

      const contentEntry = await createContentEntry(tenant.id, articleModel.id);

      const instance = await workflowService.startWorkflow(tenantContext, {
        workflowId: workflow.id,
        contentId: contentEntry.id,
        startedBy: 'user-initiator',
      });

      await workflowService.approveNode(tenantContext, {
        instanceId: instance.id,
        nodeId: 'review',
        userId: 'user-reviewer-1',
        decision: 'approved',
      });

      await expect(
        workflowService.approveNode(tenantContext, {
          instanceId: instance.id,
          nodeId: 'review',
          userId: 'user-reviewer-1',
          decision: 'approved',
        })
      ).rejects.toThrow('User has already voted on this node');
    });

    it('非运行状态的工作流不能审批', async () => {
      const { nodes, startNodeId, endNodeId } = createSerialApprovalNodes();

      const workflow = await workflowService.createWorkflow(tenantContext, {
        modelId: articleModel.id,
        name: 'Non-running Workflow',
        nodes,
        startNodeId,
        endNodeId,
      });

      const contentEntry = await createContentEntry(tenant.id, articleModel.id);

      const instance = await workflowService.startWorkflow(tenantContext, {
        workflowId: workflow.id,
        contentId: contentEntry.id,
        startedBy: 'user-initiator',
      });

      await workflowService.approveNode(tenantContext, {
        instanceId: instance.id,
        nodeId: 'review',
        userId: 'user-reviewer-1',
        decision: 'rejected',
      });

      await expect(
        workflowService.approveNode(tenantContext, {
          instanceId: instance.id,
          nodeId: 'review',
          userId: 'user-reviewer-1',
          decision: 'approved',
        })
      ).rejects.toThrow('Workflow is not running');
    });
  });

  describe('并发场景', () => {
    it('同一节点同一时间多个审批人并发审批，最终状态正确计算', async () => {
      const { nodes, startNodeId, endNodeId } = createPercentageApprovalNodes();

      const workflow = await workflowService.createWorkflow(tenantContext, {
        modelId: articleModel.id,
        name: 'Concurrent Approval Workflow',
        nodes,
        startNodeId,
        endNodeId,
      });

      const contentEntry = await createContentEntry(tenant.id, articleModel.id);

      const instance = await workflowService.startWorkflow(tenantContext, {
        workflowId: workflow.id,
        contentId: contentEntry.id,
        startedBy: 'user-initiator',
      });

      const approverIds = ['user-1', 'user-2', 'user-3', 'user-4', 'user-5', 'user-6'];
      const requests = approverIds.slice(0, 4).map(userId => () =>
        workflowService.approveNode(tenantContext, {
          instanceId: instance.id,
          nodeId: 'committee-approval',
          userId,
          decision: 'approved',
          comment: `Approved by ${userId}`,
        })
      );

      const results = await simulateConcurrentRequests(requests, 4);

      const successfulResults = results.filter(r => r && 'status' in r);
      expect(successfulResults.length).toBeGreaterThanOrEqual(3);

      const finalInstance = await workflowService.getInstance(tenant.id, instance.id);
      expect(finalInstance?.status).toBe('approved');

      const approvals = finalInstance?.approvals as Array<any>;
      const uniqueApprovers = new Set(approvals.map(a => a.userId));
      expect(uniqueApprovers.size).toBeGreaterThanOrEqual(3);
    });

    it('Webhook事件顺序投递：模拟事件队列按顺序处理', async () => {
      const eventQueue: Array<{ event: string; payload: any; timestamp: number }> = [];
      const processedEvents: Array<{ event: string; order: number }> = [];
      let processing = false;
      let retryCount = 0;

      const enqueueEvent = (event: string, payload: any) => {
        eventQueue.push({
          event,
          payload,
          timestamp: Date.now(),
        });
      };

      const processQueue = async () => {
        if (processing) return;
        processing = true;

        while (eventQueue.length > 0) {
          const event = eventQueue[0];

          try {
            if (retryCount < 2 && event.event === 'workflow.approved') {
              retryCount++;
              throw new Error('Simulated webhook failure');
            }

            processedEvents.push({
              event: event.event,
              order: processedEvents.length + 1,
            });

            eventQueue.shift();
            retryCount = 0;
          } catch (error) {
            await new Promise(resolve => setTimeout(resolve, 10));
          }
        }

        processing = false;
      };

      enqueueEvent('workflow.started', { workflowId: 'wf-1' });
      processQueue();

      await new Promise(resolve => setTimeout(resolve, 5));
      enqueueEvent('workflow.approved', { nodeId: 'review' });
      processQueue();

      await new Promise(resolve => setTimeout(resolve, 5));
      enqueueEvent('workflow.approved', { nodeId: 'editor' });
      processQueue();

      await new Promise(resolve => setTimeout(resolve, 5));
      enqueueEvent('workflow.completed', { status: 'approved' });
      processQueue();

      await new Promise(resolve => setTimeout(resolve, 100));

      expect(processedEvents.length).toBe(4);
      expect(processedEvents[0].event).toBe('workflow.started');
      expect(processedEvents[0].order).toBe(1);
      expect(processedEvents[1].event).toBe('workflow.approved');
      expect(processedEvents[1].order).toBe(2);
      expect(processedEvents[2].event).toBe('workflow.approved');
      expect(processedEvents[2].order).toBe(3);
      expect(processedEvents[3].event).toBe('workflow.completed');
      expect(processedEvents[3].order).toBe(4);

      expect(eventQueue.length).toBe(0);
    });

    it('并行审批中部分节点并发审批，状态正确累积', async () => {
      const { nodes, startNodeId, endNodeId } = createParallelApprovalNodes();

      const workflow = await workflowService.createWorkflow(tenantContext, {
        modelId: articleModel.id,
        name: 'Concurrent Parallel Workflow',
        nodes,
        startNodeId,
        endNodeId,
      });

      const contentEntry = await createContentEntry(tenant.id, articleModel.id);

      const instance = await workflowService.startWorkflow(tenantContext, {
        workflowId: workflow.id,
        contentId: contentEntry.id,
        startedBy: 'user-initiator',
      });

      const concurrentApprovals = [
        () => workflowService.approveNode(tenantContext, {
          instanceId: instance.id,
          nodeId: 'legal-review',
          userId: 'user-legal',
          decision: 'approved',
        }),
        () => workflowService.approveNode(tenantContext, {
          instanceId: instance.id,
          nodeId: 'tech-review',
          userId: 'user-tech-1',
          decision: 'approved',
        }),
        () => workflowService.approveNode(tenantContext, {
          instanceId: instance.id,
          nodeId: 'tech-review',
          userId: 'user-tech-2',
          decision: 'approved',
        }),
      ];

      await simulateConcurrentRequests(concurrentApprovals, 3);

      const instanceAfterConcurrent = await workflowService.getInstance(tenant.id, instance.id);
      expect(instanceAfterConcurrent?.status).toBe('running');

      const approvals = instanceAfterConcurrent?.approvals as Array<any>;
      expect(approvals.length).toBe(3);

      const legalApproved = approvals.some(a => a.nodeId === 'legal-review' && a.decision === 'approved');
      const tech1Approved = approvals.some(a => a.nodeId === 'tech-review' && a.userId === 'user-tech-1');
      const tech2Approved = approvals.some(a => a.nodeId === 'tech-review' && a.userId === 'user-tech-2');

      expect(legalApproved).toBe(true);
      expect(tech1Approved).toBe(true);
      expect(tech2Approved).toBe(true);

      const finalApproval = await workflowService.approveNode(tenantContext, {
        instanceId: instance.id,
        nodeId: 'business-review',
        userId: 'user-biz-1',
        decision: 'approved',
      });

      expect(finalApproval.status).toBe('approved');
    });
  });
});
