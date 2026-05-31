import { AggregateRoot } from '../../shared/entities/AggregateRoot';
import { UniqueEntityID } from '../../shared/value-objects/UniqueEntityID';

export interface SkillProps {
  name: string;
  description: string;
  category: string;
  level: number;
  parentId?: UniqueEntityID | null;
  tenantId: UniqueEntityID;
  createdAt?: Date;
  updatedAt?: Date;
}

export class Skill extends AggregateRoot<UniqueEntityID> {
  private _name: string;
  private _description: string;
  private _category: string;
  private _level: number;
  private _parentId: UniqueEntityID | null;
  private _tenantId: UniqueEntityID;

  private constructor(id: UniqueEntityID, props: SkillProps) {
    super(id);
    this._name = props.name;
    this._description = props.description;
    this._category = props.category;
    this._level = props.level;
    this._parentId = props.parentId ?? null;
    this._tenantId = props.tenantId;
    if (props.createdAt) this._createdAt = props.createdAt;
    if (props.updatedAt) this._updatedAt = props.updatedAt;
  }

  get name(): string {
    return this._name;
  }

  get description(): string {
    return this._description;
  }

  get category(): string {
    return this._category;
  }

  get level(): number {
    return this._level;
  }

  get parentId(): UniqueEntityID | null {
    return this._parentId;
  }

  get tenantId(): UniqueEntityID {
    return this._tenantId;
  }

  updateName(name: string): void {
    this._name = name;
    this.touch();
  }

  updateDescription(description: string): void {
    this._description = description;
    this.touch();
  }

  updateCategory(category: string): void {
    this._category = category;
    this.touch();
  }

  updateLevel(level: number): void {
    if (level < 1 || level > 5) {
      throw new Error('Skill level must be between 1 and 5');
    }
    this._level = level;
    this.touch();
  }

  setParent(parentId: UniqueEntityID | null): void {
    this._parentId = parentId;
    this.touch();
  }

  static create(props: Omit<SkillProps, 'parentId'> & { parentId?: string; id?: string }): Skill {
    return new Skill(UniqueEntityID.create(props.id), {
      ...props,
      parentId: props.parentId ? UniqueEntityID.create(props.parentId) : null
    });
  }
}
