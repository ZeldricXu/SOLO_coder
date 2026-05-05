const mongoose = require('mongoose');
const { v4: uuidv4 } = require('uuid');

const commentSchema = new mongoose.Schema({
  comment_id: {
    type: String,
    default: () => `comment_${uuidv4().split('-')[0]}`,
    unique: true,
    index: true
  },
  doc_id: {
    type: String,
    required: [true, '文档ID不能为空'],
    index: true
  },
  parent_comment_id: {
    type: String,
    default: null,
    index: true
  },
  content: {
    type: String,
    required: [true, '评论内容不能为空'],
    maxlength: 2000
  },
  author: {
    type: String,
    required: [true, '评论作者不能为空'],
    index: true
  },
  position: {
    line: { type: Number, default: 0 },
    start_char: { type: Number, default: 0 },
    end_char: { type: Number, default: 0 },
    selected_text: { type: String, default: '' }
  },
  status: {
    type: String,
    enum: ['open', 'resolved', 'closed'],
    default: 'open',
    index: true
  },
  resolved_by: {
    type: String,
    default: null
  },
  resolved_at: {
    type: Date,
    default: null
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
  timestamps: { createdAt: 'created_at', updatedAt: 'updated_at' }
});

commentSchema.pre('save', function(next) {
  this.updated_at = Date.now();
  next();
});

commentSchema.index({ doc_id: 1, status: 1, created_at: -1 });
commentSchema.index({ doc_id: 1, parent_comment_id: 1 });
commentSchema.index({ author: 1, created_at: -1 });

module.exports = mongoose.model('Comment', commentSchema);
