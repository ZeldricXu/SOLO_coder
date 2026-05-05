const express = require('express');
const router = express.Router();
const versionService = require('../services/versionService');
const logger = require('../utils/logger');

const getCurrentUser = (req) => {
  return req.headers['x-user-id'] || 'user_001';
};

router.get('/:docId/history', async (req, res) => {
  try {
    const { docId } = req.params;
    const { page = 1, pageSize = 20 } = req.query;
    const user = getCurrentUser(req);

    logger.info(`获取版本历史: doc_id=${docId}, user=${user}`);

    const result = await versionService.getVersionHistory(
      docId,
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
    logger.error(`获取版本历史异常: ${error.message}`, { error });
    res.status(500).json({
      code: 500,
      error: '服务器内部错误'
    });
  }
});

router.get('/:docId/:version', async (req, res) => {
  try {
    const { docId, version } = req.params;
    const user = getCurrentUser(req);

    logger.info(`获取版本详情: doc_id=${docId}, version=${version}, user=${user}`);

    const result = await versionService.getVersion(docId, version, user);

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
    logger.error(`获取版本详情异常: ${error.message}`, { error });
    res.status(500).json({
      code: 500,
      error: '服务器内部错误'
    });
  }
});

router.get('/:docId/compare/:version1/:version2', async (req, res) => {
  try {
    const { docId, version1, version2 } = req.params;
    const user = getCurrentUser(req);

    logger.info(`版本比对: doc_id=${docId}, v1=${version1}, v2=${version2}, user=${user}`);

    const result = await versionService.compareVersions(docId, version1, version2, user);

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
    logger.error(`版本比对异常: ${error.message}`, { error });
    res.status(500).json({
      code: 500,
      error: '服务器内部错误'
    });
  }
});

router.post('/:docId/restore/:version', async (req, res) => {
  try {
    const { docId, version } = req.params;
    const user = getCurrentUser(req);

    logger.info(`恢复版本: doc_id=${docId}, version=${version}, user=${user}`);

    const result = await versionService.restoreVersion(docId, version, user);

    if (result.success) {
      res.json({
        code: 200,
        data: result.data,
        message: '版本恢复成功'
      });
    } else {
      res.status(400).json({
        code: 400,
        error: result.error
      });
    }
  } catch (error) {
    logger.error(`恢复版本异常: ${error.message}`, { error });
    res.status(500).json({
      code: 500,
      error: '服务器内部错误'
    });
  }
});

router.post('/:docId/create', async (req, res) => {
  try {
    const { docId } = req.params;
    const { content, change_desc } = req.body;
    const user = getCurrentUser(req);

    logger.info(`创建新版本: doc_id=${docId}, user=${user}`);

    const result = await versionService.createVersion(
      docId,
      user,
      content,
      change_desc || '版本更新'
    );

    if (result.success) {
      res.json({
        code: 200,
        data: result.data,
        message: '版本创建成功'
      });
    } else {
      res.status(400).json({
        code: 400,
        error: result.error
      });
    }
  } catch (error) {
    logger.error(`创建版本异常: ${error.message}`, { error });
    res.status(500).json({
      code: 500,
      error: '服务器内部错误'
    });
  }
});

router.get('/:docId/stats', async (req, res) => {
  try {
    const { docId } = req.params;

    logger.info(`获取版本统计: doc_id=${docId}`);

    const result = await versionService.getVersionStats(docId);

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
    logger.error(`获取版本统计异常: ${error.message}`, { error });
    res.status(500).json({
      code: 500,
      error: '服务器内部错误'
    });
  }
});

module.exports = router;
