import { EventEmitter } from 'events';
import { IEventHandler } from '@ports/index';
import { rootLogger } from '@modules/logging';

export class EventBus implements IEventHandler {
  private logger = rootLogger.child({ module: 'EventBus' });
  private emitter: EventEmitter = new EventEmitter();

  constructor() {
    this.emitter.setMaxListeners(100);
  }

  emit(event: string, data: Record<string, unknown>): void {
    this.logger.debug('Emitting event', { event });
    this.emitter.emit(event, data);
  }

  on(event: string, handler: (data: Record<string, unknown>) => void): void {
    this.logger.debug('Registering event listener', { event });
    this.emitter.on(event, handler);
  }

  once(event: string, handler: (data: Record<string, unknown>) => void): void {
    this.logger.debug('Registering one-time event listener', { event });
    this.emitter.once(event, handler);
  }

  off(event: string, handler: (data: Record<string, unknown>) => void): void {
    this.logger.debug('Removing event listener', { event });
    this.emitter.off(event, handler);
  }

  listenerCount(event: string): number {
    return this.emitter.listenerCount(event);
  }

  eventNames(): string[] {
    return this.emitter.eventNames() as string[];
  }

  removeAllListeners(event?: string): void {
    if (event) {
      this.emitter.removeAllListeners(event);
      this.logger.debug('Removed all listeners for event', { event });
    } else {
      this.emitter.removeAllListeners();
      this.logger.debug('Removed all event listeners');
    }
  }
}

export const eventBus = new EventBus();
