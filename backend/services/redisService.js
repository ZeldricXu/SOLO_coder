const Redis = require('ioredis');
const config = require('../config/config');
const logger = require('../utils/logger');

class RedisService {
  constructor() {
    this.client = null;
    this.isConnected = false;
    this.reconnectAttempts = 0;
  }

  async connect() {
    if (this.isConnected && this.client) {
      return this.client;
    }

    try {
      const redisConfig = config.redis;
      const options = {
        host: redisConfig.host,
        port: redisConfig.port,
        password: redisConfig.password,
        db: redisConfig.db,
        keyPrefix: redisConfig.keyPrefix,
        retryStrategy: (times) => {
          this.reconnectAttempts = times;
          const delay = Math.min(times * 50, 2000);
          logger.warn(`Redis重连尝试: 第${times}次, 延迟${delay}ms`);
          return delay;
        }
      };

      this.client = new Redis(options);

      this.client.on('connect', () => {
        this.isConnected = true;
        this.reconnectAttempts = 0;
        logger.info(`Redis连接成功: ${redisConfig.host}:${redisConfig.port}`);
      });

      this.client.on('ready', () => {
        logger.info('Redis客户端已就绪');
      });

      this.client.on('error', (error) => {
        this.isConnected = false;
        logger.error(`Redis错误: ${error.message}`, { error });
      });

      this.client.on('close', () => {
        this.isConnected = false;
        logger.info('Redis连接已关闭');
      });

      this.client.on('reconnecting', (delay) => {
        logger.debug(`Redis正在重连: 延迟${delay}ms`);
      });

      await new Promise((resolve, reject) => {
        const timeout = setTimeout(() => {
          reject(new Error('Redis连接超时'));
        }, 5000);

        this.client.once('ready', () => {
          clearTimeout(timeout);
          resolve();
        });

        this.client.once('error', (error) => {
          clearTimeout(timeout);
          reject(error);
        });
      });

      return this.client;
    } catch (error) {
      logger.error(`Redis连接失败: ${error.message}`, { error });
      this.isConnected = false;
      throw error;
    }
  }

  getClient() {
    return this.client;
  }

  async get(key) {
    if (!this.isConnected) {
      await this.connect();
    }
    return this.client.get(key);
  }

  async set(key, value, ttl = null) {
    if (!this.isConnected) {
      await this.connect();
    }
    
    if (ttl) {
      return this.client.set(key, value, 'EX', Math.ceil(ttl / 1000));
    }
    return this.client.set(key, value);
  }

  async del(key) {
    if (!this.isConnected) {
      await this.connect();
    }
    return this.client.del(key);
  }

  async exists(key) {
    if (!this.isConnected) {
      await this.connect();
    }
    return this.client.exists(key);
  }

  async expire(key, ttl) {
    if (!this.isConnected) {
      await this.connect();
    }
    return this.client.expire(key, Math.ceil(ttl / 1000));
  }

  async ttl(key) {
    if (!this.isConnected) {
      await this.connect();
    }
    return this.client.ttl(key);
  }

  async keys(pattern) {
    if (!this.isConnected) {
      await this.connect();
    }
    return this.client.keys(pattern);
  }

  async hget(key, field) {
    if (!this.isConnected) {
      await this.connect();
    }
    return this.client.hget(key, field);
  }

  async hset(key, field, value) {
    if (!this.isConnected) {
      await this.connect();
    }
    return this.client.hset(key, field, value);
  }

  async hdel(key, field) {
    if (!this.isConnected) {
      await this.connect();
    }
    return this.client.hdel(key, field);
  }

  async hgetall(key) {
    if (!this.isConnected) {
      await this.connect();
    }
    return this.client.hgetall(key);
  }

  async lpush(key, value) {
    if (!this.isConnected) {
      await this.connect();
    }
    return this.client.lpush(key, value);
  }

  async rpush(key, value) {
    if (!this.isConnected) {
      await this.connect();
    }
    return this.client.rpush(key, value);
  }

  async lpop(key) {
    if (!this.isConnected) {
      await this.connect();
    }
    return this.client.lpop(key);
  }

  async rpop(key) {
    if (!this.isConnected) {
      await this.connect();
    }
    return this.client.rpop(key);
  }

  async blpop(key, timeout = 0) {
    if (!this.isConnected) {
      await this.connect();
    }
    return this.client.blpop(key, timeout);
  }

  async brpop(key, timeout = 0) {
    if (!this.isConnected) {
      await this.connect();
    }
    return this.client.brpop(key, timeout);
  }

  async llen(key) {
    if (!this.isConnected) {
      await this.connect();
    }
    return this.client.llen(key);
  }

  async lrange(key, start, stop) {
    if (!this.isConnected) {
      await this.connect();
    }
    return this.client.lrange(key, start, stop);
  }

  async zadd(key, score, value) {
    if (!this.isConnected) {
      await this.connect();
    }
    return this.client.zadd(key, score, value);
  }

  async zrange(key, start, stop, withScores = false) {
    if (!this.isConnected) {
      await this.connect();
    }
    if (withScores) {
      return this.client.zrange(key, start, stop, 'WITHSCORES');
    }
    return this.client.zrange(key, start, stop);
  }

  async zrangebyscore(key, min, max) {
    if (!this.isConnected) {
      await this.connect();
    }
    return this.client.zrangebyscore(key, min, max);
  }

  async zrem(key, value) {
    if (!this.isConnected) {
      await this.connect();
    }
    return this.client.zrem(key, value);
  }

  async zcard(key) {
    if (!this.isConnected) {
      await this.connect();
    }
    return this.client.zcard(key);
  }

  async zcount(key, min, max) {
    if (!this.isConnected) {
      await this.connect();
    }
    return this.client.zcount(key, min, max);
  }

  async incr(key) {
    if (!this.isConnected) {
      await this.connect();
    }
    return this.client.incr(key);
  }

  async decr(key) {
    if (!this.isConnected) {
      await this.connect();
    }
    return this.client.decr(key);
  }

  async setnx(key, value) {
    if (!this.isConnected) {
      await this.connect();
    }
    return this.client.setnx(key, value);
  }

  async psetex(key, ttl, value) {
    if (!this.isConnected) {
      await this.connect();
    }
    return this.client.psetex(key, ttl, value);
  }

  async ping() {
    if (!this.isConnected) {
      return 'PONG';
    }
    return this.client.ping();
  }

  async disconnect() {
    if (this.client) {
      await this.client.quit();
      this.isConnected = false;
      logger.info('Redis已断开连接');
    }
  }

  isReady() {
    return this.isConnected && this.client !== null;
  }

  getStatus() {
    return {
      isConnected: this.isConnected,
      reconnectAttempts: this.reconnectAttempts,
      config: {
        host: config.redis.host,
        port: config.redis.port,
        db: config.redis.db
      }
    };
  }
}

const redisService = new RedisService();

module.exports = redisService;
