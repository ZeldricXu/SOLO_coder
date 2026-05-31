import { PrismaClient, TrafficPolicy as DbTrafficPolicy } from '@prisma/client';
import { generatePolicyId } from '../../utils/idGenerator';
import { NotFoundError } from '../../utils/errors';
import type { CreateTrafficPolicyRequest, TrafficPolicy } from './types';
import type { PaginationParams, PaginatedResult } from '../../types';
import logger from '../../utils/logger';

const prisma = new PrismaClient();

const toPolicy = (db: DbTrafficPolicy): TrafficPolicy => ({
  policyId: db.policyId,
  name: db.name,
  policyType: db.policyType as TrafficPolicy['policyType'],
  namespace: db.namespace,
  rules: db.rules as Record<string, unknown>,
  targets: db.targets as Record<string, unknown>,
  enabled: db.enabled,
  createdAt: db.createdAt,
  updatedAt: db.updatedAt,
});

export const createPolicy = async (data: CreateTrafficPolicyRequest): Promise<TrafficPolicy> => {
  const policy = await prisma.trafficPolicy.create({
    data: {
      policyId: generatePolicyId(),
      name: data.name,
      policyType: data.policyType,
      namespace: data.namespace,
      rules: data.rules,
      targets: data.targets,
      enabled: data.enabled,
    },
  });
  logger.info({ policyId: policy.policyId, policyType: data.policyType }, 'Traffic policy created');
  return toPolicy(policy);
};

export const getPolicy = async (policyId: string): Promise<TrafficPolicy> => {
  const policy = await prisma.trafficPolicy.findUnique({ where: { policyId } });
  if (!policy) throw new NotFoundError(`Traffic policy ${policyId} not found`);
  return toPolicy(policy);
};

export const listPolicies = async (params: PaginationParams, namespace?: string, policyType?: string): Promise<PaginatedResult<TrafficPolicy>> => {
  const where: Record<string, unknown> = {};
  if (namespace) where.namespace = namespace;
  if (policyType) where.policyType = policyType;

  const [items, total] = await Promise.all([
    prisma.trafficPolicy.findMany({
      where,
      skip: (params.page - 1) * params.pageSize,
      take: params.pageSize,
      orderBy: { createdAt: 'desc' },
    }),
    prisma.trafficPolicy.count({ where }),
  ]);
  return {
    items: items.map(toPolicy),
    total,
    page: params.page,
    pageSize: params.pageSize,
    totalPages: Math.ceil(total / params.pageSize),
  };
};

export const updatePolicy = async (policyId: string, data: Partial<CreateTrafficPolicyRequest>): Promise<TrafficPolicy> => {
  const policy = await prisma.trafficPolicy.update({
    where: { policyId },
    data: {
      ...(data.name && { name: data.name }),
      ...(data.policyType && { policyType: data.policyType }),
      ...(data.namespace && { namespace: data.namespace }),
      ...(data.rules && { rules: data.rules }),
      ...(data.targets && { targets: data.targets }),
      ...(data.enabled !== undefined && { enabled: data.enabled }),
    },
  });
  logger.info({ policyId }, 'Traffic policy updated');
  return toPolicy(policy);
};

export const deletePolicy = async (policyId: string): Promise<void> => {
  await prisma.trafficPolicy.delete({ where: { policyId } });
  logger.info({ policyId }, 'Traffic policy deleted');
};

export const togglePolicy = async (policyId: string, enabled: boolean): Promise<TrafficPolicy> => {
  const policy = await prisma.trafficPolicy.update({
    where: { policyId },
    data: { enabled },
  });
  logger.info({ policyId, enabled }, 'Traffic policy toggled');
  return toPolicy(policy);
};

export const getActivePolicies = async (namespace?: string): Promise<TrafficPolicy[]> => {
  const where: Record<string, unknown> = { enabled: true };
  if (namespace) where.namespace = namespace;

  const policies = await prisma.trafficPolicy.findMany({
    where,
    orderBy: { createdAt: 'desc' },
  });
  return policies.map(toPolicy);
};

export default {
  createPolicy,
  getPolicy,
  listPolicies,
  updatePolicy,
  deletePolicy,
  togglePolicy,
  getActivePolicies,
};
