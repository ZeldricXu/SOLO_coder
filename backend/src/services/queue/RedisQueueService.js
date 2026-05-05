const { getRedisClient, isRedisAvailable } = require('../../config/redis');
const config = require('../../config');
const { QueueError } = require('../../utils/errors');
const { v4: uuidv4 } = require('uuid');

class RedisQueueService {
  constructor() {
    this.queuePrefix = config.queue?.queueNames?.notifications || 'queue:notifications';
    this.processingSet = 'queue:processing';
    this.deadLetterQueue = config.queue?.deadLetterQueue || 'queue:dead_letter';
    this.maxRetries = config.queue?.maxRetries || 3;
    this.pollInterval = config.queue?.pollInterval || 5000;
    this.isRunning = false;
    this.redis = null;
  }

  async initialize() {
    if (!config.redis?.enabled && !config.queue?.useRedis) {
      console.log('Redis is not enabled, RedisQueueService will not be used');
      return false;
    }

    try {
      this.redis = getRedisClient();
      const available = await isRedisAvailable();
      if (!available) {
        console.warn('Redis is not available, queue operations may fail');
        return false;
      }
      console.log('RedisQueueService initialized');
      return true;
    } catch (error) {
      console.error('Failed to initialize RedisQueueService:', error);
      return false;
    }
  }

  async enqueue(message, priority = 0, scheduledAt = null) {
    if (!this.redis) {
      const initialized = await this.initialize();
      if (!initialized) {
        throw new QueueError('Redis queue is not available', this.queuePrefix);
      }
    }

    const messageId = uuidv4();
    const timestamp = scheduledAt ? new Date(scheduledAt).getTime() : Date.now();
    
    const messageData = {
      id: messageId,
      ...message,
      created_at: new Date().toISOString(),
      scheduled_at: scheduledAt || new Date().toISOString(),
      retry_count: 0,
      max_retries: this.maxRetries
    };

    try {
      const pipeline = this.redis.pipeline();
      pipeline.set(this.getMessageKey(messageId), JSON.stringify(messageData));
      pipeline.zadd(this.queuePrefix, timestamp, messageId);
      await pipeline.exec();

      console.log(`Message enqueued: ${messageId}`);
      return messageId;
    } catch (error) {
      console.error('Failed to enqueue message:', error);
      throw new QueueError('Failed to enqueue message', this.queuePrefix, error);
    }
  }

  async dequeue(batchSize = 1) {
    if (!this.redis) {
      const initialized = await this.initialize();
      if (!initialized) {
        throw new QueueError('Redis queue is not available', this.queuePrefix);
      }
    }

    const now = Date.now();
    const messages = [];

    try {
      const messageIds = await this.redis.zrangebyscore(
        this.queuePrefix,
        '-inf',
        now,
        'LIMIT',
        0,
        batchSize
      );

      if (messageIds.length === 0) {
        return [];
      }

      for (const messageId of messageIds) {
        const result = await this.redis.zrem(this.queuePrefix, messageId);
        if (result === 1) {
          const messageStr = await this.redis.get(this.getMessageKey(messageId));
          if (messageStr) {
            const messageData = JSON.parse(messageStr);
            messages.push(messageData);
          }
        }
      }

      return messages;
    } catch (error) {
      console.error('Failed to dequeue messages:', error);
      throw new QueueError('Failed to dequeue messages', this.queuePrefix, error);
    }
  }

  async acknowledge(messageId, success = true, error = null) {
    if (!this.redis) {
      return;
    }

    try {
      if (success) {
        const pipeline = this.redis.pipeline();
        pipeline.del(this.getMessageKey(messageId));
        pipeline.zrem(this.queuePrefix, messageId);
        pipeline.zrem(this.processingSet, messageId);
        await pipeline.exec();
        console.log(`Message acknowledged: ${messageId}`);
      } else {
        await this.handleFailure(messageId, error);
      }
    } catch (error) {
      console.error('Failed to acknowledge message:', error);
    }
  }

  async handleFailure(messageId, error = null) {
    if (!this.redis) {
      return;
    }

    try {
      const messageStr = await this.redis.get(this.getMessageKey(messageId));
      if (!messageStr) {
        console.warn(`Message not found for retry: ${messageId}`);
        return;
      }

      const messageData = JSON.parse(messageStr);
      messageData.retry_count = (messageData.retry_count || 0) + 1;
      messageData.last_error = error?.message || 'Unknown error';
      messageData.last_error_at = new Date().toISOString();

      if (messageData.retry_count >= (messageData.max_retries || this.maxRetries)) {
        console.log(`Message ${messageId} exceeded max retries, moving to dead letter queue`);
        await this.moveToDeadLetter(messageId, messageData);
      } else {
        const backoffMs = this.getBackoffTime(messageData.retry_count);
        const scheduledAt = Date.now() + backoffMs;

        const pipeline = this.redis.pipeline();
        pipeline.set(this.getMessageKey(messageId), JSON.stringify(messageData));
        pipeline.zadd(this.queuePrefix, scheduledAt, messageId);
        await pipeline.exec();

        console.log(`Message ${messageId} scheduled for retry at ${new Date(scheduledAt).toISOString()}`);
      }
    } catch (error) {
      console.error('Failed to handle message failure:', error);
    }
  }

  async moveToDeadLetter(messageId, messageData) {
    if (!this.redis) {
      return;
    }

    try {
      const deadLetterData = {
        ...messageData,
        dead_letter_at: new Date().toISOString(),
        original_queue: this.queuePrefix
      };

      const pipeline = this.redis.pipeline();
      pipeline.del(this.getMessageKey(messageId));
      pipeline.zrem(this.queuePrefix, messageId);
      pipeline.zadd(this.deadLetterQueue, Date.now(), JSON.stringify(deadLetterData));
      await pipeline.exec();

      console.log(`Message moved to dead letter queue: ${messageId}`);
    } catch (error) {
      console.error('Failed to move message to dead letter queue:', error);
    }
  }

  getBackoffTime(retryCount) {
    const baseDelay = 1000;
    const maxDelay = 60000;
    const delay = baseDelay * Math.pow(2, retryCount - 1);
    return Math.min(delay, maxDelay);
  }

  getMessageKey(messageId) {
    return `message:${messageId}`;
  }

  async getQueueStats() {
    if (!this.redis) {
      return {
        pending: 0,
        processing: 0,
        dead_letter: 0
      };
    }

    try {
      const [pendingCount, deadLetterCount] = await Promise.all([
        this.redis.zcard(this.queuePrefix),
        this.redis.zcard(this.deadLetterQueue)
      ]);

      return {
        pending: pendingCount,
        processing: 0,
        dead_letter: deadLetterCount,
        total: pendingCount + deadLetterCount
      };
    } catch (error) {
      console.error('Failed to get queue stats:', error);
      return {
        pending: 0,
        processing: 0,
        dead_letter: 0,
        error: error.message
      };
    }
  }

  async peekPending(limit = 10) {
    if (!this.redis) {
      return [];
    }

    try {
      const messageIds = await this.redis.zrangebyscore(
        this.queuePrefix,
        '-inf',
        Date.now(),
        'LIMIT',
        0,
        limit
      );

      const messages = [];
      for (const messageId of messageIds) {
        const messageStr = await this.redis.get(this.getMessageKey(messageId));
        if (messageStr) {
          messages.push(JSON.parse(messageStr));
        }
      }

      return messages;
    } catch (error) {
      console.error('Failed to peek pending messages:', error);
      return [];
    }
  }

  async requeueDeadLetter(messageId = null) {
    if (!this.redis) {
      return 0;
    }

    try {
      if (messageId) {
        const deadLetters = await this.redis.zrange(this.deadLetterQueue, 0, -1);
        for (const dlStr of deadLetters) {
          const dl = JSON.parse(dlStr);
          if (dl.id === messageId) {
            dl.retry_count = 0;
            dl.dead_letter_at = undefined;
            dl.last_error = undefined;
            
            const pipeline = this.redis.pipeline();
            pipeline.set(this.getMessageKey(messageId), JSON.stringify(dl));
            pipeline.zadd(this.queuePrefix, Date.now(), messageId);
            pipeline.zrem(this.deadLetterQueue, dlStr);
            await pipeline.exec();
            return 1;
          }
        }
        return 0;
      } else {
        const deadLetters = await this.redis.zrange(this.deadLetterQueue, 0, -1);
        let requeued = 0;

        for (const dlStr of deadLetters) {
          const dl = JSON.parse(dlStr);
          dl.retry_count = 0;
          dl.dead_letter_at = undefined;
          dl.last_error = undefined;

          const pipeline = this.redis.pipeline();
          pipeline.set(this.getMessageKey(dl.id), JSON.stringify(dl));
          pipeline.zadd(this.queuePrefix, Date.now(), dl.id);
          pipeline.zrem(this.deadLetterQueue, dlStr);
          await pipeline.exec();
          requeued++;
        }

        return requeued;
      }
    } catch (error) {
      console.error('Failed to requeue dead letter messages:', error);
      return 0;
    }
  }

  async clearQueue(queueType = 'pending') {
    if (!this.redis) {
      return 0;
    }

    try {
      if (queueType === 'pending') {
        const count = await this.redis.zcard(this.queuePrefix);
        await this.redis.del(this.queuePrefix);
        return count;
      } else if (queueType === 'dead_letter') {
        const count = await this.redis.zcard(this.deadLetterQueue);
        await this.redis.del(this.deadLetterQueue);
        return count;
      }
      return 0;
    } catch (error) {
      console.error('Failed to clear queue:', error);
      return 0;
    }
  }
}

const redisQueueService = new RedisQueueService();

module.exports = redisQueueService;
module.exports.RedisQueueService = RedisQueueService;
