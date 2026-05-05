const mongoose = require('mongoose');
const { Schema } = mongoose;

const documentSchema = new Schema({
  doc_id: {
    type: String,
    unique: true,
    required: true
  },
  title: {
    type: String,
    required: true,
    trim: true
  },
  content: {
    type: String,
    default: ''
  },
  format: {
    type: String,
    enum: ['markdown', 'rich-text'],
    default: 'markdown'
  },
  folder_id: {
    type: String,
    ref: 'Folder',
    default: null
  },
  created_by: {
    type: String,
    required: true
  },
  collaborators: [{
    type: String
  }],
  current_version: {
    type: Number,
    default: 1
  },
  last_edited_by: {
    type: String,
    required: true
  },
  last_edited_at: {
    type: Date,
    default: Date.now
  },
  is_locked: {
    type: Boolean,
    default: false
  }
}, {
  timestamps: {
    createdAt: 'created_at',
    updatedAt: 'updated_at'
  }
});

documentSchema.index({ title: 'text', content: 'text' });
documentSchema.index({ folder_id: 1 });
documentSchema.index({ doc_id: 1 }, { unique: true });

module.exports = mongoose.model('Document', documentSchema);
