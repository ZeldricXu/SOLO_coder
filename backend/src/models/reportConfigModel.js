const db = require('../config/database');
const { v4: uuidv4 } = require('uuid');

const messageQueueModel = {
  async create(messageData) {
    const queueId = uuidv4();
    const query = `
      INSERT INTO message_queues (
        queue_id, message_type, recipient, subject, content,
        template_code, template_params, priority, max_retry,
        scheduled_at
      ) VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?)
    `;
    const params = [
      queueId,
      messageData.message_type,
      messageData.recipient,
      messageData.subject || null,
      typeof messageData.content === 'string' ? messageData.content : JSON.stringify(messageData.content),
      messageData.template_code || null,
      messageData.template_params ? JSON.stringify(messageData.template_params) : null,
      messageData.priority || 0,
      messageData.max_retry || 3,
      messageData.scheduled_at || new Date()
    ];
    await db.execute(query, params);
    return this.findById(queueId);
  },

  async findById(queueId) {
    const query = `SELECT * FROM message_queues WHERE queue_id = ?`;
    const [rows] = await db.execute(query, [queueId]);
    return rows[0] || null;
  },

  async findPending(limit = 10) {
    const query = `
      SELECT * FROM message_queues 
      WHERE status = 'pending' 
        AND scheduled_at <= NOW()
      ORDER BY priority DESC, created_at ASC
      LIMIT ?
    `;
    const [rows] = await db.execute(query, [limit]);
    return rows;
  },

  async updateStatus(queueId, status, errorMessage = null) {
    const query = `
      UPDATE message_queues 
      SET status = ?, 
          error_message = ?,
          updated_at = NOW()
      WHERE queue_id = ?
    `;
    await db.execute(query, [status, errorMessage, queueId]);
    return this.findById(queueId);
  },

  async markAsProcessing(queueId) {
    const query = `
      UPDATE message_queues 
      SET status = 'processing',
          updated_at = NOW()
      WHERE queue_id = ?
    `;
    await db.execute(query, [queueId]);
    return this.findById(queueId);
  },

  async markAsSent(queueId) {
    const query = `
      UPDATE message_queues 
      SET status = 'sent',
          sent_at = NOW(),
          updated_at = NOW()
      WHERE queue_id = ?
    `;
    await db.execute(query, [queueId]);
    return this.findById(queueId);
  },

  async markAsFailed(queueId, errorMessage) {
    const query = `
      UPDATE message_queues 
      SET status = 'failed',
          error_message = ?,
          retry_count = retry_count + 1,
          updated_at = NOW()
      WHERE queue_id = ?
    `;
    await db.execute(query, [errorMessage, queueId]);
    return this.findById(queueId);
  },

  async incrementRetry(queueId) {
    const query = `
      UPDATE message_queues 
      SET retry_count = retry_count + 1,
          status = 'pending',
          updated_at = NOW()
      WHERE queue_id = ?
    `;
    await db.execute(query, [queueId]);
    return this.findById(queueId);
  },

  async countByStatus(status) {
    const query = `SELECT COUNT(*) as count FROM message_queues WHERE status = ?`;
    const [rows] = await db.execute(query, [status]);
    return rows[0].count;
  },

  async getStats() {
    const query = `
      SELECT status, COUNT(*) as count 
      FROM message_queues 
      GROUP BY status
    `;
    const [rows] = await db.execute(query);
    const stats = { total: 0 };
    for (const row of rows) {
      stats[row.status] = row.count;
      stats.total += row.count;
    }
    return stats;
  },

  async deleteById(queueId) {
    const query = `DELETE FROM message_queues WHERE queue_id = ?`;
    const [result] = await db.execute(query, [queueId]);
    return result.affectedRows > 0;
  },

  async deleteOldRecords(days = 30) {
    const query = `
      DELETE FROM message_queues 
      WHERE created_at < DATE_SUB(NOW(), INTERVAL ? DAY)
        AND status IN ('sent', 'failed')
    `;
    const [result] = await db.execute(query, [days]);
    return result.affectedRows;
  }
};

module.exports = messageQueueModel;
