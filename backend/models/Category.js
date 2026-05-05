const mongoose = require('mongoose');
const { v4: uuidv4 } = require('uuid');

const categorySchema = new mongoose.Schema({
  category_id: {
    type: String,
    default: () => `cat_${uuidv4().split('-')[0]}`,
    unique: true,
    index: true
  },
  category_name: {
    type: String,
    required: [true, '分类名称不能为空'],
    trim: true,
    unique: true,
    maxlength: 100
  },
  parent_category: {
    type: String,
    default: null,
    index: true
  },
  description: {
    type: String,
    default: '',
    maxlength: 500
  },
  doc_count: {
    type: Number,
    default: 0,
    index: true
  },
  created_by: {
    type: String,
    default: null,
    index: true
  },
  updated_by: {
    type: String,
    default: null
  },
  is_system: {
    type: Boolean,
    default: false,
    index: true
  },
  is_active: {
    type: Boolean,
    default: true,
    index: true
  },
  sort_order: {
    type: Number,
    default: 0
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
  timestamps: { createdAt: 'created_at', updatedAt: 'updated_at' }
});

categorySchema.pre('save', function(next) {
  this.updated_at = Date.now();
  next();
});

categorySchema.index({ category_name: 1 });
categorySchema.index({ parent_category: 1 });

module.exports = mongoose.model('Category', categorySchema);
