const db = require('../config/database');
const eventModel = require('../models/eventModel');
const ticketModel = require('../models/ticketModel');
const registrationModel = require('../models/registrationModel');
const checkInModel = require('../models/checkInModel');
const reportConfigParser = require('./parsers/ReportConfigParser');
const { ReportConfigParseError, ResourceNotFoundError } = require('../utils/errors');

const analyticsService = {
  async getEventOverview(eventId) {
    const event = await eventModel.findById(eventId);
    if (!event) {
      throw new ResourceNotFoundError('Event', eventId);
    }

    const [totalRegistrations, approvedRegistrations, rejectedRegistrations, checkedInCount] = await Promise.all([
      registrationModel.countByEventId(eventId),
      registrationModel.countByEventId(eventId, { status: 'approved' }),
      registrationModel.countByEventId(eventId, { status: 'rejected' }),
      registrationModel.countByEventId(eventId, { check_in_status: true })
    ]);

    const tickets = await ticketModel.findByEventId(eventId);
    let totalRevenue = 0;
    let totalQuota = 0;
    let totalSold = 0;

    for (const ticket of tickets) {
      totalQuota += ticket.quota;
      totalSold += ticket.sold_count;
      totalRevenue += ticket.sold_count * ticket.price;
    }

    return {
      event: {
        event_id: event.event_id,
        title: event.title,
        status: event.status,
        start_time: event.start_time,
        end_time: event.end_time,
        location: event.location
      },
      registrations: {
        total: totalRegistrations,
        approved: approvedRegistrations,
        rejected: rejectedRegistrations,
        pending: totalRegistrations - approvedRegistrations - rejectedRegistrations
      },
      check_ins: {
        total: checkedInCount,
        rate: approvedRegistrations > 0 ? (checkedInCount / approvedRegistrations * 100).toFixed(2) : 0
      },
      tickets: {
        total_quota: totalQuota,
        total_sold: totalSold,
        remaining: totalQuota - totalSold,
        total_revenue: totalRevenue
      }
    };
  },

  async getRegistrationTrend(eventId, startDate, endDate, interval = 'day') {
    const event = await eventModel.findById(eventId);
    if (!event) {
      throw new ResourceNotFoundError('Event', eventId);
    }

    let dateFormat;
    switch (interval) {
      case 'hour':
        dateFormat = '%Y-%m-%d %H:00';
        break;
      case 'week':
        dateFormat = '%Y-%u';
        break;
      case 'month':
        dateFormat = '%Y-%m';
        break;
      case 'day':
      default:
        dateFormat = '%Y-%m-%d';
    }

    let whereClause = 'event_id = ?';
    const params = [eventId];

    if (startDate) {
      whereClause += ' AND created_at >= ?';
      params.push(startDate);
    }
    if (endDate) {
      whereClause += ' AND created_at <= ?';
      params.push(endDate);
    }

    const query = `
      SELECT 
        DATE_FORMAT(created_at, ?) as date,
        COUNT(*) as count,
        SUM(CASE WHEN status = 'approved' THEN 1 ELSE 0 END) as approved_count,
        SUM(CASE WHEN status = 'pending_review' THEN 1 ELSE 0 END) as pending_count,
        SUM(CASE WHEN status = 'rejected' THEN 1 ELSE 0 END) as rejected_count
      FROM registrations
      WHERE ${whereClause}
      GROUP BY DATE_FORMAT(created_at, ?)
      ORDER BY date ASC
    `;

    const [rows] = await db.execute(query, [dateFormat, ...params, dateFormat]);

    const data = rows.map(row => ({
      date: row.date,
      total: row.count,
      approved: row.approved_count,
      pending: row.pending_count,
      rejected: row.rejected_count
    }));

    return {
      event_id: eventId,
      interval,
      start_date: startDate || null,
      end_date: endDate || null,
      data
    };
  },

  async getTicketSales(eventId) {
    const event = await eventModel.findById(eventId);
    if (!event) {
      throw new ResourceNotFoundError('Event', eventId);
    }

    const tickets = await ticketModel.findByEventId(eventId);

    const salesData = tickets.map(ticket => {
      const remaining = ticket.quota - ticket.sold_count;
      const soldRate = ticket.quota > 0 ? (ticket.sold_count / ticket.quota * 100).toFixed(2) : 0;
      const revenue = ticket.sold_count * ticket.price;

      return {
        ticket_id: ticket.ticket_id,
        ticket_name: ticket.ticket_name,
        price: ticket.price,
        quota: ticket.quota,
        sold_count: ticket.sold_count,
        remaining,
        sold_rate: parseFloat(soldRate),
        revenue,
        status: ticket.status
      };
    });

    const totalQuota = tickets.reduce((sum, t) => sum + t.quota, 0);
    const totalSold = tickets.reduce((sum, t) => sum + t.sold_count, 0);
    const totalRevenue = tickets.reduce((sum, t) => sum + t.sold_count * t.price, 0);

    return {
      event_id: eventId,
      tickets: salesData,
      summary: {
        total_quota: totalQuota,
        total_sold: totalSold,
        total_revenue: totalRevenue,
        sold_rate: totalQuota > 0 ? (totalSold / totalQuota * 100).toFixed(2) : 0
      }
    };
  },

  async getCheckInStats(eventId) {
    const event = await eventModel.findById(eventId);
    if (!event) {
      throw new ResourceNotFoundError('Event', eventId);
    }

    const checkIns = await checkInModel.findByEventId(eventId);
    const approvedRegistrations = await registrationModel.countByEventId(eventId, { status: 'approved' });

    const stats = {
      total: checkIns.length,
      by_ticket: {},
      by_hour: [],
      check_in_rate: approvedRegistrations > 0 ? (checkIns.length / approvedRegistrations * 100).toFixed(2) : 0
    };

    for (const checkIn of checkIns) {
      if (!stats.by_ticket[checkIn.ticket_name || 'Default']) {
        stats.by_ticket[checkIn.ticket_name || 'Default'] = 0;
      }
      stats.by_ticket[checkIn.ticket_name || 'Default']++;

      const hour = checkIn.check_in_time ? new Date(checkIn.check_in_time).getHours() : 0;
      const existingHour = stats.by_hour.find(h => h.hour === hour);
      if (existingHour) {
        existingHour.count++;
      } else {
        stats.by_hour.push({ hour, count: 1 });
      }
    }

    stats.by_hour.sort((a, b) => a.hour - b.hour);

    return {
      event_id: eventId,
      ...stats
    };
  },

  async getCustomReport(options) {
    const { eventId, chartType, dimensions, metrics, startDate, endDate, filters, time_range: timeRange } = options;

    if (!eventId) {
      throw new ReportConfigParseError('EventId is required', 'eventId');
    }

    const event = await eventModel.findById(eventId);
    if (!event) {
      throw new ResourceNotFoundError('Event', eventId);
    }

    const configToParse = {
      chart_type: chartType || 'bar',
      dimensions,
      metrics,
      filters,
      time_range: timeRange || (startDate || endDate ? {
        start_date: startDate,
        end_date: endDate
      } : null)
    };

    const parsedConfig = reportConfigParser.parse(configToParse);

    const data = await this.executeQueryWithParsedConfig(eventId, parsedConfig);

    return {
      event_id: eventId,
      chart_type: parsedConfig.chartType,
      dimensions: parsedConfig.dimensions,
      metrics: parsedConfig.metrics,
      start_date: parsedConfig.timeRange?.startDate || null,
      end_date: parsedConfig.timeRange?.endDate || null,
      filters: parsedConfig.filters,
      data
    };
  },

  async executeQueryWithParsedConfig(eventId, parsedConfig) {
    const { queryParams } = parsedConfig;
    
    if (!queryParams || queryParams.selectFields.length === 0) {
      throw new ReportConfigParseError('Invalid query parameters', 'queryParams');
    }

    let query = `
      SELECT ${queryParams.selectFields.join(', ')}
      FROM registrations r
      WHERE r.event_id = ?
    `;

    const params = [eventId];

    if (queryParams.whereConditions && queryParams.whereConditions.length > 0) {
      query += ` AND ${queryParams.whereConditions.join(' AND ')}`;
      params.push(...queryParams.params);
    }

    if (queryParams.needsGroupBy && queryParams.groupByFields.length > 0) {
      query += ` GROUP BY ${queryParams.groupByFields.join(', ')}`;
    }

    if (queryParams.orderBy) {
      query += queryParams.orderBy;
    }

    console.log('Executing analytics query:', query);
    console.log('Query params:', params);

    const [rows] = await db.execute(query, params);

    return rows;
  },

  async getRevenueStats(eventId, startDate, endDate) {
    let whereClause = 'event_id = ?';
    const params = [eventId];

    if (startDate) {
      whereClause += ' AND created_at >= ?';
      params.push(startDate);
    }
    if (endDate) {
      whereClause += ' AND created_at <= ?';
      params.push(endDate);
    }

    const query = `
      SELECT 
        DATE_FORMAT(created_at, '%Y-%m-%d') as date,
        COALESCE(SUM(total_amount), 0) as daily_revenue,
        COUNT(*) as daily_registrations
      FROM registrations
      WHERE ${whereClause}
      GROUP BY DATE_FORMAT(created_at, '%Y-%m-%d')
      ORDER BY date ASC
    `;

    const [rows] = await db.execute(query, params);

    const totalRevenue = rows.reduce((sum, row) => sum + parseFloat(row.daily_revenue), 0);
    const totalRegistrations = rows.reduce((sum, row) => sum + row.daily_registrations, 0);

    return {
      event_id: eventId,
      start_date: startDate || null,
      end_date: endDate || null,
      daily_data: rows,
      summary: {
        total_revenue: totalRevenue,
        total_registrations: totalRegistrations,
        avg_revenue_per_registration: totalRegistrations > 0 ? (totalRevenue / totalRegistrations).toFixed(2) : 0
      }
    };
  },

  async generateReportFromConfig(config) {
    return await this.getCustomReport(config);
  },

  getAvailableDimensions() {
    return reportConfigParser.getAvailableDimensions();
  },

  getAvailableMetrics() {
    return reportConfigParser.getAvailableMetrics();
  },

  getAvailableChartTypes() {
    return reportConfigParser.getAvailableChartTypes();
  },

  validateReportConfig(config) {
    return reportConfigParser.validateConfig(config);
  }
};

module.exports = analyticsService;
