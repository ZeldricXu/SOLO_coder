const express = require('express');
const router = express.Router();
const categoryService = require('../services/categoryService');
const logger = require('../utils/logger');

const getCurrentUser = (req) => {
  return req.headers['x-user-id'] || 'user_001';
};

router.post('/create', async (req, res) => {
  try {
    const { category_name, description, parent_category } = req.body;

    logger.info(`创建分类: category_name=${category_name}`);

    const result = await categoryService.createCategory(
      category_name,
      description || '',
      parent_category || null
    );

    if (result.success) {
      res.json({
        code: 200,
        data: result.data,
        message: '分类创建成功'
      });
    } else {
      res.status(400).json({
        code: 400,
        error: result.error
      });
    }
  } catch (error) {
    logger.error(`创建分类异常: ${error.message}`, { error });
    res.status(500).json({
      code: 500,
      error: '服务器内部错误'
    });
  }
});

router.get('/list', async (req, res) => {
  try {
    const { include_parent = 'false' } = req.query;

    logger.info(`获取分类列表`);

    const result = await categoryService.getAllCategories(
      include_parent === 'true'
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
    logger.error(`获取分类列表异常: ${error.message}`, { error });
    res.status(500).json({
      code: 500,
      error: '服务器内部错误'
    });
  }
});

router.get('/:categoryId', async (req, res) => {
  try {
    const { categoryId } = req.params;

    logger.info(`获取分类详情: category_id=${categoryId}`);

    const result = await categoryService.getCategoryById(categoryId);

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
    logger.error(`获取分类详情异常: ${error.message}`, { error });
    res.status(500).json({
      code: 500,
      error: '服务器内部错误'
    });
  }
});

router.put('/:categoryId', async (req, res) => {
  try {
    const { categoryId } = req.params;
    const { category_name, description, parent_category } = req.body;

    logger.info(`更新分类: category_id=${categoryId}`);

    const result = await categoryService.updateCategory(
      categoryId,
      category_name !== undefined ? category_name : null,
      description !== undefined ? description : null,
      parent_category !== undefined ? parent_category : null
    );

    if (result.success) {
      res.json({
        code: 200,
        data: result.data,
        message: '分类更新成功'
      });
    } else {
      res.status(400).json({
        code: 400,
        error: result.error
      });
    }
  } catch (error) {
    logger.error(`更新分类异常: ${error.message}`, { error });
    res.status(500).json({
      code: 500,
      error: '服务器内部错误'
    });
  }
});

router.delete('/:categoryId', async (req, res) => {
  try {
    const { categoryId } = req.params;
    const { move_to } = req.query;

    logger.info(`删除分类: category_id=${categoryId}, move_to=${move_to}`);

    const result = await categoryService.deleteCategory(
      categoryId,
      move_to || '未分类'
    );

    if (result.success) {
      res.json({
        code: 200,
        message: result.message,
        moved_docs: result.moved_docs
      });
    } else {
      res.status(400).json({
        code: 400,
        error: result.error
      });
    }
  } catch (error) {
    logger.error(`删除分类异常: ${error.message}`, { error });
    res.status(500).json({
      code: 500,
      error: '服务器内部错误'
    });
  }
});

router.get('/tags/popular', async (req, res) => {
  try {
    const { limit = 20 } = req.query;
    const user = getCurrentUser(req);

    logger.info(`获取热门标签: user=${user}`);

    const result = await categoryService.getPopularTags(
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
    logger.error(`获取热门标签异常: ${error.message}`, { error });
    res.status(500).json({
      code: 500,
      error: '服务器内部错误'
    });
  }
});

router.post('/:docId/tags', async (req, res) => {
  try {
    const { docId } = req.params;
    const { tags } = req.body;
    const user = getCurrentUser(req);

    logger.info(`添加标签到文档: doc_id=${docId}, tags=${tags}, user=${user}`);

    if (!tags || !Array.isArray(tags)) {
      return res.status(400).json({
        code: 400,
        error: '标签必须是数组'
      });
    }

    const result = await categoryService.addTagsToDocument(docId, tags, user);

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
    logger.error(`添加标签到文档异常: ${error.message}`, { error });
    res.status(500).json({
      code: 500,
      error: '服务器内部错误'
    });
  }
});

router.delete('/:docId/tags/:tag', async (req, res) => {
  try {
    const { docId, tag } = req.params;
    const user = getCurrentUser(req);

    logger.info(`移除文档标签: doc_id=${docId}, tag=${tag}, user=${user}`);

    const result = await categoryService.removeTagFromDocument(
      docId,
      decodeURIComponent(tag),
      user
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
    logger.error(`移除文档标签异常: ${error.message}`, { error });
    res.status(500).json({
      code: 500,
      error: '服务器内部错误'
    });
  }
});

module.exports = router;
