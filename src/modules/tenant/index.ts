import { getPrismaClient, tenantFilter, withTransaction } from '../../common/prisma';
import { getCacheClient, generateCacheKey, TTL } from '../../common/cache';
import { getEventBus, EventTypes } from '../../common/events';
import { NotFoundError, ConflictError, QuotaExceededError, TenantIsolationError } from '../../common/errors';
import { getTenantConfig as getDefaultTenantConfig } from '../../common/config';
import { TenantInput, PaginationParams, PaginatedResult, TenantContext } from '../../common/types';

const prisma = getPrismaClient();
const cache = getCacheClient();
const eventBus = getEventBus();

export const createTenant = async (
  data: TenantInput,
  traceId?: string
) => {
  return withTransaction(async (tx) => {
    const existing = await tx.tenant.findFirst({
      where: { email: data.email }
    });
    if (existing) {
      throw new ConflictError('Tenant with this email already exists', { email: data.email }, traceId);
    }

    const tenant = await tx.tenant.create({ data });

    const defaultConfig = getDefaultTenantConfig(tenant.id);
    await tx.tenantConfig.create({
      data: {
        tenantId: tenant.id,
        namespace: 'default',
        version: 1,
        parameters: defaultConfig
      }
    });

    const defaultQuotas = [
      { resourceType: 'tickets', limit: 1000, unit: 'per_month' },
      { resourceType: 'agents', limit: 50, unit: 'count' },
      { resourceType: 'storage', limit: 10737418240, unit: 'bytes' },
      { resourceType: 'api_calls', limit: 100000, unit: 'per_month' }
    ];

    for (const quota of defaultQuotas) {
      await tx.resourceQuota.create({
        data: { tenantId: tenant.id, ...quota }
      });
    }

    eventBus.publish(EventTypes.TENANT_CONFIG_UPDATED, { tenantId: tenant.id, action: 'created' }, { traceId });

    await cache.del(generateCacheKey('tenant', 'list'));

    return tenant;
  });
};

export const getTenantById = async (
  tenantId: string,
  traceId?: string
) => {
  const cacheKey = generateCacheKey('tenant', tenantId);
  const cached = await cache.get(cacheKey);
  if (cached) return cached;

  const tenant = await prisma.tenant.findUnique({
    where: { id: tenantId }
  });

  if (!tenant) {
    throw new NotFoundError('Tenant not found', { tenantId }, traceId);
  }

  await cache.set(cacheKey, tenant, TTL.MEDIUM);
  return tenant;
};

export const getTenantContext = async (
  tenantId: string,
  traceId?: string
): Promise<TenantContext> => {
  const cacheKey = generateCacheKey('tenant', 'context', tenantId);
  const cached = await cache.get<TenantContext>(cacheKey);
  if (cached) return cached;

  const tenant = await getTenantById(tenantId, traceId);
  
  const [configs, quotas] = await Promise.all([
    prisma.tenantConfig.findMany({
      where: { tenantId, enabled: true },
      orderBy: { version: 'desc' }
    }),
    prisma.resourceQuota.findMany({ where: { tenantId } })
  ]);

  const latestConfig = configs[0]?.parameters || {};
  const quotaMap: Record<string, { used: number; limit: number }> = {};
  for (const q of quotas) {
    quotaMap[q.resourceType] = { used: q.used, limit: q.limit };
  }

  const context: TenantContext = {
    tenantId: tenant.id,
    name: tenant.name,
    config: latestConfig as Record<string, unknown>,
    quotas: quotaMap
  };

  await cache.set(cacheKey, context, TTL.MEDIUM);
  return context;
};

export const listTenants = async (
  params: PaginationParams
): Promise<PaginatedResult<unknown>> => {
  const cacheKey = generateCacheKey('tenant', 'list', String(params.page), String(params.pageSize));
  const cached = await cache.get(cacheKey);
  if (cached) return cached as PaginatedResult<unknown>;

  const [total, items] = await Promise.all([
    prisma.tenant.count(),
    prisma.tenant.findMany({
      skip: (params.page - 1) * params.pageSize,
      take: params.pageSize,
      orderBy: { createdAt: 'desc' }
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

export const updateTenant = async (
  tenantId: string,
  data: Partial<TenantInput>,
  traceId?: string
) => {
  const tenant = await prisma.tenant.update({
    where: { id: tenantId },
    data
  });

  await cache.del(generateCacheKey('tenant', tenantId));
  await cache.del(generateCacheKey('tenant', 'list'));
  await cache.del(generateCacheKey('tenant', 'context', tenantId));

  eventBus.publish(EventTypes.TENANT_CONFIG_UPDATED, { tenantId, action: 'updated' }, { traceId });

  return tenant;
};

export const deleteTenant = async (
  tenantId: string,
  traceId?: string
) => {
  await withTransaction(async (tx) => {
    await tx.tenantConfig.deleteMany({ where: { tenantId } });
    await tx.resourceQuota.deleteMany({ where: { tenantId } });
    await tx.usageRecord.deleteMany({ where: { tenantId } });
    await tx.invoice.deleteMany({ where: { tenantId } });
    await tx.ticket.deleteMany({ where: { tenantId } });
    await tx.tenant.delete({ where: { id: tenantId } });
  });

  await cache.del(generateCacheKey('tenant', tenantId));
  await cache.del(generateCacheKey('tenant', 'list'));
  await cache.del(generateCacheKey('tenant', 'context', tenantId));

  eventBus.publish(EventTypes.TENANT_CONFIG_UPDATED, { tenantId, action: 'deleted' }, { traceId });
};

export const setTenantConfig = async (
  tenantId: string,
  namespace: string,
  parameters: Record<string, unknown>,
  traceId?: string
) => {
  const latest = await prisma.tenantConfig.findFirst({
    where: { tenantId, namespace },
    orderBy: { version: 'desc' },
    select: { version: true }
  });

  const config = await prisma.tenantConfig.create({
    data: {
      tenantId,
      namespace,
      version: (latest?.version || 0) + 1,
      parameters,
      enabled: true
    }
  });

  await cache.del(generateCacheKey('tenant', 'context', tenantId));
  eventBus.publish(EventTypes.TENANT_CONFIG_UPDATED, { tenantId, namespace, configId: config.id }, { traceId });

  return config;
};

export const getTenantConfig = async (
  tenantId: string,
  namespace: string
) => {
  return prisma.tenantConfig.findFirst({
    where: { tenantId, namespace, enabled: true },
    orderBy: { version: 'desc' }
  });
};

export const checkQuota = async (
  tenantId: string,
  resourceType: string,
  requestedAmount: number,
  traceId?: string
): Promise<{ allowed: boolean; used: number; limit: number }> => {
  const quota = await prisma.resourceQuota.findUnique({
    where: { tenantId_resourceType: { tenantId, resourceType } }
  });

  if (!quota) {
    return { allowed: true, used: 0, limit: Infinity };
  }

  const newUsed = quota.used + requestedAmount;
  const allowed = newUsed <= quota.limit;

  if (!allowed) {
    throw new QuotaExceededError(
      `Quota exceeded for ${resourceType}`,
      { used: quota.used, limit: quota.limit, requested: requestedAmount },
      traceId
    );
  }

  return { allowed, used: newUsed, limit: quota.limit };
};

export const consumeQuota = async (
  tenantId: string,
  resourceType: string,
  amount: number,
  traceId?: string
) => {
  await checkQuota(tenantId, resourceType, amount, traceId);

  const quota = await prisma.resourceQuota.update({
    where: { tenantId_resourceType: { tenantId, resourceType } },
    data: { used: { increment: amount } }
  });

  await cache.del(generateCacheKey('tenant', 'context', tenantId));
  return quota;
};

export const releaseQuota = async (
  tenantId: string,
  resourceType: string,
  amount: number
) => {
  const quota = await prisma.resourceQuota.update({
    where: { tenantId_resourceType: { tenantId, resourceType } },
    data: { used: { decrement: amount } }
  });

  await cache.del(generateCacheKey('tenant', 'context', tenantId));
  return quota;
};

export const verifyTenantAccess = (
  resourceTenantId: string,
  contextTenantId: string,
  traceId?: string
): void => {
  if (resourceTenantId !== contextTenantId) {
    throw new TenantIsolationError(
      'Access denied - resource belongs to different tenant',
      { resourceTenantId, contextTenantId },
      traceId
    );
  }
};

export const getTenantQuotas = async (tenantId: string) => {
  return prisma.resourceQuota.findMany({
    where: tenantFilter(tenantId)
  });
};
