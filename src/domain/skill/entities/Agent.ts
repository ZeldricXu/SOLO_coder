import { AggregateRoot } from '../../shared/entities/AggregateRoot';
import { UniqueEntityID } from '../../shared/value-objects/UniqueEntityID';
import { AgentLoad } from '../value-objects/AgentLoad';
import { SkillLevel } from '../value-objects/SkillLevel';

export interface AgentSkill {
  skillId: UniqueEntityID;
  skillName: string;
  level: SkillLevel;
  lastAssessedAt: Date;
}

export interface AgentProps {
  name: string;
  email: string;
  status: 'available' | 'busy' | 'offline';
  tenantId: UniqueEntityID;
  department?: string;
  skills: AgentSkill[];
  load: AgentLoad;
  createdAt?: Date;
  updatedAt?: Date;
}

export class Agent extends AggregateRoot<UniqueEntityID> {
  private _name: string;
  private _email: string;
  private _status: 'available' | 'busy' | 'offline';
  private _tenantId: UniqueEntityID;
  private _department?: string;
  private _skills: Map<string, AgentSkill>;
  private _load: AgentLoad;

  private constructor(id: UniqueEntityID, props: AgentProps) {
    super(id);
    this._name = props.name;
    this._email = props.email;
    this._status = props.status;
    this._tenantId = props.tenantId;
    this._department = props.department;
    this._skills = new Map(props.skills.map(s => [s.skillId.value, s]));
    this._load = props.load;
    if (props.createdAt) this._createdAt = props.createdAt;
    if (props.updatedAt) this._updatedAt = props.updatedAt;
  }

  get name(): string {
    return this._name;
  }

  get email(): string {
    return this._email;
  }

  get status(): 'available' | 'busy' | 'offline' {
    return this._status;
  }

  get tenantId(): UniqueEntityID {
    return this._tenantId;
  }

  get department(): string | undefined {
    return this._department;
  }

  get load(): AgentLoad {
    return this._load;
  }

  get skills(): readonly AgentSkill[] {
    return Array.from(this._skills.values());
  }

  getSkillLevel(skillId: UniqueEntityID): number {
    const skill = this._skills.get(skillId.value);
    return skill ? skill.level.value : 0;
  }

  hasSkill(skillId: UniqueEntityID, minLevel: number = 1): boolean {
    const skill = this._skills.get(skillId.value);
    return skill ? skill.level.isSufficientFor(minLevel) : false;
  }

  setStatus(status: 'available' | 'busy' | 'offline'): void {
    this._status = status;
    this.addSimpleDomainEvent('AGENT_STATUS_UPDATED', {
      agentId: this.id.value,
      status
    });
    this.touch();
  }

  assignWork(): void {
    if (!this._load.canAcceptMoreWork()) {
      this._status = 'busy';
    }
    this._load = this._load.increment();
    this.addSimpleDomainEvent('AGENT_LOAD_UPDATED', {
      agentId: this.id.value,
      currentLoad: this._load.currentLoad,
      maxLoad: this._load.maxLoad
    });
    this.touch();
  }

  completeWork(): void {
    this._load = this._load.decrement();
    if (this._load.canAcceptMoreWork() && this._status === 'busy') {
      this._status = 'available';
    }
    this.addSimpleDomainEvent('AGENT_LOAD_UPDATED', {
      agentId: this.id.value,
      currentLoad: this._load.currentLoad,
      maxLoad: this._load.maxLoad
    });
    this.touch();
  }

  updateSkill(skillId: UniqueEntityID, level: number, skillName: string): void {
    const skillLevel = SkillLevel.create(level);
    this._skills.set(skillId.value, {
      skillId,
      skillName,
      level: skillLevel,
      lastAssessedAt: new Date()
    });
    this.addSimpleDomainEvent('AGENT_SKILL_UPDATED', {
      agentId: this.id.value,
      skillId: skillId.value,
      level
    });
    this.touch();
  }

  removeSkill(skillId: UniqueEntityID): void {
    this._skills.delete(skillId.value);
    this.touch();
  }

  isAvailableForAssignment(): boolean {
    return this._status === 'available' && this._load.canAcceptMoreWork();
  }

  calculateSkillMatchScore(
    requiredSkills: Array<{ skillId: UniqueEntityID; minLevel: number }>
  ): number {
    if (requiredSkills.length === 0) return 1;

    let totalMatch = 0;
    for (const req of requiredSkills) {
      const agentLevel = this.getSkillLevel(req.skillId);
      const matchPercentage = req.minLevel > 0 ? Math.min(1, agentLevel / req.minLevel) : 1;
      totalMatch += matchPercentage;
    }

    return totalMatch / requiredSkills.length;
  }

  static create(props: Omit<AgentProps, 'load' | 'skills'> & {
    maxLoad?: number;
    skills?: Array<{ skillId: string; skillName: string; level: number }>;
    id?: string;
  }): Agent {
    const skills: AgentSkill[] = (props.skills || []).map(s => ({
      skillId: UniqueEntityID.create(s.skillId),
      skillName: s.skillName,
      level: SkillLevel.create(s.level),
      lastAssessedAt: new Date()
    }));

    return new Agent(UniqueEntityID.create(props.id), {
      ...props,
      skills,
      load: AgentLoad.create(0, props.maxLoad || 10)
    });
  }
}
