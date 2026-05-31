import { PrismaClient, ChaosScenario as DbScenario, ChaosInjection as DbInjection } from '@prisma/client';
import { generateScenarioId, generateInjectionId } from '../../utils/idGenerator';
import { NotFoundError, ValidationError } from '../../utils/errors';
import type { CreateScenarioRequest, CreateInjectionRequest, ChaosScenario, ChaosInjection } from './types';
import type { PaginationParams, PaginatedResult } from '../../types';
import logger from '../../utils/logger';

const prisma = new PrismaClient();

const toScenario = (db: DbScenario): ChaosScenario => ({
  scenarioId: db.scenarioId,
  name: db.name,
  description: db.description ?? undefined,
  faultType: db.faultType as ChaosScenario['faultType'],
  targetScope: db.targetScope as Record<string, unknown>,
  parameters: db.parameters as Record<string, unknown>,
  autoRollback: db.autoRollback,
  rollbackConfig: db.rollbackConfig as Record<string, unknown> | undefined,
  status: db.status,
  createdBy: db.createdBy,
  createdAt: db.createdAt,
  updatedAt: db.updatedAt,
});

const toInjection = (db: DbInjection): ChaosInjection => ({
  injectionId: db.injectionId,
  scenarioId: db.scenarioId,
  targetIds: db.targetIds,
  status: db.status as ChaosInjection['status'],
  startedAt: db.startedAt ?? undefined,
  endedAt: db.endedAt ?? undefined,
  rollbackAt: db.rollbackAt ?? undefined,
  errorDetail: db.errorDetail ?? undefined,
  createdAt: db.createdAt,
  updatedAt: db.updatedAt,
});

export const createScenario = async (data: CreateScenarioRequest): Promise<ChaosScenario> => {
  const scenario = await prisma.chaosScenario.create({
    data: {
      scenarioId: generateScenarioId(),
      name: data.name,
      description: data.description,
      faultType: data.faultType,
      targetScope: data.targetScope,
      parameters: data.parameters,
      autoRollback: data.autoRollback,
      rollbackConfig: data.rollbackConfig,
      status: 'draft',
      createdBy: data.createdBy,
    },
  });
  logger.info({ scenarioId: scenario.scenarioId }, 'Chaos scenario created');
  return toScenario(scenario);
};

export const getScenario = async (scenarioId: string): Promise<ChaosScenario> => {
  const scenario = await prisma.chaosScenario.findUnique({ where: { scenarioId } });
  if (!scenario) throw new NotFoundError(`Scenario ${scenarioId} not found`);
  return toScenario(scenario);
};

export const listScenarios = async (params: PaginationParams): Promise<PaginatedResult<ChaosScenario>> => {
  const [items, total] = await Promise.all([
    prisma.chaosScenario.findMany({
      skip: (params.page - 1) * params.pageSize,
      take: params.pageSize,
      orderBy: { createdAt: 'desc' },
    }),
    prisma.chaosScenario.count(),
  ]);
  return {
    items: items.map(toScenario),
    total,
    page: params.page,
    pageSize: params.pageSize,
    totalPages: Math.ceil(total / params.pageSize),
  };
};

export const updateScenario = async (scenarioId: string, data: Partial<CreateScenarioRequest>): Promise<ChaosScenario> => {
  const scenario = await prisma.chaosScenario.update({
    where: { scenarioId },
    data: {
      ...(data.name && { name: data.name }),
      ...(data.description && { description: data.description }),
      ...(data.faultType && { faultType: data.faultType }),
      ...(data.targetScope && { targetScope: data.targetScope }),
      ...(data.parameters && { parameters: data.parameters }),
      ...(data.autoRollback !== undefined && { autoRollback: data.autoRollback }),
      ...(data.rollbackConfig && { rollbackConfig: data.rollbackConfig }),
    },
  });
  logger.info({ scenarioId }, 'Chaos scenario updated');
  return toScenario(scenario);
};

export const deleteScenario = async (scenarioId: string): Promise<void> => {
  await prisma.chaosScenario.delete({ where: { scenarioId } });
  logger.info({ scenarioId }, 'Chaos scenario deleted');
};

export const startInjection = async (data: CreateInjectionRequest): Promise<ChaosInjection> => {
  const scenario = await prisma.chaosScenario.findUnique({ where: { scenarioId: data.scenarioId } });
  if (!scenario) throw new NotFoundError(`Scenario ${data.scenarioId} not found`);
  if (scenario.status !== 'active') throw new ValidationError(`Scenario ${data.scenarioId} is not active`);

  const targetIds = data.targetIds || (scenario.targetScope as { targetIds?: string[] }).targetIds || [];
  if (targetIds.length === 0) throw new ValidationError('No targets specified for injection');

  const injection = await prisma.chaosInjection.create({
    data: {
      injectionId: generateInjectionId(),
      scenarioId: data.scenarioId,
      targetIds,
      status: 'injecting',
      startedAt: new Date(),
    },
  });
  logger.info({ injectionId: injection.injectionId, scenarioId: data.scenarioId }, 'Chaos injection started');
  return toInjection(injection);
};

export const getInjection = async (injectionId: string): Promise<ChaosInjection> => {
  const injection = await prisma.chaosInjection.findUnique({ where: { injectionId } });
  if (!injection) throw new NotFoundError(`Injection ${injectionId} not found`);
  return toInjection(injection);
};

export const listInjections = async (params: PaginationParams, scenarioId?: string): Promise<PaginatedResult<ChaosInjection>> => {
  const where = scenarioId ? { scenarioId } : {};
  const [items, total] = await Promise.all([
    prisma.chaosInjection.findMany({
      where,
      skip: (params.page - 1) * params.pageSize,
      take: params.pageSize,
      orderBy: { createdAt: 'desc' },
    }),
    prisma.chaosInjection.count({ where }),
  ]);
  return {
    items: items.map(toInjection),
    total,
    page: params.page,
    pageSize: params.pageSize,
    totalPages: Math.ceil(total / params.pageSize),
  };
};

export const rollbackInjection = async (injectionId: string): Promise<ChaosInjection> => {
  const injection = await prisma.chaosInjection.findUnique({ where: { injectionId } });
  if (!injection) throw new NotFoundError(`Injection ${injectionId} not found`);
  if (injection.status === 'completed' || injection.status === 'rolling_back') {
    throw new ValidationError(`Cannot rollback injection with status ${injection.status}`);
  }

  const updated = await prisma.chaosInjection.update({
    where: { injectionId },
    data: {
      status: 'rolling_back',
      rollbackAt: new Date(),
    },
  });
  logger.info({ injectionId }, 'Chaos injection rollback initiated');
  return toInjection(updated);
};

export const completeInjection = async (injectionId: string, success: boolean, errorDetail?: string): Promise<ChaosInjection> => {
  const injection = await prisma.chaosInjection.update({
    where: { injectionId },
    data: {
      status: success ? 'completed' : 'failed',
      endedAt: new Date(),
      errorDetail,
    },
  });
  logger.info({ injectionId, success }, 'Chaos injection completed');
  return toInjection(injection);
};

export default {
  createScenario,
  getScenario,
  listScenarios,
  updateScenario,
  deleteScenario,
  startInjection,
  getInjection,
  listInjections,
  rollbackInjection,
  completeInjection,
};
