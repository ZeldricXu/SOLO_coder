const mongoose = require('mongoose');

const transformSchema = new mongoose.Schema({
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
}, { _id: false });

const sceneObjectSchema = new mongoose.Schema({
  object_id: {
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
  object_type: {
    type: String,
    required: true,
    enum: ['wall', 'door', 'window', 'furniture']
  },
  transform: {
    type: transformSchema,
    required: true,
    default: () => ({
      position: { x: 0, y: 0, z: 0 },
      rotation: { x: 0, y: 0, z: 0 },
      scale: { x: 1, y: 1, z: 1 }
    })
  },
  material_id: {
    type: String,
    default: 'mat_default_01'
  },
  asset_id: {
    type: String
  },
  creator_id: {
    type: String,
    required: true
  },
  version: {
    type: Number,
    default: 1
  },
  locked: {
    type: Boolean,
    default: false
  },
  locked_by: {
    type: String
  },
  is_deleted: {
    type: Boolean,
    default: false
  }
}, {
  timestamps: true
});

sceneObjectSchema.index({ scene_id: 1, is_deleted: 1 });
sceneObjectSchema.index({ object_id: 1, version: 1 });

module.exports = mongoose.model('SceneObject', sceneObjectSchema);
