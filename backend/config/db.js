const mongoose = require('mongoose');
const config = require('./config');
const logger = require('../utils/logger');

const connectDB = async () => {
  try {
    const conn = await mongoose.connect(config.mongodb.uri, config.mongodb.options);
    logger.info(`MongoDB 连接成功: ${conn.connection.host}`);
    return conn;
  } catch (error) {
    logger.error(`MongoDB 连接失败: ${error.message}`, { error });
    process.exit(1);
  }
};

mongoose.connection.on('error', (err) => {
  logger.error(`MongoDB 连接错误: ${err.message}`, { error: err });
});

mongoose.connection.on('disconnected', () => {
  logger.warn('MongoDB 连接已断开');
});

mongoose.connection.on('reconnected', () => {
  logger.info('MongoDB 已重新连接');
});

process.on('SIGINT', async () => {
  try {
    await mongoose.connection.close();
    logger.info('MongoDB 连接已关闭（通过 SIGINT）');
    process.exit(0);
  } catch (error) {
    logger.error('关闭 MongoDB 连接失败', { error });
    process.exit(1);
  }
});

module.exports = connectDB;
