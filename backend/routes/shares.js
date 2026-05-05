const express = require('express');
const router = express.Router();
const shareService = require('../services/shareService');
const logger = require('../utils/logger');

const getCurrentUser = (req) => {
  return req.headers['x-user-id'] || 'user_001';
};

router.post('/:docId', async (req, res) => {
  try {
    const { docId } = req.params;
    const { share_type, target_id, permission, expires_at } = req.body;
    const user = getCurrentUser(req);

    logger.info(`分享文档: doc_id=${docId}, share_type=${share_type}, user=${user}`);

    if (!share_type || !['user', 'team', 'public'].includes(share_type)) {
      return res.status(400).json({
        code: 400,
        error: '无效的分享类型'
      });
    }

    if (share_type !== 'public' && !target_id) {
      return res.status(400).json({
        code: 400,
        error: '目标ID不能为空'
      });
    }

    const result = await shareService.shareDocument(
      docId,
      user,
      share_type,
      target_id || 'public',
      permission || 'read',
      expires_at ? new Date(expires_at) : null
    );

    if (result.success) {
      res.json({
        code: 200,
        data: result.data,
        message: '文档分享成功'
      });
    } else {
      res.status(400).json({
        code: 400,
        error: result.error
      });
    }
  } catch (error) {
    logger.error(`分享文档异常: ${error.message}`, { error });
    res.status(500).json({
      code: 500,
      error: '服务器内部错误'
    });
  }
});

router.get('/:docId', async (req, res) => {
  try {
    const { docId } = req.params;
    const user = getCurrentUser(req);

    logger.info(`获取文档分享列表: doc_id=${docId}, user=${user}`);

    const result = await shareService.getDocumentShares(docId, user);

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
    logger.error(`获取文档分享列表异常: ${error.message}`, { error });
    res.status(500).json({
      code: 500,
      error: '服务器内部错误'
    });
  }
});

router.delete('/:shareId', async (req, res) => {
  try {
    const { shareId } = req.params;
    const user = getCurrentUser(req);

    logger.info(`撤销分享: share_id=${shareId}, user=${user}`);

    const result = await shareService.revokeShare(shareId, user);

    if (result.success) {
      res.json({
        code: 200,
        message: result.message
      });
    } else {
      res.status(400).json({
        code: 400,
        error: result.error
      });
    }
  } catch (error) {
    logger.error(`撤销分享异常: ${error.message}`, { error });
    res.status(500).json({
      code: 500,
      error: '服务器内部错误'
    });
  }
});

router.get('/access/:docId', async (req, res) => {
  try {
    const { docId } = req.params;
    const user = getCurrentUser(req);

    logger.info(`检查访问权限: doc_id=${docId}, user=${user}`);

    const result = await shareService.checkShareAccess(docId, user);

    res.json({
      code: 200,
      data: result
    });
  } catch (error) {
    logger.error(`检查访问权限异常: ${error.message}`, { error });
    res.status(500).json({
      code: 500,
      error: '服务器内部错误'
    });
  }
});

module.exports = router;
