const mongoose = require('mongoose');
const { Schema } = mongoose;

const channelConfigSchema = new Schema({
  channel_type: {
    type: String,
    required: true,
    enum: ['weixin', 'weibo', 'douyin', 'bilibili', 'xigua', 'custom']
  },
  channel_name: {
    type: String,
    required: true,
    trim: true
  },
  config: {
    type: Schema.Types.Mixed,
    required: true
  },
  is_active: {
    type: Boolean,
    default: true
  },
  created_at: {
    type: Date,
    default: Date.now
  },
  updated_at: {
    type: Date,
    default: Date.now
  }
}, { _id: false });

const distributionItemSchema = new Schema({
  channel_config_id: {
    type: String,
    required: true
  },
  status: {
    type: String,
    required: true,
    enum: ['pending', 'pushing', 'success', 'failed'],
    default: 'pending'
  },
  error_message: {
    type: String,
    default: ''
  },
  external_id: {
    type: String,
    default: ''
  },
  pushed_at: {
    type: Date
  }
}, { _id: false });

const distributionTaskSchema = new Schema({
  task_id: {
    type: String,
    required: true,
    unique: true,
    index: true
  },
  media_id: {
    type: String,
    required: true,
    index: true,
    ref: 'Media'
  },
  title: {
    type: String,
    required: true,
    trim: true
  },
  description: {
    type: String,
    default: '',
    trim: true
  },
  tags: {
    type: [String],
    default: []
  },
  status: {
    type: String,
    required: true,
    enum: ['draft', 'pending', 'processing', 'completed', 'failed'],
    default: 'draft'
  },
  distributions: {
    type: [distributionItemSchema],
    default: []
  },
  scheduled_at: {
    type: Date
  },
  completed_at: {
    type: Date
  },
  created_at: {
    type: Date,
    default: Date.now,
    index: true
  },
  updated_at: {
    type: Date,
    default: Date.now
  }
}, {
  timestamps: { createdAt: 'created_at', updatedAt: 'updated_at' },
  collection: 'distribution_tasks'
});

distributionTaskSchema.index({ status: 1, created_at: 1 });

const channelSchema = new Schema({
  config_id: {
    type: String,
    required: true,
    unique: true,
    index: true
  },
  user_id: {
    type: String,
    required: true,
    index: true
  },
  channel_type: {
    type: String,
    required: true,
    enum: ['weixin', 'weibo', 'douyin', 'bilibili', 'xigua', 'custom']
  },
  channel_name: {
    type: String,
    required: true,
    trim: true
  },
  config: {
    type: Schema.Types.Mixed,
    required: true
  },
  is_active: {
    type: Boolean,
    default: true
  },
  created_at: {
    type: Date,
    default: Date.now
  },
  updated_at: {
    type: Date,
    default: Date.now
  }
}, {
  timestamps: { createdAt: 'created_at', updatedAt: 'updated_at' },
  collection: 'distribution_channels'
});

distributionTaskSchema.pre('save', function(next) {
  this.updated_at = Date.now();
  next();
});

channelSchema.pre('save', function(next) {
  this.updated_at = Date.now();
  next();
});

distributionTaskSchema.statics.findByTaskId = function(taskId) {
  return this.findOne({ task_id: taskId });
};

distributionTaskSchema.statics.listByMediaId = function(mediaId) {
  return this.find({ media_id: mediaId }).sort({ created_at: -1 });
};

distributionTaskSchema.statics.listByStatus = function(status, page = 1, limit = 20) {
  const skip = (page - 1) * limit;
  return this.find({ status })
    .populate('media_id')
    .sort({ created_at: -1 })
    .skip(skip)
    .limit(limit);
};

channelSchema.statics.findByConfigId = function(configId) {
  return this.findOne({ config_id: configId });
};

channelSchema.statics.listByUserId = function(userId, includeInactive = false) {
  const filter = { user_id: userId };
  if (!includeInactive) {
    filter.is_active = true;
  }
  return this.find(filter).sort({ created_at: -1 });
};

channelSchema.statics.createChannel = function(userId, channelType, channelName, config) {
  const channel = new this({
    config_id: `channel_${Date.now()}_${Math.random().toString(36).substr(2, 9)}`,
    user_id: userId,
    channel_type: channelType,
    channel_name: channelName,
    config: config,
    is_active: true
  });
  return channel.save();
};

module.exports = {
  DistributionTask: mongoose.model('DistributionTask', distributionTaskSchema),
  DistributionChannel: mongoose.model('DistributionChannel', channelSchema)
};
