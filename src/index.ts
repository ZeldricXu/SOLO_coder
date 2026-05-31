import express, { Request, Response, NextFunction } from 'express';
import dotenv from 'dotenv';
import logger from './utils/logger';
import { generateId, currentTimestamp } from './utils/helpers';
import { validate, ResourceCreateRequestSchema, BatchRequestSchema } from './schemas';
import { configManager } from './modules/config';
import { coreProcessor } from './modules/core';
import { imageDistributor } from './modules/image-distribution';
import { apiGateway } from './modules/api-gateway';
import { trafficController } from './modules/traffic-control';
import { mtlsManager } from './modules/mtls';
import { faultOrchestrator } from './modules/fault-injection';
import { dataAccess } from './modules/data-access';
import { storageManager } from './modules/storage';

dotenv.config();

const app = express();
const PORT = process.env.PORT || 3000;

app.use(express.json({ limit: '10mb' }));
app.use(apiGateway.middleware());
app.use(trafficController.middleware());

app.use((req: Request, res: Response, next: NextFunction) => {
  res.setHeader('X-Request-Id', req.traceContext?.traceId || generateId('req_'));
  next();
});

app.get('/health', (req: Request, res: Response) => {
  res.json({
    code: 200,
    data: {
      status: 'healthy',
      timestamp: currentTimestamp(),
      uptime: process.uptime(),
      version: '1.0.0',
    },
  });
});

app.get('/api/v1/status', (req: Request, res: Response) => {
  res.json({
    code: 200,
    data: {
      core: coreProcessor.getMetrics(),
      gateway: apiGateway.getMetrics(),
      traffic: trafficController.getMetrics(),
      image: imageDistributor.getSyncStatus(),
      storage: storageManager.getStorageStats(),
    },
  });
});

app.post('/api/v1/resources', async (req: Request, res: Response) => {
  try {
    const body = validate(ResourceCreateRequestSchema, req.body);
    
    if (body.type === 'image-distribution') {
      const task = await imageDistributor.pullImage(
        body.config.imageName as string,
        body.config.tag as string || 'latest',
        { url: body.config.registryUrl as string },
      );
      return res.status(201).json({
        code: 201,
        data: { id: task.taskId, status: task.status },
      });
    }

    if (body.type === 'backup') {
      const backup = await storageManager.createBackup(
        body.config.source as string,
        body.config.backupType as any || 'full',
        body.labels,
      );
      return res.status(201).json({
        code: 201,
        data: { id: backup.id, status: backup.status },
      });
    }

    const result = await coreProcessor.executeHandler({
      traceId: req.traceContext?.traceId || generateId('trace_'),
      namespace: body.config.namespace as string || 'default',
      params: body.config,
      payload: body.config.payload,
    });

    res.status(201).json({
      code: 201,
      data: { id: generateId('rsc_'), status: result.success ? 'provisioning' : 'failed' },
    });
  } catch (error: any) {
    res.status(400).json({
      code: 400,
      message: error.message,
      details: error.details,
    });
  }
});

app.get('/api/v1/resources/:id/status', (req: Request, res: Response) => {
  const { id } = req.params;
  
  const runInstance = coreProcessor.getRunInstance(id);
  if (runInstance) {
    return res.json({
      code: 200,
      data: {
        id,
        status: runInstance.phase,
        progress: runInstance.progress,
      },
    });
  }

  const imageTask = imageDistributor.getTask(id);
  if (imageTask) {
    return res.json({
      code: 200,
      data: {
        id,
        status: imageTask.status,
        progress: imageTask.progress,
      },
    });
  }

  const backup = storageManager.getBackup(id);
  if (backup) {
    return res.json({
      code: 200,
      data: {
        id,
        status: backup.status,
        progress: backup.status === 'completed' ? 1 : 0.5,
      },
    });
  }

  res.status(404).json({
    code: 404,
    message: 'Resource not found',
  });
});

app.post('/api/v1/resources/batch', (req: Request, res: Response) => {
  try {
    const body = validate(BatchRequestSchema, req.body);
    const batchId = generateId('batch_');
    const results = body.operations.map(op => ({
      id: op.id,
      status: 'accepted',
    }));

    res.json({
      code: 200,
      data: { batch_id: batchId, results },
    });
  } catch (error: any) {
    res.status(400).json({
      code: 400,
      message: error.message,
    });
  }
});

app.get('/api/v1/configs', (req: Request, res: Response) => {
  res.json({
    code: 200,
    data: configManager.listConfigs(),
  });
});

app.get('/api/v1/configs/:namespace', (req: Request, res: Response) => {
  const config = configManager.getConfig(req.params.namespace);
  if (config) {
    res.json({ code: 200, data: config });
  } else {
    res.status(404).json({ code: 404, message: 'Config not found' });
  }
});

app.post('/api/v1/configs/:namespace', async (req: Request, res: Response) => {
  const config = await configManager.setConfig(req.params.namespace, req.body);
  res.json({ code: 200, data: config });
});

app.get('/api/v1/traffic/canaries', (req: Request, res: Response) => {
  res.json({ code: 200, data: trafficController.listCanaries() });
});

app.post('/api/v1/traffic/canaries', (req: Request, res: Response) => {
  const canary = trafficController.createCanary(req.body);
  res.status(201).json({ code: 201, data: canary });
});

app.get('/api/v1/traffic/bluegreens', (req: Request, res: Response) => {
  res.json({ code: 200, data: trafficController.listBlueGreens() });
});

app.post('/api/v1/traffic/bluegreens', (req: Request, res: Response) => {
  const bg = trafficController.createBlueGreen(req.body);
  res.status(201).json({ code: 201, data: bg });
});

app.post('/api/v1/traffic/bluegreens/:id/switch', (req: Request, res: Response) => {
  const bg = trafficController.switchBlueGreen(req.params.id);
  if (bg) {
    res.json({ code: 200, data: bg });
  } else {
    res.status(404).json({ code: 404, message: 'BlueGreen config not found' });
  }
});

app.get('/api/v1/traffic/mirrors', (req: Request, res: Response) => {
  res.json({ code: 200, data: trafficController.listMirrors() });
});

app.post('/api/v1/traffic/mirrors', (req: Request, res: Response) => {
  const mirror = trafficController.createMirror(req.body);
  res.status(201).json({ code: 201, data: mirror });
});

app.get('/api/v1/traffic/circuitbreakers', (req: Request, res: Response) => {
  res.json({ code: 200, data: trafficController.listCircuitBreakers() });
});

app.post('/api/v1/traffic/circuitbreakers', (req: Request, res: Response) => {
  const cb = trafficController.createCircuitBreaker(req.body);
  res.status(201).json({ code: 201, data: cb });
});

app.get('/api/v1/mtls/certificates', (req: Request, res: Response) => {
  res.json({ code: 200, data: mtlsManager.listCertificates() });
});

app.post('/api/v1/mtls/certificates', async (req: Request, res: Response) => {
  try {
    const cert = await mtlsManager.issueCertificate(
      req.body.commonName,
      req.body.subjectAltNames || [],
      req.body.validityDays,
    );
    res.status(201).json({ code: 201, data: cert });
  } catch (error: any) {
    res.status(400).json({ code: 400, message: error.message });
  }
});

app.post('/api/v1/mtls/certificates/:id/rotate', async (req: Request, res: Response) => {
  const cert = await mtlsManager.rotateCertificate(req.params.id);
  if (cert) {
    res.json({ code: 200, data: cert });
  } else {
    res.status(404).json({ code: 404, message: 'Certificate not found' });
  }
});

app.post('/api/v1/mtls/certificates/:id/revoke', async (req: Request, res: Response) => {
  const success = await mtlsManager.revokeCertificate(
    req.params.id,
    req.body.reason,
    req.body.revokedBy || 'admin',
  );
  if (success) {
    res.json({ code: 200, data: { revoked: true } });
  } else {
    res.status(404).json({ code: 404, message: 'Certificate not found' });
  }
});

app.get('/api/v1/mtls/crl', (req: Request, res: Response) => {
  const crl = mtlsManager.getCRL();
  res.json({ code: 200, data: crl });
});

app.get('/api/v1/mtls/ca', (req: Request, res: Response) => {
  res.json({ code: 200, data: { caCert: mtlsManager.getCACertificate() } });
});

app.get('/api/v1/fault/scenarios', (req: Request, res: Response) => {
  res.json({ code: 200, data: faultOrchestrator.listScenarios() });
});

app.post('/api/v1/fault/scenarios', (req: Request, res: Response) => {
  const scenario = faultOrchestrator.createScenario(req.body);
  res.status(201).json({ code: 201, data: scenario });
});

app.post('/api/v1/fault/scenarios/:id/start', async (req: Request, res: Response) => {
  const result = await faultOrchestrator.startInjection(req.params.id);
  res.json({ code: 200, data: result });
});

app.post('/api/v1/fault/scenarios/:id/stop', (req: Request, res: Response) => {
  const record = faultOrchestrator.stopInjection(req.params.id, req.body.reason || 'manual');
  res.json({ code: 200, data: record });
});

app.get('/api/v1/fault/active', (req: Request, res: Response) => {
  res.json({ code: 200, data: faultOrchestrator.getActiveInjections() });
});

app.get('/api/v1/fault/metrics', (req: Request, res: Response) => {
  res.json({ code: 200, data: faultOrchestrator.getMetrics() });
});

app.get('/api/v1/data/schemas', (req: Request, res: Response) => {
  res.json({ code: 200, data: dataAccess.listSchemas() });
});

app.post('/api/v1/data/schemas', (req: Request, res: Response) => {
  const schema = dataAccess.createSchema(req.body);
  res.status(201).json({ code: 201, data: schema });
});

app.get('/api/v1/data/migrations', (req: Request, res: Response) => {
  res.json({ code: 200, data: dataAccess.listMigrations() });
});

app.post('/api/v1/data/migrations', (req: Request, res: Response) => {
  const migration = dataAccess.createMigration(req.body);
  res.status(201).json({ code: 201, data: migration });
});

app.post('/api/v1/data/migrations/:id/run', async (req: Request, res: Response) => {
  const migration = await dataAccess.runMigration(req.params.id);
  res.json({ code: 200, data: migration });
});

app.post('/api/v1/data/:schema', async (req: Request, res: Response) => {
  try {
    const record = await dataAccess.insert(req.params.schema, req.body);
    res.status(201).json({ code: 201, data: record });
  } catch (error: any) {
    res.status(400).json({ code: 400, message: error.message });
  }
});

app.get('/api/v1/data/:schema', (req: Request, res: Response) => {
  const records = dataAccess.query(req.params.schema, {
    limit: Number(req.query.limit) || 100,
    offset: Number(req.query.offset) || 0,
  });
  res.json({ code: 200, data: records });
});

app.get('/api/v1/data/:schema/:id', (req: Request, res: Response) => {
  const record = dataAccess.findById(req.params.schema, req.params.id);
  if (record) {
    res.json({ code: 200, data: record });
  } else {
    res.status(404).json({ code: 404, message: 'Record not found' });
  }
});

app.put('/api/v1/data/:schema/:id', async (req: Request, res: Response) => {
  try {
    const record = await dataAccess.update(req.params.schema, req.params.id, req.body);
    if (record) {
      res.json({ code: 200, data: record });
    } else {
      res.status(404).json({ code: 404, message: 'Record not found' });
    }
  } catch (error: any) {
    res.status(400).json({ code: 400, message: error.message });
  }
});

app.delete('/api/v1/data/:schema/:id', async (req: Request, res: Response) => {
  const deleted = await dataAccess.delete(req.params.schema, req.params.id);
  if (deleted) {
    res.json({ code: 200, data: { deleted: true } });
  } else {
    res.status(404).json({ code: 404, message: 'Record not found' });
  }
});

app.get('/api/v1/storage/backups', (req: Request, res: Response) => {
  res.json({ code: 200, data: storageManager.listBackups() });
});

app.post('/api/v1/storage/backups', async (req: Request, res: Response) => {
  const backup = await storageManager.createBackup(
    req.body.source,
    req.body.type || 'full',
    req.body.metadata,
  );
  res.status(201).json({ code: 201, data: backup });
});

app.post('/api/v1/storage/backups/:id/restore', async (req: Request, res: Response) => {
  try {
    const job = await storageManager.restoreBackup(
      req.params.id,
      req.body.targetPath,
      req.body.options,
    );
    res.json({ code: 200, data: job });
  } catch (error: any) {
    res.status(400).json({ code: 400, message: error.message });
  }
});

app.get('/api/v1/storage/snapshots', (req: Request, res: Response) => {
  res.json({ code: 200, data: storageManager.listSnapshots() });
});

app.post('/api/v1/storage/snapshots', async (req: Request, res: Response) => {
  const snapshot = await storageManager.createSnapshot(req.body.source, req.body.name);
  res.status(201).json({ code: 201, data: snapshot });
});

app.get('/api/v1/storage/stats', (req: Request, res: Response) => {
  res.json({ code: 200, data: storageManager.getStorageStats() });
});

app.get('/api/v1/images/tasks', (req: Request, res: Response) => {
  res.json({ code: 200, data: imageDistributor.listTasks() });
});

app.post('/api/v1/images/pull', async (req: Request, res: Response) => {
  const task = await imageDistributor.pullImage(
    req.body.imageName,
    req.body.tag || 'latest',
    { url: req.body.registryUrl },
  );
  res.status(201).json({ code: 201, data: task });
});

app.post('/api/v1/images/sync', async (req: Request, res: Response) => {
  const task = await imageDistributor.syncImage(
    req.body.imageName,
    req.body.tag || 'latest',
    { url: req.body.sourceRegistry },
    req.body.targetRegistries.map((url: string) => ({ url })),
  );
  res.status(201).json({ code: 201, data: task });
});

app.get('/api/v1/images/sync-status', (req: Request, res: Response) => {
  res.json({ code: 200, data: imageDistributor.getSyncStatus() });
});

app.get('/api/v1/images/persistence/status', (req: Request, res: Response) => {
  res.json({ code: 200, data: imageDistributor.getPersistenceStatus() });
});

app.post('/api/v1/images/snapshot', async (req: Request, res: Response) => {
  await imageDistributor.triggerSnapshot();
  res.json({ code: 200, data: { message: 'Snapshot triggered successfully' } });
});

app.post('/api/v1/images/recover', async (req: Request, res: Response) => {
  const report = await imageDistributor.triggerRecovery();
  res.json({ code: 200, data: report });
});

app.get('/api/v1/traces/:traceId', (req: Request, res: Response) => {
  const traces = apiGateway.getTrace(req.params.traceId);
  res.json({ code: 200, data: traces });
});

app.get('/api/v1/logs/recent', (req: Request, res: Response) => {
  const logs = apiGateway.getRecentLogs(Number(req.query.limit) || 100);
  res.json({ code: 200, data: logs });
});

app.get('/api/v1/core/cache/stats', (req: Request, res: Response) => {
  const stats = coreProcessor.getCacheStats();
  res.json({ code: 200, data: stats });
});

app.post('/api/v1/core/cache/invalidate', async (req: Request, res: Response) => {
  const count = await coreProcessor.invalidateCache(req.body.pattern);
  res.json({ code: 200, data: { invalidatedCount: count } });
});

app.post('/api/v1/core/cache/warmup', async (req: Request, res: Response) => {
  const result = await coreProcessor.triggerWarmup(req.body.keys);
  res.json({ code: 200, data: result });
});

app.get('/api/v1/core/cache/warmup-status', (req: Request, res: Response) => {
  res.json({ code: 200, data: { completed: coreProcessor.isWarmupCompleted() } });
});

app.get('/api/v1/gateway/config', (req: Request, res: Response) => {
  res.json({ code: 200, data: apiGateway.getCurrentConfig() });
});

app.put('/api/v1/gateway/config', async (req: Request, res: Response) => {
  const config = await apiGateway.updateConfig(req.body, req.body.appliedBy);
  res.json({ code: 200, data: config });
});

app.get('/api/v1/gateway/routes', (req: Request, res: Response) => {
  res.json({ code: 200, data: apiGateway.getRoutes() });
});

app.put('/api/v1/gateway/routes', async (req: Request, res: Response) => {
  const routes = await apiGateway.updateRoutes(req.body.routes, req.body.appliedBy);
  res.json({ code: 200, data: routes });
});

app.post('/api/v1/gateway/routes', async (req: Request, res: Response) => {
  const route = await apiGateway.addRoute(req.body, req.body.appliedBy);
  res.status(201).json({ code: 201, data: route });
});

app.delete('/api/v1/gateway/routes', async (req: Request, res: Response) => {
  const success = await apiGateway.removeRoute(req.body.path, req.body.method, req.body.appliedBy);
  res.json({ code: 200, data: { success } });
});

app.get('/api/v1/gateway/config/versions', (req: Request, res: Response) => {
  res.json({ code: 200, data: apiGateway.getConfigVersions() });
});

app.post('/api/v1/gateway/config/rollback', async (req: Request, res: Response) => {
  const success = await apiGateway.rollbackToVersion(req.body.version);
  res.json({ code: 200, data: { success, currentVersion: apiGateway.getCurrentVersion() } });
});

app.use((err: any, req: Request, res: Response, next: NextFunction) => {
  logger.error('Unhandled error', {
    error: err.message,
    stack: err.stack,
    path: req.path,
    traceId: req.traceContext?.traceId,
  });
  
  res.status(500).json({
    code: 500,
    message: 'Internal Server Error',
    traceId: req.traceContext?.traceId,
  });
});

async function initializeModules() {
  try {
    await configManager.initialize();
    await coreProcessor.initialize();
    logger.info('All modules initialized successfully');
  } catch (error) {
    logger.error('Failed to initialize modules', { error });
    process.exit(1);
  }
}

async function startServer() {
  await initializeModules();
  
  app.listen(PORT, () => {
    logger.info(`Server is running on port ${PORT}`);
    logger.info(`API Documentation available at /health`);
  });
}

process.on('SIGTERM', async () => {
  logger.info('SIGTERM received, shutting down gracefully');
  faultOrchestrator.stopAll();
  mtlsManager.stop();
  storageManager.stop();
  imageDistributor.stop();
  apiGateway.stop();
  await coreProcessor.shutdown();
  process.exit(0);
});

process.on('SIGINT', async () => {
  logger.info('SIGINT received, shutting down gracefully');
  faultOrchestrator.stopAll();
  mtlsManager.stop();
  storageManager.stop();
  imageDistributor.stop();
  apiGateway.stop();
  await coreProcessor.shutdown();
  process.exit(0);
});

if (require.main === module) {
  startServer();
}

export default app;
