const express = require('express');
const cors = require('cors');
const bodyParser = require('body-parser');
const path = require('path');

const config = require('./config/config');
const connectDB = require('./config/db');
const logger = require('./utils/logger');
const routes = require('./routes');

const app = express();

app.use(cors({
  origin: ['http://localhost:3000', 'http://127.0.0.1:3000'],
  credentials: true,
  methods: ['GET', 'POST', 'PUT', 'DELETE', 'OPTIONS'],
  allowedHeaders: ['Content-Type', 'Authorization', 'X-User-Id']
}));

app.use(bodyParser.json({ limit: '50mb' }));
app.use(bodyParser.urlencoded({ limit: '50mb', extended: true }));

app.use((req, res, next) => {
  const userId = req.headers['x-user-id'] || 'user_001';
  logger.info(`${req.method} ${req.path} - User: ${userId}`);
  next();
});

app.use('/api/v1', routes);

app.get('/api/health', (req, res) => {
  res.json({
    status: 'ok',
    service: 'DocHub API',
    timestamp: new Date().toISOString(),
    environment: config.nodeEnv
  });
});

app.get('/api', (req, res) => {
  res.json({
    name: 'DocHub API',
    version: '1.0.0',
    endpoints: {
      documents: '/api/v1/docs',
      versions: '/api/v1/versions',
      search: '/api/v1/search',
      categories: '/api/v1/categories',
      shares: '/api/v1/shares',
      comments: '/api/v1/comments',
      favorites: '/api/v1/favorites'
    }
  });
});

app.use((err, req, res, next) => {
  logger.error(`未处理的错误: ${err.message}`, { error: err });
  
  if (err.name === 'ValidationError') {
    return res.status(400).json({
      code: 400,
      error: '数据验证失败',
      details: err.message
    });
  }

  if (err.name === 'CastError') {
    return res.status(400).json({
      code: 400,
      error: '无效的ID格式'
    });
  }

  res.status(500).json({
    code: 500,
    error: '服务器内部错误'
  });
});

app.use('*', (req, res) => {
  res.status(404).json({
    code: 404,
    error: 'API端点不存在'
  });
});

const startServer = async () => {
  try {
    await connectDB();
    
    app.listen(config.port, () => {
      logger.info(`DocHub API 服务器启动在端口 ${config.port}`);
      logger.info(`环境: ${config.nodeEnv}`);
      logger.info(`API 基础路径: http://localhost:${config.port}/api/v1`);
    });
  } catch (error) {
    logger.error(`服务器启动失败: ${error.message}`, { error });
    process.exit(1);
  }
};

startServer();

process.on('uncaughtException', (err) => {
  logger.error(`未捕获的异常: ${err.message}`, { error: err });
  process.exit(1);
});

process.on('unhandledRejection', (reason, promise) => {
  logger.error('未处理的 Promise 拒绝', { reason });
});

module.exports = app;
