const analyticsService = require('../services/analyticsService');
const reportConfigService = require('../services/reportConfigService');
const { success, error } = require('../utils/response');

const analyticsController = {
  async getEventOverview(req, res) {
    try {
      const { eventId } = req.params;
      const overview = await analyticsService.getEventOverview(eventId);
      return success(res, overview);
    } catch (err) {
      console.error('Failed to get event overview:', err);
      return error(res, err.message || 'Failed to get event overview');
    }
  },

  async getRegistrationTrend(req, res) {
    try {
      const { eventId } = req.params;
      const { startDate, endDate, interval } = req.query;
      
      const trend = await analyticsService.getRegistrationTrend(
        eventId,
        startDate,
        endDate,
        interval
      );
      return success(res, trend);
    } catch (err) {
      console.error('Failed to get registration trend:', err);
      return error(res, err.message || 'Failed to get registration trend');
    }
  },

  async getTicketSales(req, res) {
    try {
      const { eventId } = req.params;
      const sales = await analyticsService.getTicketSales(eventId);
      return success(res, sales);
    } catch (err) {
      console.error('Failed to get ticket sales:', err);
      return error(res, err.message || 'Failed to get ticket sales');
    }
  },

  async getCheckInStats(req, res) {
    try {
      const { eventId } = req.params;
      const stats = await analyticsService.getCheckInStats(eventId);
      return success(res, stats);
    } catch (err) {
      console.error('Failed to get check-in stats:', err);
      return error(res, err.message || 'Failed to get check-in stats');
    }
  },

  async getCustomReport(req, res) {
    try {
      const { eventId, dimensions, metrics, startDate, endDate, filters, chartType } = req.body;

      if (!eventId) {
        return error(res, 'Event ID is required');
      }

      if (!dimensions || !Array.isArray(dimensions)) {
        return error(res, 'Dimensions must be an array');
      }

      if (!metrics || !Array.isArray(metrics)) {
        return error(res, 'Metrics must be an array');
      }

      const result = await analyticsService.getCustomReport({
        eventId,
        dimensions,
        metrics,
        startDate,
        endDate,
        filters
      });

      return success(res, {
        ...result,
        chart_type: chartType || 'bar'
      });
    } catch (err) {
      console.error('Failed to generate custom report:', err);
      return error(res, err.message || 'Failed to generate custom report');
    }
  },

  async getRevenueStats(req, res) {
    try {
      const { eventId } = req.params;
      const { startDate, endDate } = req.query;

      const stats = await analyticsService.getRevenueStats(eventId, startDate, endDate);
      return success(res, stats);
    } catch (err) {
      console.error('Failed to get revenue stats:', err);
      return error(res, err.message || 'Failed to get revenue stats');
    }
  },

  async getAvailableDimensions(req, res) {
    try {
      const dimensions = reportConfigService.getAvailableDimensions();
      return success(res, dimensions);
    } catch (err) {
      console.error('Failed to get available dimensions:', err);
      return error(res, 'Failed to get available dimensions');
    }
  },

  async getAvailableMetrics(req, res) {
    try {
      const metrics = reportConfigService.getAvailableMetrics();
      return success(res, metrics);
    } catch (err) {
      console.error('Failed to get available metrics:', err);
      return error(res, 'Failed to get available metrics');
    }
  },

  async getAvailableChartTypes(req, res) {
    try {
      const chartTypes = reportConfigService.getAvailableChartTypes();
      return success(res, chartTypes);
    } catch (err) {
      console.error('Failed to get available chart types:', err);
      return error(res, 'Failed to get available chart types');
    }
  },

  async getReportTemplates(req, res) {
    try {
      const { category } = req.query;
      const templates = await reportConfigService.getTemplates(category);
      return success(res, templates);
    } catch (err) {
      console.error('Failed to get report templates:', err);
      return error(res, 'Failed to get report templates');
    }
  },

  async generateFromTemplate(req, res) {
    try {
      const { eventId, templateId, startDate, endDate } = req.body;

      if (!eventId || !templateId) {
        return error(res, 'Event ID and Template ID are required');
      }

      const result = await analyticsService.generateReportFromTemplate(
        templateId,
        eventId,
        { startDate, endDate }
      );

      return success(res, result);
    } catch (err) {
      console.error('Failed to generate report from template:', err);
      return error(res, err.message || 'Failed to generate report from template');
    }
  }
};

module.exports = analyticsController;
