const { createClient } = require('redis');
require('dotenv').config();

const redisConfig = {
  url: process.env.REDIS_URL || null,
  host: process.env.REDIS_HOST || 'localhost',
  port: parseInt(process.env.REDIS_PORT) || 6379,
  password: process.env.REDIS_PASSWORD || undefined,
  database: parseInt(process.env.REDIS_DB) || 0,
  retryStrategy: (times) => {
    if (times > 10) {
      console.error('[Redis] 重连次数过多，停止重连');
      return null;
    }
    const delay = Math.min(times * 100, 3000);
    console.log(`[Redis] 尝试重连... (第 ${times} 次)`);
    return delay;
  }
};

let redisClient = null;
let isConnected = false;

const connectRedis = async () => {
  if (redisClient && isConnected) {
    return redisClient;
  }

  try {
    const clientOptions = redisConfig.url 
      ? { url: redisConfig.url }
      : {
          socket: {
            host: redisConfig.host,
            port: redisConfig.port,
            reconnectStrategy: redisConfig.retryStrategy
          },
          password: redisConfig.password,
          database: redisConfig.database
        };

    redisClient = createClient(clientOptions);

    redisClient.on('connect', () => {
      console.log('[Redis] 正在连接...');
    });

    redisClient.on('ready', () => {
      isConnected = true;
      console.log('[Redis] 连接成功');
    });

    redisClient.on('error', (error) => {
      console.error('[Redis] 连接错误:', error.message);
      isConnected = false;
    });

    redisClient.on('end', () => {
      console.log('[Redis] 连接已断开');
      isConnected = false;
    });

    await redisClient.connect();
    return redisClient;

  } catch (error) {
    console.error('[Redis] 连接失败:', error.message);
    console.log('[Redis] 将使用内存队列作为备用方案');
    return null;
  }
};

const getRedisClient = async () => {
  if (!redisClient || !isConnected) {
    await connectRedis();
  }
  return redisClient;
};

const isRedisAvailable = () => {
  return isConnected;
};

const disconnectRedis = async () => {
  if (redisClient) {
    await redisClient.quit();
    isConnected = false;
    console.log('[Redis] 已断开连接');
  }
};

const getBullConfig = () => {
  if (redisConfig.url) {
    return {
      redis: {
        url: redisConfig.url
      }
    };
  }
  return {
    redis: {
      host: redisConfig.host,
      port: redisConfig.port,
      password: redisConfig.password,
      db: redisConfig.database
    }
  };
};

module.exports = {
  connectRedis,
  getRedisClient,
  isRedisAvailable,
  disconnectRedis,
  getBullConfig,
  redisConfig
};
