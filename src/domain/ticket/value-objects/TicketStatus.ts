import { ValueObject } from '../../shared/value-objects/ValueObject';

export type TicketStatusType = 'open' | 'assigned' | 'in_progress' | 'pending' | 'resolved' | 'closed';

interface TicketStatusProps {
  value: TicketStatusType;
}

export class TicketStatus extends ValueObject<TicketStatusProps, TicketStatusType> {
  private constructor(status: TicketStatusType) {
    super({ value: status });
  }

  protected validate(props: TicketStatusProps): void {
    const validStatuses: TicketStatusType[] = ['open', 'assigned', 'in_progress', 'pending', 'resolved', 'closed'];
    if (!validStatuses.includes(props.value)) {
      throw new Error(`Invalid ticket status: ${props.value}`);
    }
  }

  get value(): TicketStatusType {
    return this.props.value;
  }

  canTransitionTo(newStatus: TicketStatusType): boolean {
    const transitions: Record<TicketStatusType, TicketStatusType[]> = {
      open: ['assigned', 'closed'],
      assigned: ['in_progress', 'pending'],
      in_progress: ['pending', 'resolved', 'assigned'],
      pending: ['in_progress', 'resolved'],
      resolved: ['closed', 'in_progress'],
      closed: []
    };
    return transitions[this.props.value]?.includes(newStatus) || false;
  }

  isActive(): boolean {
    return ['open', 'assigned', 'in_progress', 'pending'].includes(this.props.value);
  }

  isCompleted(): boolean {
    return ['resolved', 'closed'].includes(this.props.value);
  }

  static create(status: TicketStatusType = 'open'): TicketStatus {
    return new TicketStatus(status);
  }
}
