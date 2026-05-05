const pool = require('../config/database');
const { v4: uuidv4 } = require('uuid');
const logger = require('../config/logger');

const complexityModel = {
  async createAnalysis(analysisData) {
    try {
      const { analysis_id, commit_id, overall_score, status } = analysisData;
      
      const result = await pool.query(
        `INSERT INTO complexity_analysis (analysis_id, commit_id, overall_score, status, analyzed_at)
         VALUES ($1, $2, $3, $4, CURRENT_TIMESTAMP)
         RETURNING *`,
        [analysis_id || uuidv4(), commit_id, overall_score || 0, status || 'completed']
      );
      
      return result.rows[0];
    } catch (error) {
      logger.error('创建复杂度分析记录失败: %s', error.message);
      throw error;
    }
  },

  async createFileComplexity(fileData) {
    try {
      const { analysis_id, file_path, language, total_functions, avg_cyclomatic, complexity_score, status } = fileData;
      
      const result = await pool.query(
        `INSERT INTO file_complexity (analysis_id, file_path, language, total_functions, avg_cyclomatic, complexity_score, status)
         VALUES ($1, $2, $3, $4, $5, $6, $7)
         RETURNING *`,
        [analysis_id, file_path, language || 'unknown', total_functions || 0, avg_cyclomatic || 0, complexity_score || 0, status || 'acceptable']
      );
      
      return result.rows[0];
    } catch (error) {
      logger.error('创建文件复杂度记录失败: %s', error.message);
      throw error;
    }
  },

  async createFunctionComplexity(functionData) {
    try {
      const { file_complexity_id, function_name, cyclomatic, lines, params, is_above_threshold } = functionData;
      
      const result = await pool.query(
        `INSERT INTO function_complexity (file_complexity_id, function_name, cyclomatic, lines, params, is_above_threshold)
         VALUES ($1, $2, $3, $4, $5, $6)
         RETURNING *`,
        [file_complexity_id, function_name, cyclomatic || 0, lines || 0, params || 0, is_above_threshold || false]
      );
      
      return result.rows[0];
    } catch (error) {
      logger.error('创建函数复杂度记录失败: %s', error.message);
      throw error;
    }
  },

  async findById(analysis_id) {
    try {
      const result = await pool.query(
        'SELECT * FROM complexity_analysis WHERE analysis_id = $1',
        [analysis_id]
      );
      return result.rows[0] || null;
    } catch (error) {
      logger.error('查询复杂度分析失败: %s', error.message);
      throw error;
    }
  },

  async findByCommitId(commit_id) {
    try {
      const result = await pool.query(
        'SELECT * FROM complexity_analysis WHERE commit_id = $1 ORDER BY analyzed_at DESC LIMIT 1',
        [commit_id]
      );
      return result.rows[0] || null;
    } catch (error) {
      logger.error('按提交ID查询复杂度分析失败: %s', error.message);
      throw error;
    }
  },

  async getFileComplexities(analysis_id) {
    try {
      const result = await pool.query(
        'SELECT * FROM file_complexity WHERE analysis_id = $1 ORDER BY file_path',
        [analysis_id]
      );
      return result.rows;
    } catch (error) {
      logger.error('获取文件复杂度列表失败: %s', error.message);
      throw error;
    }
  },

  async getFunctionComplexities(file_complexity_id) {
    try {
      const result = await pool.query(
        'SELECT * FROM function_complexity WHERE file_complexity_id = $1 ORDER BY function_name',
        [file_complexity_id]
      );
      return result.rows;
    } catch (error) {
      logger.error('获取函数复杂度列表失败: %s', error.message);
      throw error;
    }
  },

  async getFullAnalysis(analysis_id) {
    try {
      const analysis = await this.findById(analysis_id);
      if (!analysis) return null;
      
      const files = await this.getFileComplexities(analysis_id);
      
      const filesWithFunctions = await Promise.all(
        files.map(async (file) => {
          const functions = await this.getFunctionComplexities(file.id);
          return {
            ...file,
            functions
          };
        })
      );
      
      return {
        ...analysis,
        files: filesWithFunctions
      };
    } catch (error) {
      logger.error('获取完整分析结果失败: %s', error.message);
      throw error;
    }
  },

  async updateOverallScore(analysis_id, overall_score) {
    try {
      const result = await pool.query(
        `UPDATE complexity_analysis 
         SET overall_score = $1, status = 'completed'
         WHERE analysis_id = $2
         RETURNING *`,
        [overall_score, analysis_id]
      );
      return result.rows[0] || null;
    } catch (error) {
      logger.error('更新总体评分失败: %s', error.message);
      throw error;
    }
  }
};

module.exports = complexityModel;
