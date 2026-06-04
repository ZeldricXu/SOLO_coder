import 'dotenv/config';
import express, { Request, Response } from 'express';
import { WebSocketServer } from 'ws';
import http from 'http';
import { YjsWebSocketServer } from './YjsWebSocketServer';
import { ServerConfig } from './types';

const config: ServerConfig = {
  port: parseInt(process.env.WS_PORT || '1234', 10),
  httpPort: parseInt(process.env.HTTP_PORT || '1235', 10),
  saveDebounceMs: parseInt(process.env.SAVE_DEBOUNCE_MS || '2000', 10),
  gcEnabled: process.env.GC_ENABLED !== 'false',
  redisUrl: process.env.REDIS_URL,
  redisChannel: process.env.REDIS_CHANNEL || 'yjs-collab',
  prismaUrl: process.env.DATABASE_URL || '',
  jwtSecret: process.env.JWT_SECRET || 'secret',
};

const app = express();
app.use(express.json());

const server = http.createServer(app);
const wss = new WebSocketServer({ server });

const yjsServer = new YjsWebSocketServer(config);

app.get('/health', (_req: Request, res: Response) => {
  res.json({
    status: 'ok',
    timestamp: new Date().toISOString(),
  });
});

app.get('/rooms', (_req: Request, res: Response) => {
  const rooms = yjsServer.getAllRooms();
  res.json({
    rooms,
    total: rooms.length,
  });
});

app.get('/rooms/:documentId', (req: Request, res: Response) => {
  const { documentId } = req.params;
  const room = yjsServer.getRoomInfo(documentId);

  if (!room) {
    res.status(404).json({ error: 'Room not found' });
    return;
  }

  res.json(room);
});

app.delete('/rooms/:documentId', async (req: Request, res: Response) => {
  const { documentId } = req.params;
  const closed = await yjsServer.closeRoom(documentId);

  if (!closed) {
    res.status(404).json({ error: 'Room not found' });
    return;
  }

  res.json({ success: true, message: 'Room closed' });
});

app.get('/rooms/:documentId/users', (req: Request, res: Response) => {
  const { documentId } = req.params;
  const users = yjsServer.getOnlineUsers(documentId);

  res.json({
    documentId,
    users,
    count: users.length,
  });
});

async function start() {
  await yjsServer.initialize();
  yjsServer.attachWebSocketServer(wss);

  server.listen(config.port, () => {
    console.log(`[YjsServer] WebSocket server running on port ${config.port}`);
    console.log(`[YjsServer] HTTP API running on port ${config.httpPort}`);
    console.log(`[YjsServer] Health check: http://localhost:${config.port}/health`);
    console.log(
      `[YjsServer] Redis broadcasting: ${config.redisUrl ? 'enabled' : 'disabled'}`
    );
  });

  process.on('SIGTERM', async () => {
    console.log('[YjsServer] Received SIGTERM, shutting down...');
    await yjsServer.closeAll();
    server.close(() => {
      console.log('[YjsServer] Server closed');
      process.exit(0);
    });
  });

  process.on('SIGINT', async () => {
    console.log('[YjsServer] Received SIGINT, shutting down...');
    await yjsServer.closeAll();
    server.close(() => {
      console.log('[YjsServer] Server closed');
      process.exit(0);
    });
  });
}

start().catch((error) => {
  console.error('[YjsServer] Failed to start:', error);
  process.exit(1);
});
