const mongoose = require('mongoose');

const sceneSchema = new mongoose.Schema({
  scene_id: {
    type: String,
    required: true,
    unique: true,
    index: true
  },
  name: {
    type: String,
    required: true
  },
  description: {
    type: String
  },
  creator_id: {
    type: String,
    required: true
  },
  current_version: {
    type: Number,
    default: 0
  },
  settings: {
    environment: {
      type: String,
      default: 'studio'
    },
    grid_enabled: {
      type: Boolean,
      default: true
    },
    background_color: {
      type: String,
      default: '#1a1a2e'
    }
  },
  is_active: {
    type: Boolean,
    default: true
  }
}, {
  timestamps: true
});

sceneSchema.index({ creator_id: 1, is_active: 1 });

module.exports = mongoose.model('Scene', sceneSchema);
