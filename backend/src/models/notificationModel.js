const db = require('../config/database');
const { v4: uuidv4 } = require('uuid');

const registrationModel = {
  async create(registrationData) {
    const registrationId = uuidv4();
    const query = `
      INSERT INTO registrations (
        registration_id, event_id, ticket_id, ticket_name, user_id,
        form_data, status, total_amount, notes
      ) VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?)
    `;
    const params = [
      registrationId,
      registrationData.event_id,
      registrationData.ticket_id || null,
      registrationData.ticket_name || null,
      registrationData.user_id || null,
      JSON.stringify(registrationData.form_data),
      registrationData.status || 'pending_review',
      registrationData.total_amount || 0,
      registrationData.notes || null
    ];
    await db.execute(query, params);
    return this.findById(registrationId);
  },

  async findById(registrationId) {
    const query = `
      SELECT * FROM registrations WHERE registration_id = ?
    `;
    const [rows] = await db.execute(query, [registrationId]);
    return rows[0] || null;
  },

  async findAll(filters = {}) {
    let query = 'SELECT * FROM registrations WHERE 1=1';
    const params = [];

    if (filters.event_id) {
      query += ' AND event_id = ?';
      params.push(filters.event_id);
    }
    if (filters.status) {
      query += ' AND status = ?';
      params.push(filters.status);
    }
    if (filters.check_in_status !== undefined) {
      query += ' AND check_in_status = ?';
      params.push(filters.check_in_status ? 1 : 0);
    }
    if (filters.user_id) {
      query += ' AND user_id = ?';
      params.push(filters.user_id);
    }

    query += ' ORDER BY created_at DESC';

    const [rows] = await db.execute(query, params);
    return rows;
  },

  async findByEventId(eventId, filters = {}) {
    return this.findAll({ event_id: eventId, ...filters });
  },

  async update(registrationId, updateData) {
    const allowedFields = [
      'status', 'check_in_status', 'check_in_time', 'total_amount',
      'paid_amount', 'payment_status', 'notes', 'approved_at'
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
      return this.findById(registrationId);
    }

    params.push(registrationId);
    const query = `UPDATE registrations SET ${updateFields.join(', ')} WHERE registration_id = ?`;
    await db.execute(query, params);
    return this.findById(registrationId);
  },

  async approve(registrationId, notes = '') {
    return this.update(registrationId, {
      status: 'approved',
      approved_at: new Date(),
      notes
    });
  },

  async reject(registrationId, notes = '') {
    return this.update(registrationId, {
      status: 'rejected',
      notes
    });
  },

  async cancel(registrationId) {
    return this.update(registrationId, {
      status: 'cancelled'
    });
  },

  async checkIn(registrationId, checkInMethod = 'manual') {
    const registration = await this.findById(registrationId);
    if (!registration) {
      throw new Error('Registration not found');
    }
    if (registration.check_in_status) {
      throw new Error('Already checked in');
    }
    
    await this.update(registrationId, {
      check_in_status: true,
      check_in_time: new Date()
    });

    const checkInQuery = `
      INSERT INTO check_ins (check_in_id, registration_id, event_id, check_in_method)
      VALUES (?, ?, ?, ?)
    `;
    await db.execute(checkInQuery, [uuidv4(), registrationId, registration.event_id, checkInMethod]);

    return this.findById(registrationId);
  },

  async countByEventId(eventId, filters = {}) {
    let query = 'SELECT COUNT(*) as count FROM registrations WHERE event_id = ?';
    const params = [eventId];

    if (filters.status) {
      query += ' AND status = ?';
      params.push(filters.status);
    }
    if (filters.check_in_status !== undefined) {
      query += ' AND check_in_status = ?';
      params.push(filters.check_in_status ? 1 : 0);
    }

    const [rows] = await db.execute(query, params);
    return rows[0].count;
  },

  async countByTicketId(ticketId) {
    const query = 'SELECT COUNT(*) as count FROM registrations WHERE ticket_id = ? AND status IN ("pending_review", "approved")';
    const [rows] = await db.execute(query, [ticketId]);
    return rows[0].count;
  }
};

module.exports = registrationModel;
