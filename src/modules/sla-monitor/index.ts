import { getPrismaClient, withTransaction, tenantFilter } from '../../common/prisma';
import { getCacheClient, generateCacheKey, TTL } from '../../common/cache';
import { getEventBus, EventTypes } from '../../common/events';
import { getMetricsCollector } from '../../common/metrics';
import { NotFoundError, SLABreachError, AppError } from '../../common/errors';
import { SLAPolicyInput, SLAStatus, PaginationParams, PaginatedResult, ProcessingContext } from '../../common/types';
import { verifyTenantAccess } from '../tenant';
import { getTicketById } from '../ticket-assignment';

const prisma = getPrismaClient();
const cache = getCacheClient();
const eventBus = getEventBus();
const metrics = getMetricsCollector();

const WARNING_THRESHOLD = 0.75;

export const createSLAPolicy = async (
  tenantId: string,
  data: SLAPolicyInput,
  traceId?: string
) => {
  verifyTenantAccess(tenantId, tenantId, traceId);

  const { escalationLevels, ...policyData } = data;

  return withTransaction(async (tx) => {
    const existing = await tx.sLAPolicy.findUnique({
      where: { tenantId_ticketType_priority: { tenantId, ticketType: data.ticketType, priority: data.priority } }
    });

    if (existing) {
      await tx.sLAPolicy.update({
        where: { id: existing.id },
        data: { ...policyData }
      });

      await tx.escalationLevel.deleteMany({ where: { policyId: existing.id } });

      for (const level of escalationLevels) {
        await tx.escalationLevel.create({
          data: { ...level, policyId: existing.id }
        });
      }

      return tx.sLAPolicy.findUnique({
        where: { id: existing.id },
        include: { escalationLevels: { orderBy: { level: 'asc' } } }
      });
    }

    const policy = await tx.sLAPolicy.create({
      data: {
        ...policyData,
        tenantId,
        escalationLevels: {
          create: escalationLevels
        }
      },
      include: { escalationLevels: { orderBy: { level: 'asc' } } }
    });

    await cache.del(generateCacheKey('sla', 'policies', tenantId));
    return policy;
  });
};

export const getSLAPolicy = async (
  tenantId: string,
  policyId: string,
  traceId?: string
) => {
  const policy = await prisma.sLAPolicy.findUnique({
    where: { id: policyId },
    include: { escalationLevels: { orderBy: { level: 'asc' } } }
  });

  if (!policy) {
    throw new NotFoundError('SLA Policy not found', { policyId }, traceId);
  }

  verifyTenantAccess(policy.tenantId, tenantId, traceId);
  return policy;
};

export const listSLAPolicies = async (
  tenantId: string,
  params: PaginationParams
): Promise<PaginatedResult<unknown>> => {
  const cacheKey = generateCacheKey('sla', 'policies', tenantId, String(params.page), String(params.pageSize));
  const cached = await cache.get(cacheKey);
  if (cached) return cached as PaginatedResult<unknown>;

  const [total, items] = await Promise.all([
    prisma.sLAPolicy.count({ where: tenantFilter(tenantId) }),
    prisma.sLAPolicy.findMany({
      where: tenantFilter(tenantId),
      skip: (params.page - 1) * params.pageSize,
      take: params.pageSize,
      include: { escalationLevels: { orderBy: { level: 'asc' } } },
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

export const getMatchingSLAPolicy = async (
  tenantId: string,
  ticketType: string,
  priority: string,
  traceId?: string
) => {
  const policy = await prisma.sLAPolicy.findUnique({
    where: { tenantId_ticketType_priority: { tenantId, ticketType, priority } },
    include: { escalationLevels: { orderBy: { level: 'asc' } } }
  });

  if (!policy) {
    const defaultPolicy = await prisma.sLAPolicy.findUnique({
      where: { tenantId_ticketType_priority: { tenantId, ticketType: 'default', priority: 'medium' } },
      include: { escalationLevels: { orderBy: { level: 'asc' } } }
    });

    if (!defaultPolicy) {
      return {
        id: 'default',
        name: 'Default SLA',
        responseTime: 3600,
        resolutionTime: 86400,
        escalationLevels: []
      };
    }
    return defaultPolicy;
  }

  return policy;
};

export const createSLAInstance = async (
  tenantId: string,
  ticketId: string,
  context?: Partial<ProcessingContext>
) => {
  const ticket = await getTicketById(tenantId, ticketId, context?.traceId);
  const policy = await getMatchingSLAPolicy(tenantId, ticket.type, ticket.priority, context?.traceId);

  const now = new Date();
  const responseDeadline = new Date(now.getTime() + policy.responseTime * 1000);
  const resolutionDeadline = new Date(now.getTime() + policy.resolutionTime * 1000);

  return prisma.sLAInstance.create({
    data: {
      ticketId,
      policyId: policy.id,
      responseDeadline,
      resolutionDeadline,
      status: 'active'
    }
  });
};

export const getSLAInstance = async (
  tenantId: string,
  ticketId: string,
  traceId?: string
) => {
  const instance = await prisma.sLAInstance.findUnique({
    where: { ticketId },
    include: {
      policy: {
        include: {
          escalationLevels: {
            orderBy: { level: 'asc' }
          }
        }
      }
    }
  });

  if (!instance) {
    throw new NotFoundError('SLA Instance not found', { ticketId }, traceId);
  }

  const ticket = await getTicketById(tenantId, ticketId, traceId);
  verifyTenantAccess(ticket.tenantId, tenantId, traceId);

  return { ...instance, currentStatus: calculateSLAStatus(instance) };
};

export const calculateSLAStatus = (instance: {
  responseDeadline: Date;
  resolutionDeadline: Date;
  breachedAt?: Date | null;
  status: string;
}): { status: SLAStatus; responseProgress: number; resolutionProgress: number; timeRemaining: number } => {
  const now = Date.now();
  const startTime = now;

  if (instance.breachedAt) {
    return {
      status: 'breached',
      responseProgress: 1,
      resolutionProgress: 1,
      timeRemaining: 0
    };
  }

  const responseTotal = instance.responseDeadline.getTime() - startTime;
  const resolutionTotal = instance.resolutionDeadline.getTime() - startTime;

  const responseProgress = responseTotal > 0
    ? (now - startTime) / (instance.responseDeadline.getTime() - startTime)
    : 1;
  const resolutionProgress = resolutionTotal > 0
    ? (now - startTime) / (instance.resolutionDeadline.getTime() - startTime)
    : 1;

  const timeRemaining = Math.max(0, instance.resolutionDeadline.getTime() - now);

  let status: SLAStatus = 'active';
  if (responseProgress >= 1 || resolutionProgress >= 1) {
    status = 'breached';
  } else if (responseProgress >= WARNING_THRESHOLD || resolutionProgress >= WARNING_THRESHOLD) {
    status = 'warning';
  }

  return { status, responseProgress, resolutionProgress, timeRemaining };
};

export const getSLACountdown = async (
  tenantId: string,
  ticketId: string,
  traceId?: string
) => {
  const instance = await getSLAInstance(tenantId, ticketId, traceId);
  const status = calculateSLAStatus(instance);

  return {
    ticketId,
    policyName: instance.policy.name,
    responseDeadline: instance.responseDeadline,
    resolutionDeadline: instance.resolutionDeadline,
    currentLevel: instance.currentLevel,
    ...status,
    humanReadable: formatTimeRemaining(status.timeRemaining)
  };
};

const formatTimeRemaining = (ms: number): string => {
  if (ms <= 0) return 'Expired';

  const seconds = Math.floor(ms / 1000);
  const minutes = Math.floor(seconds / 60);
  const hours = Math.floor(minutes / 60);
  const days = Math.floor(hours / 24);

  if (days > 0) return `${days}d ${hours % 24}h remaining`;
  if (hours > 0) return `${hours}h ${minutes % 60}m remaining`;
  if (minutes > 0) return `${minutes}m ${seconds % 60}s remaining`;
  return `${seconds}s remaining`;
};

export const checkSLAEscalation = async (
  tenantId: string,
  ticketId: string,
  traceId?: string
) => {
  const instance = await getSLAInstance(tenantId, ticketId, traceId);
  const status = calculateSLAStatus(instance);

  if (status.status === 'breached' && !instance.breachedAt) {
    await prisma.sLAInstance.update({
      where: { ticketId },
      data: { breachedAt: new Date(), status: 'breached' }
    });

    eventBus.publish(EventTypes.SLA_BREACHED, {
      ticketId,
      policyId: instance.policyId,
      type: 'resolution'
    }, { traceId, tenantId });

    metrics.increment('sla_breaches_total', 1, { tenantId, priority: instance.policy.priority });
    throw new SLABreachError('SLA has been breached', { ticketId }, traceId);
  }

  const escalationLevels = instance.policy.escalationLevels || [];
  const currentTimeRemaining = status.timeRemaining;
  const totalTime = instance.resolutionDeadline.getTime() - instance.responseDeadline.getTime();
  const progress = 1 - (currentTimeRemaining / totalTime);

  for (const level of escalationLevels) {
    if (progress >= level.threshold && instance.currentLevel < level.level) {
      await prisma.sLAInstance.update({
        where: { ticketId },
        data: { currentLevel: level.level }
      });

      eventBus.publish(EventTypes.SLA_WARNING, {
        ticketId,
        policyId: instance.policyId,
        level: level.level,
        action: level.action,
        targetRole: level.targetRole
      }, { traceId, tenantId });

      metrics.increment('sla_escalations_total', 1, { level: String(level.level), tenantId });

      return {
        escalated: true,
        level: level.level,
        action: level.action,
        targetRole: level.targetRole
      };
    }
  }

  if (status.status === 'warning' && instance.currentLevel === 0) {
    eventBus.publish(EventTypes.SLA_WARNING, {
      ticketId,
      policyId: instance.policyId,
      level: 0,
      warningThreshold: WARNING_THRESHOLD
    }, { traceId, tenantId });
  }

  return { escalated: false, currentLevel: instance.currentLevel, status: status.status };
};

export const monitorActiveSLAs = async (tenantId: string): Promise<{ active: number; warning: number; breached: number; met: number }> => {
  const instances = await prisma.sLAInstance.findMany({
    where: { ticket: { tenantId }, status: { not: 'met' } },
    include: { ticket: true }
  });

  const counts = { active: 0, warning: 0, breached: 0, met: 0 };

  for (const instance of instances) {
    const status = calculateSLAStatus(instance);
    counts[status.status]++;
  }

  return counts;
};

export const markSLAMet = async (
  tenantId: string,
  ticketId: string,
  context?: Partial<ProcessingContext>
) => {
  const instance = await getSLAInstance(tenantId, ticketId, context?.traceId);

  if (instance.breachedAt) {
    return instance;
  }

  const updated = await prisma.sLAInstance.update({
    where: { ticketId },
    data: { status: 'met' }
  });

  eventBus.publish(EventTypes.SLA_MET, {
    ticketId,
    policyId: instance.policyId,
    responseTime: Date.now() - instance.responseDeadline.getTime()
  }, context);

  metrics.increment('sla_met_total', 1, { tenantId, priority: instance.policy.priority });

  return updated;
};

export const getSLAReport = async (
  tenantId: string,
  startDate: Date,
  endDate: Date
) => {
  const instances = await prisma.sLAInstance.findMany({
    where: {
      createdAt: { gte: startDate, lte: endDate },
      ticket: { tenantId }
    },
    include: { ticket: true, policy: true }
  });

  const metricsData = {
    total: instances.length,
    met: instances.filter((i: { status: string }) => i.status === 'met').length,
    breached: instances.filter((i: { status: string }) => i.status === 'breached').length,
    warning: instances.filter((i: {
      responseDeadline: Date;
      resolutionDeadline: Date;
      breachedAt?: Date | null;
      status: string;
    }) => {
      const status = calculateSLAStatus(i);
      return status.status === 'warning';
    }).length,
    byPriority: {} as Record<string, { total: number; met: number; breached: number }>,
    byType: {} as Record<string, { total: number; met: number; breached: number }>,
    averageResponseTime: 0,
    averageResolutionTime: 0
  };

  let totalResponseTime = 0;
  let totalResolutionTime = 0;
  let completedCount = 0;

  for (const instance of instances) {
    const policyInstance = instance.policy as { priority?: string; ticketType?: string } | null;
    const priority = policyInstance?.priority || 'medium';
    const type = policyInstance?.ticketType || 'default';

    if (!metricsData.byPriority[priority]) {
      metricsData.byPriority[priority] = { total: 0, met: 0, breached: 0 };
    }
    metricsData.byPriority[priority].total++;
    if (instance.status === 'met') metricsData.byPriority[priority].met++;
    if (instance.status === 'breached') metricsData.byPriority[priority].breached++;

    if (!metricsData.byType[type]) {
      metricsData.byType[type] = { total: 0, met: 0, breached: 0 };
    }
    metricsData.byType[type].total++;
    if (instance.status === 'met') metricsData.byType[type].met++;
    if (instance.status === 'breached') metricsData.byType[type].breached++;

    if (instance.status === 'met' || instance.status === 'breached') {
      completedCount++;
      totalResponseTime += instance.responseDeadline.getTime() - instance.createdAt.getTime();
      totalResolutionTime += (instance.breachedAt?.getTime() || instance.resolutionDeadline.getTime()) - instance.createdAt.getTime();
    }
  }

  metricsData.averageResponseTime = completedCount > 0 ? totalResponseTime / completedCount : 0;
  metricsData.averageResolutionTime = completedCount > 0 ? totalResolutionTime / completedCount : 0;

  return {
    ...metricsData,
    complianceRate: metricsData.total > 0 ? metricsData.met / metricsData.total : 0,
    period: { startDate, endDate }
  };
};

export const startSLAMonitor = (tenantId: string, intervalMs: number = 60000) => {
  const monitorInterval = setInterval(async () => {
    try {
      const instances = await prisma.sLAInstance.findMany({
        where: { ticket: { tenantId }, status: 'active' },
        select: { ticketId: true }
      });

      for (const instance of instances) {
        try {
          await checkSLAEscalation(tenantId, instance.ticketId);
        } catch (err) {
          if (!(err instanceof SLABreachError)) {
            console.error('Error checking SLA for ticket', instance.ticketId, err);
          }
        }
      }
    } catch (err) {
      console.error('Error in SLA monitor', err);
    }
  }, intervalMs);

  return () => clearInterval(monitorInterval);
};
