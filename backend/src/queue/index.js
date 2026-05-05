const { BaseEventQueue, EVENT_TYPES, EVENT_PRIORITY, EVENT_STATUS } = require('./baseQueue');
const { MemoryEventQueue, defaultQueue } = require('./memoryQueue');
const { RedisEventQueue, getRedisQueue } = require('./redisQueue');
const { isRedisAvailable } = require('../config/redis');
const logger = require('../config/logger');

async function getDefaultQueue() {
  const useRedis = process.env.QUEUE_BACKEND === 'redis';
  
  if (useRedis) {
    try {
      const available = await isRedisAvailable();
      if (available) {
        logger.info('使用 Redis 作为队列后端');
        return getRedisQueue();
      } else {
        logger.warn('Redis 不可用，使用内存队列作为备用');
      }
    } catch (error) {
      logger.warn('Redis 连接失败，使用内存队列: %s', error.message);
    }
  }
  
  logger.info('使用内存队列');
  return defaultQueue;
}

module.exports = {
  BaseEventQueue,
  MemoryEventQueue,
  RedisEventQueue,
  defaultQueue,
  getRedisQueue,
  getDefaultQueue,
  EVENT_TYPES,
  EVENT_PRIORITY,
  EVENT_STATUS
};
