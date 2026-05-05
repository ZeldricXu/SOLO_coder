const mongoose = require('mongoose');

const operationLogSchema = new mongoose.Schema({
  operation_id: {
    type: String,
    required: true,
    unique: true,
    index: true
  },
  scene_id: {
    type: String,
    required: true,
    index: true
  },
  operation_type: {
    type: String,
    required: true,
    enum: ['object_create', 'transform_update', 'object_delete', 'material_update', 'lock_acquire', 'lock_release']
  },
  target_object: {
    type: String
  },
  parameters: {
    type: mongoose.Schema.Types.Mixed,
    required: true
  },
  user_id: {
    type: String,
    required: true
  },
  timestamp: {
    type: Date,
    required: true,
    default: Date.now
  },
  version: {
    type: Number,
    required: true
  },
  object_type: {
    type: String
  }
}, {
  timestamps: true
});

operationLogSchema.index({ scene_id: 1, version: 1 });
operationLogSchema.index({ scene_id: 1, timestamp: -1 });

module.exports = mongoose.model('OperationLog', operationLogSchema);
