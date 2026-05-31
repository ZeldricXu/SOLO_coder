import { Router, Request, Response } from 'express';
import { asyncHandler } from '../middleware/errorHandler';
import * as service from '../modules/eventStore/service';
import { EventSchema, SnapshotSchema, TimeTravelQuerySchema, ReplayEventsSchema } from '../modules/eventStore/types';

const router = Router();

const getPaginationParams = (req: Request) => ({
  page: parseInt(req.query.page as string) || 1,
  pageSize: parseInt(req.query.pageSize as string) || 20,
});

router.post('/events', asyncHandler(async (req: Request, res: Response) => {
  const data = EventSchema.parse(req.body);
  const result = await service.appendEvent(data);
  res.status(201).json({ code: 201, data: result });
}));

router.get('/events', asyncHandler(async (req: Request, res: Response) => {
  const params = getPaginationParams(req);
  const aggregateId = req.query.aggregateId as string | undefined;
  const eventType = req.query.eventType as string | undefined;
  const result = await service.listEvents(params, aggregateId, eventType);
  res.json({ code: 200, data: result });
}));

router.get('/events/:eventId', asyncHandler(async (req: Request, res: Response) => {
  const result = await service.getEvent(req.params.eventId);
  res.json({ code: 200, data: result });
}));

router.get('/aggregates/:aggregateId/events', asyncHandler(async (req: Request, res: Response) => {
  const fromVersion = req.query.fromVersion ? parseInt(req.query.fromVersion as string) : undefined;
  const toVersion = req.query.toVersion ? parseInt(req.query.toVersion as string) : undefined;
  const result = await service.getEventsByAggregate(req.params.aggregateId, fromVersion, toVersion);
  res.json({ code: 200, data: result });
}));

router.get('/aggregates', asyncHandler(async (_req: Request, res: Response) => {
  const result = await service.getAggregateVersions();
  res.json({ code: 200, data: result });
}));

router.post('/snapshots', asyncHandler(async (req: Request, res: Response) => {
  const data = SnapshotSchema.parse(req.body);
  const result = await service.createSnapshot(data);
  res.status(201).json({ code: 201, data: result });
}));

router.get('/snapshots', asyncHandler(async (req: Request, res: Response) => {
  const params = getPaginationParams(req);
  const aggregateId = req.query.aggregateId as string | undefined;
  const result = await service.listSnapshots(params, aggregateId);
  res.json({ code: 200, data: result });
}));

router.get('/snapshots/:snapshotId', asyncHandler(async (req: Request, res: Response) => {
  const result = await service.getSnapshot(req.params.snapshotId);
  res.json({ code: 200, data: result });
}));

router.get('/aggregates/:aggregateId/snapshots/latest', asyncHandler(async (req: Request, res: Response) => {
  const maxVersion = req.query.maxVersion ? parseInt(req.query.maxVersion as string) : undefined;
  const result = await service.getLatestSnapshot(req.params.aggregateId, maxVersion);
  res.json({ code: 200, data: result });
}));

router.post('/projections', asyncHandler(async (req: Request, res: Response) => {
  const { aggregateId, upToVersion } = req.body as { aggregateId: string; upToVersion?: number };
  const result = await service.buildProjection(aggregateId, upToVersion);
  res.json({ code: 200, data: result });
}));

router.post('/time-travel', asyncHandler(async (req: Request, res: Response) => {
  const data = TimeTravelQuerySchema.parse(req.body);
  const result = await service.timeTravelQuery(data);
  res.json({ code: 200, data: result });
}));

router.post('/replay', asyncHandler(async (req: Request, res: Response) => {
  const data = ReplayEventsSchema.parse(req.body);
  const result = await service.replayEvents({
    aggregateId: data.aggregateId,
    fromVersion: data.fromVersion,
    toVersion: data.toVersion,
    fromTimestamp: data.fromTimestamp ? new Date(data.fromTimestamp) : undefined,
    toTimestamp: data.toTimestamp ? new Date(data.toTimestamp) : undefined,
  });
  res.json({ code: 200, data: result });
}));

router.delete('/aggregates/:aggregateId/events', asyncHandler(async (req: Request, res: Response) => {
  const count = await service.deleteEventsByAggregate(req.params.aggregateId);
  res.json({ code: 200, data: { deleted: count } });
}));

export default router;
