const { BaseEventQueue, EVENT_STATUS, EVENT_PRIORITY } = require('./baseQueue');
const { getRedisClient, getSubscriberClient, closeRedis, REDIS_KEYS } = require('../config/redis');
const logger = require('../config/logger');

class RedisEventQueue extends BaseEventQueue {
  constructor(options = {}) {
    super(options);
    this.redis = getRedisClient();
    this.subscriber = null;
    this.isInitialized = false;
    this.waitingPromises = [];
    this.maxEventTTL = options.maxEventTTL || 7 * 24 * 60 * 60;
  }

  async initialize() {
    if (this.isInitialized) {
      return;
    }

    try {
      this.subscriber = getSubscriberClient();
      
      await this.subscriber.subscribe(REDIS_KEYS.NOTIFICATION_CHANNEL);
      
      this.subscriber.on('message', (channel, message) => {
        if (channel === REDIS_KEYS.NOTIFICATION_CHANNEL) {
          this.notifyConsumers();
        }
      });

      this.isInitialized = true;
      logger.info('RedisEventQueue 已初始化');
    } catch (error) {
      logger.error('RedisEventQueue 初始化失败: %s', error.message);
      throw error;
    }
  }

  getPriorityQueueKey(priority) {
    switch (priority) {
      case EVENT_PRIORITY.HIGH:
        return REDIS_KEYS.PENDING_HIGH;
      case EVENT_PRIORITY.MEDIUM:
        return REDIS_KEYS.PENDING_MEDIUM;
      case EVENT_PRIORITY.LOW:
        return REDIS_KEYS.PENDING_LOW;
      default:
        return REDIS_KEYS.PENDING_MEDIUM;
    }
  }

  getEventKey(eventId) {
    return `${REDIS_KEYS.EVENT_PREFIX}${eventId}`;
  }

  async push(event) {
    if (!this.isInitialized) {
      await this.initialize();
    }

    if (this.validateEvent(event)) {
      logger.error('无效的事件格式: %o', event);
      throw new Error('Invalid event format');
    }

    const eventKey = this.getEventKey(event.event_id);
    const priorityKey = this.getPriorityQueueKey(event.priority);

    const multi = this.redis.multi();

    multi.hmset(eventKey, {
      event_id: event.event_id,
      type: event.type,
      data: JSON.stringify(event.data),
      priority: event.priority,
      status: event.status,
      retries: event.retries || 0,
      created_at: event.created_at,
      processed_at: event.processed_at || '',
      completed_at: event.completed_at || '',
      error: event.error || '',
      result: event.result ? JSON.stringify(event.result) : ''
    });

    multi.expire(eventKey, this.maxEventTTL);

    multi.lpush(priorityKey, event.event_id);

    multi.publish(REDIS_KEYS.NOTIFICATION_CHANNEL, 'new_event');

    await multi.exec();

    logger.info('事件已加入Redis队列: event_id=%s, type=%s, priority=%d',
      event.event_id, event.type, event.priority);

    return event.event_id;
  }

  async pop(timeout = 5000) {
    if (!this.isInitialized) {
      await this.initialize();
    }

    const pendingQueues = [
      REDIS_KEYS.PENDING_HIGH,
      REDIS_KEYS.PENDING_MEDIUM,
      REDIS_KEYS.PENDING_LOW
    ];

    for (const queueKey of pendingQueues) {
      const result = await this.redis.rpoplpush(queueKey, REDIS_KEYS.PROCESSING_SET);
      
      if (result) {
        return await this.getEventAndMarkProcessing(result);
      }
    }

    if (timeout <= 0) {
      return null;
    }

    return new Promise((resolve) => {
      const timer = setTimeout(() => {
        const index = this.waitingPromises.indexOf(resolve);
        if (index !== -1) {
          this.waitingPromises.splice(index, 1);
        }
        resolve(null);
      }, timeout);

      this.waitingPromises.push(async () => {
        clearTimeout(timer);
        for (const queueKey of pendingQueues) {
          const result = await this.redis.rpoplpush(queueKey, REDIS_KEYS.PROCESSING_SET);
          if (result) {
            const event = await this.getEventAndMarkProcessing(result);
            resolve(event);
            return;
          }
        }
        resolve(null);
      });
    });
  }

  async getEventAndMarkProcessing(eventId) {
    const eventKey = this.getEventKey(eventId);
    const eventData = await this.redis.hgetall(eventKey);

    if (!eventData || !eventData.event_id) {
      logger.warn('事件数据不存在: event_id=%s', eventId);
      await this.redis.lrem(REDIS_KEYS.PROCESSING_SET, 0, eventId);
      return null;
    }

    const event = {
      event_id: eventData.event_id,
      type: eventData.type,
      data: JSON.parse(eventData.data || '{}'),
      priority: parseInt(eventData.priority),
      status: EVENT_STATUS.PROCESSING,
      retries: parseInt(eventData.retries) || 0,
      created_at: eventData.created_at,
      processed_at: new Date().toISOString(),
      completed_at: null,
      error: eventData.error || null,
      result: eventData.result ? JSON.parse(eventData.result) : null
    };

    await this.redis.hmset(eventKey, {
      status: event.status,
      processed_at: event.processed_at
    });

    logger.debug('事件已从Redis取出处理: event_id=%s, type=%s', event.event_id, event.type);

    return event;
  }

  notifyConsumers() {
    while (this.waitingPromises.length > 0) {
      const resolve = this.waitingPromises.shift();
      if (resolve) {
        resolve();
      }
    }
  }

  async peek() {
    if (!this.isInitialized) {
      await this.initialize();
    }

    const pendingQueues = [
      REDIS_KEYS.PENDING_HIGH,
      REDIS_KEYS.PENDING_MEDIUM,
      REDIS_KEYS.PENDING_LOW
    ];

    for (const queueKey of pendingQueues) {
      const eventId = await this.redis.lindex(queueKey, -1);
      if (eventId) {
        const eventKey = this.getEventKey(eventId);
        const eventData = await this.redis.hgetall(eventKey);
        if (eventData && eventData.event_id) {
          return {
            event_id: eventData.event_id,
            type: eventData.type,
            data: JSON.parse(eventData.data || '{}'),
            priority: parseInt(eventData.priority),
            status: eventData.status,
            retries: parseInt(eventData.retries) || 0,
            created_at: eventData.created_at
          };
        }
      }
    }

    return null;
  }

  async size() {
    if (!this.isInitialized) {
      await this.initialize();
    }

    const [highSize, mediumSize, lowSize] = await Promise.all([
      this.redis.llen(REDIS_KEYS.PENDING_HIGH),
      this.redis.llen(REDIS_KEYS.PENDING_MEDIUM),
      this.redis.llen(REDIS_KEYS.PENDING_LOW)
    ]);

    return highSize + mediumSize + lowSize;
  }

  async isEmpty() {
    const size = await this.size();
    return size === 0;
  }

  async acknowledge(eventId, success, result = null) {
    if (!this.isInitialized) {
      await this.initialize();
    }

    const eventKey = this.getEventKey(eventId);
    const eventData = await this.redis.hgetall(eventKey);

    if (!eventData || !eventData.event_id) {
      logger.warn('确认的事件不存在: event_id=%s', eventId);
      return false;
    }

    await this.redis.lrem(REDIS_KEYS.PROCESSING_SET, 0, eventId);

    if (success) {
      const now = new Date().toISOString();
      await this.redis.hmset(eventKey, {
        status: EVENT_STATUS.COMPLETED,
        completed_at: now,
        result: result ? JSON.stringify(result) : ''
      });

      await this.redis.sadd(REDIS_KEYS.COMPLETED_SET, eventId);
      await this.redis.expire(eventKey, this.maxEventTTL);

      logger.info('事件处理成功: event_id=%s', eventId);
    } else {
      const retries = parseInt(eventData.retries) || 0;

      if (retries < this.maxRetries) {
        const newRetries = retries + 1;
        await this.redis.hmset(eventKey, {
          status: EVENT_STATUS.RETRYING,
          retries: newRetries,
          error: result?.message || 'Unknown error'
        });

        const priorityKey = this.getPriorityQueueKey(parseInt(eventData.priority));
        await this.redis.lpush(priorityKey, eventId);
        await this.redis.publish(REDIS_KEYS.NOTIFICATION_CHANNEL, 'retry_event');

        logger.warn('事件处理失败，将重试: event_id=%s, retry=%d/%d',
          eventId, newRetries, this.maxRetries);
      } else {
        await this.redis.hmset(eventKey, {
          status: EVENT_STATUS.FAILED,
          error: result?.message || 'Max retries exceeded'
        });

        await this.redis.sadd(REDIS_KEYS.FAILED_SET, eventId);
        await this.redis.expire(eventKey, this.maxEventTTL);

        logger.error('事件处理失败，已达最大重试次数: event_id=%s', eventId);
      }
    }

    return true;
  }

  async retry(eventId) {
    if (!this.isInitialized) {
      await this.initialize();
    }

    const eventKey = this.getEventKey(eventId);
    const eventData = await this.redis.hgetall(eventKey);

    if (!eventData || !eventData.event_id) {
      logger.warn('重试的事件不存在: event_id=%s', eventId);
      return false;
    }

    const isProcessing = await this.redis.lpos(REDIS_KEYS.PROCESSING_SET, eventId);
    const isFailed = await this.redis.sismember(REDIS_KEYS.FAILED_SET, eventId);

    if (isProcessing) {
      await this.redis.lrem(REDIS_KEYS.PROCESSING_SET, 0, eventId);
    }

    if (isFailed) {
      await this.redis.srem(REDIS_KEYS.FAILED_SET, eventId);
    }

    await this.redis.hmset(eventKey, {
      status: EVENT_STATUS.PENDING,
      retries: 0,
      error: ''
    });

    const priorityKey = this.getPriorityQueueKey(parseInt(eventData.priority));
    await this.redis.lpush(priorityKey, eventId);
    await this.redis.publish(REDIS_KEYS.NOTIFICATION_CHANNEL, 'retry_event');

    logger.info('事件已重新加入Redis队列: event_id=%s', eventId);

    return true;
  }

  async getStats() {
    if (!this.isInitialized) {
      await this.initialize();
    }

    const [
      highSize,
      mediumSize,
      lowSize,
      processingCount,
      failedCount,
      completedCount
    ] = await Promise.all([
      this.redis.llen(REDIS_KEYS.PENDING_HIGH),
      this.redis.llen(REDIS_KEYS.PENDING_MEDIUM),
      this.redis.llen(REDIS_KEYS.PENDING_LOW),
      this.redis.llen(REDIS_KEYS.PROCESSING_SET),
      this.redis.scard(REDIS_KEYS.FAILED_SET),
      this.redis.scard(REDIS_KEYS.COMPLETED_SET)
    ]);

    return {
      pending: highSize + mediumSize + lowSize,
      pending_high: highSize,
      pending_medium: mediumSize,
      pending_low: lowSize,
      processing: processingCount,
      completed: completedCount,
      failed: failedCount,
      total: highSize + mediumSize + lowSize + processingCount + completedCount + failedCount
    };
  }

  async getPendingEvents(limit = 100) {
    if (!this.isInitialized) {
      await this.initialize();
    }

    const events = [];
    const pendingQueues = [
      REDIS_KEYS.PENDING_HIGH,
      REDIS_KEYS.PENDING_MEDIUM,
      REDIS_KEYS.PENDING_LOW
    ];

    for (const queueKey of pendingQueues) {
      if (events.length >= limit) break;
      
      const eventIds = await this.redis.lrange(queueKey, 0, limit - events.length - 1);
      
      for (const eventId of eventIds.reverse()) {
        if (events.length >= limit) break;
        
        const eventKey = this.getEventKey(eventId);
        const eventData = await this.redis.hgetall(eventKey);
        
        if (eventData && eventData.event_id) {
          events.push({
            event_id: eventData.event_id,
            type: eventData.type,
            data: JSON.parse(eventData.data || '{}'),
            priority: parseInt(eventData.priority),
            status: eventData.status,
            retries: parseInt(eventData.retries) || 0,
            created_at: eventData.created_at
          });
        }
      }
    }

    return events;
  }

  async getProcessingEvents() {
    if (!this.isInitialized) {
      await this.initialize();
    }

    const eventIds = await this.redis.lrange(REDIS_KEYS.PROCESSING_SET, 0, -1);
    const events = [];

    for (const eventId of eventIds) {
      const eventKey = this.getEventKey(eventId);
      const eventData = await this.redis.hgetall(eventKey);
      
      if (eventData && eventData.event_id) {
        events.push({
          event_id: eventData.event_id,
          type: eventData.type,
          data: JSON.parse(eventData.data || '{}'),
          priority: parseInt(eventData.priority),
          status: eventData.status,
          retries: parseInt(eventData.retries) || 0,
          created_at: eventData.created_at,
          processed_at: eventData.processed_at
        });
      }
    }

    return events;
  }

  async getFailedEvents(limit = 100) {
    if (!this.isInitialized) {
      await this.initialize();
    }

    const eventIds = await this.redis.smembers(REDIS_KEYS.FAILED_SET);
    const events = [];

    for (const eventId of eventIds.slice(0, limit)) {
      const eventKey = this.getEventKey(eventId);
      const eventData = await this.redis.hgetall(eventKey);
      
      if (eventData && eventData.event_id) {
        events.push({
          event_id: eventData.event_id,
          type: eventData.type,
          data: JSON.parse(eventData.data || '{}'),
          priority: parseInt(eventData.priority),
          status: eventData.status,
          retries: parseInt(eventData.retries) || 0,
          created_at: eventData.created_at,
          error: eventData.error
        });
      }
    }

    return events;
  }

  async close() {
    if (this.subscriber) {
      await this.subscriber.unsubscribe(REDIS_KEYS.NOTIFICATION_CHANNEL);
    }
    logger.info('RedisEventQueue 已关闭');
  }
}

let instance = null;

function getRedisQueue(options = {}) {
  if (!instance) {
    instance = new RedisEventQueue(options);
  }
  return instance;
}

module.exports = {
  RedisEventQueue,
  getRedisQueue
};
