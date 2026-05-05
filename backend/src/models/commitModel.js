const pool = require('../config/database');
const { v4: uuidv4 } = require('uuid');
const logger = require('../config/logger');

const commitModel = {
  async create(commitData) {
    try {
      const { commit_id, repo_id, author, message, files_changed, commit_time } = commitData;
      
      const result = await pool.query(
        `INSERT INTO commits (commit_id, repo_id, author, commit_time, message)
         VALUES ($1, $2, $3, $4, $5)
         ON CONFLICT (commit_id) DO UPDATE SET 
           repo_id = EXCLUDED.repo_id,
           author = EXCLUDED.author,
           commit_time = EXCLUDED.commit_time,
           message = EXCLUDED.message
         RETURNING *`,
        [commit_id || uuidv4(), repo_id, author, commit_time || new Date(), message]
      );
      
      return result.rows[0];
    } catch (error) {
      logger.error('创建提交记录失败: %s', error.message);
      throw error;
    }
  },

  async findById(commit_id) {
    try {
      const result = await pool.query(
        'SELECT * FROM commits WHERE commit_id = $1',
        [commit_id]
      );
      return result.rows[0] || null;
    } catch (error) {
      logger.error('查询提交记录失败: %s', error.message);
      throw error;
    }
  },

  async findByRepoId(repo_id, limit = 100) {
    try {
      const result = await pool.query(
        'SELECT * FROM commits WHERE repo_id = $1 ORDER BY commit_time DESC LIMIT $2',
        [repo_id, limit]
      );
      return result.rows;
    } catch (error) {
      logger.error('按仓库查询提交记录失败: %s', error.message);
      throw error;
    }
  },

  async addChangedFile(commit_id, fileData) {
    try {
      const { file_path, file_content, old_content, file_type, language, status } = fileData;
      
      const result = await pool.query(
        `INSERT INTO changed_files (commit_id, file_path, file_content, old_content, file_type, language, status)
         VALUES ($1, $2, $3, $4, $5, $6, $7)
         ON CONFLICT (commit_id, file_path) DO UPDATE SET
           file_content = EXCLUDED.file_content,
           old_content = EXCLUDED.old_content,
           file_type = EXCLUDED.file_type,
           language = EXCLUDED.language,
           status = EXCLUDED.status
         RETURNING *`,
        [commit_id, file_path, file_content, old_content, file_type || 'source', language || 'unknown', status || 'modified']
      );
      
      return result.rows[0];
    } catch (error) {
      logger.error('添加变更文件失败: %s', error.message);
      throw error;
    }
  },

  async getChangedFiles(commit_id) {
    try {
      const result = await pool.query(
        'SELECT * FROM changed_files WHERE commit_id = $1 ORDER BY file_path',
        [commit_id]
      );
      return result.rows;
    } catch (error) {
      logger.error('获取变更文件列表失败: %s', error.message);
      throw error;
    }
  },

  async getFileByPath(commit_id, file_path) {
    try {
      const result = await pool.query(
        'SELECT * FROM changed_files WHERE commit_id = $1 AND file_path = $2',
        [commit_id, file_path]
      );
      return result.rows[0] || null;
    } catch (error) {
      logger.error('查询文件失败: %s', error.message);
      throw error;
    }
  },

  async updateFileContent(commit_id, file_path, file_content, old_content) {
    try {
      const result = await pool.query(
        `UPDATE changed_files 
         SET file_content = $1, old_content = $2
         WHERE commit_id = $3 AND file_path = $4
         RETURNING *`,
        [file_content, old_content, commit_id, file_path]
      );
      return result.rows[0] || null;
    } catch (error) {
      logger.error('更新文件内容失败: %s', error.message);
      throw error;
    }
  }
};

module.exports = commitModel;
