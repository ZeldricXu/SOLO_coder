const reportModel = require('../models/reportModel');
const complexityModel = require('../models/complexityModel');
const lintModel = require('../models/lintModel');
const duplicateModel = require('../models/duplicateModel');
const commentModel = require('../models/commentModel');
const commitModel = require('../models/commitModel');
const logger = require('../config/logger');

const reportService = {
  async generateReport(commit_id, repo_id) {
    try {
      logger.info('开始生成质量报告: commit_id=%s, repo_id=%s', commit_id, repo_id);
      
      const commit = await commitModel.findById(commit_id);
      if (!commit) {
        throw new Error(`提交不存在: ${commit_id}`);
      }
      
      const complexityScore = await this.getComplexityScore(commit_id);
      const lintScore = await lintModel.calculateScore(commit_id);
      const duplicateScore = await duplicateModel.calculateScore(commit_id);
      
      const overallScore = reportModel.calculateOverallScore(
        complexityScore,
        lintScore,
        duplicateScore
      );
      
      const commentStats = await commentModel.getStatistics(commit_id);
      const lintStats = await lintModel.getStatistics(commit_id);
      const duplicateStats = await duplicateModel.getStatistics(commit_id);
      
      const reportData = {
        commit: {
          commit_id,
          repo_id,
          author: commit.author,
          message: commit.message,
          commit_time: commit.commit_time
        },
        complexity: {
          score: complexityScore
        },
        lint: {
          score: lintScore,
          statistics: lintStats
        },
        duplicate: {
          score: duplicateScore,
          statistics: duplicateStats
        },
        comments: commentStats,
        overall_score: overallScore
      };
      
      const report = await reportModel.create({
        repo_id,
        commit_id,
        overall_score: overallScore,
        complexity_score: complexityScore,
        lint_score: lintScore,
        duplicate_score: duplicateScore,
        total_issues: commentStats.total + lintStats.total,
        resolved_issues: commentStats.resolved,
        report_data: reportData
      });
      
      logger.info('质量报告生成完成: report_id=%s, overall_score=%d', 
        report.report_id, overallScore);
      
      return report;
    } catch (error) {
      logger.error('生成质量报告失败: %s', error.message);
      throw error;
    }
  },

  async getComplexityScore(commit_id) {
    try {
      const analysis = await complexityModel.findByCommitId(commit_id);
      if (analysis) {
        return analysis.overall_score;
      }
      return 100;
    } catch (error) {
      logger.warn('获取复杂度评分失败: %s', error.message);
      return 50;
    }
  },

  async getReport(report_id) {
    try {
      const report = await reportModel.findById(report_id);
      if (!report) {
        throw new Error(`报告不存在: ${report_id}`);
      }
      return report;
    } catch (error) {
      logger.error('获取质量报告失败: %s', error.message);
      throw error;
    }
  },

  async getReportByCommit(commit_id) {
    try {
      const report = await reportModel.findByCommitId(commit_id);
      return report;
    } catch (error) {
      logger.error('按提交获取质量报告失败: %s', error.message);
      throw error;
    }
  },

  async getReportsByRepo(repo_id, limit = 100) {
    try {
      return await reportModel.findByRepoId(repo_id, limit);
    } catch (error) {
      logger.error('按仓库获取质量报告失败: %s', error.message);
      throw error;
    }
  },

  async getQualityTrend(repo_id, start_date, end_date) {
    try {
      const startDate = start_date ? new Date(start_date) : new Date(Date.now() - 30 * 24 * 60 * 60 * 1000);
      const endDate = end_date ? new Date(end_date) : new Date();
      
      return await reportModel.getQualityTrend(repo_id, startDate, endDate);
    } catch (error) {
      logger.error('获取质量趋势失败: %s', error.message);
      throw error;
    }
  },

  async getLatestReport(repo_id) {
    try {
      return await reportModel.getLatestReport(repo_id);
    } catch (error) {
      logger.error('获取最新报告失败: %s', error.message);
      throw error;
    }
  },

  async getStatistics(repo_id = null) {
    try {
      return await reportModel.getStatistics(repo_id);
    } catch (error) {
      logger.error('获取报告统计失败: %s', error.message);
      throw error;
    }
  },

  async generateDashboardData(repo_id, days = 30) {
    try {
      const endDate = new Date();
      const startDate = new Date(endDate.getTime() - days * 24 * 60 * 60 * 1000);
      
      const trend = await this.getQualityTrend(repo_id, startDate, endDate);
      const latestReport = await this.getLatestReport(repo_id);
      const statistics = await this.getStatistics(repo_id);
      
      const dashboard = {
        repo_id,
        period: {
          start: startDate.toISOString(),
          end: endDate.toISOString(),
          days
        },
        current_score: latestReport?.overall_score || 0,
        trend: trend.map(t => ({
          date: t.date,
          overall_score: parseFloat(t.avg_overall_score) || 0,
          complexity_score: parseFloat(t.avg_complexity_score) || 0,
          lint_score: parseFloat(t.avg_lint_score) || 0,
          duplicate_score: parseFloat(t.avg_duplicate_score) || 0
        })),
        statistics: {
          total_reports: statistics.total_reports,
          avg_overall_score: parseFloat(statistics.avg_overall_score) || 0,
          total_issues: statistics.total_issues,
          total_resolved_issues: statistics.total_resolved_issues,
          resolution_rate: statistics.total_issues > 0 
            ? (statistics.total_resolved_issues / statistics.total_issues * 100) 
            : 0
        },
        latest_report: latestReport ? {
          report_id: latestReport.report_id,
          commit_id: latestReport.commit_id,
          overall_score: latestReport.overall_score,
          complexity_score: latestReport.complexity_score,
          lint_score: latestReport.lint_score,
          duplicate_score: latestReport.duplicate_score,
          generated_at: latestReport.generated_at
        } : null
      };
      
      return dashboard;
    } catch (error) {
      logger.error('生成仪表板数据失败: %s', error.message);
      throw error;
    }
  },

  async getReportWithDetails(report_id) {
    try {
      const report = await this.getReport(report_id);
      
      return {
        ...report,
        report_data: report.report_data
      };
    } catch (error) {
      logger.error('获取报告详情失败: %s', error.message);
      throw error;
    }
  },

  calculateScoreLevel(score) {
    if (score >= 80) return 'excellent';
    if (score >= 60) return 'good';
    if (score >= 40) return 'fair';
    return 'poor';
  },

  getScoreDescription(score) {
    const level = this.calculateScoreLevel(score);
    
    const descriptions = {
      excellent: '代码质量优秀，符合最佳实践规范',
      good: '代码质量良好，仅有少量建议性问题',
      fair: '代码质量一般，存在一些需要关注的问题',
      poor: '代码质量较差，存在较多严重问题需要修复'
    };
    
    return descriptions[level];
  }
};

module.exports = reportService;
