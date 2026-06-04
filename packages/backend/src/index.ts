import express from 'express';
import cors from 'cors';
import http from 'http';
import { WebSocketServer } from 'ws';
import path from 'path';
import { fileURLToPath } from 'url';
import { setupApiRoutes } from './routes/api.js';
import { setupCollaborationServer } from './services/CollaborationServer.js';
import { SimulationScheduler } from './services/SimulationScheduler.js';

const __filename = fileURLToPath(import.meta.url);
const __dirname = path.dirname(__filename);

const app = express();
const server = http.createServer(app);
const wss = new WebSocketServer({ server });

const PORT = process.env.PORT || 3001;

app.use(cors());
app.use(express.json({ limit: '50mb' }));

const scheduler = new SimulationScheduler();

setupApiRoutes(app, scheduler);
setupCollaborationServer(wss);

app.get('/health', (req, res) => {
  res.json({ 
    status: 'ok', 
    timestamp: Date.now(),
    workers: scheduler.getWorkerStats(),
  });
});

server.listen(PORT, () => {
  console.log(`🚀 Physics Simulation Platform Server running on port ${PORT}`);
  console.log(`📊 Health check: http://localhost:${PORT}/health`);
  console.log(`🔧 API: http://localhost:${PORT}/api`);
});

process.on('SIGTERM', () => {
  console.log('SIGTERM received, shutting down gracefully');
  scheduler.shutdown();
  server.close();
  process.exit(0);
});

process.on('SIGINT', () => {
  console.log('SIGINT received, shutting down gracefully');
  scheduler.shutdown();
  server.close();
  process.exit(0);
});
