require('dotenv').config();
const express = require('express');
const http = require('http');
const { Server } = require('socket.io');
const cors = require('cors');
const mongoose = require('mongoose');

const collaborationService = require('./services/CollaborationService');
const searchService = require('./services/SearchService');
const exportService = require('./services/ExportService');

const documentsRouter = require('./routes/documents');
const foldersRouter = require('./routes/folders');
const versionsRouter = require('./routes/versions');
const searchRouter = require('./routes/search');
const commentsRouter = require('./routes/comments');
const exportRouter = require('./routes/export');

const app = express();
const server = http.createServer(app);

const PORT = process.env.PORT || 3001;
const MONGODB_URI = process.env.MONGODB_URI || 'mongodb://localhost:27017/wikihub';
const FRONTEND_URL = process.env.FRONTEND_URL || 'http://localhost:3000';

app.use(cors({
  origin: [FRONTEND_URL, 'http://localhost:3000'],
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

app.get('/api/v1/health', (req, res) => {
  res.json({
    code: 200,
    data: {
      status: 'healthy',
      timestamp: new Date().toISOString(),
      service: 'wikihub-backend',
      mongodb: mongoose.connection.readyState === 1 ? 'connected' : 'disconnected'
    }
  });
});

app.get('/api/v1/status', (req, res) => {
  res.json({
    code: 200,
    data: {
      service: 'WikiHub Knowledge Base Platform',
      version: '1.0.0',
      features: [
        'Real-time Collaboration',
        'Version History',
        'Document Management',
        'Full-text Search',
        'Comments & Annotations',
        'PDF/HTML Export'
      ],
      endpoints: {
        documents: '/api/v1/documents',
        folders: '/api/v1/folders',
        versions: '/api/v1/versions',
        search: '/api/v1/search',
        comments: '/api/v1/comments',
        export: '/api/v1/export'
      }
    }
  });
});

app.use('/api/v1/documents', documentsRouter);
app.use('/api/v1/folders', foldersRouter);
app.use('/api/v1/versions', versionsRouter);
app.use('/api/v1/search', searchRouter);
app.use('/api/v1/comments', commentsRouter);
app.use('/api/v1/export', exportRouter);

app.use((err, req, res, next) => {
  console.error('Error:', err);
  
  if (err.name === 'ValidationError') {
    return res.status(400).json({
      code: 400,
      message: 'Validation error',
      errors: Object.values(err.errors).map(e => e.message)
    });
  }
  
  if (err.name === 'CastError') {
    return res.status(400).json({
      code: 400,
      message: 'Invalid ID format'
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
    message: 'Endpoint not found',
    path: req.originalUrl
  });
});

const io = new Server(server, {
  cors: {
    origin: [FRONTEND_URL, 'http://localhost:3000'],
    methods: ['GET', 'POST'],
    credentials: true
  },
  pingTimeout: 60000,
  pingInterval: 25000
});

async function initServices() {
  try {
    console.log('Initializing services...');
    
    console.log('Connecting to MongoDB...');
    await mongoose.connect(MONGODB_URI, {
      useNewUrlParser: true,
      useUnifiedTopology: true
    });
    console.log('MongoDB connected successfully');
    
    console.log('Initializing search service...');
    await searchService.init();
    
    console.log('Initializing export service...');
    await exportService.init();
    
    console.log('Initializing collaboration service...');
    collaborationService.init(io);
    
    console.log('All services initialized successfully');
    
  } catch (error) {
    console.error('Service initialization failed:', error);
    throw error;
  }
}

async function startServer() {
  try {
    await initServices();
    
    server.listen(PORT, () => {
      console.log(`\n========================================`);
      console.log(`  WikiHub Backend Server Started`);
      console.log(`========================================`);
      console.log(`  HTTP Server: http://localhost:${PORT}`);
      console.log(`  WebSocket: ws://localhost:${PORT}`);
      console.log(`  Environment: ${process.env.NODE_ENV || 'development'}`);
      console.log(`========================================\n`);
    });
    
    server.on('error', (error) => {
      if (error.syscall !== 'listen') {
        throw error;
      }
      
      switch (error.code) {
        case 'EACCES':
          console.error(`Port ${PORT} requires elevated privileges`);
          process.exit(1);
          break;
        case 'EADDRINUSE':
          console.error(`Port ${PORT} is already in use`);
          process.exit(1);
          break;
        default:
          throw error;
      }
    });
    
  } catch (error) {
    console.error('Server startup failed:', error);
    process.exit(1);
  }
}

process.on('SIGTERM', async () => {
  console.log('SIGTERM received, shutting down gracefully...');
  
  try {
    if (server.listening) {
      server.close();
    }
    
    await mongoose.connection.close();
    await exportService.close();
    
    console.log('Graceful shutdown completed');
    process.exit(0);
  } catch (error) {
    console.error('Shutdown error:', error);
    process.exit(1);
  }
});

process.on('SIGINT', async () => {
  console.log('\nSIGINT received, shutting down gracefully...');
  
  try {
    if (server.listening) {
      server.close();
    }
    
    await mongoose.connection.close();
    await exportService.close();
    
    console.log('Graceful shutdown completed');
    process.exit(0);
  } catch (error) {
    console.error('Shutdown error:', error);
    process.exit(1);
  }
});

process.on('uncaughtException', (error) => {
  console.error('Uncaught Exception:', error);
});

process.on('unhandledRejection', (reason, promise) => {
  console.error('Unhandled Rejection at:', promise, 'reason:', reason);
});

startServer();

module.exports = { app, server, io };
