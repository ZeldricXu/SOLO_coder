export type EventType = 
  | 'note:created'
  | 'note:updated'
  | 'note:deleted'
  | 'note:content-changed'
  | 'note:title-changed'
  | 'note:tags-changed'
  | 'index:update'
  | 'index:rebuild'
  | 'sync:status-changed'
  | 'sync:conflict-detected'
  | 'tag:created'
  | 'tag:updated'
  | 'tag:deleted'
  | 'folder:created'
  | 'folder:updated'
  | 'folder:deleted';

export interface EventPayload {
  type: EventType;
  timestamp: number;
  data?: unknown;
  source?: string;
}

export interface NoteEventData {
  note_id: string;
  title?: string;
  content?: string;
  tags?: string[];
  folder_id?: string | null;
  old_values?: {
    title?: string;
    content?: string;
    tags?: string[];
  };
}

export interface IndexEventData {
  note_id: string;
  operation: 'create' | 'update' | 'delete';
  fields?: string[];
}

export type EventCallback<T = unknown> = (payload: EventPayload & { data: T }) => void;

export interface Subscription {
  id: string;
  eventType: EventType | EventType[];
  callback: EventCallback;
  once?: boolean;
}

export interface EventBusOptions {
  maxListeners?: number;
  enableLogging?: boolean;
  batchWindowMs?: number;
  maxBatchSize?: number;
}

const DEFAULT_OPTIONS: EventBusOptions = {
  maxListeners: 100,
  enableLogging: false,
  batchWindowMs: 50,
  maxBatchSize: 50,
};

export class EventBus {
  private static instance: EventBus;
  private listeners: Map<EventType, Map<string, EventCallback>> = new Map();
  private options: EventBusOptions;
  private batchTimer: Map<EventType, NodeJS.Timeout> = new Map();
  private batchQueue: Map<EventType, EventPayload[]> = new Map();
  private listenerCount: number = 0;

  private constructor(options: EventBusOptions = DEFAULT_OPTIONS) {
    this.options = { ...DEFAULT_OPTIONS, ...options };
  }

  public static getInstance(options?: EventBusOptions): EventBus {
    if (!EventBus.instance) {
      EventBus.instance = new EventBus(options);
    }
    return EventBus.instance;
  }

  on<T = unknown>(eventType: EventType, callback: EventCallback<T>): Subscription {
    return this.addListener(eventType, callback, false);
  }

  once<T = unknown>(eventType: EventType, callback: EventCallback<T>): Subscription {
    return this.addListener(eventType, callback, true);
  }

  off(subscription: Subscription): void {
    const eventTypes = Array.isArray(subscription.eventType) 
      ? subscription.eventType 
      : [subscription.eventType];

    for (const type of eventTypes) {
      const callbacks = this.listeners.get(type);
      if (callbacks) {
        callbacks.delete(subscription.id);
        if (callbacks.size === 0) {
          this.listeners.delete(type);
        }
        this.listenerCount--;
      }
    }
  }

  emit<T = unknown>(eventType: EventType, data?: T, source?: string): void {
    const payload: EventPayload = {
      type: eventType,
      timestamp: Date.now(),
      data,
      source,
    };

    if (this.options.enableLogging) {
      console.log(`[EventBus] Emitting: ${eventType}`, data);
    }

    const callbacks = this.listeners.get(eventType);
    if (callbacks) {
      const toRemove: string[] = [];
      
      callbacks.forEach((callback, id) => {
        try {
          callback(payload as EventPayload & { data: T });
          
          const sub = this.findSubscriptionById(id);
          if (sub?.once) {
            toRemove.push(id);
          }
        } catch (error) {
          console.error(`[EventBus] Error in listener for ${eventType}:`, error);
        }
      });

      for (const id of toRemove) {
        callbacks.delete(id);
        this.listenerCount--;
      }
    }
  }

  emitBatched<T extends IndexEventData>(eventType: EventType, data: T): void {
    if (!this.batchQueue.has(eventType)) {
      this.batchQueue.set(eventType, []);
    }

    const queue = this.batchQueue.get(eventType)!;
    queue.push({
      type: eventType,
      timestamp: Date.now(),
      data,
    });

    if (queue.length >= (this.options.maxBatchSize || 50)) {
      this.flushBatch(eventType);
    } else if (!this.batchTimer.has(eventType)) {
      const timer = setTimeout(() => {
        this.flushBatch(eventType);
      }, this.options.batchWindowMs || 50);
      
      this.batchTimer.set(eventType, timer);
    }
  }

  private flushBatch(eventType: EventType): void {
    const timer = this.batchTimer.get(eventType);
    if (timer) {
      clearTimeout(timer);
      this.batchTimer.delete(eventType);
    }

    const queue = this.batchQueue.get(eventType);
    if (!queue || queue.length === 0) return;

    const callbacks = this.listeners.get(eventType);
    if (callbacks) {
      const batchedData = {
        events: queue,
        count: queue.length,
      };

      const batchedPayload: EventPayload = {
        type: eventType,
        timestamp: Date.now(),
        data: batchedData,
      };

      callbacks.forEach((callback) => {
        try {
          callback(batchedPayload as EventPayload & { data: unknown });
        } catch (error) {
          console.error(`[EventBus] Error in batched listener for ${eventType}:`, error);
        }
      });
    }

    this.batchQueue.set(eventType, []);
  }

  private addListener<T = unknown>(
    eventType: EventType,
    callback: EventCallback<T>,
    once: boolean
  ): Subscription {
    if (this.listenerCount >= (this.options.maxListeners || 100)) {
      console.warn(
        `[EventBus] Max listeners (${this.options.maxListeners}) reached. ` +
        'Consider increasing maxListeners or removing unused listeners.'
      );
    }

    const id = this.generateId();
    const subscription: Subscription = {
      id,
      eventType,
      callback: callback as EventCallback,
      once,
    };

    if (!this.listeners.has(eventType)) {
      this.listeners.set(eventType, new Map());
    }

    this.listeners.get(eventType)!.set(id, callback as EventCallback);
    this.listenerCount++;

    return subscription;
  }

  private findSubscriptionById(id: string): Subscription | undefined {
    for (const [, callbacks] of this.listeners) {
      if (callbacks.has(id)) {
        return {
          id,
          eventType: 'note:created' as EventType,
          callback: callbacks.get(id)!,
        };
      }
    }
    return undefined;
  }

  private generateId(): string {
    return `sub_${Date.now()}_${Math.random().toString(36).substr(2, 9)}`;
  }

  listenerCountFor(eventType: EventType): number {
    const callbacks = this.listeners.get(eventType);
    return callbacks ? callbacks.size : 0;
  }

  totalListenerCount(): number {
    return this.listenerCount;
  }

  removeAllListeners(eventType?: EventType): void {
    if (eventType) {
      const callbacks = this.listeners.get(eventType);
      if (callbacks) {
        this.listenerCount -= callbacks.size;
        this.listeners.delete(eventType);
      }
    } else {
      this.listeners.clear();
      this.listenerCount = 0;
    }
  }

  destroy(): void {
    for (const [, timer] of this.batchTimer) {
      clearTimeout(timer);
    }
    this.batchTimer.clear();
    this.batchQueue.clear();
    this.removeAllListeners();
  }
}

export const eventBus = EventBus.getInstance();

export const createNoteEmitter = {
  noteCreated: (noteId: string, data: Partial<NoteEventData>) => {
    eventBus.emit('note:created', { note_id: noteId, ...data });
  },
  
  noteUpdated: (noteId: string, updates: Partial<NoteEventData>) => {
    eventBus.emit('note:updated', { note_id: noteId, ...updates });
    
    if (updates.content || updates.old_values?.content) {
      eventBus.emit('note:content-changed', { 
        note_id: noteId, 
        content: updates.content,
        old_values: { content: updates.old_values?.content }
      });
    }
    
    if (updates.title || updates.old_values?.title) {
      eventBus.emit('note:title-changed', {
        note_id: noteId,
        title: updates.title,
        old_values: { title: updates.old_values?.title }
      });
    }
    
    if (updates.tags) {
      eventBus.emit('note:tags-changed', {
        note_id: noteId,
        tags: updates.tags,
        old_values: { tags: updates.old_values?.tags }
      });
    }
  },
  
  noteDeleted: (noteId: string) => {
    eventBus.emit('note:deleted', { note_id: noteId });
  },
  
  indexUpdate: (noteId: string, operation: IndexEventData['operation'], fields?: string[]) => {
    eventBus.emitBatched('index:update', {
      note_id: noteId,
      operation,
      fields,
    });
  },
};

export default EventBus;
