import { IAgentRepository } from '../../../../domain/skill/repositories/ISkillRepository';
import { Agent } from '../../../../domain/skill/entities/Agent';
import { UniqueEntityID } from '../../../../domain/shared/value-objects/UniqueEntityID';
import { IPaginationParams, IPaginatedResult } from '../../../../domain/shared/repositories/IRepository';
import { PrismaClient } from '@prisma/client';
import { getPrismaClient } from '../client/PrismaClient';
import { AgentMapper } from '../mappers/AgentMapper';
import { injectable } from 'tsyringe';

@injectable()
export class AgentRepository implements IAgentRepository {
  private readonly prisma: any;

  constructor() {
    this.prisma = getPrismaClient();
  }

  async findById(id: UniqueEntityID, tenantId: string): Promise<Agent | null> {
    const prismaAgent = await this.prisma.agent.findUnique({
      where: { id: id.value },
      include: {
        assessments: { include: { skill: true } }
      }
    });

    if (!prismaAgent || prismaAgent.tenantId !== tenantId) {
      return null;
    }

    return AgentMapper.toDomain(prismaAgent);
  }

  async findByIdWithSkills(id: UniqueEntityID, tenantId: string): Promise<Agent | null> {
    return this.findById(id, tenantId);
  }

  async findAll(tenantId: string, options?: { skip?: number; take?: number }): Promise<Agent[]> {
    const prismaAgents = await this.prisma.agent.findMany({
      where: { tenantId },
      include: {
        assessments: { include: { skill: true } }
      },
      skip: options?.skip,
      take: options?.take
    });

    return prismaAgents.map((a: any) => AgentMapper.toDomain(a));
  }

  async count(tenantId: string): Promise<number> {
    return this.prisma.agent.count({
      where: { tenantId }
    });
  }

  async save(agent: Agent, tenantId: string): Promise<Agent> {
    const agentData = AgentMapper.toPersistence(agent);

    const existing = await this.prisma.agent.findUnique({
      where: { id: agent.id.value }
    });

    let savedAgent;
    if (existing) {
      savedAgent = await this.prisma.agent.update({
        where: { id: agent.id.value },
        data: agentData,
        include: { assessments: { include: { skill: true } } }
      });

      await this.prisma.skillAssessment.deleteMany({
        where: { agentId: agent.id.value }
      });
    } else {
      savedAgent = await this.prisma.agent.create({
        data: agentData,
        include: { assessments: { include: { skill: true } } }
      });
    }

    const assessments = AgentMapper.toPersistenceAssessments(agent);
    for (const assessment of assessments) {
      await this.prisma.skillAssessment.create({
        data: assessment
      });
    }

    return AgentMapper.toDomain({
      ...savedAgent,
      assessments: assessments.map(a => ({
        id: crypto.randomUUID(),
        agentId: a.agentId,
        skillId: a.skillId,
        level: a.level,
        assessedAt: a.assessedAt,
        createdAt: new Date(),
        updatedAt: new Date(),
        skill: { id: a.skillId, name: '' }
      }))
    });
  }

  async delete(id: UniqueEntityID, tenantId: string): Promise<void> {
    await this.prisma.skillAssessment.deleteMany({
      where: { agentId: id.value }
    });
    await this.prisma.agent.delete({
      where: { id: id.value }
    });
  }

  async findAvailableAgents(tenantId: string, skillIds?: string[]): Promise<Agent[]> {
    const where: Record<string, unknown> = {
      tenantId,
      status: 'available'
    };

    if (skillIds && skillIds.length > 0) {
      where.assessments = {
        some: { skillId: { in: skillIds } }
      };
    }

    const prismaAgents = await this.prisma.agent.findMany({
      where,
      include: {
        assessments: { include: { skill: true } }
      }
    });

    return prismaAgents.map((a: any) => AgentMapper.toDomain(a));
  }

  async findByDepartment(tenantId: string, department: string): Promise<Agent[]> {
    const prismaAgents = await this.prisma.agent.findMany({
      where: { tenantId, department },
      include: {
        assessments: { include: { skill: true } }
      }
    });

    return prismaAgents.map((a: any) => AgentMapper.toDomain(a));
  }

  async findBySkill(tenantId: string, skillId: string, minLevel?: number): Promise<Agent[]> {
    const where: Record<string, unknown> = {
      tenantId,
      assessments: {
        some: {
          skillId,
          ...(minLevel !== undefined ? { level: { gte: minLevel } } : {})
        }
      }
    };

    const prismaAgents = await this.prisma.agent.findMany({
      where,
      include: {
        assessments: { include: { skill: true } }
      }
    });

    return prismaAgents.map((a: any) => AgentMapper.toDomain(a));
  }

  async findAllPaginated(
    tenantId: string,
    params: IPaginationParams & { status?: string; department?: string }
  ): Promise<IPaginatedResult<Agent>> {
    const where: Record<string, unknown> = { tenantId };
    if (params.status) where.status = params.status;
    if (params.department) where.department = params.department;

    const [total, prismaAgents] = await Promise.all([
      this.prisma.agent.count({ where }),
      this.prisma.agent.findMany({
        where,
        skip: (params.page - 1) * params.pageSize,
        take: params.pageSize,
        orderBy: { createdAt: 'desc' },
        include: { assessments: { include: { skill: true } } }
      })
    ]);

    return {
      items: prismaAgents.map((a: any) => AgentMapper.toDomain(a)),
      total,
      page: params.page,
      pageSize: params.pageSize,
      totalPages: Math.ceil(total / params.pageSize)
    };
  }
}
