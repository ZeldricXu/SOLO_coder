const express = require('express');
const router = express.Router();
const logger = require('../config/logger');
const codeAccessService = require('../services/codeAccessService');
const commitModel = require('../models/commitModel');

router.get('/commits/:commit_id', async (req, res) => {
  try {
    const { commit_id } = req.params;
    
    const commitDetails = await codeAccessService.getCommitWithFiles(commit_id);
    
    res.json({
      code: 200,
      data: commitDetails
    });
  } catch (error) {
    logger.error('获取提交详情失败: %s', error.message);
    res.status(404).json({
      code: 404,
      message: '提交不存在',
      error: error.message
    });
  }
});

router.get('/commits/repo/:repo_id', async (req, res) => {
  try {
    const { repo_id } = req.params;
    const { limit } = req.query;
    
    const commits = await codeAccessService.getCommitsByRepo(
      repo_id,
      parseInt(limit) || 100
    );
    
    res.json({
      code: 200,
      data: commits
    });
  } catch (error) {
    logger.error('获取仓库提交列表失败: %s', error.message);
    res.status(500).json({
      code: 500,
      message: '获取提交列表失败',
      error: error.message
    });
  }
});

router.get('/files/:commit_id', async (req, res) => {
  try {
    const { commit_id } = req.params;
    
    const files = await commitModel.getChangedFiles(commit_id);
    
    res.json({
      code: 200,
      data: files
    });
  } catch (error) {
    logger.error('获取变更文件列表失败: %s', error.message);
    res.status(500).json({
      code: 500,
      message: '获取文件列表失败',
      error: error.message
    });
  }
});

router.get('/files/:commit_id/*', async (req, res) => {
  try {
    const { commit_id } = req.params;
    const file_path = req.params[0];
    
    if (!file_path) {
      return res.status(400).json({
        code: 400,
        message: '缺少文件路径'
      });
    }
    
    const file = await codeAccessService.getFileContent(commit_id, file_path);
    
    res.json({
      code: 200,
      data: file
    });
  } catch (error) {
    logger.error('获取文件内容失败: %s', error.message);
    res.status(404).json({
      code: 404,
      message: '文件不存在',
      error: error.message
    });
  }
});

router.get('/diff/:commit_id/*', async (req, res) => {
  try {
    const { commit_id } = req.params;
    const file_path = req.params[0];
    
    if (!file_path) {
      return res.status(400).json({
        code: 400,
        message: '缺少文件路径'
      });
    }
    
    const diff = await codeAccessService.getFileDiff(commit_id, file_path);
    
    res.json({
      code: 200,
      data: diff
    });
  } catch (error) {
    logger.error('获取文件差异失败: %s', error.message);
    res.status(404).json({
      code: 404,
      message: '获取差异失败',
      error: error.message
    });
  }
});

router.post('/webhook', async (req, res) => {
  try {
    const { event, payload } = req.body;
    
    if (!event) {
      return res.status(400).json({
        code: 400,
        message: '缺少事件类型'
      });
    }
    
    logger.info('接收Webhook事件: %s', event);
    
    let result;
    
    if (event === 'push' || event === 'commit') {
      if (!payload || !payload.commit_id || !payload.repo_id) {
        return res.status(400).json({
          code: 400,
          message: '缺少必要的payload参数'
        });
      }
      
      result = await codeAccessService.handleCommitEvent({
        commit_id: payload.commit_id,
        repo_id: payload.repo_id,
        author: payload.author,
        message: payload.message,
        commit_time: payload.commit_time,
        files: payload.files
      });
    } else {
      return res.status(400).json({
        code: 400,
        message: '不支持的事件类型'
      });
    }
    
    res.json({
      code: 200,
      data: {
        received: true,
        event,
        result
      }
    });
  } catch (error) {
    logger.error('处理Webhook事件失败: %s', error.message);
    res.status(500).json({
      code: 500,
      message: '处理Webhook失败',
      error: error.message
    });
  }
});

router.get('/diff/compare/:commit_id', async (req, res) => {
  try {
    const { commit_id } = req.params;
    
    const files = await commitModel.getChangedFiles(commit_id);
    
    const diffs = [];
    
    for (const file of files) {
      try {
        const fileDiff = await codeAccessService.getFileDiff(commit_id, file.file_path);
        diffs.push(fileDiff);
      } catch (err) {
        logger.warn('获取文件差异失败: %s', file.file_path);
        diffs.push({
          file_path: file.file_path,
          language: file.language,
          status: file.status,
          error: err.message
        });
      }
    }
    
    res.json({
      code: 200,
      data: {
        commit_id,
        files: diffs
      }
    });
  } catch (error) {
    logger.error('获取提交差异失败: %s', error.message);
    res.status(500).json({
      code: 500,
      message: '获取差异失败',
      error: error.message
    });
  }
});

module.exports = router;
