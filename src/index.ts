import express from 'express';
import pinoHttp from 'pino-http';
import routes from './api/routes';
import { logger } from './common/logger';
import { eventBus, EVENTS } from './common/events';
import { metricsCollector } from './common/utils';

const app = express();
const PORT = process.env.PORT || 3000;

app.use(express.json({ limit: '10mb' }));
app.use(express.urlencoded({ extended: true }));

app.use(pinoHttp({
  logger,
  autoLogging: true,
}));

app.use((req, _res, next) => {
  const startTime = Date.now();
  req.on('end', () => {
    const duration = Date.now() - startTime;
    metricsCollector.record(`http.${req.method}.${req.path}.duration`, duration);
  });
  next();
});

app.use('/api/v1', routes);

app.use((_req, res) => {
  res.status(404).json({
    code: 404,
    message: 'Not found',
  });
});

app.use((err: Error, _req: express.Request, res: express.Response, _next: express.NextFunction) => {
  logger.error('Unhandled error', err);
  res.status(500).json({
    code: 500,
    message: err.message || 'Internal server error',
  });
});

eventBus.on(EVENTS.ERROR, (data) => {
  logger.error('System error', data as Error);
});

eventBus.on(EVENTS.METRICS, (data) => {
  logger.debug('Metrics collected', data as Record<string, unknown>);
});

process.on('uncaughtException', (error) => {
  logger.error('Uncaught exception', error);
  process.exit(1);
});

process.on('unhandledRejection', (reason, promise) => {
  logger.error('Unhandled rejection', reason as Error, { promise: String(promise) });
});

const server = app.listen(PORT, () => {
  logger.info(`Contract Audit Platform API server started on port ${PORT}`);
  logger.info(`Health check: http://localhost:${PORT}/api/v1/health`);
  logger.info(`Available endpoints:`);
  logger.info(`  POST /api/v1/resources`);
  logger.info(`  GET  /api/v1/resources/:id/status`);
  logger.info(`  POST /api/v1/resources/batch`);
  logger.info(`  Multi-sig: /api/v1/multisig/*`);
  logger.info(`  ZKP: /api/v1/zkp/*`);
  logger.info(`  Events: /api/v1/events/*`);
  logger.info(`  Transactions: /api/v1/transactions/*`);
  logger.info(`  Cross-chain: /api/v1/crosschain/*`);
  logger.info(`  HD Wallet: /api/v1/hdwallet/*`);
  logger.info(`  Storage: /api/v1/storage/*`);
  logger.info(`  Indexer: /api/v1/indexer/*`);
  logger.info(`  Chain: /api/v1/chain/*`);
  logger.info(`  Gas: /api/v1/gas/*`);
});

process.on('SIGTERM', () => {
  logger.info('SIGTERM received, shutting down gracefully');
  server.close(() => {
    logger.info('Server closed');
    process.exit(0);
  });
});

process.on('SIGINT', () => {
  logger.info('SIGINT received, shutting down gracefully');
  server.close(() => {
    logger.info('Server closed');
    process.exit(0);
  });
});

export default app;
