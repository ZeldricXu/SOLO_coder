import { ValueObject } from '../../shared/value-objects/ValueObject';
import { UniqueEntityID } from '../../shared/value-objects/UniqueEntityID';

interface SkillRequirementProps {
  skillId: UniqueEntityID;
  minLevel: number;
  required: boolean;
}

export class SkillRequirement extends ValueObject<SkillRequirementProps> {
  private constructor(props: SkillRequirementProps) {
    super(props);
  }

  protected validate(props: SkillRequirementProps): void {
    if (!props.skillId) {
      throw new Error('Skill ID is required');
    }
    if (props.minLevel < 0 || props.minLevel > 5) {
      throw new Error('Skill level must be between 0 and 5');
    }
  }

  get skillId(): UniqueEntityID {
    return this.props.skillId;
  }

  get minLevel(): number {
    return this.props.minLevel;
  }

  get required(): boolean {
    return this.props.required;
  }

  isSatisfiedBy(agentLevel: number): boolean {
    return agentLevel >= this.props.minLevel;
  }

  static create(skillId: string, minLevel: number = 1, required: boolean = true): SkillRequirement {
    return new SkillRequirement({
      skillId: UniqueEntityID.create(skillId),
      minLevel,
      required
    });
  }
}
