import { ValueObject } from './ValueObject';
import { randomUUID } from 'crypto';

interface UniqueEntityIDProps {
  value: string;
}

export class UniqueEntityID extends ValueObject<UniqueEntityIDProps, string> {
  private constructor(id?: string) {
    super({ value: id || randomUUID() });
  }

  protected validate(props: UniqueEntityIDProps): void {
    if (!props.value || typeof props.value !== 'string') {
      throw new Error('Invalid entity ID');
    }
  }

  get value(): string {
    return this.props.value;
  }

  toString(): string {
    return this.props.value;
  }

  static create(id?: string): UniqueEntityID {
    return new UniqueEntityID(id);
  }
}
