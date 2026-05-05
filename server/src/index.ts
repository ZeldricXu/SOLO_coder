import express from 'express';
import { createServer } from 'http';
import { WebSocketServer } from 'ws';
import cors from 'cors';
import { config } from 'dotenv';
import { join } from 'path';

import { WebSocketHandler } from './websocket/handler';
import { createApiRouter } from './routes/api';

config();

const app = express();
const httpServer = createServer(app);

const PORT = process.env.PORT || 8080;
const WS_PORT = process.env.WS_PORT || 8081;

app.use(cors({
  origin: process.env.CORS_ORIGIN || '*',
  credentials: true,
}));

app.use(express.json());
app.use(express.urlencoded({ extended: true }));

const wss = new WebSocketServer({ server: httpServer, path: '/ws' });
const wsHandler = new WebSocketHandler(wss);

const apiRouter = createApiRouter(wsHandler);
app.use('/api', apiRouter);

app.get('/', (req, res) => {
  res.json({
    name: 'VoiceTrans Server',
    version: '1.0.0',
    description: 'Real-time Speech-to-Text and Translation Service',
    endpoints: {
      websocket: 'ws://host:port/ws',
      api: 'http://host:port/api',
      health: 'http://host:port/api/health',
    },
  });
});

if (process.env.NODE_ENV === 'production') {
  const staticPath = join(__dirname, '../../client/build');
  app.use(express.static(staticPath));
  
  app.get('*', (req, res) => {
    res.sendFile(join(staticPath, 'index.html'));
  });
}

httpServer.listen(PORT, () => {
  console.log(`VoiceTrans Server running on port ${PORT}`);
  console.log(`WebSocket endpoint: ws://localhost:${PORT}/ws`);
  console.log(`API endpoint: http://localhost:${PORT}/api`);
  console.log(`Health check: http://localhost:${PORT}/api/health`);
});

process.on('SIGINT', () => {
  console.log('\nShutting down server...');
  httpServer.close(() => {
    console.log('Server shutdown complete.');
    process.exit(0);
  });
});

process.on('SIGTERM', () => {
  console.log('\nShutting down server...');
  httpServer.close(() => {
    console.log('Server shutdown complete.');
    process.exit(0);
  });
});

process.on('uncaughtException', (error) => {
  console.error('Uncaught exception:', error);
});

process.on('unhandledRejection', (reason, promise) => {
  console.error('Unhandled rejection at:', promise, 'reason:', reason);
});
