import { IEventBusPort } from '../../../application/shared/ports/IEventBusPort';
import { DomainEvent } from '../../../domain/shared/events/DomainEvent';
import { injectable } from 'tsyringe';

type EventHandler<T = unknown> = (event: DomainEvent<T>) => Promise<void>;

@injectable()
export class InMemoryEventBus implements IEventBusPort {
  private subscribers: Map<string, EventHandler[]> = new Map();

  async publish<T>(event: DomainEvent<T>): Promise<void> {
    const handlers = this.subscribers.get(event.type) || [];
    await Promise.allSettled(handlers.map(handler => handler(event)));
  }

  async publishMany<T>(events: DomainEvent<T>[]): Promise<void> {
    await Promise.all(events.map(event => this.publish(event)));
  }

  subscribe<T>(eventType: string, handler: (event: DomainEvent<T>) => Promise<void>): void {
    if (!this.subscribers.has(eventType)) {
      this.subscribers.set(eventType, []);
    }
    this.subscribers.get(eventType)!.push(handler as EventHandler);
  }

  unsubscribe<T>(eventType: string, handler: (event: DomainEvent<T>) => Promise<void>): void {
    const handlers = this.subscribers.get(eventType);
    if (handlers) {
      const index = handlers.indexOf(handler as EventHandler);
      if (index > -1) {
        handlers.splice(index, 1);
      }
    }
  }

  async publishAggregateEvents(aggregate: { domainEvents: DomainEvent<unknown>[] }): Promise<void> {
    const events = aggregate.domainEvents;
    for (const event of events) {
      await this.publish(event);
    }
  }
}
