import EventEmitter from 'events';
import { LoggerContext } from './logger';

export interface DomainEvent<T = unknown> {
  id: string;
  type: string;
  source: string;
  timestamp: string;
  data: T;
  metadata?: Record<string, unknown>;
}

export class EventBus {
  private emitter: EventEmitter;
  private logger: LoggerContext;
  private listeners: Map<string, Set<string>>;

  constructor() {
    this.emitter = new EventEmitter();
    this.emitter.setMaxListeners(100);
    this.logger = new LoggerContext({ module: 'EventBus' });
    this.listeners = new Map();
  }

  on<T = unknown>(event: string, listener: (data: T) => void | Promise<void>): string {
    const listenerId = `${event}_${Date.now()}_${Math.random().toString(36).substr(2, 9)}`;
    const wrappedListener = async (data: T) => {
      try {
        this.logger.debug('Event received', { event, listenerId });
        await listener(data);
      } catch (error) {
        this.logger.error('Event listener error', error as Error, { event, listenerId });
      }
    };
    this.emitter.on(event, wrappedListener);
    if (!this.listeners.has(event)) {
      this.listeners.set(event, new Set());
    }
    this.listeners.get(event)!.add(listenerId);
    return listenerId;
  }

  off(event: string, listenerId: string): boolean {
    const eventListeners = this.listeners.get(event);
    if (!eventListeners || !eventListeners.has(listenerId)) {
      return false;
    }
    eventListeners.delete(listenerId);
    return true;
  }

  emit<T = unknown>(event: string, data: T): boolean {
    this.logger.debug('Emitting event', { event, listenerCount: this.emitter.listenerCount(event) });
    return this.emitter.emit(event, data);
  }

  emitDomainEvent<T = unknown>(event: DomainEvent<T>): boolean {
    this.logger.debug('Emitting domain event', {
      type: event.type,
      source: event.source,
      listenerCount: this.emitter.listenerCount(event.type),
    });
    return this.emitter.emit(event.type, event);
  }

  once<T = unknown>(event: string, listener: (data: T) => void | Promise<void>): void {
    this.emitter.once(event, async (data: T) => {
      try {
        await listener(data);
      } catch (error) {
        this.logger.error('Event once listener error', error as Error, { event });
      }
    });
  }

  removeAllListeners(event?: string): void {
    if (event) {
      this.emitter.removeAllListeners(event);
      this.listeners.delete(event);
    } else {
      this.emitter.removeAllListeners();
      this.listeners.clear();
    }
  }

  listenerCount(event: string): number {
    return this.emitter.listenerCount(event);
  }

  getEvents(): string[] {
    return Array.from(this.listeners.keys());
  }
}

export const eventBus = new EventBus();

export const EVENTS = {
  PROPOSAL_CREATED: 'proposal:created',
  PROPOSAL_SIGNED: 'proposal:signed',
  PROPOSAL_EXECUTED: 'proposal:executed',
  WALLET_CONFIG_UPDATED: 'wallet:config_updated',
  PROOF_VERIFIED: 'proof:verified',
  PROOF_STRATEGY_CHANGED: 'proof:strategy_changed',
  PROOF_STRATEGY_REGISTERED: 'proof:strategy_registered',
  PROOF_STRATEGY_UNREGISTERED: 'proof:strategy_unregistered',
  CONTRACT_EVENT: 'contract:event',
  LISTENER_CREATED: 'listener:created',
  LISTENER_STARTED: 'listener:started',
  LISTENER_STOPPED: 'listener:stopped',
  LISTENER_DELETED: 'listener:deleted',
  TRANSACTION_SIGNED: 'transaction:signed',
  CROSS_CHAIN_MESSAGE: 'crosschain:message',
  ADDRESS_DERIVED: 'address:derived',
  STORAGE_PINNED: 'storage:pinned',
  BLOCK_INDEXED: 'block:indexed',
  GAS_ESTIMATED: 'gas:estimated',
  ERROR: 'system:error',
  METRICS: 'system:metrics',
} as const;
