const express = require('express');
const router = express.Router();
const logger = require('../config/logger');
const codeAccessService = require('../services/codeAccessService');
const complexityService = require('../services/complexityService');
const lintService = require('../services/lintService');
const duplicateService = require('../services/duplicateService');
const reviewTaskService = require('../services/reviewTaskService');
const { defaultWorker } = require('../worker');

router.post('/commit', async (req, res) => {
  try {
    const { commit_id, repo_id, author, message, commit_time, files, sync } = req.body;
    
    if (!commit_id || !repo_id) {
      return res.status(400).json({
        code: 400,
        message: '缺少必要参数: commit_id 或 repo_id'
      });
    }
    
    logger.info('接收代码提交分析请求: commit_id=%s, repo_id=%s, sync=%s', 
      commit_id, repo_id, sync);
    
    if (sync === true || sync === 'true') {
      logger.info('使用同步分析模式: commit_id=%s', commit_id);
      
      const commitResult = await codeAccessService.handleCommitEvent({
        commit_id,
        repo_id,
        author: author || 'unknown',
        message,
        commit_time,
        files: files || []
      });
      
      const changedFiles = commitResult.changedFiles;
      
      const complexityResult = await complexityService.analyzeCommit(commit_id, changedFiles);
      const lintResult = await lintService.analyzeCommit(commit_id, changedFiles);
      const duplicateResult = await duplicateService.analyzeCommit(commit_id, changedFiles);
      
      const overallScore = calculateOverallScore(
        complexityResult.overall_score,
        lintResult.score,
        duplicateResult.score
      );
      
      await reviewTaskService.createTaskFromAnalysis(commit_id, {
        overall_score: overallScore,
        complexity: complexityResult,
        lint: lintResult,
        duplicate: duplicateResult
      });
      
      logger.info('同步分析完成: commit_id=%s, overall_score=%d', commit_id, overallScore);
      
      return res.json({
        code: 200,
        data: {
          analysis_id: complexityResult.analysis_id,
          commit_id,
          overall_score: overallScore,
          complexity_score: complexityResult.overall_score,
          lint_score: lintResult.score,
          duplicate_score: duplicateResult.score,
          files_analyzed: changedFiles.length,
          mode: 'sync'
        }
      });
    }
    
    logger.info('使用异步分析模式: commit_id=%s', commit_id);
    
    const asyncResult = await codeAccessService.submitCommitAsync({
      commit_id,
      repo_id,
      author: author || 'unknown',
      message,
      commit_time,
      files: files || []
    });
    
    logger.info('异步分析已排队: commit_id=%s, event_id=%s', commit_id, asyncResult.event_id);
    
    return res.json({
      code: 202,
      data: {
        event_id: asyncResult.event_id,
        commit_id,
        status: 'queued',
        message: '分析任务已加入队列，将在后台处理',
        mode: 'async'
      }
    });
    
  } catch (error) {
    logger.error('代码提交分析失败: %s', error.message);
    res.status(500).json({
      code: 500,
      message: '分析失败',
      error: error.message
    });
  }
});

router.get('/event/:event_id/status', async (req, res) => {
  try {
    const { event_id } = req.params;
    
    const status = await codeAccessService.getEventStatus(event_id);
    
    res.json({
      code: 200,
      data: status
    });
  } catch (error) {
    logger.error('获取事件状态失败: %s', error.message);
    res.status(500).json({
      code: 500,
      message: '获取事件状态失败',
      error: error.message
    });
  }
});

router.post('/event/:event_id/retry', async (req, res) => {
  try {
    const { event_id } = req.params;
    
    const result = await codeAccessService.retryFailedEvent(event_id);
    
    if (result.success) {
      res.json({
        code: 200,
        data: result
      });
    } else {
      res.status(404).json({
        code: 404,
        message: '事件不存在或无法重试'
      });
    }
  } catch (error) {
    logger.error('重试事件失败: %s', error.message);
    res.status(500).json({
      code: 500,
      message: '重试事件失败',
      error: error.message
    });
  }
});

router.get('/queue/stats', async (req, res) => {
  try {
    const queueStats = await codeAccessService.getQueueStats();
    const workerStats = defaultWorker.getStats();
    
    res.json({
      code: 200,
      data: {
        queue: queueStats,
        worker: workerStats
      }
    });
  } catch (error) {
    logger.error('获取队列统计失败: %s', error.message);
    res.status(500).json({
      code: 500,
      message: '获取队列统计失败',
      error: error.message
    });
  }
});

router.get('/complexity/:analysis_id', async (req, res) => {
  try {
    const { analysis_id } = req.params;
    
    const result = await complexityService.getResults(analysis_id);
    
    if (!result) {
      return res.status(404).json({
        code: 404,
        message: '分析结果不存在'
      });
    }
    
    res.json({
      code: 200,
      data: result
    });
  } catch (error) {
    logger.error('获取复杂度分析结果失败: %s', error.message);
    res.status(500).json({
      code: 500,
      message: '获取分析结果失败',
      error: error.message
    });
  }
});

router.get('/complexity/commit/:commit_id', async (req, res) => {
  try {
    const { commit_id } = req.params;
    
    const result = await complexityService.getByCommitId(commit_id);
    
    if (!result) {
      return res.status(404).json({
        code: 404,
        message: '分析结果不存在'
      });
    }
    
    res.json({
      code: 200,
      data: result
    });
  } catch (error) {
    logger.error('获取提交复杂度分析结果失败: %s', error.message);
    res.status(500).json({
      code: 500,
      message: '获取分析结果失败',
      error: error.message
    });
  }
});

router.get('/lint/:commit_id', async (req, res) => {
  try {
    const { commit_id } = req.params;
    
    const result = await lintService.getResults(commit_id);
    
    res.json({
      code: 200,
      data: result
    });
  } catch (error) {
    logger.error('获取规范检测结果失败: %s', error.message);
    res.status(500).json({
      code: 500,
      message: '获取规范检测结果失败',
      error: error.message
    });
  }
});

router.get('/duplicate/:commit_id', async (req, res) => {
  try {
    const { commit_id } = req.params;
    
    const result = await duplicateService.getResults(commit_id);
    
    res.json({
      code: 200,
      data: result
    });
  } catch (error) {
    logger.error('获取重复检测结果失败: %s', error.message);
    res.status(500).json({
      code: 500,
      message: '获取重复检测结果失败',
      error: error.message
    });
  }
});

router.get('/languages', async (req, res) => {
  try {
    const languages = complexityService.getSupportedLanguages();
    const extensions = complexityService.getSupportedExtensions();
    
    res.json({
      code: 200,
      data: {
        languages,
        extensions
      }
    });
  } catch (error) {
    logger.error('获取支持语言列表失败: %s', error.message);
    res.status(500).json({
      code: 500,
      message: '获取支持语言列表失败',
      error: error.message
    });
  }
});

function calculateOverallScore(complexityScore, lintScore, duplicateScore) {
  const weights = {
    complexity: 0.4,
    lint: 0.4,
    duplicate: 0.2
  };
  
  const overall = 
    (complexityScore * weights.complexity) +
    (lintScore * weights.lint) +
    (duplicateScore * weights.duplicate);
  
  return Math.round(overall);
}

module.exports = router;
