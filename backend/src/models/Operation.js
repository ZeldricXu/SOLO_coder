const mongoose = require('mongoose');
const { Schema } = mongoose;

const operationSchema = new Schema({
  op_id: {
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
  op_type: {
    type: String,
    enum: ['insert', 'delete', 'replace', 'format'],
    required: true
  },
  op_data: {
    type: Schema.Types.Mixed,
    required: true
  },
  op_time: {
    type: Date,
    default: Date.now
  },
  version_number: {
    type: Number
  }
}, {
  timestamps: false
});

operationSchema.index({ doc_id: 1, op_time: 1 });
operationSchema.index({ doc_id: 1, user_id: 1 });

module.exports = mongoose.model('Operation', operationSchema);
