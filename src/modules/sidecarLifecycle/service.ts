import { PrismaClient, SidecarTemplate as DbTemplate, SidecarInjection as DbInjection } from '@prisma/client';
import { generateTemplateId, generateId } from '../../utils/idGenerator';
import { NotFoundError, ValidationError } from '../../utils/errors';
import type { CreateTemplateRequest, CreateInjectionRequest, SidecarTemplate, SidecarInjection, ConfigUpdateResult, SidecarInstance } from './types';
import type { PaginationParams, PaginatedResult } from '../../types';
import logger from '../../utils/logger';

const prisma = new PrismaClient();

const toTemplate = (db: DbTemplate): SidecarTemplate => ({
  templateId: db.templateId,
  name: db.name,
  image: db.image,
  args: db.args as string[],
  resources: db.resources as Record<string, unknown>,
  volumeMounts: db.volumeMounts as Array<Record<string, unknown>> | undefined,
  config: db.config as Record<string, unknown> | undefined,
  createdAt: db.createdAt,
  updatedAt: db.updatedAt,
});

const toInjection = (db: DbInjection): SidecarInjection => ({
  injectionId: db.injectionId,
  templateId: db.templateId,
  targetSelector: db.targetSelector as Record<string, string>,
  injectionPolicy: db.injectionPolicy as SidecarInjection['injectionPolicy'],
  enabled: db.enabled,
  createdAt: db.createdAt,
  updatedAt: db.updatedAt,
});

const sidecarInstances: Map<string, SidecarInstance> = new Map();

export const createTemplate = async (data: CreateTemplateRequest): Promise<SidecarTemplate> => {
  const template = await prisma.sidecarTemplate.create({
    data: {
      templateId: generateTemplateId(),
      name: data.name,
      image: data.image,
      args: data.args,
      resources: data.resources,
      volumeMounts: data.volumeMounts,
      config: data.config,
    },
  });
  logger.info({ templateId: template.templateId, name: data.name }, 'Sidecar template created');
  return toTemplate(template);
};

export const getTemplate = async (templateId: string): Promise<SidecarTemplate> => {
  const template = await prisma.sidecarTemplate.findUnique({ where: { templateId } });
  if (!template) throw new NotFoundError(`Sidecar template ${templateId} not found`);
  return toTemplate(template);
};

export const listTemplates = async (params: PaginationParams): Promise<PaginatedResult<SidecarTemplate>> => {
  const [items, total] = await Promise.all([
    prisma.sidecarTemplate.findMany({
      skip: (params.page - 1) * params.pageSize,
      take: params.pageSize,
      orderBy: { createdAt: 'desc' },
    }),
    prisma.sidecarTemplate.count(),
  ]);
  return {
    items: items.map(toTemplate),
    total,
    page: params.page,
    pageSize: params.pageSize,
    totalPages: Math.ceil(total / params.pageSize),
  };
};

export const updateTemplate = async (templateId: string, data: Partial<CreateTemplateRequest>): Promise<SidecarTemplate> => {
  const template = await prisma.sidecarTemplate.update({
    where: { templateId },
    data: {
      ...(data.name && { name: data.name }),
      ...(data.image && { image: data.image }),
      ...(data.args && { args: data.args }),
      ...(data.resources && { resources: data.resources }),
      ...(data.volumeMounts && { volumeMounts: data.volumeMounts }),
      ...(data.config && { config: data.config }),
    },
  });
  logger.info({ templateId }, 'Sidecar template updated');
  return toTemplate(template);
};

export const deleteTemplate = async (templateId: string): Promise<void> => {
  const injections = await prisma.sidecarInjection.findMany({ where: { templateId } });
  if (injections.length > 0) {
    throw new ValidationError(`Cannot delete template with ${injections.length} active injections`);
  }
  await prisma.sidecarTemplate.delete({ where: { templateId } });
  logger.info({ templateId }, 'Sidecar template deleted');
};

export const createInjection = async (data: CreateInjectionRequest): Promise<SidecarInjection> => {
  const template = await prisma.sidecarTemplate.findUnique({ where: { templateId: data.templateId } });
  if (!template) throw new NotFoundError(`Sidecar template ${data.templateId} not found`);

  const injection = await prisma.sidecarInjection.create({
    data: {
      injectionId: generateId('inj'),
      templateId: data.templateId,
      targetSelector: data.targetSelector,
      injectionPolicy: data.injectionPolicy,
      enabled: data.enabled,
    },
  });
  logger.info({ injectionId: injection.injectionId, templateId: data.templateId }, 'Sidecar injection created');
  return toInjection(injection);
};

export const getInjection = async (injectionId: string): Promise<SidecarInjection> => {
  const injection = await prisma.sidecarInjection.findUnique({ where: { injectionId } });
  if (!injection) throw new NotFoundError(`Sidecar injection ${injectionId} not found`);
  return toInjection(injection);
};

export const listInjections = async (params: PaginationParams, templateId?: string, enabledOnly?: boolean): Promise<PaginatedResult<SidecarInjection>> => {
  const where: Record<string, unknown> = {};
  if (templateId) where.templateId = templateId;
  if (enabledOnly !== undefined) where.enabled = enabledOnly;

  const [items, total] = await Promise.all([
    prisma.sidecarInjection.findMany({
      where,
      skip: (params.page - 1) * params.pageSize,
      take: params.pageSize,
      orderBy: { createdAt: 'desc' },
    }),
    prisma.sidecarInjection.count({ where }),
  ]);
  return {
    items: items.map(toInjection),
    total,
    page: params.page,
    pageSize: params.pageSize,
    totalPages: Math.ceil(total / params.pageSize),
  };
};

export const updateInjection = async (injectionId: string, data: Partial<CreateInjectionRequest>): Promise<SidecarInjection> => {
  const injection = await prisma.sidecarInjection.update({
    where: { injectionId },
    data: {
      ...(data.templateId && { templateId: data.templateId }),
      ...(data.targetSelector && { targetSelector: data.targetSelector }),
      ...(data.injectionPolicy && { injectionPolicy: data.injectionPolicy }),
      ...(data.enabled !== undefined && { enabled: data.enabled }),
    },
  });
  logger.info({ injectionId }, 'Sidecar injection updated');
  return toInjection(injection);
};

export const deleteInjection = async (injectionId: string): Promise<void> => {
  await prisma.sidecarInjection.delete({ where: { injectionId } });
  logger.info({ injectionId }, 'Sidecar injection deleted');
};

export const toggleInjection = async (injectionId: string, enabled: boolean): Promise<SidecarInjection> => {
  const injection = await prisma.sidecarInjection.update({
    where: { injectionId },
    data: { enabled },
  });
  logger.info({ injectionId, enabled }, 'Sidecar injection toggled');
  return toInjection(injection);
};

export const updateConfig = async (injectionId: string, key: string, value: unknown): Promise<ConfigUpdateResult> => {
  const injection = await getInjection(injectionId);
  const template = await getTemplate(injection.templateId);

  const updatedConfig = {
    ...(template.config || {}),
    [key]: value,
  };

  await prisma.sidecarTemplate.update({
    where: { templateId: injection.templateId },
    data: { config: updatedConfig },
  });

  const affectedInstances = Array.from(sidecarInstances.values()).filter(
    inst => inst.templateId === injection.templateId
  ).length;

  logger.info({ injectionId, key, affectedInstances }, 'Sidecar config updated');

  return {
    injectionId,
    updatedKeys: [key],
    affectedInstances,
    timestamp: new Date(),
  };
};

export const batchUpdateConfig = async (injectionId: string, updates: Array<{ key: string; value: unknown }>): Promise<ConfigUpdateResult> => {
  const injection = await getInjection(injectionId);
  const template = await getTemplate(injection.templateId);

  const updatedConfig = { ...(template.config || {}) };
  const updatedKeys: string[] = [];

  for (const update of updates) {
    updatedConfig[update.key] = update.value;
    updatedKeys.push(update.key);
  }

  await prisma.sidecarTemplate.update({
    where: { templateId: injection.templateId },
    data: { config: updatedConfig },
  });

  const affectedInstances = Array.from(sidecarInstances.values()).filter(
    inst => inst.templateId === injection.templateId
  ).length;

  logger.info({ injectionId, updatedKeys, affectedInstances }, 'Sidecar config batch updated');

  return {
    injectionId,
    updatedKeys,
    affectedInstances,
    timestamp: new Date(),
  };
};

export const getInjectionsForPod = async (namespace: string, labels: Record<string, string>): Promise<SidecarInjection[]> => {
  const injections = await prisma.sidecarInjection.findMany({
    where: { enabled: true },
  });

  return injections
    .map(toInjection)
    .filter(injection => {
      const selector = injection.targetSelector;
      return Object.entries(selector).every(([key, value]) => labels[key] === value);
    });
};

export const registerSidecarInstance = async (data: Omit<SidecarInstance, 'instanceId' | 'startedAt' | 'lastHeartbeat'>): Promise<SidecarInstance> => {
  const instance: SidecarInstance = {
    ...data,
    instanceId: generateId('sidecar'),
    startedAt: new Date(),
    lastHeartbeat: new Date(),
  };
  sidecarInstances.set(instance.instanceId, instance);
  logger.info({ instanceId: instance.instanceId, templateId: data.templateId }, 'Sidecar instance registered');
  return instance;
};

export const heartbeat = async (instanceId: string): Promise<SidecarInstance> => {
  const instance = sidecarInstances.get(instanceId);
  if (!instance) throw new NotFoundError(`Sidecar instance ${instanceId} not found`);
  instance.lastHeartbeat = new Date();
  sidecarInstances.set(instanceId, instance);
  return instance;
};

export const deregisterSidecarInstance = async (instanceId: string): Promise<void> => {
  if (!sidecarInstances.has(instanceId)) {
    throw new NotFoundError(`Sidecar instance ${instanceId} not found`);
  }
  sidecarInstances.delete(instanceId);
  logger.info({ instanceId }, 'Sidecar instance deregistered');
};

export const listInstances = async (templateId?: string, namespace?: string): Promise<SidecarInstance[]> => {
  let instances = Array.from(sidecarInstances.values());
  if (templateId) instances = instances.filter(i => i.templateId === templateId);
  if (namespace) instances = instances.filter(i => i.namespace === namespace);
  return instances;
};

export const getInstance = async (instanceId: string): Promise<SidecarInstance> => {
  const instance = sidecarInstances.get(instanceId);
  if (!instance) throw new NotFoundError(`Sidecar instance ${instanceId} not found`);
  return instance;
};

export const updateInstanceResources = async (instanceId: string, resources: Record<string, unknown>): Promise<SidecarInstance> => {
  const instance = sidecarInstances.get(instanceId);
  if (!instance) throw new NotFoundError(`Sidecar instance ${instanceId} not found`);
  instance.config = { ...instance.config, resources };
  sidecarInstances.set(instanceId, instance);
  logger.info({ instanceId, resources }, 'Sidecar instance resources updated');
  return instance;
};

export const getActiveInjections = async (): Promise<SidecarInjection[]> => {
  const injections = await prisma.sidecarInjection.findMany({
    where: { enabled: true },
    include: { template: true },
  });
  return injections.map(toInjection);
};

export default {
  createTemplate,
  getTemplate,
  listTemplates,
  updateTemplate,
  deleteTemplate,
  createInjection,
  getInjection,
  listInjections,
  updateInjection,
  deleteInjection,
  toggleInjection,
  updateConfig,
  batchUpdateConfig,
  getInjectionsForPod,
  registerSidecarInstance,
  heartbeat,
  deregisterSidecarInstance,
  listInstances,
  getInstance,
  updateInstanceResources,
  getActiveInjections,
};
