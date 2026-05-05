const express = require('express');
const router = express.Router();
const logger = require('../config/logger');
const reportService = require('../services/reportService');

router.post('/generate', async (req, res) => {
  try {
    const { commit_id, repo_id } = req.body;
    
    if (!commit_id || !repo_id) {
      return res.status(400).json({
        code: 400,
        message: '缺少必要参数: commit_id 或 repo_id'
      });
    }
    
    const report = await reportService.generateReport(commit_id, repo_id);
    
    res.json({
      code: 200,
      data: {
        report_id: report.report_id,
        ...report
      }
    });
  } catch (error) {
    logger.error('生成质量报告失败: %s', error.message);
    res.status(500).json({
      code: 500,
      message: '生成报告失败',
      error: error.message
    });
  }
});

router.get('/quality', async (req, res) => {
  try {
    const { repo_id, start_date, end_date, days } = req.query;
    
    if (!repo_id) {
      return res.status(400).json({
        code: 400,
        message: '缺少必要参数: repo_id'
      });
    }
    
    const dashboard = await reportService.generateDashboardData(
      repo_id,
      parseInt(days) || 30
    );
    
    res.json({
      code: 200,
      data: dashboard
    });
  } catch (error) {
    logger.error('获取质量报告失败: %s', error.message);
    res.status(500).json({
      code: 500,
      message: '获取报告失败',
      error: error.message
    });
  }
});

router.get('/trend', async (req, res) => {
  try {
    const { repo_id, start_date, end_date } = req.query;
    
    if (!repo_id) {
      return res.status(400).json({
        code: 400,
        message: '缺少必要参数: repo_id'
      });
    }
    
    const trend = await reportService.getQualityTrend(repo_id, start_date, end_date);
    
    res.json({
      code: 200,
      data: {
        repo_id,
        trend: trend.map(t => ({
          date: t.date,
          overall_score: parseFloat(t.avg_overall_score) || 0,
          complexity_score: parseFloat(t.avg_complexity_score) || 0,
          lint_score: parseFloat(t.avg_lint_score) || 0,
          duplicate_score: parseFloat(t.avg_duplicate_score) || 0,
          reports_count: parseInt(t.reports_count) || 0
        }))
      }
    });
  } catch (error) {
    logger.error('获取质量趋势失败: %s', error.message);
    res.status(500).json({
      code: 500,
      message: '获取趋势失败',
      error: error.message
    });
  }
});

router.get('/:report_id', async (req, res) => {
  try {
    const { report_id } = req.params;
    
    const report = await reportService.getReportWithDetails(report_id);
    
    res.json({
      code: 200,
      data: report
    });
  } catch (error) {
    logger.error('获取报告详情失败: %s', error.message);
    res.status(404).json({
      code: 404,
      message: '报告不存在',
      error: error.message
    });
  }
});

router.get('/commit/:commit_id', async (req, res) => {
  try {
    const { commit_id } = req.params;
    
    const report = await reportService.getReportByCommit(commit_id);
    
    if (!report) {
      return res.status(404).json({
        code: 404,
        message: '报告不存在'
      });
    }
    
    res.json({
      code: 200,
      data: report
    });
  } catch (error) {
    logger.error('获取提交报告失败: %s', error.message);
    res.status(500).json({
      code: 500,
      message: '获取报告失败',
      error: error.message
    });
  }
});

router.get('/repo/:repo_id', async (req, res) => {
  try {
    const { repo_id } = req.params;
    const { limit } = req.query;
    
    const reports = await reportService.getReportsByRepo(
      repo_id, 
      parseInt(limit) || 100
    );
    
    res.json({
      code: 200,
      data: reports
    });
  } catch (error) {
    logger.error('获取仓库报告列表失败: %s', error.message);
    res.status(500).json({
      code: 500,
      message: '获取报告列表失败',
      error: error.message
    });
  }
});

router.get('/latest/:repo_id', async (req, res) => {
  try {
    const { repo_id } = req.params;
    
    const report = await reportService.getLatestReport(repo_id);
    
    if (!report) {
      return res.status(404).json({
        code: 404,
        message: '暂无报告'
      });
    }
    
    res.json({
      code: 200,
      data: {
        report,
        score_level: reportService.calculateScoreLevel(report.overall_score),
        score_description: reportService.getScoreDescription(report.overall_score)
      }
    });
  } catch (error) {
    logger.error('获取最新报告失败: %s', error.message);
    res.status(500).json({
      code: 500,
      message: '获取报告失败',
      error: error.message
    });
  }
});

router.get('/statistics', async (req, res) => {
  try {
    const { repo_id } = req.query;
    
    const stats = await reportService.getStatistics(repo_id);
    
    res.json({
      code: 200,
      data: {
        ...stats,
        avg_overall_score: parseFloat(stats.avg_overall_score) || 0,
        resolution_rate: stats.total_issues > 0 
          ? (stats.total_resolved_issues / stats.total_issues * 100) 
          : 0
      }
    });
  } catch (error) {
    logger.error('获取报告统计失败: %s', error.message);
    res.status(500).json({
      code: 500,
      message: '获取统计失败',
      error: error.message
    });
  }
});

module.exports = router;
