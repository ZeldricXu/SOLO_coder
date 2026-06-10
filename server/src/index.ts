import express, { Request, Response } from 'express';
import cors from 'cors';
import { createServer } from 'http';
import { DEFAULT_CONFIG, ServerConfig } from './types';
import { SignalingWebSocketServer } from './server/WebSocketServer';
import { createLogger, LogLevel, Logger } from './utils/logger';
import { httpAuthMiddleware } from './middleware/auth';

const logger = createLogger('Server');

const config: ServerConfig = {
  port: parseInt(process.env.PORT || String(DEFAULT_CONFIG.port), 10),
  heartbeatInterval: parseInt(process.env.HEARTBEAT_INTERVAL || String(DEFAULT_CONFIG.heartbeatInterval), 10),
  heartbeatTimeout: parseInt(process.env.HEARTBEAT_TIMEOUT || String(DEFAULT_CONFIG.heartbeatTimeout), 10),
  cursorThrottleMs: parseInt(process.env.CURSOR_THROTTLE_MS || String(DEFAULT_CONFIG.cursorThrottleMs), 10),
  operationBufferTimeoutMs: parseInt(process.env.OP_BUFFER_TIMEOUT || String(DEFAULT_CONFIG.operationBufferTimeoutMs), 10),
  operationBufferMaxSize: parseInt(process.env.OP_BUFFER_MAX_SIZE || String(DEFAULT_CONFIG.operationBufferMaxSize), 10),
  maxOperationsPerRoom: parseInt(process.env.MAX_OPS_PER_ROOM || String(DEFAULT_CONFIG.maxOperationsPerRoom), 10)
};

if (process.env.LOG_LEVEL) {
  const levelMap: Record<string, LogLevel> = {
    debug: LogLevel.DEBUG,
    info: LogLevel.INFO,
    warn: LogLevel.WARN,
    error: LogLevel.ERROR,
    silent: LogLevel.SILENT
  };
  const level = levelMap[process.env.LOG_LEVEL.toLowerCase()];
  if (level !== undefined) {
    Logger.setLevel(level);
  }
}

const app = express();
const httpServer = createServer(app);
const wsServer = new SignalingWebSocketServer(config);

app.use(cors());
app.use(express.json());

app.get('/health', (_req: Request, res: Response) => {
  res.json({
    status: 'ok',
    timestamp: Date.now(),
    uptime: process.uptime()
  });
});

app.get('/api/stats', (_req: Request, res: Response) => {
  try {
    const stats = wsServer.getStats();
    res.json({
      ...stats,
      serverTime: Date.now(),
      uptime: process.uptime()
    });
  } catch (error) {
    logger.error('Failed to get stats', { error: error instanceof Error ? error.message : String(error) });
    res.status(500).json({ error: 'Failed to get stats' });
  }
});

app.get('/api/rooms', httpAuthMiddleware, (_req: Request, res: Response) => {
  try {
    const roomManager = wsServer.getRoomManager();
    const rooms = roomManager.getStats();
    res.json({
      rooms,
      count: Object.keys(rooms).length
    });
  } catch (error) {
    logger.error('Failed to get rooms', { error: error instanceof Error ? error.message : String(error) });
    res.status(500).json({ error: 'Failed to get rooms' });
  }
});

app.get('/api/rooms/:roomId', httpAuthMiddleware, (req: Request, res: Response) => {
  try {
    const { roomId } = req.params;
    const roomManager = wsServer.getRoomManager();
    const stats = roomManager.getRoomStats(roomId);
    if (!stats) {
      res.status(404).json({ error: 'Room not found' });
      return;
    }
    const users = roomManager.getUsers(roomId);
    res.json({
      roomId,
      ...stats,
      users
    });
  } catch (error) {
    logger.error('Failed to get room info', { error: error instanceof Error ? error.message : String(error) });
    res.status(500).json({ error: 'Failed to get room info' });
  }
});

app.get('/api/rooms/:roomId/operations', httpAuthMiddleware, (req: Request, res: Response) => {
  try {
    const { roomId } = req.params;
    const fromSequence = req.query.fromSequence ? parseInt(req.query.fromSequence as string, 10) : undefined;
    const roomManager = wsServer.getRoomManager();
    const operations = roomManager.getOperations(roomId, fromSequence);
    res.json({
      roomId,
      operations,
      count: operations.length,
      fromSequence
    });
  } catch (error) {
    logger.error('Failed to get operations', { error: error instanceof Error ? error.message : String(error) });
    res.status(500).json({ error: 'Failed to get operations' });
  }
});

app.use((_req: Request, res: Response) => {
  res.status(404).json({ error: 'Not found' });
});

wsServer.attach(httpServer);

httpServer.listen(config.port, () => {
  logger.info('Signaling server started', {
    port: config.port,
    env: process.env.NODE_ENV || 'development',
    config: {
      heartbeatInterval: config.heartbeatInterval,
      heartbeatTimeout: config.heartbeatTimeout,
      cursorThrottleMs: config.cursorThrottleMs,
      operationBufferTimeoutMs: config.operationBufferTimeoutMs,
      operationBufferMaxSize: config.operationBufferMaxSize
    }
  });
});

process.on('SIGTERM', async () => {
  logger.info('SIGTERM received, shutting down gracefully');
  await shutdown();
});

process.on('SIGINT', async () => {
  logger.info('SIGINT received, shutting down gracefully');
  await shutdown();
});

process.on('uncaughtException', (error) => {
  logger.error('Uncaught exception', { error: error.message, stack: error.stack });
});

process.on('unhandledRejection', (reason, promise) => {
  logger.error('Unhandled rejection', {
    reason: reason instanceof Error ? reason.message : String(reason),
    promise: String(promise)
  });
});

async function shutdown(): Promise<void> {
  try {
    await wsServer.close();
    httpServer.close(() => {
      logger.info('HTTP server closed');
      process.exit(0);
    });

    setTimeout(() => {
      logger.warn('Forced shutdown after timeout');
      process.exit(1);
    }, 10000);
  } catch (error) {
    logger.error('Error during shutdown', { error: error instanceof Error ? error.message : String(error) });
    process.exit(1);
  }
}

export { app, httpServer, wsServer, config };
