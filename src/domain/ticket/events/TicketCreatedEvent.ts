import { DomainEventBase } from '../../shared/events/DomainEvent';

export interface TicketCreatedEventData {
  ticketId: string;
  tenantId: string;
  title: string;
  priority: string;
  requiredSkills: Array<{ skillId: string; minLevel: number }>;
  createdAt?: Date;
}

export class TicketCreatedEvent extends DomainEventBase<TicketCreatedEventData> {
  public static readonly TYPE = 'TICKET_CREATED';

  private constructor(
    aggregateId: string,
    data: TicketCreatedEventData,
    metadata?: Record<string, unknown>
  ) {
    super(TicketCreatedEvent.TYPE, aggregateId, 'Ticket', data, metadata);
  }

  static create(
    data: Omit<TicketCreatedEventData, 'createdAt'>,
    occurredAt?: Date
  ): TicketCreatedEvent {
    return new TicketCreatedEvent(
      data.ticketId,
      {
        ...data,
        createdAt: occurredAt || new Date()
      }
    );
  }
}
