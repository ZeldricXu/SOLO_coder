const mongoose = require('mongoose');
const { Schema } = mongoose;

const versionSchema = new Schema({
  version_id: {
    type: String,
    unique: true,
    required: true
  },
  doc_id: {
    type: String,
    required: true,
    ref: 'Document'
  },
  version_number: {
    type: Number,
    required: true
  },
  content_snapshot: {
    type: String,
    required: true
  },
  title_snapshot: {
    type: String,
    required: true
  },
  edited_by: {
    type: String,
    required: true
  },
  edit_summary: {
    type: String,
    default: ''
  },
  created_at: {
    type: Date,
    default: Date.now
  }
}, {
  timestamps: false
});

versionSchema.index({ doc_id: 1, version_number: 1 }, { unique: true });
versionSchema.index({ doc_id: 1, created_at: -1 });

module.exports = mongoose.model('Version', versionSchema);
