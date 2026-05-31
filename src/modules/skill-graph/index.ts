import { getPrismaClient, withTransaction, tenantFilter } from '../../common/prisma';
import { getCacheClient, generateCacheKey, TTL } from '../../common/cache';
import { getEventBus, EventTypes } from '../../common/events';
import { NotFoundError, ConflictError } from '../../common/errors';
import { SkillInput, AgentInput, SkillAssessmentInput, PaginationParams, PaginatedResult } from '../../common/types';
import { verifyTenantAccess } from '../tenant';

const prisma = getPrismaClient();
const cache = getCacheClient();
const eventBus = getEventBus();

export const createSkill = async (
  tenantId: string,
  data: SkillInput,
  traceId?: string
) => {
  verifyTenantAccess(tenantId, tenantId, traceId);

  const existing = await prisma.skill.findFirst({
    where: { tenantId, name: data.name, category: data.category }
  });

  if (existing) {
    throw new ConflictError('Skill with this name already exists in the category', { name: data.name, category: data.category }, traceId);
  }

  const skill = await prisma.skill.create({
    data: { ...data, tenantId }
  });

  await cache.del(generateCacheKey('skills', tenantId));
  eventBus.publish(EventTypes.SKILL_ASSESSED, { skillId: skill.id, action: 'created' }, { traceId, tenantId });

  return skill;
};

export const getSkillById = async (
  tenantId: string,
  skillId: string,
  traceId?: string
) => {
  const skill = await prisma.skill.findUnique({
    where: { id: skillId },
    include: { children: true, assessments: true }
  });

  if (!skill) {
    throw new NotFoundError('Skill not found', { skillId }, traceId);
  }

  verifyTenantAccess(skill.tenantId, tenantId, traceId);
  return skill;
};

export const listSkills = async (
  tenantId: string,
  params: PaginationParams & { category?: string; parentId?: string }
): Promise<PaginatedResult<unknown>> => {
  const cacheKey = generateCacheKey('skills', tenantId, String(params.page), String(params.pageSize), params.category || 'all', params.parentId || 'root');
  const cached = await cache.get(cacheKey);
  if (cached) return cached as PaginatedResult<unknown>;

  const where: Record<string, unknown> = { tenantId };
  if (params.category) where.category = params.category;
  if (params.parentId) where.parentId = params.parentId;
  if (!params.parentId) where.parentId = null;

  const [total, items] = await Promise.all([
    prisma.skill.count({ where }),
    prisma.skill.findMany({
      where,
      skip: (params.page - 1) * params.pageSize,
      take: params.pageSize,
      orderBy: { name: 'asc' },
      include: { _count: { select: { children: true, assessments: true } } }
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

export const updateSkill = async (
  tenantId: string,
  skillId: string,
  data: Partial<SkillInput>,
  traceId?: string
) => {
  const skill = await prisma.skill.findUnique({ where: { id: skillId } });
  if (!skill) {
    throw new NotFoundError('Skill not found', { skillId }, traceId);
  }
  verifyTenantAccess(skill.tenantId, tenantId, traceId);

  const updated = await prisma.skill.update({
    where: { id: skillId },
    data
  });

  await cache.del(generateCacheKey('skills', tenantId));
  return updated;
};

export const deleteSkill = async (
  tenantId: string,
  skillId: string,
  traceId?: string
) => {
  const skill = await prisma.skill.findUnique({ where: { id: skillId } });
  if (!skill) {
    throw new NotFoundError('Skill not found', { skillId }, traceId);
  }
  verifyTenantAccess(skill.tenantId, tenantId, traceId);

  await withTransaction(async (tx) => {
    await tx.skillAssessment.deleteMany({ where: { skillId } });
    await tx.learningPathSkill.deleteMany({ where: { skillId } });
    await tx.ticketSkillRequirement.deleteMany({ where: { skillId } });
    await tx.skill.updateMany({ where: { parentId: skillId }, data: { parentId: null } });
    await tx.skill.delete({ where: { id: skillId } });
  });

  await cache.del(generateCacheKey('skills', tenantId));
};

export const createAgent = async (
  tenantId: string,
  data: AgentInput,
  traceId?: string
) => {
  verifyTenantAccess(tenantId, tenantId, traceId);

  const { skillIds, ...agentData } = data;

  return withTransaction(async (tx) => {
    const agent = await tx.agent.create({
      data: { ...agentData, tenantId }
    });

    if (skillIds && skillIds.length > 0) {
      await tx.agent.update({
        where: { id: agent.id },
        data: { skills: { connect: skillIds.map((id: string) => ({ id })) } }
      });
    }

    await cache.del(generateCacheKey('agents', tenantId));
    return agent;
  });
};

export const getAgentById = async (
  tenantId: string,
  agentId: string,
  traceId?: string
) => {
  const agent = await prisma.agent.findUnique({
    where: { id: agentId },
    include: { skills: true, assessments: true, tickets: true }
  });

  if (!agent) {
    throw new NotFoundError('Agent not found', { agentId }, traceId);
  }

  verifyTenantAccess(agent.tenantId, tenantId, traceId);
  return agent;
};

export const listAgents = async (
  tenantId: string,
  params: PaginationParams & { status?: string; skillId?: string }
): Promise<PaginatedResult<unknown>> => {
  const cacheKey = generateCacheKey('agents', tenantId, String(params.page), String(params.pageSize), params.status || 'all', params.skillId || 'all');
  const cached = await cache.get(cacheKey);
  if (cached) return cached as PaginatedResult<unknown>;

  const where: Record<string, unknown> = { tenantId };
  if (params.status) where.status = params.status;
  if (params.skillId) {
    where.skills = { some: { id: params.skillId } };
  }

  const [total, items] = await Promise.all([
    prisma.agent.count({ where }),
    prisma.agent.findMany({
      where,
      skip: (params.page - 1) * params.pageSize,
      take: params.pageSize,
      orderBy: { name: 'asc' },
      include: { skills: true, _count: { select: { tickets: true } } }
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

export const updateAgent = async (
  tenantId: string,
  agentId: string,
  data: Partial<AgentInput> & { skillIds?: string[] },
  traceId?: string
) => {
  const agent = await prisma.agent.findUnique({ where: { id: agentId } });
  if (!agent) {
    throw new NotFoundError('Agent not found', { agentId }, traceId);
  }
  verifyTenantAccess(agent.tenantId, tenantId, traceId);

  const { skillIds, ...updateData } = data;

  return withTransaction(async (tx) => {
    const updated = await tx.agent.update({
      where: { id: agentId },
      data: updateData
    });

    if (skillIds) {
      await tx.agent.update({
        where: { id: agentId },
        data: {
          skills: {
            set: skillIds.map((id: string) => ({ id }))
          }
        }
      });
    }

    await cache.del(generateCacheKey('agents', tenantId));
    eventBus.publish(EventTypes.AGENT_LOAD_UPDATED, { agentId, action: 'updated' }, { traceId, tenantId });

    return updated;
  });
};

export const deleteAgent = async (
  tenantId: string,
  agentId: string,
  traceId?: string
) => {
  const agent = await prisma.agent.findUnique({ where: { id: agentId } });
  if (!agent) {
    throw new NotFoundError('Agent not found', { agentId }, traceId);
  }
  verifyTenantAccess(agent.tenantId, tenantId, traceId);

  await withTransaction(async (tx) => {
    await tx.skillAssessment.deleteMany({ where: { agentId } });
    await tx.learningPath.deleteMany({ where: { agentId } });
    await tx.ticketAssignment.deleteMany({ where: { agentId } });
    await tx.ticket.updateMany({ where: { agentId }, data: { agentId: null, status: 'open' } });
    await tx.agent.delete({ where: { id: agentId } });
  });

  await cache.del(generateCacheKey('agents', tenantId));
};

export const createSkillAssessment = async (
  tenantId: string,
  data: SkillAssessmentInput,
  traceId?: string
) => {
  const [agent, skill] = await Promise.all([
    prisma.agent.findUnique({ where: { id: data.agentId } }),
    prisma.skill.findUnique({ where: { id: data.skillId } })
  ]);

  if (!agent) throw new NotFoundError('Agent not found', { agentId: data.agentId }, traceId);
  if (!skill) throw new NotFoundError('Skill not found', { skillId: data.skillId }, traceId);

  verifyTenantAccess(agent.tenantId, tenantId, traceId);
  verifyTenantAccess(skill.tenantId, tenantId, traceId);

  const assessment = await prisma.skillAssessment.upsert({
    where: { agentId_skillId: { agentId: data.agentId, skillId: data.skillId } },
    update: data,
    create: data
  });

  eventBus.publish(EventTypes.SKILL_ASSESSED, {
    agentId: data.agentId,
    skillId: data.skillId,
    level: data.level
  }, { traceId, tenantId });

  return assessment;
};

export const getAgentSkills = async (
  tenantId: string,
  agentId: string,
  traceId?: string
) => {
  const agent = await prisma.agent.findUnique({ where: { id: agentId } });
  if (!agent) throw new NotFoundError('Agent not found', { agentId }, traceId);
  verifyTenantAccess(agent.tenantId, tenantId, traceId);

  return prisma.skillAssessment.findMany({
    where: { agentId },
    include: { skill: true }
  });
};

export const getSkillTree = async (
  tenantId: string
) => {
  const cacheKey = generateCacheKey('skills', 'tree', tenantId);
  const cached = await cache.get(cacheKey);
  if (cached) return cached;

  const skills = await prisma.skill.findMany({
    where: tenantFilter(tenantId),
    orderBy: { level: 'asc' }
  });

  const rootSkills = skills.filter((s: { parentId?: string | null }) => !s.parentId);
  const skillMap = new Map(
    skills.map((s: { id: string; parentId?: string | null }) => [s.id, { ...s, children: [] as unknown[] }])
  );

  for (const skill of skills) {
    if (skill.parentId) {
      const parent = skillMap.get(skill.parentId);
      if (parent) {
        const parentWithChildren = parent as { children: unknown[] };
        parentWithChildren.children.push(skillMap.get(skill.id));
      }
    }
  }

  const tree = rootSkills.map((s: { id: string }) => skillMap.get(s.id));
  await cache.set(cacheKey, tree, TTL.MEDIUM);
  return tree;
};

export const generateLearningPath = async (
  tenantId: string,
  agentId: string,
  targetSkillIds: string[],
  traceId?: string
) => {
  const agent = await prisma.agent.findUnique({
    where: { id: agentId },
    include: { assessments: true }
  });

  if (!agent) throw new NotFoundError('Agent not found', { agentId }, traceId);
  verifyTenantAccess(agent.tenantId, tenantId, traceId);

  const currentSkills = new Map(
    agent.assessments.map((a: { skillId: string; level: number }) => [a.skillId, a.level])
  );
  const requiredSkills = await prisma.skill.findMany({
    where: { id: { in: targetSkillIds }, tenantId }
  });

  const learningPathSkills: Array<{ skillId: string; order: number; status: string }> = [];
  let order = 0;

  for (const targetSkill of requiredSkills) {
    const currentLevel = currentSkills.get(targetSkill.id) || 0;
    const requiredLevel = targetSkill.level;

    if (currentLevel < requiredLevel) {
      learningPathSkills.push({
        skillId: targetSkill.id,
        order: order++,
        status: 'pending'
      });
    }
  }

  return withTransaction(async (tx) => {
    const learningPath = await tx.learningPath.create({
      data: {
        agentId,
        name: `Learning Path - ${new Date().toLocaleDateString()}`,
        description: 'Generated learning path based on target skills',
        status: 'in_progress'
      }
    });

    for (const item of learningPathSkills) {
      await tx.learningPathSkill.create({
        data: {
          learningPathId: learningPath.id,
          ...item
        }
      });
    }

    return tx.learningPath.findUnique({
      where: { id: learningPath.id },
      include: { skills: { include: { skill: true }, orderBy: { order: 'asc' } } }
    });
  });
};

export const getLearningPaths = async (
  tenantId: string,
  agentId: string,
  traceId?: string
) => {
  const agent = await prisma.agent.findUnique({ where: { id: agentId } });
  if (!agent) throw new NotFoundError('Agent not found', { agentId }, traceId);
  verifyTenantAccess(agent.tenantId, tenantId, traceId);

  return prisma.learningPath.findMany({
    where: { agentId },
    include: { skills: { include: { skill: true }, orderBy: { order: 'asc' } } },
    orderBy: { createdAt: 'desc' }
  });
};

export const updateAgentLoad = async (
  agentId: string,
  load: number,
  traceId?: string
) => {
  const updated = await prisma.agent.update({
    where: { id: agentId },
    data: { currentLoad: load }
  });

  eventBus.publish(EventTypes.AGENT_LOAD_UPDATED, {
    agentId,
    currentLoad: load,
    maxLoad: updated.maxLoad
  }, { traceId });

  return updated;
};

export const recommendAgentsForSkill = async (
  tenantId: string,
  skillId: string,
  minLevel: number = 0,
  limit: number = 10
) => {
  return prisma.skillAssessment.findMany({
    where: {
      skillId,
      level: { gte: minLevel },
      agent: { tenantId, status: 'available' }
    },
    include: { agent: true, skill: true },
    orderBy: { level: 'desc' },
    take: limit
  });
};

export const getSkillGapAnalysis = async (
  tenantId: string,
  agentId: string,
  requiredSkillIds: string[],
  traceId?: string
) => {
  const agent = await prisma.agent.findUnique({
    where: { id: agentId },
    include: { assessments: { include: { skill: true } } }
  });

  if (!agent) throw new NotFoundError('Agent not found', { agentId }, traceId);
  verifyTenantAccess(agent.tenantId, tenantId, traceId);

  const currentSkills = new Map(
    agent.assessments.map((a: { skillId: string; level: number }) => [a.skillId, a.level])
  );
  const requiredSkills = await prisma.skill.findMany({
    where: { id: { in: requiredSkillIds }, tenantId }
  });

  const gaps = [];
  const strengths = [];

  for (const skill of requiredSkills) {
    const currentLevel = Number(currentSkills.get(skill.id)) || 0;
    const skillLevel = Number(skill.level) || 0;
    if (currentLevel < skillLevel) {
      gaps.push({
        skillId: skill.id,
        skillName: skill.name,
        requiredLevel: skillLevel,
        currentLevel,
        gap: skillLevel - currentLevel
      });
    } else {
      strengths.push({
        skillId: skill.id,
        skillName: skill.name,
        requiredLevel: skillLevel,
        currentLevel
      });
    }
  }

  return {
    agentId,
    agentName: agent.name,
    totalRequired: requiredSkills.length,
    gaps,
    strengths,
    coverage: strengths.length / requiredSkills.length
  };
};
