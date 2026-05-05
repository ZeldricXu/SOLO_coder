const db = require('../config/database');
const { v4: uuidv4 } = require('uuid');

const eventModel = {
  async create(eventData) {
    const eventId = uuidv4();
    const query = `
      INSERT INTO events (
        event_id, organizer_id, title, description, start_time, end_time,
        location, max_attendees, status, need_approval, cover_image
      ) VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?)
    `;
    const params = [
      eventId,
      eventData.organizer_id,
      eventData.title,
      eventData.description || null,
      eventData.start_time,
      eventData.end_time,
      eventData.location || null,
      eventData.max_attendees || 0,
      eventData.status || 'draft',
      eventData.need_approval || false,
      eventData.cover_image || null
    ];
    await db.execute(query, params);
    return this.findById(eventId);
  },

  async findById(eventId) {
    const query = `
      SELECT * FROM events WHERE event_id = ?
    `;
    const [rows] = await db.execute(query, [eventId]);
    return rows[0] || null;
  },

  async findAll(filters = {}) {
    let query = 'SELECT * FROM events WHERE 1=1';
    const params = [];

    if (filters.organizer_id) {
      query += ' AND organizer_id = ?';
      params.push(filters.organizer_id);
    }
    if (filters.status) {
      query += ' AND status = ?';
      params.push(filters.status);
    }

    query += ' ORDER BY created_at DESC';

    const [rows] = await db.execute(query, params);
    return rows;
  },

  async update(eventId, updateData) {
    const allowedFields = [
      'title', 'description', 'start_time', 'end_time', 'location',
      'max_attendees', 'status', 'need_approval', 'cover_image'
    ];
    
    const updateFields = [];
    const params = [];

    for (const field of allowedFields) {
      if (updateData[field] !== undefined) {
        updateFields.push(`${field} = ?`);
        params.push(updateData[field]);
      }
    }

    if (updateFields.length === 0) {
      return this.findById(eventId);
    }

    params.push(eventId);
    const query = `UPDATE events SET ${updateFields.join(', ')} WHERE event_id = ?`;
    await db.execute(query, params);
    return this.findById(eventId);
  },

  async delete(eventId) {
    const query = 'DELETE FROM events WHERE event_id = ?';
    const [result] = await db.execute(query, [eventId]);
    return result.affectedRows > 0;
  },

  async publish(eventId) {
    return this.update(eventId, { status: 'published' });
  },

  async close(eventId) {
    return this.update(eventId, { status: 'closed' });
  },

  async cancel(eventId) {
    return this.update(eventId, { status: 'cancelled' });
  },

  async checkOwnership(eventId, organizerId) {
    const event = await this.findById(eventId);
    return event && event.organizer_id === organizerId;
  }
};

module.exports = eventModel;
