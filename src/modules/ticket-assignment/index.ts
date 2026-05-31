import { getPrismaClient, withTransaction, tenantFilter } from '../../common/prisma';
import { getCacheClient, generateCacheKey, TTL } from '../../common/cache';
import { getEventBus, EventTypes } from '../../common/events';
import { getMetricsCollector } from '../../common/metrics';
import { NotFoundError, NoMatchingAgentError, AppError } from '../../common/errors';
import { TicketInput, SkillMatchResult, SkillMatch, PaginationParams, PaginatedResult, ProcessingContext } from '../../common/types';
import { verifyTenantAccess } from '../tenant';
import { updateAgentLoad, getAgentById } from '../skill-graph';

const prisma = getPrismaClient();
const cache = getCacheClient();
const eventBus = getEventBus();
const metrics = getMetricsCollector();

const LOAD_WEIGHT = 0.4;
const SKILL_WEIGHT = 0.6;

export const createTicket = async (
  tenantId: string,
  data: TicketInput,
  context?: Partial<ProcessingContext>
) => {
  verifyTenantAccess(tenantId, tenantId, context?.traceId);

  const { requiredSkills, ...ticketData } = data;

  return withTransaction(async (tx) => {
    const ticket = await tx.ticket.create({
      data: {
        ...ticketData,
        tenantId,
        status: 'open'
      }
    });

    for (const skill of requiredSkills) {
      await tx.ticketSkillRequirement.create({
        data: {
          ticketId: ticket.id,
          ...skill
        }
      });
    }

    eventBus.publish(EventTypes.TICKET_CREATED, {
      ticketId: ticket.id,
      tenantId,
      type: data.type,
      priority: data.priority
    }, context);

    metrics.increment('tickets_created_total', 1, { priority: data.priority, type: data.type, tenantId });

    return tx.ticket.findUnique({
      where: { id: ticket.id },
      include: { requiredSkills: { include: { skill: true } } }
    });
  });
};

export const getTicketById = async (
  tenantId: string,
  ticketId: string,
  traceId?: string
) => {
  const ticket = await prisma.ticket.findUnique({
    where: { id: ticketId },
    include: {
      requiredSkills: { include: { skill: true } },
      assignments: { include: { agent: true } },
      agent: true,
      slaInstance: true
    }
  });

  if (!ticket) {
    throw new NotFoundError('Ticket not found', { ticketId }, traceId);
  }

  verifyTenantAccess(ticket.tenantId, tenantId, traceId);
  return ticket;
};

export const listTickets = async (
  tenantId: string,
  params: PaginationParams & { status?: string; priority?: string; agentId?: string }
): Promise<PaginatedResult<unknown>> => {
  const cacheKey = generateCacheKey('tickets', tenantId, String(params.page), String(params.pageSize), params.status || 'all', params.priority || 'all', params.agentId || 'all');
  const cached = await cache.get(cacheKey);
  if (cached) return cached as PaginatedResult<unknown>;

  const where: Record<string, unknown> = { tenantId };
  if (params.status) where.status = params.status;
  if (params.priority) where.priority = params.priority;
  if (params.agentId) where.agentId = params.agentId;

  const [total, items] = await Promise.all([
    prisma.ticket.count({ where }),
    prisma.ticket.findMany({
      where,
      skip: (params.page - 1) * params.pageSize,
      take: params.pageSize,
      orderBy: { createdAt: 'desc' },
      include: { agent: true, requiredSkills: { include: { skill: true } } }
    })
  ]);

  const result: PaginatedResult<unknown> = {
    items,
    total,
    page: params.page,
    pageSize: params.pageSize,
    totalPages: Math.ceil(total / params.pageSize)
  };

  await cache.set(cacheKey, result, TTL.SHORT);
  return result;
};

export const updateTicket = async (
  tenantId: string,
  ticketId: string,
  data: Partial<TicketInput> & { status?: string },
  traceId?: string
) => {
  const ticket = await prisma.ticket.findUnique({ where: { id: ticketId } });
  if (!ticket) {
    throw new NotFoundError('Ticket not found', { ticketId }, traceId);
  }
  verifyTenantAccess(ticket.tenantId, tenantId, traceId);

  const updated = await prisma.ticket.update({
    where: { id: ticketId },
    data
  });

  await cache.del(generateCacheKey('tickets', tenantId));
  eventBus.publish(EventTypes.TICKET_UPDATED, { ticketId, changes: data }, { traceId, tenantId });

  return updated;
};

export const deleteTicket = async (
  tenantId: string,
  ticketId: string,
  traceId?: string
) => {
  const ticket = await prisma.ticket.findUnique({ where: { id: ticketId } });
  if (!ticket) {
    throw new NotFoundError('Ticket not found', { ticketId }, traceId);
  }
  verifyTenantAccess(ticket.tenantId, tenantId, traceId);

  await withTransaction(async (tx) => {
    await tx.ticketSkillRequirement.deleteMany({ where: { ticketId } });
    await tx.ticketAssignment.deleteMany({ where: { ticketId } });
    await tx.sLAInstance.deleteMany({ where: { ticketId } });
    await tx.approvalInstance.deleteMany({ where: { ticketId } });
    await tx.ticket.delete({ where: { id: ticketId } });
  });

  await cache.del(generateCacheKey('tickets', tenantId));
};

export const calculateSkillMatch = async (
  tenantId: string,
  ticketId: string,
  agentId: string,
  traceId?: string
): Promise<SkillMatchResult> => {
  const [ticket, agent] = await Promise.all([
    getTicketById(tenantId, ticketId, traceId),
    getAgentById(tenantId, agentId, traceId)
  ]);

  const requiredSkills = ticket.requiredSkills as Array<{ skillId: string; minLevel: number; skill?: { name: string } }>;
  const agentAssessments = new Map(
    agent.assessments.map((a: { skillId: string; level: number }) => [a.skillId, a.level])
  );

  const skillMatches: SkillMatch[] = [];
  let totalMatchScore = 0;

  for (const req of requiredSkills) {
    const agentLevel = Number(agentAssessments.get(req.skillId)) || 0;
    const minLevel = Number(req.minLevel) || 0;
    const match = agentLevel >= minLevel;
    const matchPercentage = minLevel > 0 ? Math.min(1, agentLevel / minLevel) : 1;

    skillMatches.push({
      skillId: req.skillId,
      skillName: req.skill?.name || req.skillId,
      requiredLevel: minLevel,
      agentLevel,
      match
    });

    totalMatchScore += matchPercentage;
  }

  const skillScore = requiredSkills.length > 0 ? totalMatchScore / requiredSkills.length : 1;
  const loadFactor = agent.maxLoad > 0 ? agent.currentLoad / agent.maxLoad : 0;
  const loadScore = 1 - loadFactor;

  const finalScore = (skillScore * SKILL_WEIGHT) + (loadScore * LOAD_WEIGHT);

  return {
    agentId,
    score: finalScore,
    skillMatches,
    loadFactor
  };
};

export const findMatchingAgents = async (
  tenantId: string,
  ticketId: string,
  options: { threshold?: number; limit?: number; strategy?: string } = {},
  traceId?: string
): Promise<SkillMatchResult[]> => {
  const { threshold = 0.5, limit = 10, strategy = 'hybrid' } = options;

  const ticket = await getTicketById(tenantId, ticketId, traceId);
  const requiredSkillIds = (ticket.requiredSkills as Array<{ skillId: string }>).map((s: { skillId: string }) => s.skillId);

  const where: Record<string, unknown> = {
    tenantId,
    status: 'available',
    currentLoad: { lt: prisma.agent.fields.maxLoad }
  };

  if (requiredSkillIds.length > 0) {
    where.assessments = {
      some: { skillId: { in: requiredSkillIds } }
    };
  }

  const availableAgents = await prisma.agent.findMany({
    where,
    include: { assessments: true }
  });

  if (availableAgents.length === 0) {
    throw new NoMatchingAgentError('No available agents found for this ticket', { ticketId }, traceId);
  }

  const matchResults: SkillMatchResult[] = [];

  for (const agent of availableAgents) {
    const match = await calculateSkillMatch(tenantId, ticketId, agent.id, traceId);
    if (match.score >= threshold) {
      matchResults.push(match);
    }
  }

  if (matchResults.length === 0) {
    throw new NoMatchingAgentError('No agents meet the skill requirements for this ticket', {
      ticketId,
      threshold,
      checkedAgents: availableAgents.length
    }, traceId);
  }

  matchResults.sort((a, b) => {
    switch (strategy) {
      case 'skill_first':
        return b.skillMatches.filter(m => m.match).length - a.skillMatches.filter(m => m.match).length;
      case 'load_first':
        return a.loadFactor - b.loadFactor;
      case 'hybrid':
      default:
        return b.score - a.score;
    }
  });

  return matchResults.slice(0, limit);
};

export const assignTicket = async (
  tenantId: string,
  ticketId: string,
  agentId?: string,
  autoAssign: boolean = true,
  context?: Partial<ProcessingContext>
) => {
  const traceId = context?.traceId;

  return withTransaction(async (tx) => {
    const ticket = await tx.ticket.findUnique({
      where: { id: ticketId },
      include: { requiredSkills: true }
    });

    if (!ticket) {
      throw new NotFoundError('Ticket not found', { ticketId }, traceId);
    }
    verifyTenantAccess(ticket.tenantId, tenantId, traceId);

    let targetAgentId = agentId;
    let matchResults: SkillMatchResult[] = [];
    let reason = 'manual';

    if (!targetAgentId && autoAssign) {
      matchResults = await findMatchingAgents(tenantId, ticketId, {}, traceId);
      const bestMatch = matchResults[0];
      targetAgentId = bestMatch.agentId;
      reason = `auto_assign:score=${bestMatch.score.toFixed(3)}`;
    }

    if (!targetAgentId) {
      throw new NoMatchingAgentError('No agent specified and auto-assignment failed', { ticketId }, traceId);
    }

    const agent = await tx.agent.findUnique({ where: { id: targetAgentId } });
    if (!agent) {
      throw new NotFoundError('Agent not found', { agentId: targetAgentId }, traceId);
    }

    const newLoad = Math.min(agent.maxLoad, agent.currentLoad + 1);

    const [assignment, updatedTicket] = await Promise.all([
      tx.ticketAssignment.create({
        data: {
          ticketId,
          agentId: targetAgentId,
          reason,
          score: matchResults[0]?.score || 0,
          status: 'active'
        }
      }),
      tx.ticket.update({
        where: { id: ticketId },
        data: { agentId: targetAgentId, status: 'assigned' }
      }),
      tx.agent.update({
        where: { id: targetAgentId },
        data: { currentLoad: newLoad }
      })
    ]);

    await cache.del(generateCacheKey('tickets', tenantId));
    await cache.del(generateCacheKey('agents', tenantId));

    eventBus.publish(EventTypes.TICKET_ASSIGNED, {
      ticketId,
      agentId: targetAgentId,
      reason,
      score: assignment.score
    }, context);

    metrics.increment('tickets_assigned_total', 1, { reason, tenantId });
    metrics.histogram('assignment_score', assignment.score, { tenantId });

    return {
      assignment,
      ticket: updatedTicket,
      matchResults: matchResults.slice(0, 5)
    };
  });
};

export const bulkAssignTickets = async (
  tenantId: string,
  ticketIds: string[],
  context?: Partial<ProcessingContext>
) => {
  const results = [];

  for (const ticketId of ticketIds) {
    try {
      const result = await assignTicket(tenantId, ticketId, undefined, true, context);
      results.push({ ticketId, status: 'success', ...result });
    } catch (err) {
      results.push({
        ticketId,
        status: 'failed',
        error: err instanceof Error ? err.message : String(err)
      });
    }
  }

  return results;
};

export const resolveTicket = async (
  tenantId: string,
  ticketId: string,
  resolution: string,
  context?: Partial<ProcessingContext>
) => {
  const ticket = await getTicketById(tenantId, ticketId, context?.traceId);

  if (ticket.agentId) {
    const agent = await getAgentById(tenantId, ticket.agentId, context?.traceId);
    const newLoad = Math.max(0, agent.currentLoad - 1);
    await updateAgentLoad(ticket.agentId, newLoad, context?.traceId);
  }

  const updated = await prisma.ticket.update({
    where: { id: ticketId },
    data: { status: 'resolved' }
  });

  await cache.del(generateCacheKey('tickets', tenantId));
  eventBus.publish(EventTypes.TICKET_RESOLVED, { ticketId, resolution }, context);

  return updated;
};

export const closeTicket = async (
  tenantId: string,
  ticketId: string,
  context?: Partial<ProcessingContext>
) => {
  const updated = await prisma.ticket.update({
    where: { id: ticketId },
    data: { status: 'closed' }
  });

  await cache.del(generateCacheKey('tickets', tenantId));
  eventBus.publish(EventTypes.TICKET_CLOSED, { ticketId }, context);

  return updated;
};

export const reassignTicket = async (
  tenantId: string,
  ticketId: string,
  newAgentId: string,
  reason: string,
  context?: Partial<ProcessingContext>
) => {
  const ticket = await getTicketById(tenantId, ticketId, context?.traceId);
  const oldAgentId = ticket.agentId;

  return withTransaction(async (tx) => {
    if (oldAgentId) {
      const oldAgent = await tx.agent.findUnique({ where: { id: oldAgentId } });
      if (oldAgent) {
        await tx.agent.update({
          where: { id: oldAgentId },
          data: { currentLoad: Math.max(0, oldAgent.currentLoad - 1) }
        });
      }

      await tx.ticketAssignment.updateMany({
        where: { ticketId, status: 'active' },
        data: { status: 'reassigned' }
      });
    }

    const newAgent = await tx.agent.findUnique({ where: { id: newAgentId } });
    if (!newAgent) {
      throw new NotFoundError('Agent not found', { agentId: newAgentId }, context?.traceId);
    }

    const [assignment, updatedTicket] = await Promise.all([
      tx.ticketAssignment.create({
        data: {
          ticketId,
          agentId: newAgentId,
          reason,
          score: 0,
          status: 'active'
        }
      }),
      tx.ticket.update({
        where: { id: ticketId },
        data: { agentId: newAgentId, status: 'assigned' }
      }),
      tx.agent.update({
        where: { id: newAgentId },
        data: { currentLoad: Math.min(newAgent.maxLoad, newAgent.currentLoad + 1) }
      })
    ]);

    await cache.del(generateCacheKey('tickets', tenantId));
    eventBus.publish(EventTypes.TICKET_ASSIGNED, { ticketId, agentId: newAgentId, reason }, context);

    return { assignment, ticket: updatedTicket };
  });
};

export const getAssignmentAnalytics = async (
  tenantId: string,
  startDate: Date,
  endDate: Date
) => {
  const assignments = await prisma.ticketAssignment.findMany({
    where: {
      createdAt: { gte: startDate, lte: endDate },
      agent: { tenantId }
    },
    include: { agent: true, ticket: true }
  });

  const byAgent: Record<string, { count: number; avgScore: number; totalScore: number }> = {};

  for (const assignment of assignments) {
    if (!byAgent[assignment.agentId]) {
      byAgent[assignment.agentId] = { count: 0, avgScore: 0, totalScore: 0 };
    }
    byAgent[assignment.agentId].count++;
    byAgent[assignment.agentId].totalScore += assignment.score;
    byAgent[assignment.agentId].avgScore = byAgent[assignment.agentId].totalScore / byAgent[assignment.agentId].count;
  }

  return {
    totalAssignments: assignments.length,
    autoAssignments: assignments.filter((a: { reason: string }) => a.reason.startsWith('auto_assign')).length,
    manualAssignments: assignments.filter((a: { reason: string }) => a.reason === 'manual').length,
    averageScore: assignments.length > 0 ? assignments.reduce((sum: number, a: { score: number }) => sum + a.score, 0) / assignments.length : 0,
    byAgent,
    period: { startDate, endDate }
  };
};
