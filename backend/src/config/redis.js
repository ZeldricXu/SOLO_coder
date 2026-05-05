const Redis = require('ioredis');
const logger = require('../config/logger');

const redisConfig = {
  host: process.env.REDIS_HOST || 'localhost',
  port: parseInt(process.env.REDIS_PORT) || 6379,
  password: process.env.REDIS_PASSWORD || undefined,
  db: parseInt(process.env.REDIS_DB) || 0,
  keyPrefix: process.env.REDIS_KEY_PREFIX || 'codereview:',
  retryStrategy: (times) => {
    const delay = Math.min(times * 50, 2000);
    return delay;
  },
  reconnectOnError: (err) => {
    const targetError = 'READONLY';
    if (err.message.slice(0, targetError.length) === targetError) {
      return true;
    }
    return false;
  }
};

let redisClient = null;
let subscriberClient = null;

function getRedisClient() {
  if (!redisClient) {
    redisClient = new Redis(redisConfig);
    
    redisClient.on('connect', () => {
      logger.info('Redis 连接已建立: host=%s, port=%d', redisConfig.host, redisConfig.port);
    });
    
    redisClient.on('error', (err) => {
      logger.error('Redis 连接错误: %s', err.message);
    });
    
    redisClient.on('close', () => {
      logger.warn('Redis 连接已关闭');
    });
    
    redisClient.on('reconnecting', (delay) => {
      logger.info('Redis 正在重连: delay=%dms', delay);
    });
  }
  
  return redisClient;
}

function getSubscriberClient() {
  if (!subscriberClient) {
    subscriberClient = new Redis(redisConfig);
    
    subscriberClient.on('connect', () => {
      logger.info('Redis 订阅客户端已连接');
    });
    
    subscriberClient.on('error', (err) => {
      logger.error('Redis 订阅客户端错误: %s', err.message);
    });
  }
  
  return subscriberClient;
}

async function closeRedis() {
  if (redisClient) {
    await redisClient.quit();
    redisClient = null;
    logger.info('Redis 客户端已关闭');
  }
  
  if (subscriberClient) {
    await subscriberClient.quit();
    subscriberClient = null;
    logger.info('Redis 订阅客户端已关闭');
  }
}

async function isRedisAvailable() {
  try {
    const client = getRedisClient();
    const pong = await client.ping();
    return pong === 'PONG';
  } catch (error) {
    logger.warn('Redis 不可用: %s', error.message);
    return false;
  }
}

const REDIS_KEYS = {
  PENDING_QUEUE: 'queue:pending',
  PENDING_HIGH: 'queue:pending:high',
  PENDING_MEDIUM: 'queue:pending:medium',
  PENDING_LOW: 'queue:pending:low',
  PROCESSING_SET: 'queue:processing',
  FAILED_SET: 'queue:failed',
  COMPLETED_SET: 'queue:completed',
  EVENT_PREFIX: 'event:',
  NOTIFICATION_CHANNEL: 'channel:queue_notification',
  STATS_KEY: 'queue:stats'
};

module.exports = {
  getRedisClient,
  getSubscriberClient,
  closeRedis,
  isRedisAvailable,
  redisConfig,
  REDIS_KEYS
};
