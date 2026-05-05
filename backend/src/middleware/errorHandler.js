const logger = require('../config/logger');

const errorHandler = (err, req, res, next) => {
  logger.error('请求处理错误: %s', err.message);
  logger.error('错误堆栈: %s', err.stack);
  
  let statusCode = err.statusCode || 500;
  let message = err.message || 'Internal Server Error';
  
  if (err.name === 'ValidationError') {
    statusCode = 400;
    message = '请求参数验证失败';
  } else if (err.name === 'UnauthorizedError') {
    statusCode = 401;
    message = '未授权访问';
  } else if (err.name === 'NotFoundError') {
    statusCode = 404;
    message = '资源不存在';
  } else if (err.code === 'ECONNREFUSED') {
    message = '数据库连接失败';
  }
  
  res.status(statusCode).json({
    code: statusCode,
    message,
    error: process.env.NODE_ENV === 'development' ? err.stack : undefined
  });
};

const notFoundHandler = (req, res) => {
  res.status(404).json({
    code: 404,
    message: 'API路由不存在',
    path: req.path
  });
};

module.exports = {
  errorHandler,
  notFoundHandler
};
