const express = require('express');
const router = express.Router();
const commentService = require('../services/commentService');
const logger = require('../utils/logger');

const getCurrentUser = (req) => {
  return req.headers['x-user-id'] || 'user_001';
};

router.post('/:docId', async (req, res) => {
  try {
    const { docId } = req.params;
    const { content, position, parent_comment_id } = req.body;
    const user = getCurrentUser(req);

    logger.info(`创建评论: doc_id=${docId}, user=${user}`);

    const result = await commentService.createComment(
      docId,
      user,
      content,
      position || {},
      parent_comment_id || null
    );

    if (result.success) {
      res.json({
        code: 200,
        data: result.data,
        message: '评论创建成功'
      });
    } else {
      res.status(400).json({
        code: 400,
        error: result.error
      });
    }
  } catch (error) {
    logger.error(`创建评论异常: ${error.message}`, { error });
    res.status(500).json({
      code: 500,
      error: '服务器内部错误'
    });
  }
});

router.get('/:docId', async (req, res) => {
  try {
    const { docId } = req.params;
    const { status = 'all' } = req.query;
    const user = getCurrentUser(req);

    logger.info(`获取文档评论: doc_id=${docId}, status=${status}, user=${user}`);

    const result = await commentService.getDocumentComments(
      docId,
      user,
      status
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
    logger.error(`获取文档评论异常: ${error.message}`, { error });
    res.status(500).json({
      code: 500,
      error: '服务器内部错误'
    });
  }
});

router.put('/:commentId', async (req, res) => {
  try {
    const { commentId } = req.params;
    const { content } = req.body;
    const user = getCurrentUser(req);

    logger.info(`更新评论: comment_id=${commentId}, user=${user}`);

    const result = await commentService.updateComment(
      commentId,
      user,
      content
    );

    if (result.success) {
      res.json({
        code: 200,
        data: result.data,
        message: '评论更新成功'
      });
    } else {
      res.status(400).json({
        code: 400,
        error: result.error
      });
    }
  } catch (error) {
    logger.error(`更新评论异常: ${error.message}`, { error });
    res.status(500).json({
      code: 500,
      error: '服务器内部错误'
    });
  }
});

router.delete('/:commentId', async (req, res) => {
  try {
    const { commentId } = req.params;
    const user = getCurrentUser(req);

    logger.info(`删除评论: comment_id=${commentId}, user=${user}`);

    const result = await commentService.deleteComment(commentId, user);

    if (result.success) {
      res.json({
        code: 200,
        message: result.message,
        deleted_replies: result.deleted_replies
      });
    } else {
      res.status(400).json({
        code: 400,
        error: result.error
      });
    }
  } catch (error) {
    logger.error(`删除评论异常: ${error.message}`, { error });
    res.status(500).json({
      code: 500,
      error: '服务器内部错误'
    });
  }
});

router.post('/:commentId/resolve', async (req, res) => {
  try {
    const { commentId } = req.params;
    const user = getCurrentUser(req);

    logger.info(`解决评论: comment_id=${commentId}, user=${user}`);

    const result = await commentService.resolveComment(commentId, user);

    if (result.success) {
      res.json({
        code: 200,
        data: result.data,
        message: '评论已解决'
      });
    } else {
      res.status(400).json({
        code: 400,
        error: result.error
      });
    }
  } catch (error) {
    logger.error(`解决评论异常: ${error.message}`, { error });
    res.status(500).json({
      code: 500,
      error: '服务器内部错误'
    });
  }
});

router.post('/:commentId/close', async (req, res) => {
  try {
    const { commentId } = req.params;
    const user = getCurrentUser(req);

    logger.info(`关闭评论: comment_id=${commentId}, user=${user}`);

    const result = await commentService.closeComment(commentId, user);

    if (result.success) {
      res.json({
        code: 200,
        data: result.data,
        message: '评论已关闭'
      });
    } else {
      res.status(400).json({
        code: 400,
        error: result.error
      });
    }
  } catch (error) {
    logger.error(`关闭评论异常: ${error.message}`, { error });
    res.status(500).json({
      code: 500,
      error: '服务器内部错误'
    });
  }
});

module.exports = router;
