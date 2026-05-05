const app = require('./app');
const logger = require('./config/logger');
const { defaultWorker } = require('./worker');
require('dotenv').config();

const PORT = process.env.PORT || 3001;
const HOST = process.env.HOST || '0.0.0.0';
const ENABLE_WORKER = process.env.ENABLE_WORKER !== 'false';

const server = app.listen(PORT, HOST, async () => {
  logger.info(`CodeReview 后端服务已启动`);
  logger.info(`服务地址: http://${HOST}:${PORT}`);
  logger.info(`API文档: http://${HOST}:${PORT}/api/v1`);
  logger.info(`环境: ${process.env.NODE_ENV || 'development'}`);
  
  if (ENABLE_WORKER) {
    logger.info('正在启动后台分析 Worker...');
    try {
      await defaultWorker.start();
      logger.info('后台分析 Worker 已成功启动');
    } catch (error) {
      logger.error('启动 Worker 失败: %s', error.message);
    }
  } else {
    logger.info('Worker 已禁用 (ENABLE_WORKER=false)');
  }
  
  console.log('\n========================================');
  console.log('  CodeReview 代码审查平台');
  console.log('========================================');
  console.log(`后端服务已启动: http://localhost:${PORT}`);
  console.log('API端点:');
  console.log('  - POST /api/v1/analysis/commit - 代码提交分析');
  console.log('  - GET  /api/v1/analysis/event/:id/status - 事件状态查询');
  console.log('  - GET  /api/v1/analysis/queue/stats - 队列统计');
  console.log('  - GET  /api/v1/analysis/complexity/:id - 复杂度分析结果');
  console.log('  - GET  /api/v1/review/workload - 审查人员负载统计');
  console.log('  - POST /api/v1/review/comment - 创建审查意见');
  console.log('  - GET  /api/v1/report/quality - 质量报告');
  console.log('  - GET  /api/v1/code/diff/:commit_id - 代码差异');
  console.log('========================================');
  console.log('Worker 状态:', ENABLE_WORKER ? '已启用' : '已禁用');
  console.log('========================================\n');
});

process.on('SIGTERM', async () => {
  logger.info('收到SIGTERM信号，正在关闭服务...');
  
  if (ENABLE_WORKER) {
    logger.info('正在停止后台 Worker...');
    try {
      await defaultWorker.stop();
      logger.info('后台 Worker 已停止');
    } catch (error) {
      logger.error('停止 Worker 失败: %s', error.message);
    }
  }
  
  server.close(() => {
    logger.info('服务已优雅关闭');
    process.exit(0);
  });
});

process.on('SIGINT', async () => {
  logger.info('收到SIGINT信号，正在关闭服务...');
  
  if (ENABLE_WORKER) {
    logger.info('正在停止后台 Worker...');
    try {
      await defaultWorker.stop();
      logger.info('后台 Worker 已停止');
    } catch (error) {
      logger.error('停止 Worker 失败: %s', error.message);
    }
  }
  
  server.close(() => {
    logger.info('服务已优雅关闭');
    process.exit(0);
  });
});

process.on('uncaughtException', (error) => {
  logger.error('未捕获的异常: %s', error.message);
  logger.error('异常堆栈: %s', error.stack);
  process.exit(1);
});

process.on('unhandledRejection', (reason, promise) => {
  logger.error('未处理的Promise拒绝: %s', reason);
  logger.error('Promise: %o', promise);
});

module.exports = server;
