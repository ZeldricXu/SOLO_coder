const mongoose = require('mongoose');
const { Schema } = mongoose;

const chunkInfoSchema = new Schema({
  index: {
    type: Number,
    required: true
  },
  size: {
    type: Number,
    required: true
  },
  uploaded_at: {
    type: Date,
    default: Date.now
  },
  temp_path: {
    type: String,
    required: true
  }
}, { _id: false });

const uploadSessionSchema = new Schema({
  file_id: {
    type: String,
    required: true,
    unique: true,
    index: true
  },
  filename: {
    type: String,
    required: true,
    trim: true
  },
  total_size: {
    type: Number,
    required: true,
    min: 0
  },
  total_chunks: {
    type: Number,
    required: true,
    min: 1
  },
  chunk_size: {
    type: Number,
    required: true,
    default: 5 * 1024 * 1024
  },
  mime_type: {
    type: String,
    required: true
  },
  file_type: {
    type: String,
    required: true,
    enum: ['image', 'video', 'audio', 'other']
  },
  user_id: {
    type: String,
    default: ''
  },
  uploaded_chunks: {
    type: [chunkInfoSchema],
    default: []
  },
  uploaded_size: {
    type: Number,
    default: 0
  },
  upload_progress: {
    type: Number,
    default: 0,
    min: 0,
    max: 100
  },
  status: {
    type: String,
    required: true,
    enum: ['pending', 'uploading', 'completed', 'failed', 'merged'],
    default: 'pending'
  },
  temp_dir: {
    type: String,
    required: true
  },
  last_activity_at: {
    type: Date,
    default: Date.now
  },
  created_at: {
    type: Date,
    default: Date.now,
    index: true
  },
  expires_at: {
    type: Date,
    required: true
  }
}, {
  timestamps: { createdAt: 'created_at' },
  collection: 'upload_sessions'
});

uploadSessionSchema.index({ last_activity_at: 1 });
uploadSessionSchema.index({ expires_at: 1 }, { expireAfterSeconds: 0 });

uploadSessionSchema.pre('save', function(next) {
  this.last_activity_at = Date.now();
  
  if (this.total_chunks > 0) {
    this.upload_progress = Math.round((this.uploaded_chunks.length / this.total_chunks) * 100);
  }
  
  next();
});

uploadSessionSchema.statics.findByFileId = function(fileId) {
  return this.findOne({ file_id: fileId });
};

uploadSessionSchema.statics.createSession = function(fileId, filename, totalSize, totalChunks, chunkSize, mimeType, fileType, tempDir, userId = '') {
  const session = new this({
    file_id: fileId,
    filename: filename,
    total_size: totalSize,
    total_chunks: totalChunks,
    chunk_size: chunkSize,
    mime_type: mimeType,
    file_type: fileType,
    user_id: userId,
    temp_dir: tempDir,
    status: 'uploading',
    expires_at: new Date(Date.now() + 24 * 60 * 60 * 1000)
  });
  return session.save();
};

uploadSessionSchema.statics.addChunk = function(fileId, chunkIndex, chunkSize, tempPath) {
  return this.findOneAndUpdate(
    { file_id: fileId },
    {
      $push: {
        uploaded_chunks: {
          index: chunkIndex,
          size: chunkSize,
          temp_path: tempPath
        }
      },
      $inc: { uploaded_size: chunkSize },
      $set: { status: 'uploading' }
    },
    { new: true }
  );
};

uploadSessionSchema.statics.markCompleted = function(fileId) {
  return this.findOneAndUpdate(
    { file_id: fileId },
    { $set: { status: 'completed' } },
    { new: true }
  );
};

uploadSessionSchema.statics.markMerged = function(fileId) {
  return this.findOneAndUpdate(
    { file_id: fileId },
    { $set: { status: 'merged' } },
    { new: true }
  );
};

uploadSessionSchema.statics.markFailed = function(fileId) {
  return this.findOneAndUpdate(
    { file_id: fileId },
    { $set: { status: 'failed' } },
    { new: true }
  );
};

uploadSessionSchema.methods.isComplete = function() {
  return this.uploaded_chunks.length === this.total_chunks;
};

uploadSessionSchema.methods.getMissingChunks = function() {
  const uploadedIndices = this.uploaded_chunks.map(chunk => chunk.index);
  const allIndices = Array.from({ length: this.total_chunks }, (_, i) => i);
  return allIndices.filter(index => !uploadedIndices.includes(index));
};

uploadSessionSchema.methods.getSortedChunks = function() {
  return [...this.uploaded_chunks].sort((a, b) => a.index - b.index);
};

module.exports = mongoose.model('UploadSession', uploadSessionSchema);
