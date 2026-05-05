const Redis = require('ioredis');
const config = require('./index');

const redisConfig = {
  host: process.env.REDIS_HOST || 'localhost',
  port: parseInt(process.env.REDIS_PORT) || 6379,
  password: process.env.REDIS_PASSWORD || undefined,
  db: parseInt(process.env.REDIS_DB) || 0,
  keyPrefix: process.env.REDIS_PREFIX || 'eventhub:',
  retryStrategy: (times) => {
    const delay = Math.min(times * 50, 2000);
    return delay;
  },
  maxRetriesPerRequest: 3,
  enableReadyCheck: true
};

let redisClient = null;
let redisSubscriber = null;

function createRedisClient(options = {}) {
  const client = new Redis({
    ...redisConfig,
    ...options
  });

  client.on('connect', () => {
    console.log('Redis client connected');
  });

  client.on('ready', () => {
    console.log('Redis client ready');
  });

  client.on('error', (err) => {
    console.error('Redis client error:', err.message);
  });

  client.on('close', () => {
    console.log('Redis client connection closed');
  });

  client.on('reconnecting', (time) => {
    console.log(`Redis client reconnecting in ${time}ms`);
  });

  return client;
}

function getRedisClient() {
  if (!redisClient) {
    redisClient = createRedisClient();
  }
  return redisClient;
}

function getRedisSubscriber() {
  if (!redisSubscriber) {
    redisSubscriber = createRedisClient();
  }
  return redisSubscriber;
}

async function closeRedisConnections() {
  if (redisClient) {
    await redisClient.quit();
    redisClient = null;
  }
  if (redisSubscriber) {
    await redisSubscriber.quit();
    redisSubscriber = null;
  }
}

async function isRedisAvailable() {
  try {
    const client = getRedisClient();
    await client.ping();
    return true;
  } catch (err) {
    console.error('Redis is not available:', err.message);
    return false;
  }
}

module.exports = {
  getRedisClient,
  getRedisSubscriber,
  closeRedisConnections,
  isRedisAvailable,
  createRedisClient,
  redisConfig
};
