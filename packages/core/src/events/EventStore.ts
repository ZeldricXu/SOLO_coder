import {
  GameEvent,
  EventStoreConfig,
  EventFilter,
  EventSubscriber,
  GameStateSnapshot,
  EventType,
  MoveEventData,
  AttackEventData,
  SkillEventData,
  StatusEffectEventData,
  DeathEventData,
  TerrainEventData,
  DelayedSkillEventData,
} from '../types';
import type { ID } from '../types/common';
import { generateId, createChecksum, deepClone, toJSON, fromJSON } from '../utils';
import type { EventBus } from './EventBus';

const DEFAULT_CONFIG: EventStoreConfig = {
  maxEvents: 10000,
  enableCompression: true,
  enableSnapshots: true,
  snapshotInterval: 100,
  persistenceAdapter: 'memory',
};

export class EventStore {
  private events: GameEvent[] = [];
  private subscribers: EventSubscriber[] = [];
  private snapshots: GameStateSnapshot[] = [];
  private config: EventStoreConfig;
  private _eventBus: EventBus | null = null;
  private appendingFromBus: boolean = false;

  constructor(config: Partial<EventStoreConfig> = {}) {
    this.config = { ...DEFAULT_CONFIG, ...config };
  }

  get eventBus(): EventBus | null {
    return this._eventBus;
  }

  setEventBus(eventBus: EventBus | null): void {
    this._eventBus = eventBus;

    if (eventBus) {
      const bus = eventBus as EventBus & { setEventStore?: (store: EventStore) => void };
      if (bus.getEventStore?.() !== this) {
        bus.setEventStore?.(this);
      }
    }
  }

  append<T = unknown>(event: Omit<GameEvent, 'id' | 'timestamp' | 'version'> & { data?: T }): GameEvent {
    const fullEvent: GameEvent = {
      id: generateId(),
      type: event.type,
      timestamp: Date.now(),
      turnNumber: event.turnNumber,
      data: (event.data ?? {}) as Record<string, unknown>,
      metadata: event.metadata ?? {},
      version: 1,
    };

    this.events.push(fullEvent);

    if (this.config.enableCompression && this.events.length > this.config.maxEvents) {
      this.compress();
    }

    if (this.config.enableSnapshots && this.events.length % this.config.snapshotInterval === 0) {
      this.createSnapshot();
    }

    this.notifySubscribers(fullEvent);

    if (this._eventBus && !this.appendingFromBus) {
      const bus = this._eventBus as EventBus & { setEmittingFromStore?: (v: boolean) => void; publishFromStore?: (e: GameEvent) => void };
      bus.setEmittingFromStore?.(true);
      try {
        bus.publishFromStore?.(fullEvent);
      } finally {
        bus.setEmittingFromStore?.(false);
      }
    }

    return fullEvent;
  }

  appendFromBus<T = unknown>(event: Omit<GameEvent, 'id' | 'timestamp' | 'version'> & { data?: T }): GameEvent {
    this.appendingFromBus = true;
    try {
      return this.append(event);
    } finally {
      this.appendingFromBus = false;
    }
  }

  query(filter: EventFilter): GameEvent[] {
    return this.events.filter((event) => this.matchesFilter(event, filter));
  }

  subscribe(
    filter: EventFilter,
    callback: (event: GameEvent) => void,
    options: { priority?: number; once?: boolean } = {}
  ): ID {
    const subscriber: EventSubscriber = {
      id: generateId(),
      filter,
      callback,
      once: options.once ?? false,
      priority: options.priority ?? 0,
    };

    this.subscribers.push(subscriber);
    this.subscribers.sort((a, b) => b.priority - a.priority);

    return subscriber.id;
  }

  unsubscribe(subscriberId: ID): boolean {
    const index = this.subscribers.findIndex((s) => s.id === subscriberId);
    if (index === -1) return false;
    this.subscribers.splice(index, 1);
    return true;
  }

  createSnapshot(state: unknown = {}): GameStateSnapshot {
    const lastEvent = this.events[this.events.length - 1];
    const stateStr = JSON.stringify(state);
    const snapshot: GameStateSnapshot = {
      id: generateId(),
      eventId: lastEvent?.id ?? '',
      eventIndex: this.events.length - 1,
      turnNumber: lastEvent?.turnNumber ?? 0,
      state: deepClone(state),
      timestamp: Date.now(),
      checksum: createChecksum(stateStr),
    };

    this.snapshots.push(snapshot);
    return snapshot;
  }

  getEvents(startIndex?: number, endIndex?: number): GameEvent[] {
    const start = startIndex ?? 0;
    const end = endIndex ?? this.events.length;
    return deepClone(this.events.slice(start, end));
  }

  getSnapshots(): GameStateSnapshot[] {
    return deepClone(this.snapshots);
  }

  clear(): void {
    this.events = [];
    this.snapshots = [];
  }

  compress(): void {
    if (this.events.length <= this.config.maxEvents) return;

    const overflow = this.events.length - this.config.maxEvents;
    const keepFrom = Math.floor(overflow * 1.5);

    const nearestSnapshot = this.findNearestSnapshot(keepFrom);
    if (nearestSnapshot) {
      this.events = this.events.slice(nearestSnapshot.eventIndex + 1);
      this.snapshots = this.snapshots.filter((s) => s.eventIndex >= nearestSnapshot.eventIndex);
      this.snapshots.forEach((s) => {
        s.eventIndex -= nearestSnapshot.eventIndex + 1;
      });
    } else {
      this.events = this.events.slice(overflow);
    }
  }

  private findNearestSnapshot(eventIndex: number): GameStateSnapshot | null {
    if (this.snapshots.length === 0) return null;

    let nearest: GameStateSnapshot | null = null;
    let minDistance = Infinity;

    for (const snapshot of this.snapshots) {
      const distance = Math.abs(snapshot.eventIndex - eventIndex);
      if (distance < minDistance && snapshot.eventIndex <= eventIndex) {
        minDistance = distance;
        nearest = snapshot;
      }
    }

    return nearest;
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

  private notifySubscribers(event: GameEvent): void {
    const toRemove: ID[] = [];

    for (const subscriber of this.subscribers) {
      if (this.matchesFilter(event, subscriber.filter)) {
        try {
          subscriber.callback(event);
        } catch (e) {
          console.error('Subscriber callback error:', e);
        }
        if (subscriber.once) {
          toRemove.push(subscriber.id);
        }
      }
    }

    for (const id of toRemove) {
      this.unsubscribe(id);
    }
  }

  getConfig(): EventStoreConfig {
    return deepClone(this.config);
  }

  getEventCount(): number {
    return this.events.length;
  }

  getEventById(id: ID): GameEvent | undefined {
    return this.events.find((e) => e.id === id);
  }

  toJSON(): Record<string, unknown> {
    return {
      events: this.events,
      snapshots: this.snapshots,
      config: this.config,
    };
  }

  static fromJSON(data: Record<string, unknown>): EventStore {
    const store = new EventStore(data.config as Partial<EventStoreConfig>);
    store.events = deepClone(data.events as GameEvent[]);
    store.snapshots = deepClone(data.snapshots as GameStateSnapshot[]);
    return store;
  }

  serialize(): string {
    return toJSON(this.toJSON());
  }

  static deserialize(json: string): EventStore {
    const data = fromJSON<Record<string, unknown>>(json);
    return EventStore.fromJSON(data);
  }
}

export type {
  MoveEventData,
  AttackEventData,
  SkillEventData,
  StatusEffectEventData,
  DeathEventData,
  TerrainEventData,
  DelayedSkillEventData,
};
