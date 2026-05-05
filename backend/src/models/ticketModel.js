const db = require('../config/database');
const { v4: uuidv4 } = require('uuid');
const { TicketQuotaExhaustedError, OptimisticLockError, ResourceNotFoundError } = require('../utils/errors');

const ticketModel = {
  async create(ticketData) {
    const ticketId = uuidv4();
    const query = `
      INSERT INTO tickets (
        ticket_id, event_id, ticket_name, description, price,
        quota, sold_count, max_per_user, start_time, end_time,
        status, version
      ) VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, 1)
    `;
    const params = [
      ticketId,
      ticketData.event_id,
      ticketData.ticket_name,
      ticketData.description || null,
      ticketData.price || 0,
      ticketData.quota || 0,
      ticketData.sold_count || 0,
      ticketData.max_per_user || 1,
      ticketData.start_time || null,
      ticketData.end_time || null,
      ticketData.status || 'available'
    ];
    await db.execute(query, params);
    return this.findById(ticketId);
  },

  async findById(ticketId) {
    const query = `SELECT * FROM tickets WHERE ticket_id = ?`;
    const [rows] = await db.execute(query, [ticketId]);
    return rows[0] || null;
  },

  async findByEventId(eventId) {
    const query = `SELECT * FROM tickets WHERE event_id = ? ORDER BY created_at ASC`;
    const [rows] = await db.execute(query, [eventId]);
    return rows;
  },

  async getAvailableTicketsWithDetails(eventId, excludeTicketId = null) {
    const allTickets = await this.findByEventId(eventId);
    
    return allTickets
      .filter(ticket => {
        const isAvailable = ticket.status === 'available' && 
          (ticket.sold_count < ticket.quota) &&
          (!ticket.start_time || new Date(ticket.start_time) <= new Date()) &&
          (!ticket.end_time || new Date(ticket.end_time) > new Date());
        
        if (excludeTicketId && ticket.ticket_id === excludeTicketId) {
          return false;
        }
        
        return isAvailable;
      })
      .map(ticket => ({
        ticket_id: ticket.ticket_id,
        ticket_name: ticket.ticket_name,
        price: ticket.price,
        remaining: ticket.quota - ticket.sold_count,
        quota: ticket.quota,
        sold_count: ticket.sold_count
      }))
      .sort((a, b) => a.price - b.price);
  },

  async update(ticketId, updateData) {
    const allowedFields = [
      'ticket_name', 'description', 'price', 'quota', 'max_per_user',
      'start_time', 'end_time', 'status'
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
      return this.findById(ticketId);
    }

    updateFields.push('version = version + 1');
    params.push(ticketId);
    const query = `UPDATE tickets SET ${updateFields.join(', ')} WHERE ticket_id = ?`;
    await db.execute(query, params);
    return this.findById(ticketId);
  },

  async updateWithVersion(ticketId, updateData, expectedVersion) {
    const allowedFields = [
      'ticket_name', 'description', 'price', 'quota', 'sold_count', 
      'max_per_user', 'start_time', 'end_time', 'status'
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
      const ticket = await this.findById(ticketId);
      return ticket && ticket.version === expectedVersion;
    }

    updateFields.push('version = version + 1');
    
    const query = `
      UPDATE tickets 
      SET ${updateFields.join(', ')} 
      WHERE ticket_id = ? AND version = ?
    `;
    
    params.push(ticketId, expectedVersion);
    const [result] = await db.execute(query, params);
    
    return result.affectedRows > 0;
  },

  async decreaseQuota(ticketId) {
    const ticket = await this.findById(ticketId);
    if (!ticket) {
      throw new ResourceNotFoundError('Ticket', ticketId);
    }

    const remainingQuota = ticket.quota - ticket.sold_count;
    
    if (remainingQuota <= 0) {
      const alternatives = await this.getAvailableTicketsWithDetails(ticket.event_id, ticketId);
      
      throw new TicketQuotaExhaustedError(
        ticketId,
        ticket.ticket_name,
        remainingQuota,
        alternatives
      );
    }

    const newSoldCount = ticket.sold_count + 1;
    const newStatus = newSoldCount >= ticket.quota ? 'sold_out' : 'available';

    const success = await this.updateWithVersion(
      ticketId,
      { sold_count: newSoldCount, status: newStatus },
      ticket.version
    );

    if (!success) {
      throw new OptimisticLockError('库存更新失败，请刷新页面后重试');
    }

    return this.findById(ticketId);
  },

  async decreaseQuotaWithLock(ticketId, quantity = 1) {
    const maxRetries = 5;
    let retries = 0;

    while (retries < maxRetries) {
      const ticket = await this.findById(ticketId);
      if (!ticket) {
        throw new ResourceNotFoundError('Ticket', ticketId);
      }

      const remainingQuota = ticket.quota - ticket.sold_count;
      
      if (remainingQuota < quantity) {
        const alternatives = await this.getAvailableTicketsWithDetails(ticket.event_id, ticketId);
        
        throw new TicketQuotaExhaustedError(
          ticketId,
          ticket.ticket_name,
          remainingQuota,
          alternatives
        );
      }

      const newSoldCount = ticket.sold_count + quantity;
      const newStatus = newSoldCount >= ticket.quota ? 'sold_out' : 'available';

      const success = await this.updateWithVersion(
        ticketId,
        { sold_count: newSoldCount, status: newStatus },
        ticket.version
      );

      if (success) {
        return this.findById(ticketId);
      }

      retries++;
      if (retries >= maxRetries) {
        throw new OptimisticLockError('库存更新失败，当前并发量过高，请稍后重试');
      }

      await new Promise(resolve => setTimeout(resolve, 100 * retries));
    }
  },

  async increaseQuota(ticketId, quantity = 1) {
    const ticket = await this.findById(ticketId);
    if (!ticket) {
      throw new ResourceNotFoundError('Ticket', ticketId);
    }

    let newSoldCount = ticket.sold_count - quantity;
    if (newSoldCount < 0) {
      newSoldCount = 0;
    }

    const newStatus = newSoldCount < ticket.quota ? 'available' : 'sold_out';

    return await this.update(ticketId, {
      sold_count: newSoldCount,
      status: newStatus
    });
  },

  async delete(ticketId) {
    const query = 'DELETE FROM tickets WHERE ticket_id = ?';
    const [result] = await db.execute(query, [ticketId]);
    return result.affectedRows > 0;
  },

  async deleteByEventId(eventId) {
    const query = 'DELETE FROM tickets WHERE event_id = ?';
    const [result] = await db.execute(query, [eventId]);
    return result.affectedRows;
  },

  async countByEventId(eventId) {
    const query = 'SELECT COUNT(*) as count FROM tickets WHERE event_id = ?';
    const [rows] = await db.execute(query, [eventId]);
    return rows[0].count;
  },

  async getAvailableTickets(eventId) {
    const query = `
      SELECT * FROM tickets 
      WHERE event_id = ? 
        AND status = 'available'
        AND (start_time IS NULL OR start_time <= NOW())
        AND (end_time IS NULL OR end_time > NOW())
        AND sold_count < quota
      ORDER BY created_at ASC
    `;
    const [rows] = await db.execute(query, [eventId]);
    return rows;
  },

  async getTicketStatus(ticketId) {
    const ticket = await this.findById(ticketId);
    if (!ticket) {
      throw new ResourceNotFoundError('Ticket', ticketId);
    }

    const remaining = ticket.quota - ticket.sold_count;
    const soldRate = ticket.quota > 0 ? (ticket.sold_count / ticket.quota * 100).toFixed(2) : 0;

    return {
      ticket_id: ticket.ticket_id,
      ticket_name: ticket.ticket_name,
      price: ticket.price,
      quota: ticket.quota,
      sold_count: ticket.sold_count,
      remaining,
      sold_rate: parseFloat(soldRate),
      status: ticket.status,
      is_available: ticket.status === 'available' && remaining > 0
    };
  }
};

module.exports = ticketModel;
