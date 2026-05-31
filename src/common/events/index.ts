import { EventEmitter } from 'events';
import { v4 as uuidv4 } from 'uuid';
import { Event as IEvent, ProcessingContext } from '../types';

type EventHandler<T = unknown> = (event: IEvent<T>) => Promise<void> | void;

class EventBus {
  private emitter: EventEmitter;
  private handlers: Map<string, Set<EventHandler>>;

  constructor() {
    this.emitter = new EventEmitter();
    this.emitter.setMaxListeners(100);
    this.handlers = new Map();
  }

  publish<T = unknown>(
    type: string,
    data: T,
    context?: Partial<ProcessingContext>,
    source: string = 'system'
  ): IEvent<T> {
    const event: IEvent<T> = {
      id: uuidv4(),
      type,
      source,
      timestamp: new Date(),
      data,
      traceId: context?.traceId || uuidv4()
    };

    this.emitter.emit(type, event);
    this.emitter.emit('*', event);

    return event;
  }

  subscribe<T = unknown>(type: string, handler: EventHandler<T>): () => void {
    if (!this.handlers.has(type)) {
      this.handlers.set(type, new Set());
    }
    this.handlers.get(type)!.add(handler as EventHandler);
    this.emitter.on(type, handler as EventHandler);

    return () => {
      this.unsubscribe(type, handler);
    };
  }

  unsubscribe<T = unknown>(type: string, handler: EventHandler<T>): void {
    const handlers = this.handlers.get(type);
    if (handlers) {
      handlers.delete(handler as EventHandler);
    }
    this.emitter.off(type, handler as EventHandler);
  }

  subscribeAll<T = unknown>(handler: EventHandler<T>): () => void {
    this.emitter.on('*', handler as EventHandler);
    return () => {
      this.emitter.off('*', handler as EventHandler);
    };
  }

  once<T = unknown>(type: string, handler: EventHandler<T>): void {
    this.emitter.once(type, handler as EventHandler);
  }

  async publishAsync<T = unknown>(
    type: string,
    data: T,
    context?: Partial<ProcessingContext>,
    source: string = 'system'
  ): Promise<IEvent<T>> {
    const event = this.publish(type, data, context, source);
    
    const handlers = this.handlers.get(type);
    if (handlers) {
      await Promise.allSettled(
        Array.from(handlers).map(h => Promise.resolve(h(event)))
      );
    }

    const globalHandlers = this.handlers.get('*');
    if (globalHandlers) {
      await Promise.allSettled(
        Array.from(globalHandlers).map(h => Promise.resolve(h(event)))
      );
    }

    return event;
  }

  getEventCount(type?: string): number {
    if (type) {
      return this.handlers.get(type)?.size ?? 0;
    }
    return Array.from(this.handlers.values()).reduce((sum, set) => sum + set.size, 0);
  }

  getRegisteredEvents(): string[] {
    return Array.from(this.handlers.keys());
  }

  removeAllListeners(type?: string): void {
    if (type) {
      this.emitter.removeAllListeners(type);
      this.handlers.delete(type);
    } else {
      this.emitter.removeAllListeners();
      this.handlers.clear();
    }
  }
}

const eventBus = new EventBus();

export const getEventBus = (): EventBus => eventBus;

export const EventTypes = {
  TICKET_CREATED: 'ticket.created',
  TICKET_ASSIGNED: 'ticket.assigned',
  TICKET_UPDATED: 'ticket.updated',
  TICKET_RESOLVED: 'ticket.resolved',
  TICKET_CLOSED: 'ticket.closed',
  
  USAGE_RECORDED: 'usage.recorded',
  INVOICE_GENERATED: 'invoice.generated',
  INVOICE_PAID: 'invoice.paid',
  
  SLA_WARNING: 'sla.warning',
  SLA_BREACHED: 'sla.breached',
  SLA_MET: 'sla.met',
  
  APPROVAL_REQUESTED: 'approval.requested',
  APPROVAL_COMPLETED: 'approval.completed',
  
  PROCESS_STARTED: 'process.started',
  PROCESS_COMPLETED: 'process.completed',
  PROCESS_FAILED: 'process.failed',
  
  AGENT_LOAD_UPDATED: 'agent.load.updated',
  SKILL_ASSESSED: 'skill.assessed',
  
  DOCUMENT_COMPARED: 'document.compared',
  TENANT_CONFIG_UPDATED: 'tenant.config.updated',
  
  METRICS_RECORDED: 'metrics.recorded',
  ERROR_OCCURRED: 'error.occurred'
} as const;

export type EventType = typeof EventTypes[keyof typeof EventTypes];
