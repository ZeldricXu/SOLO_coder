const express = require('express');
const http = require('http');
const { Server } = require('socket.io');
const cors = require('cors');
const helmet = require('helmet');
require('dotenv').config();

const authRoutes = require('./routes/authRoutes');
const taskRoutes = require('./routes/taskRoutes');
const eventRoutes = require('./routes/eventRoutes');
const fileRoutes = require('./routes/fileRoutes');
const notificationRoutes = require('./routes/notificationRoutes');
const notificationWorker = require('./services/notificationWorker');
const redisNotificationQueue = require('./services/redisNotificationQueue');
const { connectRedis } = require('./config/redis');

const app = express();
const server = http.createServer(app);

app.use(helmet());
app.use(cors({
  origin: process.env.CORS_ORIGIN || 'http://localhost:3000',
  credentials: true
}));
app.use(express.json({ limit: '10mb' }));
app.use(express.urlencoded({ extended: true, limit: '10mb' }));

app.get('/api/health', (req, res) => {
  res.json({
    code: 200,
    message: 'TaskFlow API 服务运行正常',
    timestamp: new Date().toISOString()
  });
});

app.use('/api/v1/auth', authRoutes);
app.use('/api/v1/tasks', taskRoutes);
app.use('/api/v1/calendar/events', eventRoutes);
app.use('/api/v1/files', fileRoutes);
app.use('/api/v1/notifications', notificationRoutes);

app.get('/api/v1/queue/stats', async (req, res) => {
  try {
    const stats = await redisNotificationQueue.getQueueStats();
    res.json({
      code: 200,
      data: stats
    });
  } catch (error) {
    res.status(500).json({
      code: 500,
      message: '获取队列统计失败'
    });
  }
});

app.post('/api/v1/queue/retry-failed', async (req, res) => {
  try {
    const result = await redisNotificationQueue.retryFailedNotifications();
    res.json({
      code: 200,
      data: result
    });
  } catch (error) {
    res.status(500).json({
      code: 500,
      message: '重试失败任务出错'
    });
  }
});

app.use((err, req, res, next) => {
  console.error('错误:', err);
  
  if (err instanceof SyntaxError && err.status === 400 && 'body' in err) {
    return res.status(400).json({
      code: 400,
      message: '请求体格式错误'
    });
  }

  res.status(500).json({
    code: 500,
    message: '服务器内部错误'
  });
});

app.use('*', (req, res) => {
  res.status(404).json({
    code: 404,
    message: '接口不存在'
  });
});

const io = new Server(server, {
  cors: {
    origin: process.env.CORS_ORIGIN || 'http://localhost:3000',
    methods: ['GET', 'POST'],
    credentials: true
  }
});

const jwt = require('jsonwebtoken');

io.use(async (socket, next) => {
  try {
    const token = socket.handshake.auth.token;
    if (!token) {
      return next(new Error('未提供认证令牌'));
    }

    const decoded = jwt.verify(token, process.env.JWT_SECRET);
    socket.userId = decoded.userId;
    next();
  } catch (error) {
    next(new Error('令牌无效'));
  }
});

io.on('connection', (socket) => {
  console.log(`用户连接: ${socket.userId}`);
  
  socket.join(`user:${socket.userId}`);

  socket.on('disconnect', () => {
    console.log(`用户断开连接: ${socket.userId}`);
  });

  socket.on('join_task', (taskId) => {
    socket.join(`task:${taskId}`);
    console.log(`用户 ${socket.userId} 加入任务房间: ${taskId}`);
  });

  socket.on('leave_task', (taskId) => {
    socket.leave(`task:${taskId}`);
    console.log(`用户 ${socket.userId} 离开任务房间: ${taskId}`);
  });
});

notificationWorker.initializeSocketIO(io);
redisNotificationQueue.initializeSocketIO(io);

const PORT = process.env.PORT || 3001;

const startServer = async () => {
  console.log('正在初始化服务...');
  
  console.log('正在连接 Redis...');
  await connectRedis();
  
  console.log('正在启动通知队列 Worker...');
  await redisNotificationQueue.startWorker();
  await notificationWorker.startWorker();
  
  server.listen(PORT, () => {
    console.log(`TaskFlow 服务器运行在端口 ${PORT}`);
    console.log(`API 地址: http://localhost:${PORT}/api`);
    console.log(`Socket.IO 地址: http://localhost:${PORT}`);
    console.log(`Redis NotificationQueue 已启动`);
    console.log(`NotificationWorker 已启动`);
  });
};

startServer();

process.on('SIGTERM', async () => {
  console.log('正在关闭服务...');
  await redisNotificationQueue.stopWorker();
  process.exit(0);
});

process.on('SIGINT', async () => {
  console.log('正在关闭服务...');
  await redisNotificationQueue.stopWorker();
  process.exit(0);
});
