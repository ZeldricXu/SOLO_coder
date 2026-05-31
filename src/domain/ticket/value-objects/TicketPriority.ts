import { ValueObject } from '../../shared/value-objects/ValueObject';

export type TicketPriorityType = 'low' | 'medium' | 'high' | 'urgent';

interface TicketPriorityProps {
  value: TicketPriorityType;
}

export class TicketPriority extends ValueObject<TicketPriorityProps, TicketPriorityType> {
  private constructor(priority: TicketPriorityType) {
    super({ value: priority });
  }

  protected validate(props: TicketPriorityProps): void {
    const validPriorities: TicketPriorityType[] = ['low', 'medium', 'high', 'urgent'];
    if (!validPriorities.includes(props.value)) {
      throw new Error(`Invalid ticket priority: ${props.value}`);
    }
  }

  get value(): TicketPriorityType {
    return this.props.value;
  }

  get numericValue(): number {
    const values: Record<TicketPriorityType, number> = {
      low: 1,
      medium: 2,
      high: 3,
      urgent: 4
    };
    return values[this.props.value];
  }

  isHigherThan(other: TicketPriority): boolean {
    return this.numericValue > other.numericValue;
  }

  static create(priority: TicketPriorityType = 'medium'): TicketPriority {
    return new TicketPriority(priority);
  }
}
