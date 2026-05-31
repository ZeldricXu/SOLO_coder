import { getPrismaClient, withTransaction, tenantFilter } from '../../common/prisma';
import { getCacheClient, generateCacheKey, TTL } from '../../common/cache';
import { getEventBus, EventTypes } from '../../common/events';
import { NotFoundError, ValidationError } from '../../common/errors';
import { ApprovalRuleInput, ApprovalStrategy, PaginationParams, PaginatedResult, ProcessingContext } from '../../common/types';
import { verifyTenantAccess } from '../tenant';
import { getTicketById } from '../ticket-assignment';

const prisma = getPrismaClient();
const cache = getCacheClient();
const eventBus = getEventBus();

export const createApprovalRule = async (
  tenantId: string,
  data: ApprovalRuleInput,
  traceId?: string
) => {
  verifyTenantAccess(tenantId, tenantId, traceId);

  const rule = await prisma.approvalRule.create({
    data: {
      ...data,
      tenantId
    }
  });

  await cache.del(generateCacheKey('approval_rules', tenantId));
  return rule;
};

export const getApprovalRule = async (
  tenantId: string,
  ruleId: string,
  traceId?: string
) => {
  const rule = await prisma.approvalRule.findUnique({
    where: { id: ruleId }
  });

  if (!rule) {
    throw new NotFoundError('Approval rule not found', { ruleId }, traceId);
  }

  verifyTenantAccess(rule.tenantId, tenantId, traceId);
  return rule;
};

export const listApprovalRules = async (
  tenantId: string,
  params: PaginationParams & { type?: string }
): Promise<PaginatedResult<unknown>> => {
  const cacheKey = generateCacheKey('approval_rules', tenantId, String(params.page), String(params.pageSize), params.type || 'all');
  const cached = await cache.get(cacheKey);
  if (cached) return cached as PaginatedResult<unknown>;

  const where: Record<string, unknown> = { tenantId };
  if (params.type) where.type = params.type;

  const [total, items] = await Promise.all([
    prisma.approvalRule.count({ where }),
    prisma.approvalRule.findMany({
      where,
      skip: (params.page - 1) * params.pageSize,
      take: params.pageSize,
      orderBy: { name: 'asc' }
    })
  ]);

  const result: PaginatedResult<unknown> = {
    items,
    total,
    page: params.page,
    pageSize: params.pageSize,
    totalPages: Math.ceil(total / params.pageSize)
  };

  await cache.set(cacheKey, result, TTL.MEDIUM);
  return result;
};

export const updateApprovalRule = async (
  tenantId: string,
  ruleId: string,
  data: Partial<ApprovalRuleInput>,
  traceId?: string
) => {
  const rule = await prisma.approvalRule.findUnique({ where: { id: ruleId } });
  if (!rule) {
    throw new NotFoundError('Approval rule not found', { ruleId }, traceId);
  }
  verifyTenantAccess(rule.tenantId, tenantId, traceId);

  const updated = await prisma.approvalRule.update({
    where: { id: ruleId },
    data
  });

  await cache.del(generateCacheKey('approval_rules', tenantId));
  await cache.del(generateCacheKey('approval_rule', ruleId));
  return updated;
};

export const deleteApprovalRule = async (
  tenantId: string,
  ruleId: string,
  traceId?: string
) => {
  const rule = await prisma.approvalRule.findUnique({ where: { id: ruleId } });
  if (!rule) {
    throw new NotFoundError('Approval rule not found', { ruleId }, traceId);
  }
  verifyTenantAccess(rule.tenantId, tenantId, traceId);

  await withTransaction(async (tx) => {
    await tx.approvalParticipant.deleteMany({
      where: { approval: { ruleId } }
    });
    await tx.approvalInstance.deleteMany({ where: { ruleId } });
    await tx.approvalRule.delete({ where: { id: ruleId } });
  });

  await cache.del(generateCacheKey('approval_rules', tenantId));
  await cache.del(generateCacheKey('approval_rule', ruleId));
};

export const createApprovalInstance = async (
  tenantId: string,
  ticketId: string,
  ruleId: string,
  strategy: ApprovalStrategy = 'ANY',
  approverIds: string[],
  context?: Partial<ProcessingContext>
) => {
  const [ticket, rule] = await Promise.all([
    getTicketById(tenantId, ticketId, context?.traceId),
    getApprovalRule(tenantId, ruleId, context?.traceId)
  ]);

  return withTransaction(async (tx) => {
    const instance = await tx.approvalInstance.create({
      data: {
        ticketId,
        ruleId,
        status: 'pending',
        strategy,
        tenantId,
        variables: {}
      }
    });

    for (const approverId of approverIds) {
      await tx.approvalParticipant.create({
        data: {
          approvalId: instance.id,
          approverId,
          approverType: 'user',
          status: 'pending',
          dynamicResolver: rule.config.resolver as string | undefined
        }
      });
    }

    eventBus.publish(EventTypes.APPROVAL_REQUESTED, {
      approvalId: instance.id,
      ticketId,
      ruleId,
      strategy,
      approverCount: approverIds.length
    }, context);

    return getApprovalInstance(tenantId, instance.id, context?.traceId);
  });
};

export const getApprovalInstance = async (
  tenantId: string,
  approvalId: string,
  traceId?: string
) => {
  const instance = await prisma.approvalInstance.findUnique({
    where: { id: approvalId },
    include: {
      approvers: { orderBy: { createdAt: 'asc' } },
      rule: true,
      ticket: true
    }
  });

  if (!instance) {
    throw new NotFoundError('Approval instance not found', { approvalId }, traceId);
  }
  verifyTenantAccess(instance.tenantId, tenantId, traceId);

  return {
    ...instance,
    summary: calculateApprovalSummary(instance.approvers, instance.strategy as ApprovalStrategy)
  };
};

export const calculateApprovalSummary = (
  participants: Array<{ status: string }>,
  strategy: ApprovalStrategy
): { approved: number; rejected: number; pending: number; completed: boolean; result: 'approved' | 'rejected' | 'pending' } => {
  const approved = participants.filter(p => p.status === 'approved').length;
  const rejected = participants.filter(p => p.status === 'rejected').length;
  const pending = participants.filter(p => p.status === 'pending').length;
  const total = participants.length;

  let completed = false;
  let result: 'approved' | 'rejected' | 'pending' = 'pending';

  switch (strategy) {
    case 'ALL':
      if (approved === total) {
        completed = true;
        result = 'approved';
      } else if (rejected > 0) {
        completed = true;
        result = 'rejected';
      }
      break;

    case 'ANY':
      if (approved > 0) {
        completed = true;
        result = 'approved';
      } else if (rejected === total) {
        completed = true;
        result = 'rejected';
      }
      break;

    case 'MAJORITY':
      const majority = Math.floor(total / 2) + 1;
      if (approved >= majority) {
        completed = true;
        result = 'approved';
      } else if (rejected > total / 2) {
        completed = true;
        result = 'rejected';
      } else if (pending === 0) {
        completed = true;
        result = approved > rejected ? 'approved' : 'rejected';
      }
      break;

    case 'SEQUENTIAL':
      const firstPending = participants.findIndex(p => p.status === 'pending');
      if (participants[0]?.status === 'rejected') {
        completed = true;
        result = 'rejected';
      } else if (approved === total) {
        completed = true;
        result = 'approved';
      }
      break;
  }

  return { approved, rejected, pending, completed, result };
};

export const resolveApproval = async (
  tenantId: string,
  approvalId: string,
  approverId: string,
  decision: 'approved' | 'rejected',
  comment?: string,
  context?: Partial<ProcessingContext>
) => {
  return withTransaction(async (tx) => {
    const instance = await tx.approvalInstance.findUnique({
      where: { id: approvalId },
      include: { approvers: true }
    });

    if (!instance) {
      throw new NotFoundError('Approval instance not found', { approvalId }, context?.traceId);
    }
    verifyTenantAccess(instance.tenantId, tenantId, context?.traceId);

    if (instance.status !== 'pending') {
      return instance;
    }

    const participant = instance.approvers.find(
      (p: { approverId: string; approverType: string; status: string }) =>
        p.approverId === approverId && p.approverType === 'user'
    );

    if (!participant) {
      throw new ValidationError('You are not an approver for this instance', { approvalId, approverId }, context?.traceId);
    }

    if (participant.status !== 'pending') {
      return instance;
    }

    await tx.approvalParticipant.update({
      where: { id: participant.id },
      data: {
        status: decision,
        comment,
        resolvedAt: new Date()
      }
    });

    const updatedInstance = await tx.approvalInstance.findUnique({
      where: { id: approvalId },
      include: { approvers: true }
    });

    if (!updatedInstance) return instance;

    const summary = calculateApprovalSummary(updatedInstance.approvers, instance.strategy as ApprovalStrategy);

    if (summary.completed) {
      await tx.approvalInstance.update({
        where: { id: approvalId },
        data: {
          status: summary.result
        }
      });

      eventBus.publish(EventTypes.APPROVAL_COMPLETED, {
        approvalId,
        result: summary.result,
        approvedCount: summary.approved,
        rejectedCount: summary.rejected
      }, context);
    }

    return getApprovalInstance(tenantId, approvalId, context?.traceId);
  });
};

export const resolveDynamicApprovers = async (
  tenantId: string,
  approvalId: string,
  traceId?: string
) => {
  const instance = await getApprovalInstance(tenantId, approvalId, traceId);
  const ruleConfig = instance.rule.config as Record<string, unknown>;

  if (!ruleConfig.dynamicResolver) {
    return instance.approvers;
  }

  const resolverType = ruleConfig.dynamicResolver as string;
  const ticket = instance.ticket as Record<string, unknown>;

  const resolvedApprovers: Array<{ approverId: string; approverType: string; resolver: string }> = [];

  switch (resolverType) {
    case 'ticket_owner':
      if (ticket.agentId) {
        resolvedApprovers.push({
          approverId: ticket.agentId as string,
          approverType: 'user',
          resolver: resolverType
        });
      }
      break;

    case 'department_head':
      const department = ticket.department as string;
      if (department) {
        const headId = await resolveDepartmentHead(department);
        if (headId) {
          resolvedApprovers.push({
            approverId: headId,
            approverType: 'user',
            resolver: resolverType
          });
        }
      }
      break;

    case 'role_based':
      const role = ruleConfig.requiredRole as string;
      if (role) {
        const users = await resolveUsersByRole(tenantId, role);
        resolvedApprovers.push(...users.map(id => ({
          approverId: id,
          approverType: 'user',
          resolver: resolverType
        })));
      }
      break;

    case 'hierarchy':
      const agentId = ticket.agentId as string;
      if (agentId) {
        const hierarchy = await resolveManagementChain(agentId, ruleConfig.levels as number || 1);
        resolvedApprovers.push(...hierarchy.map(id => ({
          approverId: id,
          approverType: 'user',
          resolver: resolverType
        })));
      }
      break;
  }

  return withTransaction(async (tx) => {
    for (const approver of resolvedApprovers) {
      const existing = instance.approvers.find(
        (p: { approverId: string; approverType: string }) =>
          p.approverId === approver.approverId && p.approverType === approver.approverType
      );

      if (!existing) {
        await tx.approvalParticipant.create({
          data: {
            approvalId,
            approverId: approver.approverId,
            approverType: approver.approverType,
            status: 'pending',
            dynamicResolver: approver.resolver
          }
        });
      }
    }

    return getApprovalInstance(tenantId, approvalId, traceId);
  });
};

const resolveDepartmentHead = async (department: string): Promise<string | null> => {
  return `dept_head_${department}`;
};

const resolveUsersByRole = async (tenantId: string, role: string): Promise<string[]> => {
  const agents = await prisma.agent.findMany({
    where: { tenantId, status: 'available' },
    take: 5
  });
  return agents.map((a: { id: string }) => a.id);
};

const resolveManagementChain = async (agentId: string, levels: number): Promise<string[]> => {
  const chain: string[] = [];
  let currentId = agentId;
  for (let i = 0; i < levels; i++) {
    const managerId = `manager_of_${currentId}`;
    chain.push(managerId);
    currentId = managerId;
  }
  return chain;
};

export const listApprovalsForTicket = async (
  tenantId: string,
  ticketId: string,
  traceId?: string
) => {
  const ticket = await getTicketById(tenantId, ticketId, traceId);

  return prisma.approvalInstance.findMany({
    where: { ticketId, tenantId },
    include: { approvers: true, rule: true },
    orderBy: { createdAt: 'desc' }
  });
};

export const listApprovalsForApprover = async (
  tenantId: string,
  approverId: string,
  params: PaginationParams & { status?: string }
): Promise<PaginatedResult<unknown>> => {
  const where: Record<string, unknown> = {
    approverId,
    approval: { tenantId }
  };

  if (params.status) {
    where.status = params.status;
  }

  const [total, items] = await Promise.all([
    prisma.approvalParticipant.count({ where }),
    prisma.approvalParticipant.findMany({
      where,
      skip: (params.page - 1) * params.pageSize,
      take: params.pageSize,
      include: {
        approval: {
          include: { ticket: true, rule: true }
        }
      },
      orderBy: { createdAt: 'desc' }
    })
  ]);

  return {
    items,
    total,
    page: params.page,
    pageSize: params.pageSize,
    totalPages: Math.ceil(total / params.pageSize)
  };
};

export const getApprovalAnalytics = async (
  tenantId: string,
  startDate: Date,
  endDate: Date
) => {
  const approvals = await prisma.approvalInstance.findMany({
    where: {
      tenantId,
      createdAt: { gte: startDate, lte: endDate }
    },
    include: { approvers: true, rule: true }
  });

  const stats = {
    total: approvals.length,
    approved: approvals.filter((a: { status: string }) => a.status === 'approved').length,
    rejected: approvals.filter((a: { status: string }) => a.status === 'rejected').length,
    pending: approvals.filter((a: { status: string }) => a.status === 'pending').length,
    byStrategy: {} as Record<string, { total: number; approved: number; rejected: number }>,
    averageTime: 0,
    byRule: {} as Record<string, { total: number; approved: number }>
  };

  let totalTime = 0;
  let completedCount = 0;

  for (const approval of approvals) {
    const strategy = approval.strategy;
    if (!stats.byStrategy[strategy]) {
      stats.byStrategy[strategy] = { total: 0, approved: 0, rejected: 0 };
    }
    stats.byStrategy[strategy].total++;
    if (approval.status === 'approved') stats.byStrategy[strategy].approved++;
    if (approval.status === 'rejected') stats.byStrategy[strategy].rejected++;

    const ruleName = (approval.rule as { name: string }).name;
    if (!stats.byRule[ruleName]) {
      stats.byRule[ruleName] = { total: 0, approved: 0 };
    }
    stats.byRule[ruleName].total++;
    if (approval.status === 'approved') stats.byRule[ruleName].approved++;

    if (approval.status !== 'pending' && approval.approvers.length > 0) {
      const firstResponse = approval.approvers.find(
        (a: { resolvedAt?: Date | null }) => a.resolvedAt
      );
      if (firstResponse?.resolvedAt) {
        totalTime += firstResponse.resolvedAt.getTime() - approval.createdAt.getTime();
        completedCount++;
      }
    }
  }

  stats.averageTime = completedCount > 0 ? totalTime / completedCount : 0;

  return {
    ...stats,
    approvalRate: stats.total > 0 ? stats.approved / stats.total : 0,
    period: { startDate, endDate }
  };
};

export const delegateApproval = async (
  tenantId: string,
  approvalId: string,
  fromApproverId: string,
  toApproverId: string,
  context?: Partial<ProcessingContext>
) => {
  return withTransaction(async (tx) => {
    const instance = await tx.approvalInstance.findUnique({
      where: { id: approvalId },
      include: { approvers: true }
    });

    if (!instance) {
      throw new NotFoundError('Approval instance not found', { approvalId }, context?.traceId);
    }
    verifyTenantAccess(instance.tenantId, tenantId, context?.traceId);

    const fromParticipant = instance.approvers.find(
      (p: { approverId: string; status: string }) =>
        p.approverId === fromApproverId && p.status === 'pending'
    );

    if (!fromParticipant) {
      throw new ValidationError('No pending approval found for this approver', { approvalId, fromApproverId }, context?.traceId);
    }

    await tx.approvalParticipant.update({
      where: { id: fromParticipant.id },
      data: {
        status: 'delegated',
        resolvedAt: new Date(),
        comment: `Delegated to ${toApproverId}`
      }
    });

    await tx.approvalParticipant.create({
      data: {
        approvalId,
        approverId: toApproverId,
        approverType: 'user',
        status: 'pending',
        dynamicResolver: `delegated_from_${fromApproverId}`
      }
    });

    return getApprovalInstance(tenantId, approvalId, context?.traceId);
  });
};
