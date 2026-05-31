import { Event, Snapshot } from '../types';
import { logger } from '../logging';
import NodeCache from 'node-cache';

export interface EventStoreConfig {
  maxEventsPerAggregate?: number;
  snapshotInterval?: number;
  cacheTTL?: number;
}

export interface EventStream {
  aggregateId: string;
  events: Event[];
  version: number;
}

export interface Projection<T = any> {
  aggregateId: string;
  state: T;
  version: number;
  updatedAt: string;
}

export type EventHandler<T = any> = (state: T, event: Event) => T;

export class EventStore {
  private config: EventStoreConfig;
  private eventLog: Map<string, Event[]> = new Map();
  private snapshots: Map<string, Snapshot> = new Map();
  private projections: Map<string, Projection> = new Map();
  private eventCache: NodeCache;
  private handlers: Map<string, EventHandler> = new Map();

  constructor(config: EventStoreConfig = {}) {
    this.config = {
      maxEventsPerAggregate: 1000,
      snapshotInterval: 100,
      cacheTTL: 3600,
      ...config
    };
    this.eventCache = new NodeCache({ stdTTL: this.config.cacheTTL, checkperiod: 120 });
  }

  async appendEvent(event: Event): Promise<void> {
    const aggregateEvents = this.eventLog.get(event.aggregate_id) || [];
    
    if (aggregateEvents.length > 0) {
      const lastEvent = aggregateEvents[aggregateEvents.length - 1];
      if (event.version !== lastEvent.version + 1) {
        throw new Error(`Version conflict: expected ${lastEvent.version + 1}, got ${event.version}`);
      }
    }

    aggregateEvents.push(event);
    this.eventLog.set(event.aggregate_id, aggregateEvents);
    this.eventCache.del(event.aggregate_id);

    if (aggregateEvents.length % this.config.snapshotInterval! === 0) {
      await this.createSnapshot(event.aggregate_id);
    }
  }

  async getEvents(aggregateId: string, fromVersion: number = 0): Promise<Event[]> {
    const cacheKey = `${aggregateId}:${fromVersion}`;
    const cached = this.eventCache.get<Event[]>(cacheKey);
    if (cached) return cached;

    const events = this.eventLog.get(aggregateId) || [];
    const filtered = events.filter(e => e.version >= fromVersion);
    this.eventCache.set(cacheKey, filtered);
    return filtered;
  }

  async createSnapshot(aggregateId: string): Promise<Snapshot> {
    const events = this.eventLog.get(aggregateId) || [];
    if (events.length === 0) {
      throw new Error(`No events found for aggregate: ${aggregateId}`);
    }

    const lastEvent = events[events.length - 1];
    const snapshot: Snapshot = {
      snapshot_id: `snap_${Date.now()}_${Math.random().toString(36).substr(2, 9)}`,
      timestamp: new Date().toISOString(),
      metrics: { throughput: events.length, latency_p99: 0, error_rate: 0 },
      dimensions: { aggregateId, lastEventId: lastEvent.event_id, version: lastEvent.version.toString() }
    };

    this.snapshots.set(aggregateId, snapshot);
    return snapshot;
  }

  async getSnapshot(aggregateId: string): Promise<Snapshot | null> {
    return this.snapshots.get(aggregateId) || null;
  }

  async reconstructProjection<T>(
    aggregateId: string,
    initialState: T,
    handler: EventHandler<T>
  ): Promise<Projection<T>> {
    const snapshot = await this.getSnapshot(aggregateId);
    const fromVersion = snapshot ? parseInt(snapshot.dimensions.version) + 1 : 0;
    const events = await this.getEvents(aggregateId, fromVersion);

    let state: T = { ...initialState };
    let version = fromVersion;

    for (const event of events) {
      state = handler(state, event);
      version = event.version;
    }

    const projection: Projection<T> = {
      aggregateId,
      state,
      version,
      updatedAt: new Date().toISOString()
    };

    this.projections.set(aggregateId, projection);
    return projection;
  }

  async timeTravelQuery<T>(
    aggregateId: string,
    targetVersion: number,
    initialState: T,
    handler: EventHandler<T>
  ): Promise<T> {
    const events = await this.getEvents(aggregateId, 0);
    const filteredEvents = events.filter(e => e.version <= targetVersion);

    let state: T = { ...initialState };
    for (const event of filteredEvents) {
      state = handler(state, event);
    }

    return state;
  }

  async replayEvents(aggregateId: string, startVersion: number = 0): Promise<void> {
    const events = await this.getEvents(aggregateId, startVersion);
    for (const event of events) {
      for (const [, handler] of this.handlers) {
        try {
          handler(null as any, event);
        } catch (error) {
          logger.error('Error replaying event', error as Error, { eventId: event.event_id });
        }
      }
    }
  }

  registerHandler(eventType: string, handler: EventHandler): void {
    this.handlers.set(eventType, handler);
  }

  async getEventStream(aggregateId: string): Promise<EventStream> {
    const events = await this.getEvents(aggregateId);
    return {
      aggregateId,
      events,
      version: events.length > 0 ? events[events.length - 1].version : 0
    };
  }

  getStats(): { totalEvents: number; totalAggregates: number; totalSnapshots: number } {
    let totalEvents = 0;
    for (const events of this.eventLog.values()) {
      totalEvents += events.length;
    }
    return {
      totalEvents,
      totalAggregates: this.eventLog.size,
      totalSnapshots: this.snapshots.size
    };
  }
}

export const createEventStore = (config?: EventStoreConfig): EventStore => {
  return new EventStore(config);
};
