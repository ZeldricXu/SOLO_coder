const mongoose = require('mongoose');
const { v4: uuidv4 } = require('uuid');

const shareSchema = new mongoose.Schema({
  share_id: {
    type: String,
    default: () => `share_${uuidv4().split('-')[0]}`,
    unique: true,
    index: true
  },
  doc_id: {
    type: String,
    required: [true, '文档ID不能为空'],
    index: true
  },
  share_type: {
    type: String,
    enum: ['user', 'team', 'public'],
    required: [true, '分享类型不能为空'],
    index: true
  },
  target_id: {
    type: String,
    required: [true, '目标ID不能为空'],
    index: true
  },
  permission: {
    type: String,
    enum: ['read', 'write', 'admin'],
    default: 'read',
    index: true
  },
  created_by: {
    type: String,
    required: [true, '创建者不能为空'],
    index: true
  },
  expires_at: {
    type: Date,
    default: null
  },
  is_active: {
    type: Boolean,
    default: true,
    index: true
  },
  created_at: {
    type: Date,
    default: Date.now
  }
}, {
  timestamps: { createdAt: 'created_at' }
});

shareSchema.index({ doc_id: 1, is_active: 1 });
shareSchema.index({ target_id: 1, share_type: 1, is_active: 1 });
shareSchema.index({ created_by: 1, created_at: -1 });

module.exports = mongoose.model('Share', shareSchema);
