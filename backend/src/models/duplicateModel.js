const pool = require('../config/database');
const logger = require('../config/logger');

const duplicateModel = {
  async createResult(duplicateData) {
    try {
      const { commit_id, file_path1, file_path2, similarity, lines_count, fragment1, fragment2 } = duplicateData;
      
      const result = await pool.query(
        `INSERT INTO duplicate_results (commit_id, file_path1, file_path2, similarity, lines_count, fragment1, fragment2)
         VALUES ($1, $2, $3, $4, $5, $6, $7)
         RETURNING *`,
        [commit_id, file_path1, file_path2, similarity || 0, lines_count || 0, fragment1, fragment2]
      );
      
      return result.rows[0];
    } catch (error) {
      logger.error('创建重复检测结果失败: %s', error.message);
      throw error;
    }
  },

  async createBatchResults(commit_id, duplicates) {
    try {
      const createdResults = [];
      
      for (const duplicate of duplicates) {
        const created = await this.createResult({
          commit_id,
          file_path1: duplicate.file_path1,
          file_path2: duplicate.file_path2,
          similarity: duplicate.similarity,
          lines_count: duplicate.lines_count,
          fragment1: duplicate.fragment1,
          fragment2: duplicate.fragment2
        });
        createdResults.push(created);
      }
      
      return createdResults;
    } catch (error) {
      logger.error('批量创建重复检测结果失败: %s', error.message);
      throw error;
    }
  },

  async findByCommitId(commit_id) {
    try {
      const result = await pool.query(
        'SELECT * FROM duplicate_results WHERE commit_id = $1 ORDER BY similarity DESC',
        [commit_id]
      );
      return result.rows;
    } catch (error) {
      logger.error('按提交ID查询重复检测结果失败: %s', error.message);
      throw error;
    }
  },

  async getStatistics(commit_id) {
    try {
      const result = await pool.query(
        `SELECT 
           COUNT(*) as total_duplicates,
           AVG(similarity) as avg_similarity,
           SUM(lines_count) as total_duplicate_lines,
           COUNT(DISTINCT file_path1) as files_involved
         FROM duplicate_results 
         WHERE commit_id = $1`,
        [commit_id]
      );
      
      const row = result.rows[0];
      return {
        total_duplicates: parseInt(row.total_duplicates) || 0,
        avg_similarity: parseFloat(row.avg_similarity) || 0,
        total_duplicate_lines: parseInt(row.total_duplicate_lines) || 0,
        files_involved: parseInt(row.files_involved) || 0
      };
    } catch (error) {
      logger.error('获取重复检测统计失败: %s', error.message);
      throw error;
    }
  },

  async calculateScore(commit_id) {
    try {
      const stats = await this.getStatistics(commit_id);
      
      let score = 100;
      
      if (stats.total_duplicates > 0) {
        const penalty = Math.min(50, stats.total_duplicate_lines * 0.5);
        score -= penalty;
      }
      
      return Math.max(0, score);
    } catch (error) {
      logger.error('计算重复检测评分失败: %s', error.message);
      throw error;
    }
  },

  async deleteByCommitId(commit_id) {
    try {
      const result = await pool.query(
        'DELETE FROM duplicate_results WHERE commit_id = $1',
        [commit_id]
      );
      return result.rowCount;
    } catch (error) {
      logger.error('删除重复检测结果失败: %s', error.message);
      throw error;
    }
  }
};

module.exports = duplicateModel;
