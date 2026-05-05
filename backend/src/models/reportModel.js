const pool = require('../config/database');
const { v4: uuidv4 } = require('uuid');
const logger = require('../config/logger');

const reportModel = {
  async create(reportData) {
    try {
      const { 
        report_id, 
        repo_id, 
        commit_id, 
        overall_score, 
        complexity_score, 
        lint_score, 
        duplicate_score, 
        total_issues, 
        resolved_issues, 
        report_data 
      } = reportData;
      
      const result = await pool.query(
        `INSERT INTO quality_reports 
         (report_id, repo_id, commit_id, overall_score, complexity_score, lint_score, 
          duplicate_score, total_issues, resolved_issues, report_data, generated_at)
         VALUES ($1, $2, $3, $4, $5, $6, $7, $8, $9, $10, CURRENT_TIMESTAMP)
         RETURNING *`,
        [
          report_id || uuidv4(),
          repo_id,
          commit_id,
          overall_score || 0,
          complexity_score || 0,
          lint_score || 0,
          duplicate_score || 0,
          total_issues || 0,
          resolved_issues || 0,
          report_data || {}
        ]
      );
      
      return result.rows[0];
    } catch (error) {
      logger.error('创建质量报告失败: %s', error.message);
      throw error;
    }
  },

  async findById(report_id) {
    try {
      const result = await pool.query(
        'SELECT * FROM quality_reports WHERE report_id = $1',
        [report_id]
      );
      return result.rows[0] || null;
    } catch (error) {
      logger.error('查询质量报告失败: %s', error.message);
      throw error;
    }
  },

  async findByRepoId(repo_id, limit = 100) {
    try {
      const result = await pool.query(
        'SELECT * FROM quality_reports WHERE repo_id = $1 ORDER BY generated_at DESC LIMIT $2',
        [repo_id, limit]
      );
      return result.rows;
    } catch (error) {
      logger.error('按仓库查询质量报告失败: %s', error.message);
      throw error;
    }
  },

  async findByCommitId(commit_id) {
    try {
      const result = await pool.query(
        'SELECT * FROM quality_reports WHERE commit_id = $1 ORDER BY generated_at DESC LIMIT 1',
        [commit_id]
      );
      return result.rows[0] || null;
    } catch (error) {
      logger.error('按提交查询质量报告失败: %s', error.message);
      throw error;
    }
  },

  async getQualityTrend(repo_id, start_date, end_date) {
    try {
      const result = await pool.query(
        `SELECT 
           DATE(generated_at) as date,
           AVG(overall_score) as avg_overall_score,
           AVG(complexity_score) as avg_complexity_score,
           AVG(lint_score) as avg_lint_score,
           AVG(duplicate_score) as avg_duplicate_score,
           COUNT(*) as reports_count
         FROM quality_reports 
         WHERE repo_id = $1 
           AND generated_at >= $2 
           AND generated_at <= $3
         GROUP BY DATE(generated_at)
         ORDER BY date ASC`,
        [repo_id, start_date, end_date]
      );
      
      return result.rows;
    } catch (error) {
      logger.error('获取质量趋势失败: %s', error.message);
      throw error;
    }
  },

  async getLatestReport(repo_id) {
    try {
      const result = await pool.query(
        'SELECT * FROM quality_reports WHERE repo_id = $1 ORDER BY generated_at DESC LIMIT 1',
        [repo_id]
      );
      return result.rows[0] || null;
    } catch (error) {
      logger.error('获取最新报告失败: %s', error.message);
      throw error;
    }
  },

  async getStatistics(repo_id = null) {
    try {
      let query = `
        SELECT 
          COUNT(*) as total_reports,
          AVG(overall_score) as avg_overall_score,
          AVG(complexity_score) as avg_complexity_score,
          AVG(lint_score) as avg_lint_score,
          AVG(duplicate_score) as avg_duplicate_score,
          SUM(total_issues) as total_issues,
          SUM(resolved_issues) as total_resolved_issues
        FROM quality_reports
      `;
      const params = [];
      
      if (repo_id) {
        query += ' WHERE repo_id = $1';
        params.push(repo_id);
      }
      
      const result = await pool.query(query, params);
      
      const row = result.rows[0];
      return {
        total_reports: parseInt(row.total_reports) || 0,
        avg_overall_score: parseFloat(row.avg_overall_score) || 0,
        avg_complexity_score: parseFloat(row.avg_complexity_score) || 0,
        avg_lint_score: parseFloat(row.avg_lint_score) || 0,
        avg_duplicate_score: parseFloat(row.avg_duplicate_score) || 0,
        total_issues: parseInt(row.total_issues) || 0,
        total_resolved_issues: parseInt(row.total_resolved_issues) || 0
      };
    } catch (error) {
      logger.error('获取报告统计失败: %s', error.message);
      throw error;
    }
  },

  async calculateOverallScore(complexity_score, lint_score, duplicate_score) {
    const weights = {
      complexity: 0.4,
      lint: 0.4,
      duplicate: 0.2
    };
    
    const overall = 
      (complexity_score * weights.complexity) +
      (lint_score * weights.lint) +
      (duplicate_score * weights.duplicate);
    
    return Math.round(overall);
  }
};

module.exports = reportModel;
