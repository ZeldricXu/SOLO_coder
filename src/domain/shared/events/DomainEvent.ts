export interface DomainEvent<TData = unknown> {
  readonly id: string;
  readonly type: string;
  readonly aggregateId: string;
  readonly aggregateType: string;
  readonly data: TData;
  readonly occurredAt: Date;
  readonly version: number;
  readonly metadata?: Record<string, unknown>;
}

export abstract class DomainEventBase<TData = unknown> implements DomainEvent<TData> {
  readonly id: string;
  readonly occurredAt: Date;
  readonly version: number;

  constructor(
    readonly type: string,
    readonly aggregateId: string,
    readonly aggregateType: string,
    readonly data: TData,
    readonly metadata?: Record<string, unknown>
  ) {
    this.id = crypto.randomUUID();
    this.occurredAt = new Date();
    this.version = 1;
  }
}

export class TicketCreatedEvent extends DomainEventBase<{
  ticketId: string;
  tenantId: string;
  type: string;
  priority: string;
}> {
  constructor(
    aggregateId: string,
    data: { ticketId: string; tenantId: string; type: string; priority: string },
    metadata?: Record<string, unknown>
  ) {
    super('TICKET_CREATED', aggregateId, 'Ticket', data, metadata);
  }
}

export class TicketAssignedEvent extends DomainEventBase<{
  ticketId: string;
  agentId: string;
  reason: string;
  score?: number;
}> {
  constructor(
    aggregateId: string,
    data: { ticketId: string; agentId: string; reason: string; score?: number },
    metadata?: Record<string, unknown>
  ) {
    super('TICKET_ASSIGNED', aggregateId, 'Ticket', data, metadata);
  }
}

export class SLABreachEvent extends DomainEventBase<{
  ticketId: string;
  policyId: string;
  type: 'response' | 'resolution';
}> {
  constructor(
    aggregateId: string,
    data: { ticketId: string; policyId: string; type: 'response' | 'resolution' },
    metadata?: Record<string, unknown>
  ) {
    super('SLA_BREACHED', aggregateId, 'SLAInstance', data, metadata);
  }
}

export interface IEventBus {
  publish<T>(event: DomainEvent<T>): Promise<void>;
  publishMany<T>(events: DomainEvent<T>[]): Promise<void>;
  subscribe<T>(eventType: string, handler: (event: DomainEvent<T>) => Promise<void>): void;
  unsubscribe(eventType: string, handler: (event: DomainEvent<unknown>) => void): void;
}
