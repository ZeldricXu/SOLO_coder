const { EventEmitter } = require('events');
const config = require('../config/config');
const logger = require('../utils/logger');

class EventBus extends EventEmitter {
  constructor() {
    super();
    this.subscribers = new Map();
    this.isInitialized = false;
  }

  init() {
    if (this.isInitialized) {
      return;
    }

    this.on('error', (error) => {
      logger.error('事件总线错误:', { error: error.message });
    });

    this.isInitialized = true;
    logger.info('事件总线已初始化');
  }

  publish(eventName, data = {}) {
    const event = {
      id: this.generateEventId(),
      name: eventName,
      data,
      timestamp: Date.now()
    };

    logger.debug(`发布事件: ${eventName}`, { eventId: event.id });

    this.emit(eventName, event);
    this.emit('*', event);

    const subs = this.subscribers.get(eventName) || [];
    subs.forEach(sub => {
      try {
        sub.callback(event);
      } catch (error) {
        logger.error(`事件订阅者处理失败: ${eventName}`, { error: error.message });
      }
    });

    return event.id;
  }

  subscribe(eventName, callback, options = {}) {
    const { once = false, priority = 0 } = options;

    if (!this.subscribers.has(eventName)) {
      this.subscribers.set(eventName, []);
    }

    const subscription = {
      id: this.generateSubscriptionId(),
      callback,
      once,
      priority
    };

    const subs = this.subscribers.get(eventName);
    const insertIndex = subs.findIndex(s => s.priority < priority);
    if (insertIndex === -1) {
      subs.push(subscription);
    } else {
      subs.splice(insertIndex, 0, subscription);
    }

    logger.debug(`订阅事件: ${eventName}`, { subscriptionId: subscription.id });

    return subscription.id;
  }

  unsubscribe(subscriptionId) {
    for (const [eventName, subs] of this.subscribers.entries()) {
      const index = subs.findIndex(s => s.id === subscriptionId);
      if (index !== -1) {
        subs.splice(index, 1);
        logger.debug(`取消订阅: subscriptionId=${subscriptionId}`);
        return true;
      }
    }
    return false;
  }

  subscribeOnce(eventName, callback) {
    return this.subscribe(eventName, callback, { once: true });
  }

  on(eventName, listener) {
    return super.on(eventName, (event) => {
      try {
        listener(event);
      } catch (error) {
        logger.error(`事件监听器错误: ${eventName}`, { error: error.message });
      }
    });
  }

  once(eventName, listener) {
    return super.once(eventName, (event) => {
      try {
        listener(event);
      } catch (error) {
        logger.error(`一次性事件监听器错误: ${eventName}`, { error: error.message });
      }
    });
  }

  generateEventId() {
    return `evt_${Date.now()}_${Math.random().toString(36).substr(2, 9)}`;
  }

  generateSubscriptionId() {
    return `sub_${Date.now()}_${Math.random().toString(36).substr(2, 9)}`;
  }

  shutdown() {
    this.removeAllListeners();
    this.subscribers.clear();
    this.isInitialized = false;
    logger.info('事件总线已关闭');
  }
}

const eventBus = new EventBus();
eventBus.init();

module.exports = eventBus;
