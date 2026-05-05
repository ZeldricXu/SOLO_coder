const mongoose = require('mongoose');
const { Schema } = mongoose;

const metadataSchema = new Schema({
  duration: { type: Number, default: 0 },
  width: { type: Number, default: 0 },
  height: { type: Number, default: 0 },
  bitrate: { type: Number, default: 0 },
  thumbnail: { type: String, default: '' }
}, { _id: false });

const mediaSchema = new Schema({
  media_id: {
    type: String,
    required: true,
    unique: true,
    index: true
  },
  filename: {
    type: String,
    required: true,
    trim: true
  },
  file_type: {
    type: String,
    required: true,
    enum: ['image', 'video', 'audio', 'other']
  },
  file_size: {
    type: Number,
    required: true,
    min: 0
  },
  mime_type: {
    type: String,
    required: true
  },
  metadata: {
    type: metadataSchema,
    default: {}
  },
  tags: {
    type: [String],
    default: []
  },
  folder_id: {
    type: String,
    default: ''
  },
  status: {
    type: String,
    required: true,
    enum: ['pending', 'uploading', 'processing', 'pending_review', 'approved', 'rejected', 'failed'],
    default: 'uploading'
  },
  upload_progress: {
    type: Number,
    default: 0,
    min: 0,
    max: 100
  },
  storage_path: {
    type: String,
    required: true
  },
  file_id: {
    type: String,
    required: true,
    index: true
  },
  created_at: {
    type: Date,
    default: Date.now,
    index: true
  },
  updated_at: {
    type: Date,
    default: Date.now
  },
  reviewed_at: {
    type: Date
  }
}, {
  timestamps: { createdAt: 'created_at', updatedAt: 'updated_at' },
  collection: 'media_files'
});

mediaSchema.index({ filename: 'text', tags: 'text' });
mediaSchema.index({ file_type: 1, status: 1 });

mediaSchema.pre('save', function(next) {
  this.updated_at = Date.now();
  next();
});

mediaSchema.statics.findByMediaId = function(mediaId) {
  return this.findOne({ media_id: mediaId });
};

mediaSchema.statics.findByFileId = function(fileId) {
  return this.findOne({ file_id: fileId });
};

mediaSchema.statics.listByStatus = function(status, page = 1, limit = 20) {
  const skip = (page - 1) * limit;
  return this.find({ status })
    .sort({ created_at: -1 })
    .skip(skip)
    .limit(limit);
};

mediaSchema.statics.search = function(query, fileType, status, page = 1, limit = 20, mediaIds = null) {
  const skip = (page - 1) * limit;
  const filter = {};
  
  if (mediaIds && Array.isArray(mediaIds) && mediaIds.length > 0) {
    filter.media_id = { $in: mediaIds };
  }
  
  if (query) {
    filter.$or = [
      { filename: { $regex: query, $options: 'i' } },
      { tags: { $regex: query, $options: 'i' } }
    ];
  }
  
  if (fileType) {
    filter.file_type = fileType;
  }
  
  if (status) {
    filter.status = status;
  }
  
  return this.find(filter)
    .sort({ created_at: -1 })
    .skip(skip)
    .limit(limit);
};

module.exports = mongoose.model('Media', mediaSchema);
