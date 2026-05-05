const pool = require('../config/database');
const logger = require('../config/logger');

const lintModel = {
  async createResult(lintData) {
    try {
      const { commit_id, file_path, rule_id, severity, line, column, message, source } = lintData;
      
      const result = await pool.query(
        `INSERT INTO lint_results (commit_id, file_path, rule_id, severity, line, column, message, source)
         VALUES ($1, $2, $3, $4, $5, $6, $7, $8)
         RETURNING *`,
        [commit_id, file_path, rule_id, severity, line || 0, column || 0, message, source]
      );
      
      return result.rows[0];
    } catch (error) {
      logger.error('创建规范检测结果失败: %s', error.message);
      throw error;
    }
  },

  async createBatchResults(commit_id, file_path, results) {
    try {
      const createdResults = [];
      
      for (const result of results) {
        const created = await this.createResult({
          commit_id,
          file_path,
          rule_id: result.ruleId || result.rule_id,
          severity: result.severity,
          line: result.line,
          column: result.column,
          message: result.message,
          source: result.source
        });
        createdResults.push(created);
      }
      
      return createdResults;
    } catch (error) {
      logger.error('批量创建规范检测结果失败: %s', error.message);
      throw error;
    }
  },

  async findByCommitId(commit_id) {
    try {
      const result = await pool.query(
        'SELECT * FROM lint_results WHERE commit_id = $1 ORDER BY file_path, line',
        [commit_id]
      );
      return result.rows;
    } catch (error) {
      logger.error('按提交ID查询规范检测结果失败: %s', error.message);
      throw error;
    }
  },

  async findByCommitAndFile(commit_id, file_path) {
    try {
      const result = await pool.query(
        'SELECT * FROM lint_results WHERE commit_id = $1 AND file_path = $2 ORDER BY line',
        [commit_id, file_path]
      );
      return result.rows;
    } catch (error) {
      logger.error('按提交和文件查询规范检测结果失败: %s', error.message);
      throw error;
    }
  },

  async getStatistics(commit_id) {
    try {
      const result = await pool.query(
        `SELECT 
           severity,
           COUNT(*) as count,
           COUNT(DISTINCT file_path) as files_affected
         FROM lint_results 
         WHERE commit_id = $1 
         GROUP BY severity`,
        [commit_id]
      );
      
      const stats = {
        total: 0,
        errors: 0,
        warnings: 0,
        infos: 0
      };
      
      result.rows.forEach(row => {
        const count = parseInt(row.count);
        stats.total += count;
        if (row.severity === 'error') stats.errors = count;
        else if (row.severity === 'warning') stats.warnings = count;
        else if (row.severity === 'info') stats.infos = count;
      });
      
      return stats;
    } catch (error) {
      logger.error('获取规范检测统计失败: %s', error.message);
      throw error;
    }
  },

  async calculateScore(commit_id) {
    try {
      const stats = await this.getStatistics(commit_id);
      
      let score = 100;
      
      score -= stats.errors * 10;
      score -= stats.warnings * 3;
      score -= stats.infos * 1;
      
      return Math.max(0, score);
    } catch (error) {
      logger.error('计算规范检测评分失败: %s', error.message);
      throw error;
    }
  },

  async deleteByCommitId(commit_id) {
    try {
      const result = await pool.query(
        'DELETE FROM lint_results WHERE commit_id = $1',
        [commit_id]
      );
      return result.rowCount;
    } catch (error) {
      logger.error('删除规范检测结果失败: %s', error.message);
      throw error;
    }
  }
};

module.exports = lintModel;
