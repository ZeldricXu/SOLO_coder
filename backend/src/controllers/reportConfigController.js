const reportConfigService = require('../services/reportConfigService');
const { success, error } = require('../utils/response');

const reportConfigController = {
  async getTemplates(req, res) {
    try {
      const { category } = req.query;
      const templates = await reportConfigService.getTemplates(category);
      return success(res, templates);
    } catch (err) {
      console.error('Failed to get report templates:', err);
      return error(res, 'Failed to get report templates');
    }
  },

  async getTemplateById(req, res) {
    try {
      const { templateId } = req.params;
      const template = await reportConfigService.getTemplateById(templateId);
      if (!template) {
        return error(res, 'Template not found', 404);
      }
      return success(res, template);
    } catch (err) {
      console.error('Failed to get report template:', err);
      return error(res, 'Failed to get report template');
    }
  },

  async createConfig(req, res) {
    try {
      const { user } = req;
      const configData = {
        ...req.body,
        created_by: user?.user_id
      };

      const config = await reportConfigService.createConfig(configData);
      return success(res, config, 'Report config created successfully');
    } catch (err) {
      console.error('Failed to create report config:', err);
      return error(res, err.message || 'Failed to create report config');
    }
  },

  async getConfigs(req, res) {
    try {
      const { eventId, isPublic, isDefault } = req.query;
      const filters = {};
      
      if (eventId) filters.event_id = eventId;
      if (isPublic !== undefined) filters.is_public = isPublic === 'true';
      if (isDefault !== undefined) filters.is_default = isDefault === 'true';

      const configs = await reportConfigService.getConfigs(filters);
      return success(res, configs);
    } catch (err) {
      console.error('Failed to get report configs:', err);
      return error(res, 'Failed to get report configs');
    }
  },

  async getConfigById(req, res) {
    try {
      const { configId } = req.params;
      const config = await reportConfigService.getConfigById(configId);
      if (!config) {
        return error(res, 'Config not found', 404);
      }
      return success(res, config);
    } catch (err) {
      console.error('Failed to get report config:', err);
      return error(res, 'Failed to get report config');
    }
  },

  async updateConfig(req, res) {
    try {
      const { configId } = req.params;
      const configData = req.body;

      const config = await reportConfigService.updateConfig(configId, configData);
      return success(res, config, 'Report config updated successfully');
    } catch (err) {
      console.error('Failed to update report config:', err);
      return error(res, err.message || 'Failed to update report config');
    }
  },

  async deleteConfig(req, res) {
    try {
      const { configId } = req.params;
      const result = await reportConfigService.deleteConfig(configId);
      if (!result) {
        return error(res, 'Config not found', 404);
      }
      return success(res, null, 'Report config deleted successfully');
    } catch (err) {
      console.error('Failed to delete report config:', err);
      return error(res, 'Failed to delete report config');
    }
  },

  async generateReportFromConfig(req, res) {
    try {
      const { configId } = req.params;
      const { eventId, startDate, endDate, filters } = req.body;

      const result = await reportConfigService.generateReport(configId, {
        eventId,
        startDate,
        endDate,
        filters
      });

      return success(res, result);
    } catch (err) {
      console.error('Failed to generate report from config:', err);
      return error(res, err.message || 'Failed to generate report');
    }
  },

  async generateCustomReport(req, res) {
    try {
      const { eventId, chartType, dimensions, metrics, startDate, endDate, filters } = req.body;

      if (!eventId) {
        return error(res, 'Event ID is required');
      }

      if (!dimensions || !Array.isArray(dimensions)) {
        return error(res, 'Dimensions must be an array');
      }

      if (!metrics || !Array.isArray(metrics)) {
        return error(res, 'Metrics must be an array');
      }

      const result = await reportConfigService.generateCustomReport({
        eventId,
        chartType,
        dimensions,
        metrics,
        startDate,
        endDate,
        filters
      });

      return success(res, result);
    } catch (err) {
      console.error('Failed to generate custom report:', err);
      return error(res, err.message || 'Failed to generate custom report');
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
  }
};

module.exports = reportConfigController;
