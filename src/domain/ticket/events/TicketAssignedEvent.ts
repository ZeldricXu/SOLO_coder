import { DomainEventBase } from '../../shared/events/DomainEvent';

export interface TicketAssignedEventData {
  ticketId: string;
  tenantId: string;
  agentId: string;
  previousAgentId?: string;
  assignedBy: string;
  priority: string;
  assignedAt?: Date;
}

export class TicketAssignedEvent extends DomainEventBase<TicketAssignedEventData> {
  public static readonly TYPE = 'TICKET_ASSIGNED';

  private constructor(
    aggregateId: string,
    data: TicketAssignedEventData,
    metadata?: Record<string, unknown>
  ) {
    super(TicketAssignedEvent.TYPE, aggregateId, 'Ticket', data, metadata);
  }

  static create(
    data: Omit<TicketAssignedEventData, 'assignedAt'>,
    occurredAt?: Date
  ): TicketAssignedEvent {
    return new TicketAssignedEvent(
      data.ticketId,
      {
        ...data,
        assignedAt: occurredAt || new Date()
      }
    );
  }
}
