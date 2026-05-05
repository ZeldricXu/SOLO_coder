const express = require('express');
const router = express.Router();
const favoriteService = require('../services/favoriteService');
const logger = require('../utils/logger');

const getCurrentUser = (req) => {
  return req.headers['x-user-id'] || 'user_001';
};

router.post('/:docId/toggle', async (req, res) => {
  try {
    const { docId } = req.params;
    const user = getCurrentUser(req);

    logger.info(`切换收藏状态: doc_id=${docId}, user=${user}`);

    const result = await favoriteService.toggleFavorite(docId, user);

    if (result.success) {
      res.json({
        code: 200,
        data: result.data,
        message: result.data.message
      });
    } else {
      res.status(400).json({
        code: 400,
        error: result.error
      });
    }
  } catch (error) {
    logger.error(`切换收藏状态异常: ${error.message}`, { error });
    res.status(500).json({
      code: 500,
      error: '服务器内部错误'
    });
  }
});

router.get('/list', async (req, res) => {
  try {
    const { page = 1, pageSize = 20 } = req.query;
    const user = getCurrentUser(req);

    logger.info(`获取收藏列表: user=${user}`);

    const result = await favoriteService.getUserFavorites(
      user,
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
    logger.error(`获取收藏列表异常: ${error.message}`, { error });
    res.status(500).json({
      code: 500,
      error: '服务器内部错误'
    });
  }
});

router.get('/:docId/check', async (req, res) => {
  try {
    const { docId } = req.params;
    const user = getCurrentUser(req);

    logger.info(`检查收藏状态: doc_id=${docId}, user=${user}`);

    const result = await favoriteService.checkFavorite(docId, user);

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
    logger.error(`检查收藏状态异常: ${error.message}`, { error });
    res.status(500).json({
      code: 500,
      error: '服务器内部错误'
    });
  }
});

module.exports = router;
