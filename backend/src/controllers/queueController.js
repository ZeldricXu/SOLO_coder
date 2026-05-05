const messageQueueService = require('../services/messageQueueService');
const { success, error } = require('../utils/response');

const queueController = {
  async getStats(req, res) {
    try {
      const stats = await messageQueueService.getQueueStats();
      return success(res, stats);
    } catch (err) {
      console.error('Failed to get queue stats:', err);
      return error(res, 'Failed to get queue stats');
    }
  },

  async getPendingMessages(req, res) {
    try {
      const messages = await messageQueueService.getPendingMessages();
      return success(res, messages);
    } catch (err) {
      console.error('Failed to get pending messages:', err);
      return error(res, 'Failed to get pending messages');
    }
  },

  async cancelMessage(req, res) {
    try {
      const { queueId } = req.params;
      const result = await messageQueueService.cancelMessage(queueId);
      return success(res, result, 'Message cancelled successfully');
    } catch (err) {
      console.error('Failed to cancel message:', err);
      return error(res, err.message || 'Failed to cancel message');
    }
  },

  async retryMessage(req, res) {
    try {
      const { queueId } = req.params;
      const result = await messageQueueService.retryFailedMessage(queueId);
      return success(res, result, 'Message queued for retry');
    } catch (err) {
      console.error('Failed to retry message:', err);
      return error(res, err.message || 'Failed to retry message');
    }
  },

  async enqueueEmail(req, res) {
    try {
      const { recipient, subject, content, priority, scheduledAt } = req.body;
      
      if (!recipient || !subject || !content) {
        return error(res, 'Recipient, subject and content are required');
      }

      const result = await messageQueueService.enqueueEmail(
        recipient,
        subject,
        content,
        {
          priority: priority || 0,
          scheduledAt: scheduledAt ? new Date(scheduledAt) : undefined
        }
      );

      return success(res, result, 'Email queued successfully');
    } catch (err) {
      console.error('Failed to enqueue email:', err);
      return error(res, 'Failed to enqueue email');
    }
  },

  async enqueueSMS(req, res) {
    try {
      const { phone, templateCode, templateParams, priority } = req.body;
      
      if (!phone || !templateCode) {
        return error(res, 'Phone and template code are required');
      }

      const result = await messageQueueService.enqueueSMS(
        phone,
        templateCode,
        templateParams || {},
        {
          priority: priority || 0
        }
      );

      return success(res, result, 'SMS queued successfully');
    } catch (err) {
      console.error('Failed to enqueue SMS:', err);
      return error(res, 'Failed to enqueue SMS');
    }
  },

  async processQueueManually(req, res) {
    try {
      await messageQueueService.processQueue();
      const stats = await messageQueueService.getQueueStats();
      return success(res, stats, 'Queue processed manually');
    } catch (err) {
      console.error('Failed to process queue:', err);
      return error(res, 'Failed to process queue');
    }
  }
};

module.exports = queueController;
