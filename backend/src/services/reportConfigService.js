const messageQueueModel = require('../models/messageQueueModel');
const notificationService = require('./notificationService');
const config = require('../config');

class MessageQueueService {
  constructor() {
    this.isRunning = false;
    this.pollInterval = config.queue?.pollInterval || 5000;
    this.batchSize = config.queue?.batchSize || 10;
    this.maxRetries = config.queue?.maxRetries || 3;
  }

  async enqueueEmail(recipient, subject, content, options = {}) {
    return await messageQueueModel.create({
      message_type: 'email',
      recipient,
      subject,
      content: { html: content },
      priority: options.priority || 0,
      max_retry: options.maxRetry || this.maxRetries,
      scheduled_at: options.scheduledAt || new Date()
    });
  }

  async enqueueSMS(phone, templateCode, templateParams, options = {}) {
    return await messageQueueModel.create({
      message_type: 'sms',
      recipient: phone,
      content: { templateCode, templateParams },
      template_code: templateCode,
      template_params: templateParams,
      priority: options.priority || 0,
      max_retry: options.maxRetry || this.maxRetries,
      scheduled_at: options.scheduledAt || new Date()
    });
  }

  async enqueueNotification(notificationData) {
    return await messageQueueModel.create({
      message_type: notificationData.recipient_type === 'sms' ? 'sms' : 'email',
      recipient: notificationData.recipient,
      subject: notificationData.subject,
      content: notificationData.content,
      priority: notificationData.priority || 0,
      max_retry: notificationData.maxRetry || this.maxRetries,
      scheduled_at: notificationData.scheduledAt || new Date()
    });
  }

  async processQueue() {
    const pendingMessages = await messageQueueModel.findPending(this.batchSize);
    
    for (const message of pendingMessages) {
      try {
        await messageQueueModel.markAsProcessing(message.queue_id);
        await this.processMessage(message);
        await messageQueueModel.markAsSent(message.queue_id);
        console.log(`Message ${message.queue_id} sent successfully`);
      } catch (error) {
        console.error(`Failed to process message ${message.queue_id}:`, error.message);
        
        const retryCount = message.retry_count + 1;
        if (retryCount >= message.max_retry) {
          await messageQueueModel.markAsFailed(message.queue_id, error.message);
        } else {
          await messageQueueModel.incrementRetry(message.queue_id);
        }
      }
    }
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

    this.workerLoop();
  }

  async workerLoop() {
    while (this.isRunning) {
      try {
        await this.processQueue();
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
    return await messageQueueModel.getStats();
  }

  async getPendingMessages() {
    return await messageQueueModel.findPending(100);
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
}

const messageQueueService = new MessageQueueService();

module.exports = messageQueueService;
module.exports.MessageQueueService = MessageQueueService;
