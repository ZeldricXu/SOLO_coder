import { Agent, AgentSkill } from '../../../../domain/skill/entities/Agent';
import { AgentLoad } from '../../../../domain/skill/value-objects/AgentLoad';
import { SkillLevel } from '../../../../domain/skill/value-objects/SkillLevel';
import { UniqueEntityID } from '../../../../domain/shared/value-objects/UniqueEntityID';

export class AgentMapper {
  static toDomain(prismaAgent: any): Agent {
    const skills: AgentSkill[] = (prismaAgent.assessments || []).map((assessment: any) => ({
      skillId: UniqueEntityID.create(assessment.skillId),
      skillName: assessment.skill?.name || '',
      level: SkillLevel.create(assessment.level),
      lastAssessedAt: assessment.assessedAt
    }));

    return Agent.create({
      id: prismaAgent.id,
      name: prismaAgent.name,
      email: prismaAgent.email,
      status: prismaAgent.status as any,
      tenantId: UniqueEntityID.create(prismaAgent.tenantId),
      department: prismaAgent.department || undefined,
      skills: skills.map((s: AgentSkill) => ({
        skillId: s.skillId.value,
        skillName: s.skillName,
        level: s.level.value
      })),
      maxLoad: prismaAgent.maxLoad
    });
  }

  static toPersistence(agent: Agent): {
    id: string;
    name: string;
    email: string;
    status: string;
    tenantId: string;
    department: string | null;
    currentLoad: number;
    maxLoad: number;
    createdAt: Date;
    updatedAt: Date;
  } {
    return {
      id: agent.id.value,
      name: agent.name,
      email: agent.email,
      status: agent.status,
      tenantId: agent.tenantId.value,
      department: agent.department || null,
      currentLoad: agent.load.currentLoad,
      maxLoad: agent.load.maxLoad,
      createdAt: agent.createdAt,
      updatedAt: agent.updatedAt
    };
  }

  static toPersistenceAssessments(agent: Agent): Array<{
    agentId: string;
    skillId: string;
    level: number;
    assessedAt: Date;
  }> {
    return agent.skills.map((skill: AgentSkill) => ({
      agentId: agent.id.value,
      skillId: skill.skillId.value,
      level: skill.level.value,
      assessedAt: skill.lastAssessedAt
    }));
  }
}
