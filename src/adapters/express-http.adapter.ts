import express, { Router } from 'express';
import {
  createRequestContext,
  createAuthMiddleware,
  createPermissionMiddleware,
  createRateLimitMiddleware,
  requestLogger,
  errorHandler,
  authService,
} from '@modules/gateway';
import { createRateLimiter } from '@modules/gateway';
import { coreHandler, responseBuilder } from '@core/index';
import { rootLogger } from '@modules/logging';
import { metricsAggregator } from '@modules/metrics';
import { logPipeline } from '@modules/logpipeline';
import { notificationService } from '@modules/notification';
import { storageManager } from '@modules/storage';
import { topologyBuilder } from '@modules/topology';
import { profiler } from '@modules/profiling';
import { scheduler } from '@modules/scheduler';
import { generateId, nowISO, nowEpoch } from '@utils/index';
import { z } from 'zod';
const rateLimiter = createRateLimiter(false);
const authMiddleware = createAuthMiddleware(authService);
const rateLimitMiddleware = createRateLimitMiddleware(rateLimiter);
const LoginSchema = z.object({
 username: z.string().min(1),
 password: z.string().min(1),
});
const NotificationSchema = z.object({
 type: z.enum(['email', 'webhook', 'slack']),
 recipient: z.string().min(1),
 content: z.record(z.unknown()),
 max_retries: z.number().int().min(0).default(3),
});
const MetricIngestSchema = z.object({
 metric_name: z.string().min(1),
 value: z.number(),
 tags: z.record(z.string()).default({}),
});
const StoragePutSchema = z.object({
 bucket: z.string().min(1),
 key: z.string().min(1),
 data: z.string().base64(),
 metadata: z.record(z.string()).optional(),
});
const TaskScheduleSchema = z.object({
 name: z.string().min(1),
 cron_expression: z.string().min(1),
 payload: z.record(z.unknown()).default({}),
 enabled: z.boolean().default(true),
});
export class ExpressHttpAdapter {
 private app: express.Application;
 private logger = rootLogger.child({ module: 'ExpressHttpAdapter' });
 constructor() {
 this.app = express();
 this.setupMiddleware();
 this.setupRoutes();
 this.setupErrorHandling();
 }
 private setupMiddleware(): void {
 this.app.use(express.json({ limit: '10mb' }));
 this.app.use(express.urlencoded({ extended: true }));
 this.app.use(createRequestContext);
 this.app.use(requestLogger);
 }
 private setupRoutes(): void {
 const apiRouter = Router();

 apiRouter.get('/health', (_req, res) => {
 res.json(responseBuilder.success({
 status: 'healthy',
 timestamp: nowISO(),
 uptime: process.uptime(),
 environment: process.env.NODE_ENV || 'development',
 version: process.env.npm_package_version || '1.0.0',
 }));
 });

 apiRouter.get('/health/live', (_req, res) => {
 res.status(200).json({
 status: 'alive',
 timestamp: nowISO(),
 });
 });

 apiRouter.get('/health/ready', async (_req, res) => {
 const checks = {
 http_server: true,
 metrics_aggregator: true,
 scheduler: true,
 };
 const isReady = Object.values(checks).every((v) => v);
 res.status(isReady ? 200 : 503).json(responseBuilder.success({
 status: isReady ? 'ready' : 'not_ready',
 timestamp: nowISO(),
 checks,
 }));
 });

 apiRouter.post('/auth/login', async (req, res) => {
 try {
 const validated = LoginSchema.parse(req.body);
 const token = await authService.generateToken({
 user_id: generateId('user_'),
 username: validated.username,
 roles: ['user'],
 permissions: ['resources:read', 'resources:write'],
 tenant_id: 'default',
 });
 res.json(responseBuilder.success({ token, expires_in: 3600 }));
 }
 catch (error) {
 res.status(400).json(responseBuilder.badRequest('Invalid request'));
 }
 });
 apiRouter.get('/health', (_req, res) => {
 res.json(responseBuilder.success({
 status: 'healthy',
 timestamp: nowISO(),
 uptime_ms: process.uptime() * 1000,
 }));
 });
 apiRouter.use(authMiddleware);
 apiRouter.use(rateLimitMiddleware);
 const resourcesRouter = Router();
 resourcesRouter.post('/', async (req, res) => {
 try {
 const result = await coreHandler.createResource(req.body, req.principal);
 res.status(result.code).json(result);
 }
 catch (error) {
 this.logger.error('Create resource error', { error: (error as Error).message });
 res.status(500).json(responseBuilder.internalError());
 }
 });
 resourcesRouter.get('/:id/status', async (req, res) => {
 try {
 const result = await coreHandler.getResourceStatus(req.params.id);
 res.status(result.code).json(result);
 }
 catch (error) {
 this.logger.error('Get resource status error', { error: (error as Error).message });
 res.status(500).json(responseBuilder.internalError());
 }
 });
 resourcesRouter.post('/batch', async (req, res) => {
 try {
 const result = await coreHandler.batchOperation(req.body);
 res.status(result.code).json(result);
 }
 catch (error) {
 this.logger.error('Batch operation error', { error: (error as Error).message });
 res.status(500).json(responseBuilder.internalError());
 }
 });
 resourcesRouter.get('/', async (req, res) => {
 try {
 const result = coreHandler.listResources({
 type: req.query.type as string,
 status: req.query.status as string,
 page: parseInt(req.query.page as string) || 1,
 page_size: parseInt(req.query.page_size as string) || 20,
 });
 res.status(result.code).json(result);
 }
 catch (error) {
 this.logger.error('List resources error', { error: (error as Error).message });
 res.status(500).json(responseBuilder.internalError());
 }
 });
 apiRouter.use('/resources', resourcesRouter);
 const metricsRouter = Router();
 metricsRouter.post('/ingest', async (req, res) => {
 try {
 if (Array.isArray(req.body)) {
 const points = req.body.map((p) => ({
 ...MetricIngestSchema.parse(p),
 timestamp: nowEpoch(),
 }));
 await metricsAggregator.ingestBatch(points);
 }
 else {
 const validated = MetricIngestSchema.parse(req.body);
 await metricsAggregator.ingest({
 ...validated,
 timestamp: nowEpoch(),
 });
 }
 res.json(responseBuilder.success({ status: 'accepted' }));
 }
 catch (error) {
 res.status(400).json(responseBuilder.badRequest('Invalid metric data'));
 }
 });
 metricsRouter.get('/query', async (req, res) => {
 try {
 const metricName = req.query.metric_name as string;
 const tags = req.query.tags as string;
 const startTime = parseInt(req.query.start_time as string) || (nowEpoch() - 3600000);
 const endTime = parseInt(req.query.end_time as string) || nowEpoch();
 const tagObject = req.query.tags ? JSON.parse(tags) : {};
 const result = await metricsAggregator.query(metricName, tagObject, startTime, endTime);
 res.json(responseBuilder.success(result));
 }
 catch (error) {
 this.logger.error('Query metrics error', { error: (error as Error).message });
 res.status(500).json(responseBuilder.internalError());
 }
 });
 metricsRouter.get('/aggregated', async (req, res) => {
 try {
 const metricName = req.query.metric_name as string;
 const tags = req.query.tags as string;
 const window = (req.query.window as string) || '1m';
 const tagObject = tags ? JSON.parse(tags) : {};
 const result = await metricsAggregator.getAggregated(metricName, tagObject, window);
 res.json(responseBuilder.success(result));
 }
 catch (error) {
 this.logger.error('Get aggregated metrics error', { error: (error as Error).message });
 res.status(500).json(responseBuilder.internalError());
 }
 });
 apiRouter.use('/metrics', metricsRouter);
 const logsRouter = Router();
 logsRouter.post('/ingest', async (req, res) => {
 try {
 if (Array.isArray(req.body)) {
 await logPipeline.processBatch(req.body);
 }
 else {
 await logPipeline.collect(req.body);
 }
 res.json(responseBuilder.success({ status: 'accepted' }));
 }
 catch (error) {
 res.status(400).json(responseBuilder.badRequest('Invalid log data'));
 }
 });
 apiRouter.use('/logs', logsRouter);
 const notificationsRouter = Router();
 notificationsRouter.post('/', async (req, res) => {
 try {
 const validated = NotificationSchema.parse(req.body);
 const result = await notificationService.send(validated);
 res.status(201).json(responseBuilder.created(result));
 }
 catch (error) {
 res.status(400).json(responseBuilder.badRequest('Invalid notification data'));
 }
 });
 notificationsRouter.get('/:id/status', async (req, res) => {
 try {
 const result = await notificationService.getStatus(req.params.id);
 if (!result) {
 res.status(404).json(responseBuilder.notFound('Notification not found'));
 return;
 }
 res.json(responseBuilder.success(result));
 }
 catch (error) {
 this.logger.error('Get notification status error', { error: (error as Error).message });
 res.status(500).json(responseBuilder.internalError());
 }
 });
 notificationsRouter.post('/:id/retry', async (req, res) => {
 try {
 const result = await notificationService.retry(req.params.id);
 res.json(responseBuilder.success(result));
 }
 catch (error) {
 this.logger.error('Retry notification error', { error: (error as Error).message });
 res.status(500).json(responseBuilder.internalError());
 }
 });
 apiRouter.use('/notifications', notificationsRouter);
 const storageRouter = Router();
 storageRouter.post('/', async (req, res) => {
 try {
 const validated = StoragePutSchema.parse(req.body);
 const data = Buffer.from(validated.data, 'base64');
 const result = await storageManager.storeObject(validated.bucket, validated.key, data, validated.metadata);
 res.status(201).json(responseBuilder.created(result));
 }
 catch (error) {
 res.status(400).json(responseBuilder.badRequest('Invalid storage data'));
 }
 });
 storageRouter.get('/search', async (req, res) => {
 try {
 const query = req.query.query as string;
 const queryObject = query ? JSON.parse(query) : {};
 const result = await storageManager.searchObjects(queryObject);
 res.json(responseBuilder.success(result));
 }
 catch (error) {
 this.logger.error('Search objects error', { error: (error as Error).message });
 res.status(500).json(responseBuilder.internalError());
 }
 });
 storageRouter.get('/:bucket', async (req, res) => {
 try {
 const key = req.query.key as string;
 if (!key) {
 const result = await storageManager.listObjects(req.params.bucket);
 res.json(responseBuilder.success(result));
 return;
 }
 const data = await storageManager.getObject(req.params.bucket, key);
 if (!data) {
 res.status(404).json(responseBuilder.notFound('Object not found'));
 return;
 }
 const metadata = await storageManager.getObjectMetadata(req.params.bucket, key);
 res.setHeader('Content-Type', metadata?.content_type || 'application/octet-stream');
 res.send(data);
 }
 catch (error) {
 this.logger.error('Get object error', { error: (error as Error).message });
 res.status(500).json(responseBuilder.internalError());
 }
 });
 storageRouter.delete('/:bucket', async (req, res) => {
 try {
 const key = req.query.key as string;
 if (!key) {
 res.status(400).json(responseBuilder.badRequest('Key parameter required'));
 return;
 }
 const result = await storageManager.deleteObject(req.params.bucket, key);
 if (!result) {
 res.status(404).json(responseBuilder.notFound('Object not found'));
 return;
 }
 res.json(responseBuilder.success({ deleted: true }));
 }
 catch (error) {
 this.logger.error('Delete object error', { error: (error as Error).message });
 res.status(500).json(responseBuilder.internalError());
 }
 });
 apiRouter.use('/storage', storageRouter);
 const topologyRouter = Router();
 topologyRouter.get('/graph', async (req, res) => {
 try {
 const service = req.query.service as string | undefined;
 const result = await topologyBuilder.getGraph(service);
 res.json(responseBuilder.success(result));
 }
 catch (error) {
 this.logger.error('Get topology graph error', { error: (error as Error).message });
 res.status(500).json(responseBuilder.internalError());
 }
 });
 topologyRouter.get('/services/:service/dependencies', async (req, res) => {
 try {
 const result = await topologyBuilder.getDependencies(req.params.service);
 res.json(responseBuilder.success(result));
 }
 catch (error) {
 this.logger.error('Get dependencies error', { error: (error as Error).message });
 res.status(500).json(responseBuilder.internalError());
 }
 });
 topologyRouter.get('/services/:service/dependents', async (req, res) => {
 try {
 const result = await topologyBuilder.getDependents(req.params.service);
 res.json(responseBuilder.success(result));
 }
 catch (error) {
 this.logger.error('Get dependents error', { error: (error as Error).message });
 res.status(500).json(responseBuilder.internalError());
 }
 });
 apiRouter.use('/topology', topologyRouter);
 const profilingRouter = Router();
 profilingRouter.post('/cpu/start', async (req, res) => {
 try {
 const duration = req.body.duration ? parseInt(req.body.duration) : undefined;
 const profileId = await profiler.startCPUProfiling(duration);
 res.json(responseBuilder.success({ profile_id: profileId }));
 }
 catch (error) {
 this.logger.error('Start CPU profiling error', { error: (error as Error).message });
 res.status(500).json(responseBuilder.internalError());
 }
 });
 profilingRouter.post('/cpu/stop', async (req, res) => {
 try {
 const profileId = req.body.profile_id as string;
 const samples = await profiler.stopCPUProfiling(profileId);
 const flameGraph = profiler.generateFlameGraph(samples);
 res.json(responseBuilder.success({ samples: samples.length, flame_graph: flameGraph }));
 }
 catch (error) {
 this.logger.error('Stop CPU profiling error', { error: (error as Error).message });
 res.status(500).json(responseBuilder.internalError());
 }
 });
 profilingRouter.post('/memory/start', async (req, res) => {
 try {
 const duration = req.body.duration ? parseInt(req.body.duration) : undefined;
 const profileId = await profiler.startMemoryProfiling(duration);
 res.json(responseBuilder.success({ profile_id: profileId }));
 }
 catch (error) {
 this.logger.error('Start memory profiling error', { error: (error as Error).message });
 res.status(500).json(responseBuilder.internalError());
 }
 });
 profilingRouter.post('/memory/stop', async (req, res) => {
 try {
 const profileId = req.body.profile_id as string;
 const samples = await profiler.stopMemoryProfiling(profileId);
 const flameGraph = profiler.generateFlameGraph(samples);
 res.json(responseBuilder.success({ samples: samples.length, flame_graph: flameGraph }));
 }
 catch (error) {
 this.logger.error('Stop memory profiling error', { error: (error as Error).message });
 res.status(500).json(responseBuilder.internalError());
 }
 });
 apiRouter.use('/profiling', profilingRouter);
 const schedulerRouter = Router();
 schedulerRouter.post('/', async (req, res) => {
 try {
 const validated = TaskScheduleSchema.parse(req.body);
 const result = await scheduler.schedule(validated);
 res.status(201).json(responseBuilder.created(result));
 }
 catch (error) {
 res.status(400).json(responseBuilder.badRequest('Invalid task data'));
 }
 });
 schedulerRouter.get('/', async (_req, res) => {
 try {
 const result = await scheduler.list();
 res.json(responseBuilder.success(result));
 }
 catch (error) {
 this.logger.error('List tasks error', { error: (error as Error).message });
 res.status(500).json(responseBuilder.internalError());
 }
 });
 schedulerRouter.get('/:id', async (req, res) => {
 try {
 const result = await scheduler.get(req.params.id);
 if (!result) {
 res.status(404).json(responseBuilder.notFound('Task not found'));
 return;
 }
 res.json(responseBuilder.success(result));
 }
 catch (error) {
 this.logger.error('Get task error', { error: (error as Error).message });
 res.status(500).json(responseBuilder.internalError());
 }
 });
 schedulerRouter.post('/:id/trigger', async (req, res) => {
 try {
 await scheduler.trigger(req.params.id);
 res.json(responseBuilder.success({ triggered: true }));
 }
 catch (error) {
 this.logger.error('Trigger task error', { error: (error as Error).message });
 res.status(500).json(responseBuilder.internalError());
 }
 });
 schedulerRouter.delete('/:id', async (req, res) => {
 try {
 const result = await scheduler.unschedule(req.params.id);
 if (!result) {
 res.status(404).json(responseBuilder.notFound('Task not found'));
 return;
 }
 res.json(responseBuilder.success({ unscheduled: true }));
 }
 catch (error) {
 this.logger.error('Unschedule task error', { error: (error as Error).message });
 res.status(500).json(responseBuilder.internalError());
 }
 });
 apiRouter.use('/scheduler', schedulerRouter);
 this.app.use('/api/v1', apiRouter);
 this.app.get('/api/v1', (_req, res) => {
 res.json(responseBuilder.success({
 version: 'v1',
 endpoints: [
 '/auth/login',
 '/health',
 '/resources',
 '/metrics',
 '/logs',
 '/notifications',
 '/storage',
 '/topology',
 '/profiling',
 '/scheduler',
 ],
 }));
 });
 }
 private setupErrorHandling(): void {
 this.app.use(errorHandler);
 this.app.use((_req, res) => {
 res.status(404).json(responseBuilder.notFound('Endpoint not found'));
 });
 }
 getApp(): express.Application {
 return this.app;
 }
 start(port: number, host: string): Promise<void> {
 return new Promise((resolve) => {
 this.app.listen(port, host, () => {
 this.logger.info('HTTP server started', { port, host });
 resolve();
 });
 });
 }
}
export const httpAdapter = new ExpressHttpAdapter();
