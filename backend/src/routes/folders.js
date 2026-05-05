const express = require('express');
const router = express.Router();
const folderService = require('../services/FolderService');

const DEFAULT_USER_ID = 'user_default';

router.get('/', async (req, res) => {
  try {
    const { parent_id } = req.query;
    
    const folders = await folderService.getFolderTree(parent_id || null);
    
    res.json({
      code: 200,
      data: folders
    });
  } catch (error) {
    console.error('Get folders error:', error);
    res.status(500).json({
      code: 500,
      message: 'Failed to get folders',
      error: error.message
    });
  }
});

router.get('/tree', async (req, res) => {
  try {
    const tree = await folderService.getFullTree();
    
    res.json({
      code: 200,
      data: tree
    });
  } catch (error) {
    console.error('Get folder tree error:', error);
    res.status(500).json({
      code: 500,
      message: 'Failed to get folder tree',
      error: error.message
    });
  }
});

router.get('/:folder_id', async (req, res) => {
  try {
    const { folder_id } = req.params;
    const folder = await folderService.getFolder(folder_id);
    
    res.json({
      code: 200,
      data: folder
    });
  } catch (error) {
    console.error('Get folder error:', error);
    
    if (error.message.includes('not found')) {
      return res.status(404).json({
        code: 404,
        message: 'Folder not found'
      });
    }
    
    res.status(500).json({
      code: 500,
      message: 'Failed to get folder',
      error: error.message
    });
  }
});

router.get('/:folder_id/path', async (req, res) => {
  try {
    const { folder_id } = req.params;
    const path = await folderService.getFolderPath(folder_id);
    
    res.json({
      code: 200,
      data: path
    });
  } catch (error) {
    console.error('Get folder path error:', error);
    res.status(500).json({
      code: 500,
      message: 'Failed to get folder path',
      error: error.message
    });
  }
});

router.post('/', async (req, res) => {
  try {
    const { name, parent_id = null, created_by } = req.body;
    
    if (!name) {
      return res.status(400).json({
        code: 400,
        message: 'Folder name is required'
      });
    }
    
    const userId = created_by || DEFAULT_USER_ID;
    const folder = await folderService.createFolder(name, parent_id, userId);
    
    res.status(201).json({
      code: 201,
      data: folder
    });
  } catch (error) {
    console.error('Create folder error:', error);
    res.status(500).json({
      code: 500,
      message: 'Failed to create folder',
      error: error.message
    });
  }
});

router.put('/:folder_id', async (req, res) => {
  try {
    const { folder_id } = req.params;
    const { name, is_expanded, parent_id } = req.body;
    
    let folder;
    
    if (parent_id !== undefined) {
      folder = await folderService.moveFolder(folder_id, parent_id);
    } else {
      folder = await folderService.updateFolder(folder_id, { name, is_expanded });
    }
    
    res.json({
      code: 200,
      data: folder
    });
  } catch (error) {
    console.error('Update folder error:', error);
    
    if (error.message.includes('not found')) {
      return res.status(404).json({
        code: 404,
        message: 'Folder not found'
      });
    }
    
    res.status(500).json({
      code: 500,
      message: 'Failed to update folder',
      error: error.message
    });
  }
});

router.delete('/:folder_id', async (req, res) => {
  try {
    const { folder_id } = req.params;
    const { recursive = false } = req.query;
    
    const result = await folderService.deleteFolder(folder_id, recursive === 'true');
    
    res.json({
      code: 200,
      data: result
    });
  } catch (error) {
    console.error('Delete folder error:', error);
    
    if (error.message.includes('not found')) {
      return res.status(404).json({
        code: 404,
        message: 'Folder not found'
      });
    }
    
    if (error.message.includes('not empty')) {
      return res.status(400).json({
        code: 400,
        message: error.message
      });
    }
    
    res.status(500).json({
      code: 500,
      message: 'Failed to delete folder',
      error: error.message
    });
  }
});

router.post('/:folder_id/move', async (req, res) => {
  try {
    const { folder_id } = req.params;
    const { new_parent_id } = req.body;
    
    const folder = await folderService.moveFolder(folder_id, new_parent_id);
    
    res.json({
      code: 200,
      data: folder
    });
  } catch (error) {
    console.error('Move folder error:', error);
    res.status(500).json({
      code: 500,
      message: 'Failed to move folder',
      error: error.message
    });
  }
});

router.post('/reorder', async (req, res) => {
  try {
    const { parent_id, ordered_ids } = req.body;
    
    if (!ordered_ids || !Array.isArray(ordered_ids)) {
      return res.status(400).json({
        code: 400,
        message: 'ordered_ids must be an array'
      });
    }
    
    const items = await folderService.reorderItems(parent_id || null, ordered_ids);
    
    res.json({
      code: 200,
      data: items
    });
  } catch (error) {
    console.error('Reorder items error:', error);
    res.status(500).json({
      code: 500,
      message: 'Failed to reorder items',
      error: error.message
    });
  }
});

router.post('/:folder_id/expand', async (req, res) => {
  try {
    const { folder_id } = req.params;
    const { expanded = true } = req.body;
    
    const folder = await folderService.expandCollapse(folder_id, expanded);
    
    res.json({
      code: 200,
      data: folder
    });
  } catch (error) {
    console.error('Expand folder error:', error);
    
    if (error.message.includes('not found')) {
      return res.status(404).json({
        code: 404,
        message: 'Folder not found'
      });
    }
    
    res.status(500).json({
      code: 500,
      message: 'Failed to expand folder',
      error: error.message
    });
  }
});

router.get('/search/:name', async (req, res) => {
  try {
    const { name } = req.params;
    const { parent_id } = req.query;
    
    const folders = await folderService.searchFolders(name, parent_id || null);
    
    res.json({
      code: 200,
      data: folders
    });
  } catch (error) {
    console.error('Search folders error:', error);
    res.status(500).json({
      code: 500,
      message: 'Failed to search folders',
      error: error.message
    });
  }
});

module.exports = router;
