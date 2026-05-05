require('dotenv').config();

const express = require('express');
const cors = require('cors');
const mongoose = require('mongoose');
const http = require('http');

const uploadRoutes = require('./routes/uploadRoutes');
const mediaRoutes = require('./routes/mediaRoutes');
const reviewRoutes = require('./routes/reviewRoutes');
const distributionRoutes = require('./routes/distributionRoutes');

const { uploadService } = require('./services/uploadService');
const storageService = require('./services/storageService');
const { queueService, QUEUES } = require('./services/queueService');
const { websocketService } = require('./services/websocketService');
const { mediaProcessingWorker } = require('./services/mediaService');

const databaseConfig = require('./config/database');

const app = express();
const server = http.createServer(app);
const PORT = process.env.PORT || 3000;

app.use(cors({
  origin: process.env.NODE_ENV === 'production' 
    ? ['https://your-domain.com'] 
    : ['http://localhost:8080', 'http://127.0.0.1:8080'],
  credentials: true,
  methods: ['GET', 'POST', 'PUT', 'DELETE', 'OPTIONS'],
  allowedHeaders: ['Content-Type', 'Authorization']
}));

app.use(express.json({ limit: '50mb' }));
app.use(express.urlencoded({ extended: true, limit: '50mb' }));

app.use((req, res, next) => {
  console.log(`[${new Date().toISOString()}] ${req.method} ${req.path}`);
  next();
});

app.use('/api/v1/media/upload', uploadRoutes);
app.use('/api/v1/media', mediaRoutes);
app.use('/api/v1/reviews', reviewRoutes);
app.use('/api/v1/distribution', distributionRoutes);

app.get('/api/health', async (req, res) => {
  const queueStats = await queueService.getAllStats();
  const wsStats = websocketService.getStats();
  
  res.status(200).json({
    code: 200,
    data: {
      status: 'healthy',
      timestamp: new Date().toISOString(),
      service: 'MediaHub API',
      features: {
        asyncQueue: true,
        websocket: true,
        sessionIsolation: true,
        redisPersistence: true
      },
      queueStats: queueStats,
      websocketStats: wsStats
    }
  });
});

app.get('/api/v1/config', (req, res) => {
  res.status(200).json({
    code: 200,
    data: {
      chunk_size: parseInt(process.env.UPLOAD_CHUNK_SIZE) || 5 * 1024 * 1024,
      max_file_size: parseInt(process.env.UPLOAD_MAX_FILE_SIZE) || 5 * 1024 * 1024 * 1024,
      allowed_types: {
        image: ['image/jpeg', 'image/png', 'image/gif', 'image/webp', 'image/bmp'],
        video: ['video/mp4', 'video/webm', 'video/ogg', 'video/quicktime', 'video/x-msvideo'],
        audio: ['audio/mpeg', 'audio/ogg', 'audio/wav', 'audio/webm', 'audio/flac']
      },
      websocket: {
        enabled: true,
        path: '/ws'
      }
    }
  });
});

app.get('/api/v1/queue/stats', async (req, res) => {
  const stats = await queueService.getAllStats();
  res.status(200).json({
    code: 200,
    data: stats
  });
});

app.get('/api/v1/websocket/stats', (req, res) => {
  const stats = websocketService.getStats();
  res.status(200).json({
    code: 200,
    data: stats
  });
});

app.use((err, req, res, next) => {
  console.error('Error:', err);
  
  if (err instanceof SyntaxError && err.status === 400 && 'body' in err) {
    return res.status(400).json({
      code: 400,
      message: 'Invalid JSON payload'
    });
  }

  res.status(500).json({
    code: 500,
    message: 'Internal server error',
    error: process.env.NODE_ENV === 'development' ? err.message : undefined
  });
});

app.use('*', (req, res) => {
  res.status(404).json({
    code: 404,
    message: 'Endpoint not found'
  });
});

async function initializeQueueService() {
  console.log('Initializing async queue service...');
  
  try {
    await queueService.initialize();
    console.log('Redis queue service connected and initialized');
  } catch (error) {
    console.warn('Redis connection failed, queue service will be limited:', error.message);
    console.warn('Please ensure Redis is running for full queue persistence support');
  }
  
  queueService.registerQueue(QUEUES.MEDIA_PROCESSING, { concurrency: 2 });
  queueService.registerQueue(QUEUES.DISTRIBUTION, { concurrency: 3 });
  queueService.registerQueue(QUEUES.THUMBNAIL, { concurrency: 5 });
  
  queueService.registerWorker(QUEUES.MEDIA_PROCESSING, async (job, helpers) => {
    console.log(`[QueueWorker] Processing job: ${job.id}, type: ${job.type}`);
    return mediaProcessingWorker.execute(job, helpers);
  });
  
  queueService.on('job:added', (job) => {
    websocketService.notifyJobProgress(job.id, job.type, 0, 'queued');
  });
  
  queueService.on('job:start', (job) => {
    websocketService.notifyJobProgress(job.id, job.type, 0, 'processing');
  });
  
  queueService.on('job:progress', (job, progress) => {
    websocketService.notifyJobProgress(job.id, job.type, progress, 'processing');
  });
  
  queueService.on('job:completed', (job, result) => {
    websocketService.notifyJobCompleted(job.id, job.type, result);
    console.log(`[QueueWorker] Job completed: ${job.id}`);
  });
  
  queueService.on('job:failed', (job, error) => {
    websocketService.notifyJobFailed(job.id, job.type, error.message);
    console.error(`[QueueWorker] Job failed: ${job.id}, error: ${error.message}`);
  });
  
  console.log('Async queue service initialized');
}

function initializeWebSocketService() {
  console.log('Initializing WebSocket service...');
  
  websocketService.initialize(server);
  
  console.log('WebSocket service initialized');
}

async function initializeServices() {
  try {
    console.log('Connecting to MongoDB...');
    await mongoose.connect(databaseConfig.uri);
    console.log('MongoDB connected successfully');

    console.log('Initializing storage service...');
    await storageService.ensureBucketExists();
    console.log('Storage service initialized');

    console.log('Initializing upload service...');
    await uploadService.ensureTempDirExists();
    console.log('Upload service initialized');
    
    initializeQueueService();
    initializeWebSocketService();

  } catch (error) {
    console.error('Service initialization failed:', error);
    throw error;
  }
}

process.on('SIGINT', async () => {
  console.log('Shutting down gracefully...');
  
  try {
    queueService.shutdown();
    websocketService.shutdown();
    
    await mongoose.connection.close();
    console.log('MongoDB connection closed');
  } catch (error) {
    console.error('Error during shutdown:', error);
  }
  
  process.exit(0);
});

process.on('unhandledRejection', (reason, promise) => {
  console.error('Unhandled Rejection at:', promise, 'reason:', reason);
});

process.on('uncaughtException', (error) => {
  console.error('Uncaught Exception:', error);
});

async function startServer() {
  try {
    await initializeServices();
    
    server.listen(PORT, () => {
      console.log(`
╔════════════════════════════════════════════════════════════╗
║                    MediaHub API Server                       ║
╠════════════════════════════════════════════════════════════╣
║  Environment: ${(process.env.NODE_ENV || 'development').padEnd(43)}║
║  Port:        ${String(PORT).padEnd(43)}║
║  Started at:  ${new Date().toISOString().padEnd(43)}║
╠════════════════════════════════════════════════════════════╣
║  Enabled Features:                                           ║
║  ├── ✅  Session Isolated Storage (per-user, per-session)   ║
║  ├── ✅  Async Queue Processing (media processing)           ║
║  ├── ✅  WebSocket Real-time Notifications                   ║
║  ├── ✅  Distributed Lock Management                         ║
║  └── ✅  Thumbnail Lazy Loading Support                      ║
╠════════════════════════════════════════════════════════════╣
║  API Endpoints:                                              ║
║  ├── GET  /api/health                                       ║
║  ├── GET  /api/v1/config                                    ║
║  ├── GET  /api/v1/queue/stats                               ║
║  ├── GET  /api/v1/websocket/stats                           ║
║  ├── POST /api/v1/media/upload/session                     ║
║  ├── POST /api/v1/media/upload/chunk                       ║
║  ├── POST /api/v1/media/upload/complete                    ║
║  ├── GET  /api/v1/media                                    ║
║  ├── GET  /api/v1/reviews/pending                          ║
║  └── GET  /api/v1/distribution/channels                    ║
╠════════════════════════════════════════════════════════════╣
║  WebSocket Endpoint:                                        ║
║  └── ws://localhost:${String(PORT).padEnd(42)}║
║      Path: /ws                                               ║
╚════════════════════════════════════════════════════════════╝
      `);
    });
  } catch (error) {
    console.error('Failed to start server:', error);
    process.exit(1);
  }
}

startServer();

module.exports = { app, server };
