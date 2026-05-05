const express = require('express');
const router = express.Router();
const searchService = require('../services/SearchService');

router.get('/query', async (req, res) => {
  try {
    const { q, folder_id, limit = 20, offset = 0, user_id } = req.query;
    
    if (!q || q.trim() === '') {
      return res.status(400).json({
        code: 400,
        message: 'Search query is required'
      });
    }
    
    const results = await searchService.search(q.trim(), {
      folder_id: folder_id || undefined,
      limit: parseInt(limit),
      offset: parseInt(offset),
      user_id: user_id || undefined
    });
    
    res.json({
      code: 200,
      data: results
    });
  } catch (error) {
    console.error('Search error:', error);
    res.status(500).json({
      code: 500,
      message: 'Failed to execute search',
      error: error.message
    });
  }
});

router.get('/suggest', async (req, res) => {
  try {
    const { q } = req.query;
    
    if (!q || q.trim() === '') {
      return res.status(400).json({
        code: 400,
        message: 'Query is required for suggestions'
      });
    }
    
    const suggestions = await searchService.suggest(q.trim());
    
    res.json({
      code: 200,
      data: suggestions
    });
  } catch (error) {
    console.error('Suggest error:', error);
    res.status(500).json({
      code: 500,
      message: 'Failed to get suggestions',
      error: error.message
    });
  }
});

router.post('/reindex', async (req, res) => {
  try {
    const result = await searchService.rebuildIndex();
    
    res.json({
      code: 200,
      data: result
    });
  } catch (error) {
    console.error('Reindex error:', error);
    res.status(500).json({
      code: 500,
      message: 'Failed to rebuild index',
      error: error.message
    });
  }
});

router.get('/status', async (req, res) => {
  try {
    const isConnected = searchService.isConnected;
    
    res.json({
      code: 200,
      data: {
        search_engine: isConnected ? 'elasticsearch' : 'mongodb',
        connected: isConnected
      }
    });
  } catch (error) {
    console.error('Search status error:', error);
    res.status(500).json({
      code: 500,
      message: 'Failed to get search status',
      error: error.message
    });
  }
});

module.exports = router;
