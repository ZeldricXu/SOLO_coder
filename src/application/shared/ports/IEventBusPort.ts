import { DomainEvent } from '../../../domain/shared/events/DomainEvent';

export interface IEventBusPort {
  publish<T>(event: DomainEvent<T>): Promise<void>;
  publishMany<T>(events: DomainEvent<T>[]): Promise<void>;
  subscribe<T>(eventType: string, handler: (event: DomainEvent<T>) => Promise<void>): void;
  unsubscribe<T>(eventType: string, handler: (event: DomainEvent<T>) => Promise<void>): void;
  publishAggregateEvents(aggregate: { domainEvents: DomainEvent<unknown>[] }): Promise<void>;
}

export const EVENT_BUS_PORT = Symbol('IEventBusPort');
