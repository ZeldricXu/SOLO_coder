import { ValueObject } from '../../shared/value-objects/ValueObject';

interface SkillLevelProps {
  value: number;
}

export class SkillLevel extends ValueObject<SkillLevelProps, number> {
  private constructor(level: number) {
    super({ value: level });
  }

  protected validate(props: SkillLevelProps): void {
    if (props.value < 0 || props.value > 5) {
      throw new Error('Skill level must be between 0 and 5');
    }
    if (!Number.isInteger(props.value)) {
      throw new Error('Skill level must be an integer');
    }
  }

  get value(): number {
    return this.props.value;
  }

  isSufficientFor(requiredLevel: number): boolean {
    return this.props.value >= requiredLevel;
  }

  getDescription(): string {
    const descriptions: Record<number, string> = {
      0: '无经验',
      1: '初学者',
      2: '有一定经验',
      3: '熟练',
      4: '高级',
      5: '专家'
    };
    return descriptions[this.props.value] || '未知';
  }

  static create(level: number = 1): SkillLevel {
    return new SkillLevel(level);
  }
}
