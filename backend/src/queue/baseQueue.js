const { v4: uuidv4 } = require('uuid');
const logger = require('../config/logger');

const EVENT_TYPES = {
  CODE_COMMIT: 'code_commit',
  ANALYSIS_REQUEST: 'analysis_request',
  COMPLEXITY_ANALYSIS: 'complexity_analysis',
  LINT_ANALYSIS: 'lint_analysis',
  DUPLICATE_ANALYSIS: 'duplicate_analysis',
  REVIEW_TASK_CREATE: 'review_task_create'
};

const EVENT_PRIORITY = {
  HIGH: 1,
  MEDIUM: 2,
  LOW: 3
};

const EVENT_STATUS = {
  PENDING: 'pending',
  PROCESSING: 'processing',
  COMPLETED: 'completed',
  FAILED: 'failed',
  RETRYING: 'retrying'
};

class BaseEventQueue {
  constructor(options = {}) {
    this.maxRetries = options.maxRetries || 3;
    this.retryDelay = options.retryDelay || 5000;
    this.maxSize = options.maxSize || 10000;
  }

  async push(event) {
    throw new Error('Method push() must be implemented by subclass');
  }

  async pop() {
    throw new Error('Method pop() must be implemented by subclass');
  }

  async peek() {
    throw new Error('Method peek() must be implemented by subclass');
  }

  async size() {
    throw new Error('Method size() must be implemented by subclass');
  }

  async isEmpty() {
    throw new Error('Method isEmpty() must be implemented by subclass');
  }

  async acknowledge(eventId, success, result = null) {
    throw new Error('Method acknowledge() must be implemented by subclass');
  }

  async retry(eventId) {
    throw new Error('Method retry() must be implemented by subclass');
  }

  createEvent(type, data, priority = EVENT_PRIORITY.MEDIUM) {
    return {
      event_id: uuidv4(),
      type,
      data,
      priority,
      status: EVENT_STATUS.PENDING,
      retries: 0,
      created_at: new Date().toISOString(),
      processed_at: null,
      completed_at: null,
      error: null,
      result: null
    };
  }

  validateEvent(event) {
    if (!event || !event.event_id) {
      return false;
    }
    if (!Object.values(EVENT_TYPES).includes(event.type)) {
      return false;
    }
    return true;
  }
}

module.exports = {
  BaseEventQueue,
  EVENT_TYPES,
  EVENT_PRIORITY,
  EVENT_STATUS
};
