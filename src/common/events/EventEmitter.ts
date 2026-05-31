export type EventHandler<T = unknown> = (event: T) => void | Promise<void>;

export interface Event<T = unknown> {
  type: string;
  timestamp: string;
  payload: T;
  metadata?: Record<string, unknown>;
}

export class EventBus {
  private handlers: Map<string, Set<EventHandler>> = new Map();

  on<T>(eventType: string, handler: EventHandler<T>): void {
    if (!this.handlers.has(eventType)) {
      this.handlers.set(eventType, new Set());
    }
    this.handlers.get(eventType)!.add(handler as EventHandler);
  }

  off<T>(eventType: string, handler: EventHandler<T>): void {
    const eventHandlers = this.handlers.get(eventType);
    if (eventHandlers) {
      eventHandlers.delete(handler as EventHandler);
    }
  }

  async emit<T>(eventType: string, payload: T, metadata?: Record<string, unknown>): Promise<void> {
    const event: Event<T> = {
      type: eventType,
      timestamp: new Date().toISOString(),
      payload,
      metadata
    };

    const eventHandlers = this.handlers.get(eventType);
    if (eventHandlers) {
      await Promise.all(
        Array.from(eventHandlers).map(handler =>
          Promise.resolve(handler(event)).catch(error => {
            console.error(`Event handler error for ${eventType}:`, error);
          })
        )
      );
    }

    const wildcardHandlers = this.handlers.get('*');
    if (wildcardHandlers) {
      await Promise.all(
        Array.from(wildcardHandlers).map(handler =>
          Promise.resolve(handler(event)).catch(error => {
            console.error(`Wildcard event handler error:`, error);
          })
        )
      );
    }
  }

  clear(): void {
    this.handlers.clear();
  }

  getHandlerCount(eventType?: string): number {
    if (eventType) {
      return this.handlers.get(eventType)?.size || 0;
    }
    return Array.from(this.handlers.values()).reduce((sum, set) => sum + set.size, 0);
  }
}

export const eventBus = new EventBus();
