const { BaseEventQueue, EVENT_STATUS, EVENT_PRIORITY } = require('./baseQueue');
const logger = require('../config/logger');

class MemoryEventQueue extends BaseEventQueue {
  constructor(options = {}) {
    super(options);
    this.queue = [];
    this.processing = new Map();
    this.completed = new Map();
    this.failed = new Map();
    this.waitingConsumers = [];
  }

  async push(event) {
    if (this.queue.length >= this.maxSize) {
      logger.error('队列已满，无法添加新事件: max_size=%d', this.maxSize);
      throw new Error('Queue is full');
    }

    if (!this.validateEvent(event)) {
      logger.error('无效的事件格式: %o', event);
      throw new Error('Invalid event format');
    }

    const insertIndex = this.findInsertIndex(event.priority);
    this.queue.splice(insertIndex, 0, event);

    logger.info('事件已加入队列: event_id=%s, type=%s, priority=%d, queue_size=%d',
      event.event_id, event.type, event.priority, this.queue.length);

    this.notifyConsumers();

    return event.event_id;
  }

  findInsertIndex(priority) {
    for (let i = 0; i < this.queue.length; i++) {
      if (this.queue[i].priority > priority) {
        return i;
      }
    }
    return this.queue.length;
  }

  notifyConsumers() {
    while (this.waitingConsumers.length > 0 && this.queue.length > 0) {
      const resolve = this.waitingConsumers.shift();
      if (resolve) {
        resolve(true);
      }
    }
  }

  async pop(timeout = 5000) {
    if (this.queue.length > 0) {
      return this.getNextEvent();
    }

    return new Promise((resolve) => {
      const timer = setTimeout(() => {
        const index = this.waitingConsumers.indexOf(resolve);
        if (index !== -1) {
          this.waitingConsumers.splice(index, 1);
        }
        resolve(null);
      }, timeout);

      this.waitingConsumers.push((hasEvent) => {
        clearTimeout(timer);
        if (hasEvent) {
          resolve(this.getNextEvent());
        } else {
          resolve(null);
        }
      });
    });
  }

  getNextEvent() {
    if (this.queue.length === 0) {
      return null;
    }

    const event = this.queue.shift();
    event.status = EVENT_STATUS.PROCESSING;
    event.processed_at = new Date().toISOString();

    this.processing.set(event.event_id, event);

    logger.debug('事件已取出处理: event_id=%s, type=%s', event.event_id, event.type);

    return event;
  }

  async peek() {
    if (this.queue.length === 0) {
      return null;
    }
    return { ...this.queue[0] };
  }

  async size() {
    return this.queue.length;
  }

  async isEmpty() {
    return this.queue.length === 0;
  }

  async acknowledge(eventId, success, result = null) {
    const event = this.processing.get(eventId);
    if (!event) {
      logger.warn('确认的事件不存在于处理中: event_id=%s', eventId);
      return false;
    }

    this.processing.delete(eventId);

    if (success) {
      event.status = EVENT_STATUS.COMPLETED;
      event.completed_at = new Date().toISOString();
      event.result = result;
      this.completed.set(eventId, event);
      logger.info('事件处理成功: event_id=%s, type=%s', eventId, event.type);
    } else {
      if (event.retries < this.maxRetries) {
        event.retries++;
        event.status = EVENT_STATUS.RETRYING;
        event.error = result?.message || 'Unknown error';
        
        const insertIndex = this.findInsertIndex(event.priority);
        this.queue.splice(insertIndex, 0, event);
        
        logger.warn('事件处理失败，将重试: event_id=%s, retry=%d/%d', 
          eventId, event.retries, this.maxRetries);
        
        this.notifyConsumers();
      } else {
        event.status = EVENT_STATUS.FAILED;
        event.error = result?.message || 'Max retries exceeded';
        this.failed.set(eventId, event);
        logger.error('事件处理失败，已达最大重试次数: event_id=%s', eventId);
      }
    }

    return true;
  }

  async retry(eventId) {
    let event = this.failed.get(eventId);
    if (!event) {
      event = this.processing.get(eventId);
    }
    if (!event) {
      logger.warn('重试的事件不存在: event_id=%s', eventId);
      return false;
    }

    event.retries = 0;
    event.status = EVENT_STATUS.PENDING;
    event.error = null;

    if (this.failed.has(eventId)) {
      this.failed.delete(eventId);
    }
    if (this.processing.has(eventId)) {
      this.processing.delete(eventId);
    }

    const insertIndex = this.findInsertIndex(event.priority);
    this.queue.splice(insertIndex, 0, event);

    logger.info('事件已重新加入队列: event_id=%s', eventId);
    this.notifyConsumers();

    return true;
  }

  async getStats() {
    return {
      pending: this.queue.length,
      processing: this.processing.size,
      completed: this.completed.size,
      failed: this.failed.size,
      total: this.queue.length + this.processing.size + this.completed.size + this.failed.size
    };
  }

  async getPendingEvents(limit = 100) {
    return this.queue.slice(0, limit).map(e => ({ ...e }));
  }

  async getProcessingEvents() {
    return Array.from(this.processing.values()).map(e => ({ ...e }));
  }

  async getFailedEvents(limit = 100) {
    return Array.from(this.failed.values()).slice(-limit).map(e => ({ ...e }));
  }

  async clearCompleted(olderThanHours = 24) {
    const now = Date.now();
    const cutoff = now - olderThanHours * 60 * 60 * 1000;
    let cleared = 0;

    for (const [id, event] of this.completed.entries()) {
      if (new Date(event.completed_at).getTime() < cutoff) {
        this.completed.delete(id);
        cleared++;
      }
    }

    logger.info('已清理 %d 个已完成的历史事件', cleared);
    return cleared;
  }
}

const instance = new MemoryEventQueue({
  maxRetries: parseInt(process.env.QUEUE_MAX_RETRIES) || 3,
  retryDelay: parseInt(process.env.QUEUE_RETRY_DELAY) || 5000,
  maxSize: parseInt(process.env.QUEUE_MAX_SIZE) || 10000
});

module.exports = {
  MemoryEventQueue,
  defaultQueue: instance
};
