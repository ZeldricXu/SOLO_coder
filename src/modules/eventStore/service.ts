import { PrismaClient, Event as DbEvent, Snapshot as DbSnapshot } from '@prisma/client';
import { generateEventId, generateSnapshotId } from '../../utils/idGenerator';
import { NotFoundError, ConflictError } from '../../utils/errors';
import type { CreateEventRequest, CreateSnapshotRequest, Event, Snapshot, ProjectionResult, ReplayResult, TimeTravelQueryRequest } from './types';
import type { PaginationParams, PaginatedResult } from '../../types';
import logger from '../../utils/logger';

const prisma = new PrismaClient();

const toEvent = (db: DbEvent): Event => ({
  eventId: db.eventId,
  eventType: db.eventType,
  aggregateId: db.aggregateId,
  version: db.version,
  payload: db.payload as Record<string, unknown>,
  metadata: db.metadata as Record<string, unknown> | undefined,
  timestamp: db.timestamp,
});

const toSnapshot = (db: DbSnapshot): Snapshot => ({
  snapshotId: db.snapshotId,
  aggregateId: (db as unknown as { aggregateId: string }).aggregateId,
  version: (db as unknown as { version: number }).version,
  state: db.metrics as Record<string, unknown>,
  metadata: (db as unknown as { metadata?: Record<string, unknown> }).metadata,
  createdAt: db.timestamp,
});

export const appendEvent = async (data: CreateEventRequest): Promise<Event> => {
  const lastEvent = await prisma.event.findFirst({
    where: { aggregateId: data.aggregateId },
    orderBy: { version: 'desc' },
  });

  if (lastEvent && data.version !== lastEvent.version + 1) {
    throw new ConflictError(`Expected version ${lastEvent.version + 1}, got ${data.version}`);
  }

  const event = await prisma.event.create({
    data: {
      eventId: generateEventId(),
      eventType: data.eventType,
      aggregateId: data.aggregateId,
      version: data.version,
      payload: data.payload,
      metadata: data.metadata,
      resourceId: 'res_' + data.aggregateId,
    },
  });

  logger.info({ eventId: event.eventId, eventType: data.eventType, aggregateId: data.aggregateId }, 'Event appended');
  return toEvent(event);
};

export const getEvent = async (eventId: string): Promise<Event> => {
  const event = await prisma.event.findUnique({ where: { eventId } });
  if (!event) throw new NotFoundError(`Event ${eventId} not found`);
  return toEvent(event);
};

export const listEvents = async (params: PaginationParams, aggregateId?: string, eventType?: string): Promise<PaginatedResult<Event>> => {
  const where: Record<string, unknown> = {};
  if (aggregateId) where.aggregateId = aggregateId;
  if (eventType) where.eventType = eventType;

  const [items, total] = await Promise.all([
    prisma.event.findMany({
      where,
      skip: (params.page - 1) * params.pageSize,
      take: params.pageSize,
      orderBy: { timestamp: 'asc' },
    }),
    prisma.event.count({ where }),
  ]);
  return {
    items: items.map(toEvent),
    total,
    page: params.page,
    pageSize: params.pageSize,
    totalPages: Math.ceil(total / params.pageSize),
  };
};

export const getEventsByAggregate = async (aggregateId: string, fromVersion?: number, toVersion?: number): Promise<Event[]> => {
  const where: Record<string, unknown> = { aggregateId };
  if (fromVersion !== undefined) {
    where.version = { ...(where.version as object || {}), gte: fromVersion };
  }
  if (toVersion !== undefined) {
    where.version = { ...(where.version as object || {}), lte: toVersion };
  }

  const events = await prisma.event.findMany({
    where,
    orderBy: { version: 'asc' },
  });
  return events.map(toEvent);
};

export const createSnapshot = async (data: CreateSnapshotRequest): Promise<Snapshot> => {
  const snapshot = await prisma.snapshot.create({
    data: {
      snapshotId: generateSnapshotId(),
      timestamp: new Date(),
      metrics: data.state,
      dimensions: {
        aggregateId: data.aggregateId,
        version: data.version,
        metadata: data.metadata || {},
      },
    },
  });

  logger.info({ snapshotId: snapshot.snapshotId, aggregateId: data.aggregateId, version: data.version }, 'Snapshot created');
  return toSnapshot(snapshot);
};

export const getSnapshot = async (snapshotId: string): Promise<Snapshot> => {
  const snapshot = await prisma.snapshot.findUnique({ where: { snapshotId } });
  if (!snapshot) throw new NotFoundError(`Snapshot ${snapshotId} not found`);
  return toSnapshot(snapshot);
};

export const getLatestSnapshot = async (aggregateId: string, maxVersion?: number): Promise<Snapshot | null> => {
  const where: Record<string, unknown> = {
    dimensions: {
      path: ['aggregateId'],
      equals: aggregateId,
    },
  };

  const snapshots = await prisma.snapshot.findMany({
    orderBy: { timestamp: 'desc' },
    take: 10,
  });

  const matchingSnapshots = snapshots.filter(s => {
    const dims = s.dimensions as { aggregateId?: string; version?: number };
    if (dims.aggregateId !== aggregateId) return false;
    if (maxVersion !== undefined && dims.version !== undefined && dims.version > maxVersion) return false;
    return true;
  });

  if (matchingSnapshots.length === 0) return null;
  return toSnapshot(matchingSnapshots[0]);
};

export const listSnapshots = async (params: PaginationParams, aggregateId?: string): Promise<PaginatedResult<Snapshot>> => {
  let snapshots = await prisma.snapshot.findMany({
    skip: (params.page - 1) * params.pageSize,
    take: params.pageSize,
    orderBy: { timestamp: 'desc' },
  });

  if (aggregateId) {
    snapshots = snapshots.filter(s => {
      const dims = s.dimensions as { aggregateId?: string };
      return dims.aggregateId === aggregateId;
    });
  }

  const total = await prisma.snapshot.count();

  return {
    items: snapshots.map(toSnapshot),
    total,
    page: params.page,
    pageSize: params.pageSize,
    totalPages: Math.ceil(total / params.pageSize),
  };
};

const applyEvent = (state: Record<string, unknown>, event: Event): Record<string, unknown> => {
  return {
    ...state,
    ...event.payload,
    _lastEvent: {
      eventId: event.eventId,
      eventType: event.eventType,
      version: event.version,
      timestamp: event.timestamp,
    },
  };
};

export const buildProjection = async (aggregateId: string, upToVersion?: number, upToTimestamp?: Date): Promise<ProjectionResult> => {
  const latestSnapshot = await getLatestSnapshot(aggregateId, upToVersion);
  let state: Record<string, unknown> = latestSnapshot?.state || {};
  let startVersion = latestSnapshot?.version || 0;
  let eventsApplied = 0;

  const where: Record<string, unknown> = { aggregateId };
  if (upToVersion !== undefined) {
    where.version = { lte: upToVersion, gt: startVersion };
  } else {
    where.version = { gt: startVersion };
  }

  let events = await prisma.event.findMany({
    where,
    orderBy: { version: 'asc' },
  });

  if (upToTimestamp) {
    events = events.filter(e => e.timestamp <= upToTimestamp);
  }

  for (const event of events) {
    state = applyEvent(state, toEvent(event));
    eventsApplied++;
  }

  const lastEvent = events.length > 0 ? events[events.length - 1] : null;
  const finalVersion = lastEvent?.version || startVersion;

  return {
    aggregateId,
    version: finalVersion,
    state,
    asOf: new Date(),
    eventsApplied,
  };
};

export const timeTravelQuery = async (params: TimeTravelQueryRequest): Promise<ProjectionResult> => {
  const timestamp = new Date(params.timestamp);
  return buildProjection(params.aggregateId, undefined, timestamp);
};

export const replayEvents = async (params: {
  aggregateId: string;
  fromVersion?: number;
  toVersion?: number;
  fromTimestamp?: Date;
  toTimestamp?: Date;
}): Promise<ReplayResult> => {
  let events = await getEventsByAggregate(params.aggregateId, params.fromVersion, params.toVersion);

  if (params.fromTimestamp) {
    events = events.filter(e => e.timestamp >= params.fromTimestamp!);
  }
  if (params.toTimestamp) {
    events = events.filter(e => e.timestamp <= params.toTimestamp!);
  }

  let state: Record<string, unknown> = {};
  for (const event of events) {
    state = applyEvent(state, event);
  }

  return {
    aggregateId: params.aggregateId,
    events,
    finalState: state,
    fromVersion: params.fromVersion,
    toVersion: params.toVersion,
  };
};

export const deleteEventsByAggregate = async (aggregateId: string): Promise<number> => {
  const result = await prisma.event.deleteMany({ where: { aggregateId } });
  logger.info({ aggregateId, count: result.count }, 'Events deleted');
  return result.count;
};

export const getAggregateVersions = async (): Promise<Array<{ aggregateId: string; latestVersion: number; eventCount: number }>> => {
  const events = await prisma.event.findMany();
  const aggregates: Record<string, { latestVersion: number; eventCount: number }> = {};

  for (const event of events) {
    if (!aggregates[event.aggregateId]) {
      aggregates[event.aggregateId] = { latestVersion: 0, eventCount: 0 };
    }
    aggregates[event.aggregateId].eventCount++;
    if (event.version > aggregates[event.aggregateId].latestVersion) {
      aggregates[event.aggregateId].latestVersion = event.version;
    }
  }

  return Object.entries(aggregates).map(([aggregateId, data]) => ({
    aggregateId,
    latestVersion: data.latestVersion,
    eventCount: data.eventCount,
  }));
};

export default {
  appendEvent,
  getEvent,
  listEvents,
  getEventsByAggregate,
  createSnapshot,
  getSnapshot,
  getLatestSnapshot,
  listSnapshots,
  buildProjection,
  timeTravelQuery,
  replayEvents,
  deleteEventsByAggregate,
  getAggregateVersions,
};
