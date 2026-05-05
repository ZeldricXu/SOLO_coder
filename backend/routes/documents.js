const express = require('express');
const router = express.Router();
const documentService = require('../services/documentService');
const versionService = require('../services/versionService');
const asyncSaveService = require('../services/asyncSaveService');
const logger = require('../utils/logger');

const getCurrentUser = (req) => {
  return req.headers['x-user-id'] || 'user_001';
};

router.post('/create', async (req, res) => {
  try {
    const { title, content, category, tags } = req.body;
    const user = getCurrentUser(req);

    logger.info(`创建文档请求: user=${user}, title=${title}`);

    const result = await documentService.createDocument(
      user, 
      title, 
      content, 
      category || '未分类', 
      tags || []
    );

    if (result.success) {
      res.json({
        code: 200,
        data: result.data,
        message: '文档创建成功'
      });
    } else {
      res.status(400).json({
        code: 400,
        error: result.error
      });
    }
  } catch (error) {
    logger.error(`创建文档异常: ${error.message}`, { error });
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

    const result = await documentService.getUserDocuments(
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
    logger.error(`获取文档列表异常: ${error.message}`, { error });
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

    logger.info(`获取文档详情: doc_id=${docId}, user=${user}`);

    const result = await documentService.getDocument(docId, user);

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
    logger.error(`获取文档详情异常: ${error.message}`, { error });
    res.status(500).json({
      code: 500,
      error: '服务器内部错误'
    });
  }
});

router.post('/:docId/edit', async (req, res) => {
  try {
    const { docId } = req.params;
    const { content, change_desc, title, category, tags } = req.body;
    const user = getCurrentUser(req);

    logger.info(`编辑文档: doc_id=${docId}, user=${user}`);

    const updateResult = await documentService.updateDocument(
      docId,
      user,
      content,
      change_desc || '',
      title,
      category,
      tags
    );

    if (!updateResult.success) {
      return res.status(400).json({
        code: 400,
        error: updateResult.error
      });
    }

    if (content || title || category || tags) {
      const versionResult = await versionService.createVersion(
        docId,
        user,
        content,
        change_desc || '内容更新'
      );

      if (versionResult.success) {
        updateResult.data.version = versionResult.data.version;
      }
    }

    res.json({
      code: 200,
      data: updateResult.data,
      message: '文档更新成功'
    });
  } catch (error) {
    logger.error(`编辑文档异常: ${error.message}`, { error });
    res.status(500).json({
      code: 500,
      error: '服务器内部错误'
    });
  }
});

router.delete('/:docId', async (req, res) => {
  try {
    const { docId } = req.params;
    const user = getCurrentUser(req);

    logger.info(`删除文档: doc_id=${docId}, user=${user}`);

    const result = await documentService.deleteDocument(docId, user);

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
    logger.error(`删除文档异常: ${error.message}`, { error });
    res.status(500).json({
      code: 500,
      error: '服务器内部错误'
    });
  }
});

router.post('/:docId/status', async (req, res) => {
  try {
    const { docId } = req.params;
    const { status } = req.body;
    const user = getCurrentUser(req);

    const document = await documentService.getDocument(docId, user);
    
    if (!document.success) {
      return res.status(400).json({
        code: 400,
        error: document.error
      });
    }

    const validStatuses = ['draft', 'published', 'archived'];
    if (!validStatuses.includes(status)) {
      return res.status(400).json({
        code: 400,
        error: '无效的文档状态'
      });
    }

    const doc = document.data;
    if (doc.author !== user && !doc.permissions.admin.includes(user)) {
      return res.status(403).json({
        code: 403,
        error: '无权限修改文档状态'
      });
    }

    doc.status = status;
    await doc.save();

    res.json({
      code: 200,
      data: {
        doc_id: docId,
        status: status
      }
    });
  } catch (error) {
    logger.error(`修改文档状态异常: ${error.message}`, { error });
    res.status(500).json({
      code: 500,
      error: '服务器内部错误'
    });
  }
});

router.post('/:docId/edit-async', async (req, res) => {
  try {
    const { docId } = req.params;
    const { content, change_desc, title, category, tags, wait_for_save, timeout = 5000 } = req.body;
    const user = getCurrentUser(req);

    logger.info(`异步编辑文档: doc_id=${docId}, user=${user}, wait_for_save=${wait_for_save}`);

    const options = {
      content,
      changeDesc: change_desc || '内容更新',
      title,
      category,
      tags
    };

    const result = await asyncSaveService.saveDocumentAsync(docId, user, options);

    if (wait_for_save && result.data && result.data.task_id) {
      const waitResult = await asyncSaveService.waitForSave(
        result.data.task_id,
        parseInt(timeout)
      );
      
      if (waitResult.success && waitResult.data.status === 'completed') {
        res.json({
          code: 200,
          data: {
            ...result.data,
            save_completed: true,
            save_result: waitResult.data.result
          },
          message: '文档更新并保存成功'
        });
        return;
      }
      
      if (waitResult.data && waitResult.data.status === 'failed') {
        res.status(500).json({
          code: 500,
          error: waitResult.data.error || '保存失败'
        });
        return;
      }
    }

    if (result.success) {
      res.json({
        code: 202,
        data: result.data,
        message: '保存请求已提交，后台正在处理'
      });
    } else {
      res.status(400).json({
        code: 400,
        error: result.error
      });
    }
  } catch (error) {
    logger.error(`异步编辑文档异常: ${error.message}`, { error });
    res.status(500).json({
      code: 500,
      error: '服务器内部错误'
    });
  }
});

router.post('/:docId/quick-save', async (req, res) => {
  try {
    const { docId } = req.params;
    const { content, change_desc } = req.body;
    const user = getCurrentUser(req);

    logger.info(`快速保存文档: doc_id=${docId}, user=${user}`);

    const result = await asyncSaveService.quickSave(
      docId,
      user,
      content,
      { changeDesc: change_desc }
    );

    if (result.success) {
      const statusCode = result.async ? 202 : 200;
      res.status(statusCode).json({
        code: statusCode,
        data: result.data,
        async: result.async || false,
        message: result.data.message || '保存成功'
      });
    } else {
      res.status(400).json({
        code: 400,
        error: result.error
      });
    }
  } catch (error) {
    logger.error(`快速保存文档异常: ${error.message}`, { error });
    res.status(500).json({
      code: 500,
      error: '服务器内部错误'
    });
  }
});

router.get('/save-status/:taskId', async (req, res) => {
  try {
    const { taskId } = req.params;

    logger.debug(`查询保存状态: task_id=${taskId}`);

    const result = asyncSaveService.getSaveStatus(taskId);

    if (result.success) {
      res.json({
        code: 200,
        data: result.data
      });
    } else {
      res.status(404).json({
        code: 404,
        error: result.error
      });
    }
  } catch (error) {
    logger.error(`查询保存状态异常: ${error.message}`, { error });
    res.status(500).json({
      code: 500,
      error: '服务器内部错误'
    });
  }
});

router.post('/save-status/:taskId/wait', async (req, res) => {
  try {
    const { taskId } = req.params;
    const { timeout = 30000 } = req.body;

    logger.debug(`等待保存完成: task_id=${taskId}, timeout=${timeout}`);

    const result = await asyncSaveService.waitForSave(
      taskId,
      parseInt(timeout)
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
    logger.error(`等待保存完成异常: ${error.message}`, { error });
    res.status(500).json({
      code: 500,
      error: '服务器内部错误'
    });
  }
});

router.post('/save-status/:taskId/cancel', async (req, res) => {
  try {
    const { taskId } = req.params;

    logger.info(`取消保存任务: task_id=${taskId}`);

    const result = asyncSaveService.cancelSave(taskId);

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
    logger.error(`取消保存任务异常: ${error.message}`, { error });
    res.status(500).json({
      code: 500,
      error: '服务器内部错误'
    });
  }
});

router.get('/save-stats', async (req, res) => {
  try {
    const stats = asyncSaveService.getStats();

    res.json({
      code: 200,
      data: stats
    });
  } catch (error) {
    logger.error(`获取保存统计异常: ${error.message}`, { error });
    res.status(500).json({
      code: 500,
      error: '服务器内部错误'
    });
  }
});

module.exports = router;
