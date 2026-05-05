const distributionService = require('../services/distributionService');

class DistributionController {
  async createChannel(req, res) {
    try {
      const user_id = req.user?.id || 'system';
      const { channel_type, channel_name, config } = req.body;
      
      if (!channel_type || !channel_name || !config) {
        return res.status(400).json({
          code: 400,
          message: 'Missing required fields: channel_type, channel_name, config'
        });
      }

      const result = await distributionService.createChannel(
        user_id,
        channel_type,
        channel_name,
        config
      );

      if (!result.success) {
        return res.status(400).json({
          code: 400,
          message: result.message
        });
      }

      return res.status(200).json({
        code: 200,
        data: result
      });
    } catch (error) {
      console.error('Error in createChannel:', error);
      return res.status(500).json({
        code: 500,
        message: 'Internal server error',
        error: error.message
      });
    }
  }

  async getChannel(req, res) {
    try {
      const { config_id } = req.params;
      
      if (!config_id) {
        return res.status(400).json({
          code: 400,
          message: 'Missing config_id parameter'
        });
      }

      const result = await distributionService.getChannel(config_id);

      if (!result.success) {
        return res.status(404).json({
          code: 404,
          message: result.message
        });
      }

      return res.status(200).json({
        code: 200,
        data: result.channel
      });
    } catch (error) {
      console.error('Error in getChannel:', error);
      return res.status(500).json({
        code: 500,
        message: 'Internal server error',
        error: error.message
      });
    }
  }

  async listChannels(req, res) {
    try {
      const user_id = req.user?.id || 'system';
      const { include_inactive } = req.query;
      
      const result = await distributionService.listChannels(
        user_id,
        include_inactive === 'true'
      );

      return res.status(200).json({
        code: 200,
        data: result.channels
      });
    } catch (error) {
      console.error('Error in listChannels:', error);
      return res.status(500).json({
        code: 500,
        message: 'Internal server error',
        error: error.message
      });
    }
  }

  async updateChannel(req, res) {
    try {
      const { config_id } = req.params;
      const { channel_name, config, is_active } = req.body;
      
      if (!config_id) {
        return res.status(400).json({
          code: 400,
          message: 'Missing config_id parameter'
        });
      }

      const updates = {};
      if (channel_name !== undefined) updates.channel_name = channel_name;
      if (config !== undefined) updates.config = config;
      if (is_active !== undefined) updates.is_active = is_active;

      if (Object.keys(updates).length === 0) {
        return res.status(400).json({
          code: 400,
          message: 'No update fields provided'
        });
      }

      const result = await distributionService.updateChannel(config_id, updates);

      if (!result.success) {
        return res.status(404).json({
          code: 404,
          message: result.message
        });
      }

      return res.status(200).json({
        code: 200,
        data: result.channel
      });
    } catch (error) {
      console.error('Error in updateChannel:', error);
      return res.status(500).json({
        code: 500,
        message: 'Internal server error',
        error: error.message
      });
    }
  }

  async deleteChannel(req, res) {
    try {
      const { config_id } = req.params;
      
      if (!config_id) {
        return res.status(400).json({
          code: 400,
          message: 'Missing config_id parameter'
        });
      }

      const result = await distributionService.deleteChannel(config_id);

      if (!result.success) {
        return res.status(404).json({
          code: 404,
          message: result.message
        });
      }

      return res.status(200).json({
        code: 200,
        data: result
      });
    } catch (error) {
      console.error('Error in deleteChannel:', error);
      return res.status(500).json({
        code: 500,
        message: 'Internal server error',
        error: error.message
      });
    }
  }

  async createDistributionTask(req, res) {
    try {
      const { media_id, title, description, tags, channel_config_ids } = req.body;
      
      if (!media_id || !channel_config_ids || !Array.isArray(channel_config_ids)) {
        return res.status(400).json({
          code: 400,
          message: 'Missing required fields: media_id and channel_config_ids (array)'
        });
      }

      const result = await distributionService.createDistributionTask(
        media_id,
        title,
        description,
        tags,
        channel_config_ids
      );

      if (!result.success) {
        return res.status(400).json({
          code: 400,
          message: result.message
        });
      }

      return res.status(200).json({
        code: 200,
        data: result
      });
    } catch (error) {
      console.error('Error in createDistributionTask:', error);
      return res.status(500).json({
        code: 500,
        message: 'Internal server error',
        error: error.message
      });
    }
  }

  async getDistributionTask(req, res) {
    try {
      const { task_id } = req.params;
      
      if (!task_id) {
        return res.status(400).json({
          code: 400,
          message: 'Missing task_id parameter'
        });
      }

      const result = await distributionService.getDistributionTask(task_id);

      if (!result.success) {
        return res.status(404).json({
          code: 404,
          message: result.message
        });
      }

      return res.status(200).json({
        code: 200,
        data: result.task
      });
    } catch (error) {
      console.error('Error in getDistributionTask:', error);
      return res.status(500).json({
        code: 500,
        message: 'Internal server error',
        error: error.message
      });
    }
  }

  async listDistributionTasks(req, res) {
    try {
      const { status, page = 1, limit = 20 } = req.query;
      
      const pageNum = parseInt(page) || 1;
      const limitNum = parseInt(limit) || 20;

      const result = await distributionService.listDistributionTasks(
        status,
        pageNum,
        limitNum
      );

      return res.status(200).json({
        code: 200,
        data: result
      });
    } catch (error) {
      console.error('Error in listDistributionTasks:', error);
      return res.status(500).json({
        code: 500,
        message: 'Internal server error',
        error: error.message
      });
    }
  }

  async executeDistribution(req, res) {
    try {
      const { task_id } = req.params;
      
      if (!task_id) {
        return res.status(400).json({
          code: 400,
          message: 'Missing task_id parameter'
        });
      }

      const result = await distributionService.executeDistribution(task_id);

      if (!result.success) {
        return res.status(400).json({
          code: 400,
          message: result.message
        });
      }

      return res.status(200).json({
        code: 200,
        data: result
      });
    } catch (error) {
      console.error('Error in executeDistribution:', error);
      return res.status(500).json({
        code: 500,
        message: 'Internal server error',
        error: error.message
      });
    }
  }

  async batchDistribute(req, res) {
    try {
      const { media_ids, channel_config_ids, title_template, description_template } = req.body;
      
      if (!media_ids || !Array.isArray(media_ids) || media_ids.length === 0) {
        return res.status(400).json({
          code: 400,
          message: 'media_ids must be a non-empty array'
        });
      }
      
      if (!channel_config_ids || !Array.isArray(channel_config_ids) || channel_config_ids.length === 0) {
        return res.status(400).json({
          code: 400,
          message: 'channel_config_ids must be a non-empty array'
        });
      }

      const result = await distributionService.batchDistribute(
        media_ids,
        channel_config_ids,
        title_template,
        description_template
      );

      return res.status(200).json({
        code: 200,
        data: result
      });
    } catch (error) {
      console.error('Error in batchDistribute:', error);
      return res.status(500).json({
        code: 500,
        message: 'Internal server error',
        error: error.message
      });
    }
  }

  async getDistributionStats(req, res) {
    try {
      const result = await distributionService.getDistributionStats();

      return res.status(200).json({
        code: 200,
        data: result.stats
      });
    } catch (error) {
      console.error('Error in getDistributionStats:', error);
      return res.status(500).json({
        code: 500,
        message: 'Internal server error',
        error: error.message
      });
    }
  }
}

module.exports = new DistributionController();
