const express = require('express');
const router = express.Router();
const searchService = require('../services/searchService');
const logger = require('../utils/logger');

const getCurrentUser = (req) => {
  return req.headers['x-user-id'] || 'user_001';
};

router.get('/search', async (req, res) => {
  try {
    const { keyword, category, tags, status, page = 1, pageSize = 20 } = req.query;
    const user = getCurrentUser(req);

    logger.info(`文档检索: user=${user}, keyword=${keyword}, category=${category}`);

    const tagsArray = tags ? tags.split(',').map(t => t.trim()).filter(t => t) : [];

    const result = await searchService.searchDocuments(
      user,
      keyword || '',
      category || null,
      tagsArray,
      status || null,
      parseInt(page),
      parseInt(pageSize)
    );

    if (result.success) {
      res.json({
        code: 200,
        data: result.data
      });
    } else {
      res.status(400).json({
        code: 400,
        error: result.error
      });
    }
  } catch (error) {
    logger.error(`文档检索异常: ${error.message}`, { error });
    res.status(500).json({
      code: 500,
      error: '服务器内部错误'
    });
  }
});

router.get('/quick', async (req, res) => {
  try {
    const { keyword, limit = 10 } = req.query;
    const user = getCurrentUser(req);

    logger.info(`快速检索: user=${user}, keyword=${keyword}`);

    const result = await searchService.quickSearch(
      user,
      keyword || '',
      parseInt(limit)
    );

    if (result.success) {
      res.json({
        code: 200,
        data: result.data
      });
    } else {
      res.status(400).json({
        code: 400,
        error: result.error
      });
    }
  } catch (error) {
    logger.error(`快速检索异常: ${error.message}`, { error });
    res.status(500).json({
      code: 500,
      error: '服务器内部错误'
    });
  }
});

router.get('/recent', async (req, res) => {
  try {
    const { limit = 10 } = req.query;
    const user = getCurrentUser(req);

    logger.info(`获取最近文档: user=${user}`);

    const result = await searchService.getRecentDocuments(
      user,
      parseInt(limit)
    );

    if (result.success) {
      res.json({
        code: 200,
        data: result.data
      });
    } else {
      res.status(400).json({
        code: 400,
        error: result.error
      });
    }
  } catch (error) {
    logger.error(`获取最近文档异常: ${error.message}`, { error });
    res.status(500).json({
      code: 500,
      error: '服务器内部错误'
    });
  }
});

router.get('/category/:categoryName', async (req, res) => {
  try {
    const { categoryName } = req.params;
    const { page = 1, pageSize = 20 } = req.query;
    const user = getCurrentUser(req);

    logger.info(`按分类获取文档: user=${user}, category=${categoryName}`);

    const result = await searchService.getDocumentsByCategory(
      user,
      decodeURIComponent(categoryName),
      parseInt(page),
      parseInt(pageSize)
    );

    if (result.success) {
      res.json({
        code: 200,
        data: result.data
      });
    } else {
      res.status(400).json({
        code: 400,
        error: result.error
      });
    }
  } catch (error) {
    logger.error(`按分类获取文档异常: ${error.message}`, { error });
    res.status(500).json({
      code: 500,
      error: '服务器内部错误'
    });
  }
});

router.get('/tags', async (req, res) => {
  try {
    const { tags, page = 1, pageSize = 20 } = req.query;
    const user = getCurrentUser(req);

    if (!tags) {
      return res.status(400).json({
        code: 400,
        error: '标签参数不能为空'
      });
    }

    const tagsArray = tags.split(',').map(t => t.trim()).filter(t => t);

    logger.info(`按标签获取文档: user=${user}, tags=${tagsArray}`);

    const result = await searchService.getDocumentsByTags(
      user,
      tagsArray,
      parseInt(page),
      parseInt(pageSize)
    );

    if (result.success) {
      res.json({
        code: 200,
        data: result.data
      });
    } else {
      res.status(400).json({
        code: 400,
        error: result.error
      });
    }
  } catch (error) {
    logger.error(`按标签获取文档异常: ${error.message}`, { error });
    res.status(500).json({
      code: 500,
      error: '服务器内部错误'
    });
  }
});

module.exports = router;
