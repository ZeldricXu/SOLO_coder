import type {
  EventType,
  GameEvent,
  EventFilter,
  EventPayloadMap,
  EventData,
} from '../types';
import type { ID } from '../types/common';
import { generateId } from '../utils';
import type { EventStore } from './EventStore';

export interface EventBusSubscribeOptions {
  priority?: number;
  once?: boolean;
  subscriberId?: ID;
  group?: string;
}

export interface EventBusSubscriber<T extends EventType = EventType> {
  id: ID;
  eventType: T | '*';
  filter?: EventFilter;
  handler: (event: GameEvent & { data: EventData<T> }) => void;
  priority: number;
  once: boolean;
  group?: string;
}

export class EventBus {
  private subscribers: Map<string, EventBusSubscriber[]>;
  private allSubscribers: EventBusSubscriber[];
  private eventStore: EventStore | null;
  private _isPaused: boolean;
  private currentTurn: number;
  private emittingFromStore: boolean;

  constructor(eventStore?: EventStore) {
    this.subscribers = new Map();
    this.allSubscribers = [];
    this.eventStore = eventStore ?? null;
    this._isPaused = false;
    this.currentTurn = 0;
    this.emittingFromStore = false;
  }

  subscribe<T extends EventType>(
    eventType: T | EventFilter,
    handler: (event: GameEvent & { data: EventData<T> }) => void,
    options: EventBusSubscribeOptions = {}
  ): ID {
    const { priority = 0, once = false, subscriberId, group } = options;
    const id = subscriberId ?? generateId();

    let filter: EventFilter | undefined;
    let typeKey: string;

    if (typeof eventType === 'string') {
      typeKey = eventType;
    } else {
      filter = eventType;
      typeKey = '*';
    }

    const subscriber: EventBusSubscriber<T> = {
      id,
      eventType: typeKey as T | '*',
      filter,
      handler,
      priority,
      once,
      group,
    };

    if (!this.subscribers.has(typeKey)) {
      this.subscribers.set(typeKey, []);
    }
    const list = this.subscribers.get(typeKey)!;
    list.push(subscriber as unknown as EventBusSubscriber);
    list.sort((a, b) => b.priority - a.priority);

    this.allSubscribers.push(subscriber as unknown as EventBusSubscriber);

    return id;
  }

  unsubscribe(subscriberId: ID): boolean {
    let found = false;

    for (const [, list] of this.subscribers) {
      const index = list.findIndex(s => s.id === subscriberId);
      if (index !== -1) {
        list.splice(index, 1);
        found = true;
        break;
      }
    }

    const allIndex = this.allSubscribers.findIndex(s => s.id === subscriberId);
    if (allIndex !== -1) {
      this.allSubscribers.splice(allIndex, 1);
      found = true;
    }

    return found;
  }

  unsubscribeGroup(group: string): number {
    const groupSubscribers = this.allSubscribers.filter(s => s.group === group);
    for (const sub of groupSubscribers) {
      this.unsubscribe(sub.id);
    }
    return groupSubscribers.length;
  }

  publish<T extends EventType>(
    eventOrType: T | GameEvent,
    data?: EventData<T>,
    metadata?: GameEvent['metadata']
  ): GameEvent {
    let event: GameEvent;

    if (typeof eventOrType === 'string') {
      event = {
        id: generateId(),
        type: eventOrType,
        timestamp: Date.now(),
        turnNumber: this.currentTurn,
        data: (data ?? {}) as Record<string, unknown>,
        metadata: metadata ?? {},
        version: 1,
      };
    } else {
      event = eventOrType;
    }

    if (this.eventStore && !this.emittingFromStore) {
      const store = this.eventStore as EventStore & { appendFromBus?: (e: GameEvent) => GameEvent };
      if (store.appendFromBus) {
        store.appendFromBus(event);
      } else {
        this.eventStore.append(event);
      }
    }

    if (this._isPaused) {
      return event;
    }

    this.dispatchEvent(event);

    return event;
  }

  private dispatchEvent(event: GameEvent): void {
    const toRemove: ID[] = [];

    const typeSubscribers = this.subscribers.get(event.type) ?? [];
    const wildcardSubscribers = this.subscribers.get('*') ?? [];

    const allMatching = [...typeSubscribers, ...wildcardSubscribers];
    allMatching.sort((a, b) => b.priority - a.priority);

    for (const subscriber of allMatching) {
      if (subscriber.filter && !this.matchesFilter(event, subscriber.filter)) {
        continue;
      }

      try {
        subscriber.handler(event);
      } catch (e) {
        console.error('EventBus subscriber error:', e);
      }

      if (subscriber.once) {
        toRemove.push(subscriber.id);
      }
    }

    for (const id of toRemove) {
      this.unsubscribe(id);
    }
  }

  private matchesFilter(event: GameEvent, filter: EventFilter): boolean {
    if (filter.types && !filter.types.includes(event.type)) return false;
    if (filter.sources && event.metadata.source && !filter.sources.includes(event.metadata.source)) return false;
    if (filter.targets && event.metadata.target && !filter.targets.includes(event.metadata.target)) return false;
    if (filter.factions && event.metadata.faction && !filter.factions.includes(event.metadata.faction)) return false;
    if (filter.turnRange) {
      const [minTurn, maxTurn] = filter.turnRange;
      if (event.turnNumber < minTurn || event.turnNumber > maxTurn) return false;
    }
    if (filter.timestampRange) {
      const [minTime, maxTime] = filter.timestampRange;
      if (event.timestamp < minTime || event.timestamp > maxTime) return false;
    }
    if (filter.customFilter && !filter.customFilter(event)) return false;
    return true;
  }

  once<T extends EventType>(
    eventType: T,
    handler: (event: GameEvent & { data: EventData<T> }) => void,
    options?: Omit<EventBusSubscribeOptions, 'once'>
  ): ID {
    return this.subscribe(eventType, handler, { ...options, once: true });
  }

  waitFor<T extends EventType>(
    eventType: T,
    timeout?: number
  ): Promise<GameEvent & { data: EventData<T> }> {
    return new Promise((resolve, reject) => {
      let timeoutId: ReturnType<typeof setTimeout> | null = null;

      const subscriberId = this.once(eventType, (event) => {
        if (timeoutId) {
          clearTimeout(timeoutId);
        }
        resolve(event);
      });

      if (timeout !== undefined) {
        timeoutId = setTimeout(() => {
          this.unsubscribe(subscriberId);
          reject(new Error(`Timeout waiting for event: ${eventType}`));
        }, timeout);
      }
    });
  }

  pause(): void {
    this._isPaused = true;
  }

  resume(): void {
    this._isPaused = false;
  }

  isPaused(): boolean {
    return this._isPaused;
  }

  clear(): void {
    this.subscribers.clear();
    this.allSubscribers = [];
  }

  getSubscriberCount(eventType?: EventType): number {
    if (eventType) {
      return this.subscribers.get(eventType)?.length ?? 0;
    }
    return this.allSubscribers.length;
  }

  setEventStore(eventStore: EventStore | null): void {
    this.eventStore = eventStore;

    if (eventStore) {
      (eventStore as EventStore & { setEventBus?: (bus: EventBus) => void }).setEventBus?.(this);
    }
  }

  getEventStore(): EventStore | null {
    return this.eventStore;
  }

  setCurrentTurn(turn: number): void {
    this.currentTurn = turn;
  }

  getCurrentTurn(): number {
    return this.currentTurn;
  }

  setEmittingFromStore(value: boolean): void {
    this.emittingFromStore = value;
  }

  publishFromStore(event: GameEvent): void {
    if (this._isPaused) return;
    this.dispatchEvent(event);
  }
}
