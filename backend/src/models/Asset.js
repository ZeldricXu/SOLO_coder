const mongoose = require('mongoose');

const assetSchema = new mongoose.Schema({
  asset_id: {
    type: String,
    required: true,
    unique: true,
    index: true
  },
  name: {
    type: String,
    required: true
  },
  category: {
    type: String,
    required: true,
    enum: ['furniture', 'door', 'window', 'material', 'decoration']
  },
  subcategory: {
    type: String
  },
  description: {
    type: String
  },
  preview_url: {
    type: String
  },
  model_url: {
    type: String
  },
  default_transform: {
    position: {
      x: { type: Number, default: 0 },
      y: { type: Number, default: 0 },
      z: { type: Number, default: 0 }
    },
    rotation: {
      x: { type: Number, default: 0 },
      y: { type: Number, default: 0 },
      z: { type: Number, default: 0 }
    },
    scale: {
      x: { type: Number, default: 1 },
      y: { type: Number, default: 1 },
      z: { type: Number, default: 1 }
    }
  },
  default_material_id: {
    type: String
  },
  tags: [{
    type: String
  }],
  is_active: {
    type: Boolean,
    default: true
  }
}, {
  timestamps: true
});

assetSchema.index({ category: 1, is_active: 1 });
assetSchema.index({ tags: 1, is_active: 1 });

module.exports = mongoose.model('Asset', assetSchema);
