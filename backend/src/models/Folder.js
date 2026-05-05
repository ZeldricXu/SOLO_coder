const mongoose = require('mongoose');
const { Schema } = mongoose;

const folderSchema = new Schema({
  folder_id: {
    type: String,
    unique: true,
    required: true
  },
  name: {
    type: String,
    required: true,
    trim: true
  },
  parent_id: {
    type: String,
    ref: 'Folder',
    default: null
  },
  created_by: {
    type: String,
    required: true
  },
  order: {
    type: Number,
    default: 0
  },
  is_expanded: {
    type: Boolean,
    default: false
  }
}, {
  timestamps: {
    createdAt: 'created_at',
    updatedAt: 'updated_at'
  }
});

folderSchema.index({ parent_id: 1, order: 1 });
folderSchema.index({ folder_id: 1 }, { unique: true });

module.exports = mongoose.model('Folder', folderSchema);
