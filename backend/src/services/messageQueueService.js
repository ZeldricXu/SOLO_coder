const messageQueueModel = require('../models/messageQueueModel');
const notificationService = require('./notificationService');
const redisQueueService = require('./queue/RedisQueueService');
const config = require('../config');

class MessageQueueService {
  constructor() {
    this.isRunning = false;
    this.pollInterval = config.queue?.pollInterval || 5000;
    this.batchSize = config.queue?.batchSize || 10;
    this.maxRetries = config.queue?.maxRetries || 3;
    this.useRedis = config.queue?.useRedis && config.redis?.enabled;
    this.redisInitialized = false;
  }

  async initializeRedis() {
    if (this.redisInitialized) {
      return true;
    }

    if (!this.useRedis) {
      return false;
    }

    try {
      this.redisInitialized = await redisQueueService.initialize();
      if (this.redisInitialized) {
        console.log('Redis queue initialized successfully');
      }
      return this.redisInitialized;
    } catch (error) {
      console.error('Failed to initialize Redis queue:', error);
      this.useRedis = false;
      return false;
    }
  }

  async enqueueEmail(recipient, subject, content, options = {}) {
    const messageData = {
      message_type: 'email',
      recipient,
      subject,
      content: { html: content },
      priority: options.priority || 0,
      max_retry: options.maxRetry || this.maxRetries,
      scheduled_at: options.scheduledAt || new Date()
    };

    return await this.enqueueMessage(messageData);
  }

  async enqueueSMS(phone, templateCode, templateParams, options = {}) {
    const messageData = {
      message_type: 'sms',
      recipient: phone,
      content: { templateCode, templateParams },
      template_code: templateCode,
      template_params: templateParams,
      priority: options.priority || 0,
      max_retry: options.maxRetry || this.maxRetries,
      scheduled_at: options.scheduledAt || new Date()
    };

    return await this.enqueueMessage(messageData);
  }

  async enqueueNotification(notificationData) {
    const messageData = {
      message_type: notificationData.recipient_type === 'sms' ? 'sms' : 'email',
      recipient: notificationData.recipient,
      subject: notificationData.subject,
      content: notificationData.content,
      priority: notificationData.priority || 0,
      max_retry: notificationData.maxRetry || this.maxRetries,
      scheduled_at: notificationData.scheduledAt || new Date(),
      notification_id: notificationData.notification_id
    };

    return await this.enqueueMessage(messageData);
  }

  async enqueueMessage(messageData) {
    await this.initializeRedis();

    if (this.useRedis && this.redisInitialized) {
      try {
        const messageId = await redisQueueService.enqueue(
          messageData,
          messageData.priority || 0,
          messageData.scheduled_at
        );
        console.log(`Message enqueued to Redis: ${messageId}`);
        
        await messageQueueModel.create({
          ...messageData,
          queue_id: messageId,
          status: 'pending'
        });
        
        return { message_id: messageId, queue: 'redis' };
      } catch (error) {
        console.error('Redis enqueue failed, falling back to database:', error);
      }
    }

    const dbMessage = await messageQueueModel.create({
      ...messageData,
      status: 'pending'
    });
    console.log(`Message enqueued to database: ${dbMessage.queue_id}`);
    
    return { message_id: dbMessage.queue_id, queue: 'database' };
  }

  async processQueue() {
    await this.initializeRedis();

    let processed = 0;

    if (this.useRedis && this.redisInitialized) {
      try {
        const messages = await redisQueueService.dequeue(this.batchSize);
        
        for (const message of messages) {
          try {
            await this.processMessage(message);
            await redisQueueService.acknowledge(message.id, true);
            console.log(`Redis message processed successfully: ${message.id}`);
            processed++;
          } catch (error) {
            console.error(`Failed to process Redis message ${message.id}:`, error.message);
            await redisQueueService.acknowledge(message.id, false, error);
          }
        }
      } catch (error) {
        console.error('Failed to process Redis queue:', error);
      }
    }

    try {
      const pendingMessages = await messageQueueModel.findPending(this.batchSize);
      
      for (const message of pendingMessages) {
        try {
          await messageQueueModel.markAsProcessing(message.queue_id);
          await this.processMessage(message);
          await messageQueueModel.markAsSent(message.queue_id);
          console.log(`Database message processed successfully: ${message.queue_id}`);
          processed++;
        } catch (error) {
          console.error(`Failed to process database message ${message.queue_id}:`, error.message);
          
          const retryCount = message.retry_count + 1;
          if (retryCount >= message.max_retry) {
            await messageQueueModel.markAsFailed(message.queue_id, error.message);
          } else {
            await messageQueueModel.incrementRetry(message.queue_id);
          }
        }
      }
    } catch (error) {
      console.error('Failed to process database queue:', error);
    }

    return processed;
  }

  async processMessage(message) {
    const content = typeof message.content === 'string' 
      ? JSON.parse(message.content) 
      : message.content;

    switch (message.message_type) {
      case 'email':
        await this.sendEmailMessage(message, content);
        break;
      case 'sms':
        await this.sendSMSMessage(message, content);
        break;
      case 'webhook':
        await this.sendWebhookMessage(message, content);
        break;
      default:
        throw new Error(`Unknown message type: ${message.message_type}`);
    }
  }

  async sendEmailMessage(message, content) {
    const result = await notificationService.sendEmail(
      message.recipient,
      message.subject,
      content.html || content
    );

    if (!result.success) {
      throw new Error(result.error || 'Email send failed');
    }

    return result;
  }

  async sendSMSMessage(message, content) {
    const result = await notificationService.sendSMS(
      message.recipient,
      content.templateCode || message.template_code,
      content.templateParams || message.template_params
    );

    if (!result.success) {
      throw new Error(result.message || 'SMS send failed');
    }

    return result;
  }

  async sendWebhookMessage(message, content) {
    console.log('Webhook message:', message, content);
    return { success: true };
  }

  startWorker() {
    if (this.isRunning) {
      console.log('Message queue worker is already running');
      return;
    }

    this.isRunning = true;
    console.log('Message queue worker started');
    console.log(`Queue mode: ${this.useRedis ? 'Redis (with database fallback)' : 'Database only'}`);

    this.workerLoop();
  }

  async workerLoop() {
    while (this.isRunning) {
      try {
        const processed = await this.processQueue();
        if (processed > 0) {
          console.log(`[${new Date().toISOString()}] Processed ${processed} messages`);
        }
      } catch (error) {
        console.error('Error in message queue worker:', error);
      }

      await new Promise(resolve => setTimeout(resolve, this.pollInterval));
    }
  }

  stopWorker() {
    this.isRunning = false;
    console.log('Message queue worker stopped');
  }

  async getQueueStats() {
    await this.initializeRedis();

    const stats = {
      redis: null,
      database: await messageQueueModel.getStats()
    };

    if (this.useRedis && this.redisInitialized) {
      try {
        stats.redis = await redisQueueService.getQueueStats();
      } catch (error) {
        console.error('Failed to get Redis queue stats:', error);
        stats.redis = { error: error.message };
      }
    }

    return stats;
  }

  async getPendingMessages() {
    await this.initializeRedis();

    const messages = {
      redis: [],
      database: await messageQueueModel.findPending(100)
    };

    if (this.useRedis && this.redisInitialized) {
      try {
        messages.redis = await redisQueueService.peekPending(100);
      } catch (error) {
        console.error('Failed to peek Redis pending messages:', error);
      }
    }

    return messages;
  }

  async cancelMessage(queueId) {
    const message = await messageQueueModel.findById(queueId);
    if (!message) {
      throw new Error('Message not found');
    }
    if (message.status !== 'pending') {
      throw new Error('Cannot cancel message that is already processing');
    }
    
    return await messageQueueModel.updateStatus(queueId, 'cancelled', 'Cancelled by user');
  }

  async retryFailedMessage(queueId) {
    const message = await messageQueueModel.findById(queueId);
    if (!message) {
      throw new Error('Message not found');
    }
    if (message.status !== 'failed') {
      throw new Error('Can only retry failed messages');
    }
    
    return await messageQueueModel.updateStatus(queueId, 'pending', null);
  }

  async requeueDeadLetters() {
    if (this.useRedis && this.redisInitialized) {
      return await redisQueueService.requeueDeadLetter();
    }
    return 0;
  }
}

const messageQueueService = new MessageQueueService();

module.exports = messageQueueService;
module.exports.MessageQueueService = MessageQueueService;
