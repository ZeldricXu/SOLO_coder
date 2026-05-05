const mongoose = require('mongoose');
const { v4: uuidv4 } = require('uuid');

const documentSchema = new mongoose.Schema({
  doc_id: {
    type: String,
    default: () => `doc_${uuidv4().split('-')[0]}`,
    unique: true,
    index: true
  },
  title: {
    type: String,
    required: [true, '文档标题不能为空'],
    trim: true,
    maxlength: 200
  },
  content: {
    type: String,
    required: [true, '文档内容不能为空']
  },
  author: {
    type: String,
    required: [true, '文档作者不能为空'],
    index: true
  },
  category: {
    type: String,
    default: '未分类',
    index: true
  },
  tags: {
    type: [String],
    default: [],
    index: true
  },
  status: {
    type: String,
    enum: ['draft', 'published', 'archived'],
    default: 'draft',
    index: true
  },
  current_version: {
    type: String,
    default: 'v1'
  },
  version_count: {
    type: Number,
    default: 1
  },
  favorites: {
    type: [String],
    default: [],
    index: true
  },
  permissions: {
    read: {
      type: [String],
      default: []
    },
    write: {
      type: [String],
      default: []
    },
    admin: {
      type: [String],
      default: []
    }
  },
  created_at: {
    type: Date,
    default: Date.now,
    index: true
  },
  updated_at: {
    type: Date,
    default: Date.now,
    index: true
  }
}, {
  timestamps: { createdAt: 'created_at', updatedAt: 'updated_at' }
});

documentSchema.pre('save', function(next) {
  this.updated_at = Date.now();
  next();
});

documentSchema.index({ title: 'text', content: 'text', tags: 'text' });

module.exports = mongoose.model('Document', documentSchema);
