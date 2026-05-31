import { EventEmitter } from 'events';
import logger from './logger';

export type EventHandler = (payload: any) => void | Promise<void>;

class EventBus {
  private emitter: EventEmitter;

  constructor() {
    this.emitter = new EventEmitter();
    this.emitter.setMaxListeners(100);
  }

  on(event: string, handler: EventHandler): void {
    this.emitter.on(event, async (payload) => {
      try {
        await handler(payload);
      } catch (error) {
        logger.error(`Event handler error for ${event}`, { error });
      }
    });
  }

  emit(event: string, payload: any): void {
    this.emitter.emit(event, payload);
    logger.debug('Event emitted', { event, payload: typeof payload });
  }

  off(event: string, handler: EventHandler): void {
    this.emitter.off(event, handler);
  }

  once(event: string, handler: EventHandler): void {
    this.emitter.once(event, handler);
  }
}

export const eventBus = new EventBus();
