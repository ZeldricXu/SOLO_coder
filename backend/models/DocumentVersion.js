const mongoose = require('mongoose');
const { v4: uuidv4 } = require('uuid');

const documentVersionSchema = new mongoose.Schema({
  version_id: {
    type: String,
    default: () => `ver_${uuidv4().split('-')[0]}`,
    unique: true,
    index: true
  },
  doc_id: {
    type: String,
    required: [true, '文档ID不能为空'],
    index: true
  },
  version: {
    type: String,
    required: [true, '版本号不能为空'],
    index: true
  },
  content: {
    type: String,
    required: [true, '版本内容不能为空']
  },
  change_desc: {
    type: String,
    default: '',
    maxlength: 500
  },
  author: {
    type: String,
    required: [true, '版本作者不能为空'],
    index: true
  },
  created_at: {
    type: Date,
    default: Date.now,
    index: true
  },
  
  is_compressed: {
    type: Boolean,
    default: false,
    index: true
  },
  
  compression_algorithm: {
    type: String,
    default: 'gzip',
    enum: ['gzip', 'deflate', 'brotli']
  },
  
  original_size: {
    type: Number,
    default: 0
  },
  
  compressed_size: {
    type: Number,
    default: 0
  },
  
  content_type: {
    type: String,
    default: 'full',
    enum: ['full', 'delta'],
    index: true
  },
  
  base_version: {
    type: String,
    default: null,
    index: true
  },
  
  delta_from_version: {
    type: String,
    default: null
  },
  
  delta_operations: {
    type: mongoose.Schema.Types.Mixed,
    default: null
  },
  
  is_full_version: {
    type: Boolean,
    default: true,
    index: true
  },
  
  version_number: {
    type: Number,
    default: 1,
    index: true
  },
  
  strategy_used: {
    type: String,
    default: 'default',
    enum: ['line', 'paragraph', 'word', 'default'],
    index: true
  },
  
  document_type: {
    type: String,
    default: null,
    enum: ['code', 'richText', 'plainText', null],
    index: true
  }
}, {
  timestamps: { createdAt: 'created_at' }
});

documentVersionSchema.index({ doc_id: 1, version: 1 }, { unique: true });
documentVersionSchema.index({ doc_id: 1, created_at: -1 });
documentVersionSchema.index({ doc_id: 1, is_full_version: 1 });
documentVersionSchema.index({ doc_id: 1, content_type: 1 });

documentVersionSchema.pre('save', function(next) {
  if (this.isNew && !this.version_number) {
    const match = this.version.match(/v(\d+)/);
    if (match) {
      this.version_number = parseInt(match[1], 10);
    }
  }
  next();
});

module.exports = mongoose.model('DocumentVersion', documentVersionSchema);
