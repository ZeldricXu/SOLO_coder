const { DistributionTask, DistributionChannel } = require('../models/Distribution');
const Media = require('../models/Media');
const { v4: uuidv4 } = require('uuid');

class DistributionService {
  generateTaskId() {
    return `dist_${Date.now()}_${Math.random().toString(36).substr(2, 9)}`;
  }

  async createChannel(userId, channelType, channelName, config) {
    try {
      const validChannelTypes = ['weixin', 'weibo', 'douyin', 'bilibili', 'xigua', 'custom'];
      if (!validChannelTypes.includes(channelType)) {
        return {
          success: false,
          message: 'Invalid channel type'
        };
      }

      if (!config || typeof config !== 'object') {
        return {
          success: false,
          message: 'Channel configuration is required'
        };
      }

      const channel = await DistributionChannel.createChannel(
        userId,
        channelType,
        channelName,
        config
      );

      return {
        success: true,
        config_id: channel.config_id,
        channel_type: channel.channel_type,
        channel_name: channel.channel_name,
        is_active: channel.is_active
      };
    } catch (error) {
      console.error('Error creating channel:', error);
      return {
        success: false,
        message: 'Failed to create channel',
        error: error.message
      };
    }
  }

  async getChannel(configId) {
    try {
      const channel = await DistributionChannel.findByConfigId(configId);
      
      if (!channel) {
        return {
          success: false,
          message: 'Channel not found'
        };
      }

      return {
        success: true,
        channel: channel.toObject()
      };
    } catch (error) {
      console.error('Error getting channel:', error);
      return {
        success: false,
        message: 'Failed to get channel',
        error: error.message
      };
    }
  }

  async listChannels(userId, includeInactive = false) {
    try {
      const channels = await DistributionChannel.listByUserId(userId, includeInactive);
      
      return {
        success: true,
        channels: channels.map(c => c.toObject())
      };
    } catch (error) {
      console.error('Error listing channels:', error);
      return {
        success: false,
        message: 'Failed to list channels',
        error: error.message
      };
    }
  }

  async updateChannel(configId, updates) {
    try {
      const channel = await DistributionChannel.findByConfigId(configId);
      
      if (!channel) {
        return {
          success: false,
          message: 'Channel not found'
        };
      }

      const allowedFields = ['channel_name', 'config', 'is_active'];
      for (const field of allowedFields) {
        if (updates[field] !== undefined) {
          channel[field] = updates[field];
        }
      }

      await channel.save();

      return {
        success: true,
        channel: channel.toObject()
      };
    } catch (error) {
      console.error('Error updating channel:', error);
      return {
        success: false,
        message: 'Failed to update channel',
        error: error.message
      };
    }
  }

  async deleteChannel(configId) {
    try {
      const channel = await DistributionChannel.findByConfigId(configId);
      
      if (!channel) {
        return {
          success: false,
          message: 'Channel not found'
        };
      }

      await channel.deleteOne();

      return {
        success: true,
        message: 'Channel deleted successfully'
      };
    } catch (error) {
      console.error('Error deleting channel:', error);
      return {
        success: false,
        message: 'Failed to delete channel',
        error: error.message
      };
    }
  }

  async createDistributionTask(mediaId, title, description, tags, channelConfigIds) {
    try {
      const media = await Media.findByMediaId(mediaId);
      
      if (!media) {
        return {
          success: false,
          message: 'Media not found'
        };
      }

      if (media.status !== 'approved') {
        return {
          success: false,
          message: 'Media must be approved before distribution'
        };
      }

      const distributions = [];
      for (const configId of channelConfigIds) {
        const channel = await DistributionChannel.findByConfigId(configId);
        if (channel && channel.is_active) {
          distributions.push({
            channel_config_id: configId,
            status: 'pending'
          });
        }
      }

      if (distributions.length === 0) {
        return {
          success: false,
          message: 'No valid active channels found'
        };
      }

      const task = new DistributionTask({
        task_id: this.generateTaskId(),
        media_id: mediaId,
        title: title || media.filename,
        description: description || '',
        tags: tags || [],
        status: 'pending',
        distributions: distributions
      });

      await task.save();

      return {
        success: true,
        task_id: task.task_id,
        media_id: mediaId,
        status: task.status,
        distributions_count: distributions.length
      };
    } catch (error) {
      console.error('Error creating distribution task:', error);
      return {
        success: false,
        message: 'Failed to create distribution task',
        error: error.message
      };
    }
  }

  async getDistributionTask(taskId) {
    try {
      const task = await DistributionTask.findByTaskId(taskId);
      
      if (!task) {
        return {
          success: false,
          message: 'Distribution task not found'
        };
      }

      const media = await Media.findByMediaId(task.media_id);

      return {
        success: true,
        task: {
          ...task.toObject(),
          media: media ? media.toObject() : null
        }
      };
    } catch (error) {
      console.error('Error getting distribution task:', error);
      return {
        success: false,
        message: 'Failed to get distribution task',
        error: error.message
      };
    }
  }

  async listDistributionTasks(status = null, page = 1, limit = 20) {
    try {
      const tasks = await DistributionTask.listByStatus(status, page, limit);
      
      return {
        success: true,
        tasks: tasks.map(t => t.toObject()),
        pagination: {
          page,
          limit
        }
      };
    } catch (error) {
      console.error('Error listing distribution tasks:', error);
      return {
        success: false,
        message: 'Failed to list distribution tasks',
        error: error.message
      };
    }
  }

  async executeDistribution(taskId) {
    try {
      const task = await DistributionTask.findByTaskId(taskId);
      
      if (!task) {
        return {
          success: false,
          message: 'Distribution task not found'
        };
      }

      if (task.status !== 'pending') {
        return {
          success: false,
          message: 'Task is not in pending status'
        };
      }

      task.status = 'processing';
      await task.save();

      process.nextTick(async () => {
        try {
          const media = await Media.findByMediaId(task.media_id);
          
          if (!media) {
            throw new Error('Media not found');
          }

          for (const distribution of task.distributions) {
            try {
              distribution.status = 'pushing';
              await task.save();

              const channel = await DistributionChannel.findByConfigId(
                distribution.channel_config_id
              );

              if (!channel || !channel.is_active) {
                distribution.status = 'failed';
                distribution.error_message = 'Channel not active or not found';
                continue;
              }

              const pushResult = await this.pushToChannel(
                channel,
                media,
                task
              );

              if (pushResult.success) {
                distribution.status = 'success';
                distribution.external_id = pushResult.external_id;
                distribution.pushed_at = new Date();
              } else {
                distribution.status = 'failed';
                distribution.error_message = pushResult.error;
              }

            } catch (pushError) {
              console.error('Error pushing to channel:', pushError);
              distribution.status = 'failed';
              distribution.error_message = pushError.message;
            }
          }

          const allSuccess = task.distributions.every(d => d.status === 'success');
          const anySuccess = task.distributions.some(d => d.status === 'success');

          if (allSuccess) {
            task.status = 'completed';
          } else if (anySuccess) {
            task.status = 'completed';
          } else {
            task.status = 'failed';
          }

          task.completed_at = new Date();
          await task.save();

        } catch (error) {
          console.error('Error executing distribution:', error);
          task.status = 'failed';
          await task.save();
        }
      });

      return {
        success: true,
        task_id: task.task_id,
        status: 'processing',
        message: 'Distribution task started'
      };

    } catch (error) {
      console.error('Error starting distribution:', error);
      return {
        success: false,
        message: 'Failed to start distribution',
        error: error.message
      };
    }
  }

  async pushToChannel(channel, media, task) {
    try {
      console.log(`Pushing to channel ${channel.channel_name} (${channel.channel_type})`);
      console.log(`Media: ${media.filename}, Title: ${task.title}`);

      await new Promise(resolve => setTimeout(resolve, 1000));

      return {
        success: true,
        external_id: `${channel.channel_type}_${uuidv4().substr(0, 8)}`
      };

    } catch (error) {
      console.error('Error in pushToChannel:', error);
      return {
        success: false,
        error: error.message
      };
    }
  }

  async batchDistribute(mediaIds, channelConfigIds, titleTemplate, descriptionTemplate) {
    try {
      const results = [];

      for (const mediaId of mediaIds) {
        const media = await Media.findByMediaId(mediaId);
        
        if (!media || media.status !== 'approved') {
          results.push({
            media_id: mediaId,
            success: false,
            message: media ? 'Media not approved' : 'Media not found'
          });
          continue;
        }

        const title = titleTemplate 
          ? titleTemplate.replace('{filename}', media.filename)
          : media.filename;
        
        const description = descriptionTemplate || '';

        const createResult = await this.createDistributionTask(
          mediaId,
          title,
          description,
          [],
          channelConfigIds
        );

        if (createResult.success) {
          const executeResult = await this.executeDistribution(createResult.task_id);
          results.push({
            media_id: mediaId,
            task_id: createResult.task_id,
            success: true,
            status: executeResult.status
          });
        } else {
          results.push({
            media_id: mediaId,
            success: false,
            message: createResult.message
          });
        }
      }

      return {
        success: true,
        total: mediaIds.length,
        results: results
      };

    } catch (error) {
      console.error('Error in batch distribution:', error);
      return {
        success: false,
        message: 'Failed to execute batch distribution',
        error: error.message
      };
    }
  }

  async getDistributionStats() {
    try {
      const total = await DistributionTask.countDocuments();
      const draft = await DistributionTask.countDocuments({ status: 'draft' });
      const pending = await DistributionTask.countDocuments({ status: 'pending' });
      const processing = await DistributionTask.countDocuments({ status: 'processing' });
      const completed = await DistributionTask.countDocuments({ status: 'completed' });
      const failed = await DistributionTask.countDocuments({ status: 'failed' });

      return {
        success: true,
        stats: {
          total,
          draft,
          pending,
          processing,
          completed,
          failed
        }
      };
    } catch (error) {
      console.error('Error getting distribution stats:', error);
      return {
        success: false,
        message: 'Failed to get distribution stats',
        error: error.message
      };
    }
  }
}

module.exports = new DistributionService();
