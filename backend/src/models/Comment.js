const mongoose = require('mongoose');
const { Schema } = mongoose;

const commentSchema = new Schema({
  comment_id: {
    type: String,
    unique: true,
    required: true
  },
  doc_id: {
    type: String,
    required: true,
    ref: 'Document'
  },
  user_id: {
    type: String,
    required: true
  },
  content: {
    type: String,
    required: true,
    trim: true
  },
  position: {
    start_offset: { type: Number },
    end_offset: { type: Number }
  },
  parent_comment_id: {
    type: String,
    ref: 'Comment',
    default: null
  },
  is_resolved: {
    type: Boolean,
    default: false
  },
  resolved_by: {
    type: String
  },
  resolved_at: {
    type: Date
  }
}, {
  timestamps: {
    createdAt: 'created_at',
    updatedAt: 'updated_at'
  }
});

commentSchema.index({ doc_id: 1, created_at: -1 });
commentSchema.index({ doc_id: 1, is_resolved: 1 });

module.exports = mongoose.model('Comment', commentSchema);
