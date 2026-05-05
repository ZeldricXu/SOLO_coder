const express = require('express');
const router = express.Router();
const { v4: uuidv4 } = require('uuid');
const Scene = require('../models/Scene');
const SceneObject = require('../models/SceneObject');

router.get('/list', async (req, res) => {
  try {
    const { user_id } = req.query;
    
    const filter = { is_active: true };
    if (user_id) {
      filter.creator_id = user_id;
    }
    
    const scenes = await Scene.find(filter).sort({ updatedAt: -1 });
    
    res.json({
      code: 200,
      data: {
        scenes: scenes.map(scene => ({
          scene_id: scene.scene_id,
          name: scene.name,
          description: scene.description,
          creator_id: scene.creator_id,
          current_version: scene.current_version,
          settings: scene.settings,
          created_at: scene.createdAt,
          updated_at: scene.updatedAt
        })),
        total: scenes.length
      }
    });
  } catch (error) {
    console.error('Error fetching scenes:', error);
    res.status(500).json({
      code: 500,
      message: 'Failed to fetch scenes',
      error: error.message
    });
  }
});

router.post('/create', async (req, res) => {
  try {
    const { name, description, user_id, settings } = req.body;
    
    if (!name || !user_id) {
      return res.status(400).json({
        code: 400,
        message: 'Name and user_id are required'
      });
    }
    
    const scene_id = `scene_${Date.now()}_${Math.random().toString(36).substr(2, 8)}`;
    
    const scene = new Scene({
      scene_id,
      name,
      description: description || '',
      creator_id: user_id,
      current_version: 0,
      settings: settings || {
        environment: 'studio',
        grid_enabled: true,
        background_color: '#1a1a2e'
      }
    });
    
    await scene.save();
    
    res.json({
      code: 200,
      data: {
        scene: {
          scene_id: scene.scene_id,
          name: scene.name,
          description: scene.description,
          creator_id: scene.creator_id,
          current_version: scene.current_version,
          settings: scene.settings,
          created_at: scene.createdAt,
          updated_at: scene.updatedAt
        }
      }
    });
  } catch (error) {
    console.error('Error creating scene:', error);
    res.status(500).json({
      code: 500,
      message: 'Failed to create scene',
      error: error.message
    });
  }
});

router.get('/:scene_id', async (req, res) => {
  try {
    const { scene_id } = req.params;
    const { include_objects } = req.query;
    
    const scene = await Scene.findOne({ scene_id, is_active: true });
    
    if (!scene) {
      return res.status(404).json({
        code: 404,
        message: 'Scene not found'
      });
    }
    
    const result = {
      scene_id: scene.scene_id,
      name: scene.name,
      description: scene.description,
      creator_id: scene.creator_id,
      current_version: scene.current_version,
      settings: scene.settings,
      created_at: scene.createdAt,
      updated_at: scene.updatedAt
    };
    
    if (include_objects === 'true') {
      const objects = await SceneObject.find({
        scene_id,
        is_deleted: false
      });
      
      result.objects = objects.map(obj => obj.toObject());
    }
    
    res.json({
      code: 200,
      data: { scene: result }
    });
  } catch (error) {
    console.error('Error fetching scene:', error);
    res.status(500).json({
      code: 500,
      message: 'Failed to fetch scene',
      error: error.message
    });
  }
});

router.put('/:scene_id', async (req, res) => {
  try {
    const { scene_id } = req.params;
    const { name, description, settings } = req.body;
    
    const updateData = {};
    if (name !== undefined) updateData.name = name;
    if (description !== undefined) updateData.description = description;
    if (settings !== undefined) updateData.settings = settings;
    
    const scene = await Scene.findOneAndUpdate(
      { scene_id, is_active: true },
      updateData,
      { new: true }
    );
    
    if (!scene) {
      return res.status(404).json({
        code: 404,
        message: 'Scene not found'
      });
    }
    
    res.json({
      code: 200,
      data: {
        scene: {
          scene_id: scene.scene_id,
          name: scene.name,
          description: scene.description,
          creator_id: scene.creator_id,
          current_version: scene.current_version,
          settings: scene.settings,
          created_at: scene.createdAt,
          updated_at: scene.updatedAt
        }
      }
    });
  } catch (error) {
    console.error('Error updating scene:', error);
    res.status(500).json({
      code: 500,
      message: 'Failed to update scene',
      error: error.message
    });
  }
});

router.delete('/:scene_id', async (req, res) => {
  try {
    const { scene_id } = req.params;
    
    const scene = await Scene.findOneAndUpdate(
      { scene_id },
      { is_active: false },
      { new: true }
    );
    
    if (!scene) {
      return res.status(404).json({
        code: 404,
        message: 'Scene not found'
      });
    }
    
    await SceneObject.updateMany(
      { scene_id },
      { is_deleted: true }
    );
    
    res.json({
      code: 200,
      data: {
        success: true,
        message: 'Scene deleted successfully'
      }
    });
  } catch (error) {
    console.error('Error deleting scene:', error);
    res.status(500).json({
      code: 500,
      message: 'Failed to delete scene',
      error: error.message
    });
  }
});

module.exports = router;
