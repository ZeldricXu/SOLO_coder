const express = require('express');
const cors = require('cors');
const config = require('./config');
const routes = require('./routes');
const messageQueueService = require('./services/messageQueueService');

const app = express();

app.use(cors({
  origin: process.env.FRONTEND_URL || 'http://localhost:3000',
  credentials: true
}));

app.use(express.json({ limit: '10mb' }));
app.use(express.urlencoded({ extended: true, limit: '10mb' }));

app.use('/api/v1', routes);

app.get('/health', (req, res) => {
  res.json({
    code: 200,
    data: {
      status: 'ok',
      timestamp: new Date().toISOString(),
      queue_enabled: config.queue?.enabled || false,
      worker_enabled: config.queue?.workerEnabled || false
    }
  });
});

app.use((err, req, res, next) => {
  console.error('Error:', err);
  
  if (err.isOperational) {
    const statusCode = err.statusCode || 500;
    const errorResponse = {
      code: err.code || 'UNKNOWN_ERROR',
      message: err.message || 'An error occurred'
    };

    if (err.errors) {
      errorResponse.errors = err.errors;
    }

    if (err.ticket_id) {
      errorResponse.ticket_id = err.ticket_id;
    }
    if (err.ticket_name) {
      errorResponse.ticket_name = err.ticket_name;
    }
    if (err.remaining_quota !== undefined) {
      errorResponse.remaining_quota = err.remaining_quota;
    }
    if (err.alternatives) {
      errorResponse.alternatives = err.alternatives;
    }
    if (err.parsePath) {
      errorResponse.parse_path = err.parsePath;
    }

    res.status(statusCode).json(errorResponse);
    return;
  }

  res.status(err.statusCode || err.status || 500).json({
    code: err.code || 'INTERNAL_ERROR',
    message: err.message || 'Internal Server Error'
  });
});

app.use('*', (req, res) => {
  res.status(404).json({
    code: 404,
    message: 'Resource not found'
  });
});

const PORT = config.port;

app.listen(PORT, () => {
  console.log(`EventHub Backend Server running on port ${PORT}`);
  console.log(`API Base URL: http://localhost:${PORT}/api/v1`);
  
  if (config.queue?.enabled && config.queue?.workerEnabled) {
    console.log('Starting message queue worker...');
    messageQueueService.startWorker();
    console.log('Message queue worker started');
  }
});

process.on('SIGTERM', () => {
  console.log('SIGTERM received, shutting down...');
  if (messageQueueService.isRunning) {
    messageQueueService.stopWorker();
  }
  process.exit(0);
});

process.on('SIGINT', () => {
  console.log('SIGINT received, shutting down...');
  if (messageQueueService.isRunning) {
    messageQueueService.stopWorker();
  }
  process.exit(0);
});

module.exports = app;
