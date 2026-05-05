const express = require('express');
const router = express.Router();
const versionService = require('../services/VersionService');
const searchService = require('../services/SearchService');
const Document = require('../models/Document');

const DEFAULT_USER_ID = 'user_default';

router.get('/:doc_id', async (req, res) => {
  try {
    const { doc_id } = req.params;
    const { page = 1, limit = 20 } = req.query;
    
    const result = await versionService.getVersions(
      doc_id,
      parseInt(page),
      parseInt(limit)
    );
    
    res.json({
      code: 200,
      data: result
    });
  } catch (error) {
    console.error('Get versions error:', error);
    res.status(500).json({
      code: 500,
      message: 'Failed to get versions',
      error: error.message
    });
  }
});

router.get('/:doc_id/:version_number', async (req, res) => {
  try {
    const { doc_id, version_number } = req.params;
    
    const version = await versionService.getVersion(
      doc_id,
      parseInt(version_number)
    );
    
    res.json({
      code: 200,
      data: version
    });
  } catch (error) {
    console.error('Get version error:', error);
    
    if (error.message.includes('not found')) {
      return res.status(404).json({
        code: 404,
        message: 'Version not found'
      });
    }
    
    res.status(500).json({
      code: 500,
      message: 'Failed to get version',
      error: error.message
    });
  }
});

router.post('/:doc_id/snapshot', async (req, res) => {
  try {
    const { doc_id } = req.params;
    const { user_id, edit_summary = '' } = req.body;
    
    const userId = user_id || DEFAULT_USER_ID;
    const version = await versionService.createSnapshot(doc_id, userId, edit_summary);
    
    if (!version) {
      return res.json({
        code: 200,
        message: 'No changes detected, snapshot skipped',
        data: null
      });
    }
    
    res.status(201).json({
      code: 201,
      data: version
    });
  } catch (error) {
    console.error('Create snapshot error:', error);
    
    if (error.message.includes('not found')) {
      return res.status(404).json({
        code: 404,
        message: 'Document not found'
      });
    }
    
    res.status(500).json({
      code: 500,
      message: 'Failed to create snapshot',
      error: error.message
    });
  }
});

router.post('/:doc_id/restore/:version_number', async (req, res) => {
  try {
    const { doc_id, version_number } = req.params;
    const { user_id } = req.body;
    
    const userId = user_id || DEFAULT_USER_ID;
    const result = await versionService.restoreVersion(
      doc_id,
      parseInt(version_number),
      userId
    );
    
    const doc = await Document.findOne({ doc_id });
    if (doc) {
      searchService.queueIncrementalUpdate(doc);
    }
    
    res.json({
      code: 200,
      data: result
    });
  } catch (error) {
    console.error('Restore version error:', error);
    
    if (error.message.includes('not found')) {
      return res.status(404).json({
        code: 404,
        message: 'Version or document not found'
      });
    }
    
    res.status(500).json({
      code: 500,
      message: 'Failed to restore version',
      error: error.message
    });
  }
});

router.get('/:doc_id/compare/:version_1/:version_2', async (req, res) => {
  try {
    const { doc_id, version_1, version_2 } = req.params;
    
    const result = await versionService.compareVersions(
      doc_id,
      parseInt(version_1),
      parseInt(version_2)
    );
    
    res.json({
      code: 200,
      data: result
    });
  } catch (error) {
    console.error('Compare versions error:', error);
    
    if (error.message.includes('not found')) {
      return res.status(404).json({
        code: 404,
        message: 'One or both versions not found'
      });
    }
    
    res.status(500).json({
      code: 500,
      message: 'Failed to compare versions',
      error: error.message
    });
  }
});

router.post('/:doc_id/cleanup', async (req, res) => {
  try {
    const { doc_id } = req.params;
    const { keep_recent = 100 } = req.body;
    
    const deletedCount = await versionService.cleanupOldVersions(
      doc_id,
      parseInt(keep_recent)
    );
    
    res.json({
      code: 200,
      data: {
        doc_id,
        deleted_versions: deletedCount,
        kept_recent: keep_recent
      }
    });
  } catch (error) {
    console.error('Cleanup versions error:', error);
    res.status(500).json({
      code: 500,
      message: 'Failed to cleanup old versions',
      error: error.message
    });
  }
});

module.exports = router;
