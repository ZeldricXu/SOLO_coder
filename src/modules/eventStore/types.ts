import { z } from 'zod';
import type { EventType } from '../../types';

export const EventSchema = z.object({
  eventType: z.string().min(1),
  aggregateId: z.string().min(1),
  version: z.number().int().nonnegative(),
  payload: z.record(z.unknown()),
  metadata: z.record(z.unknown()).optional(),
});

export const SnapshotSchema = z.object({
  aggregateId: z.string().min(1),
  version: z.number().int().nonnegative(),
  state: z.record(z.unknown()),
  metadata: z.record(z.unknown()).optional(),
});

export const TimeTravelQuerySchema = z.object({
  aggregateId: z.string().min(1),
  timestamp: z.string().datetime(),
});

export const ReplayEventsSchema = z.object({
  aggregateId: z.string().min(1),
  fromVersion: z.number().int().nonnegative().optional(),
  toVersion: z.number().int().nonnegative().optional(),
  fromTimestamp: z.string().datetime().optional(),
  toTimestamp: z.string().datetime().optional(),
});

export type CreateEventRequest = z.infer<typeof EventSchema>;
export type CreateSnapshotRequest = z.infer<typeof SnapshotSchema>;
export type TimeTravelQueryRequest = z.infer<typeof TimeTravelQuerySchema>;
export type ReplayEventsRequest = z.infer<typeof ReplayEventsSchema>;

export interface Event {
  eventId: string;
  eventType: string;
  aggregateId: string;
  version: number;
  payload: Record<string, unknown>;
  metadata?: Record<string, unknown>;
  timestamp: Date;
}

export interface Snapshot {
  snapshotId: string;
  aggregateId: string;
  version: number;
  state: Record<string, unknown>;
  metadata?: Record<string, unknown>;
  createdAt: Date;
}

export interface ProjectionResult {
  aggregateId: string;
  version: number;
  state: Record<string, unknown>;
  asOf: Date;
  eventsApplied: number;
}

export interface ReplayResult {
  aggregateId: string;
  events: Event[];
  finalState: Record<string, unknown>;
  fromVersion?: number;
  toVersion?: number;
}
