import { IRepository, IPaginationParams, IPaginatedResult } from '../../shared/repositories/IRepository';
import { Skill } from '../entities/Skill';
import { Agent } from '../entities/Agent';
import { UniqueEntityID } from '../../shared/value-objects/UniqueEntityID';

export interface ISkillRepository extends IRepository<Skill> {
  findByCategory(tenantId: string, category: string): Promise<Skill[]>;
  findByName(tenantId: string, name: string): Promise<Skill | null>;
  findSkillTree(tenantId: string): Promise<Skill[]>;
  findByParentId(tenantId: string, parentId: string): Promise<Skill[]>;
}

export interface IAgentRepository extends IRepository<Agent> {
  findByIdWithSkills(id: UniqueEntityID, tenantId: string): Promise<Agent | null>;
  findAvailableAgents(tenantId: string, skillIds?: string[]): Promise<Agent[]>;
  findByDepartment(tenantId: string, department: string): Promise<Agent[]>;
  findBySkill(tenantId: string, skillId: string, minLevel?: number): Promise<Agent[]>;
  findAllPaginated(
    tenantId: string,
    params: IPaginationParams & { status?: string; department?: string }
  ): Promise<IPaginatedResult<Agent>>;
}

export interface ISkillAssessmentRepository {
  recordAssessment(
    agentId: UniqueEntityID,
    skillId: UniqueEntityID,
    level: number,
    assessedBy?: string
  ): Promise<void>;
  getAssessmentHistory(agentId: UniqueEntityID, skillId: UniqueEntityID): Promise<Array<{ level: number; assessedAt: Date; assessedBy?: string }>>;
}
