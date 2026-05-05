const pool = require('../config/database');
const { v4: uuidv4 } = require('uuid');
const logger = require('../config/logger');

const commentModel = {
  async create(commentData) {
    try {
      const { 
        comment_id, 
        commit_id, 
        file_path, 
        line_start, 
        line_end, 
        comment_type, 
        content, 
        author, 
        parent_comment_id 
      } = commentData;
      
      const result = await pool.query(
        `INSERT INTO comments 
         (comment_id, commit_id, file_path, line_start, line_end, comment_type, content, author, status, parent_comment_id)
         VALUES ($1, $2, $3, $4, $5, $6, $7, $8, $9, $10)
         RETURNING *`,
        [
          comment_id || uuidv4(),
          commit_id,
          file_path,
          line_start,
          line_end || line_start,
          comment_type || 'comment',
          content,
          author,
          'open',
          parent_comment_id || null
        ]
      );
      
      return result.rows[0];
    } catch (error) {
      logger.error('创建审查意见失败: %s', error.message);
      throw error;
    }
  },

  async findById(comment_id) {
    try {
      const result = await pool.query(
        'SELECT * FROM comments WHERE comment_id = $1',
        [comment_id]
      );
      return result.rows[0] || null;
    } catch (error) {
      logger.error('查询审查意见失败: %s', error.message);
      throw error;
    }
  },

  async findByCommitId(commit_id) {
    try {
      const result = await pool.query(
        'SELECT * FROM comments WHERE commit_id = $1 ORDER BY created_at DESC',
        [commit_id]
      );
      return result.rows;
    } catch (error) {
      logger.error('按提交ID查询审查意见失败: %s', error.message);
      throw error;
    }
  },

  async findByCommitAndFile(commit_id, file_path) {
    try {
      const result = await pool.query(
        'SELECT * FROM comments WHERE commit_id = $1 AND file_path = $2 ORDER BY line_start, created_at',
        [commit_id, file_path]
      );
      return result.rows;
    } catch (error) {
      logger.error('按提交和文件查询审查意见失败: %s', error.message);
      throw error;
    }
  },

  async findByLineRange(commit_id, file_path, line_start, line_end) {
    try {
      const result = await pool.query(
        `SELECT * FROM comments 
         WHERE commit_id = $1 
           AND file_path = $2 
           AND ((line_start <= $3 AND line_end >= $3) 
                OR (line_start <= $4 AND line_end >= $4)
                OR (line_start >= $3 AND line_end <= $4))
         ORDER BY line_start, created_at`,
        [commit_id, file_path, line_start, line_end || line_start]
      );
      return result.rows;
    } catch (error) {
      logger.error('按行范围查询审查意见失败: %s', error.message);
      throw error;
    }
  },

  async findReplies(parent_comment_id) {
    try {
      const result = await pool.query(
        'SELECT * FROM comments WHERE parent_comment_id = $1 ORDER BY created_at',
        [parent_comment_id]
      );
      return result.rows;
    } catch (error) {
      logger.error('查询回复意见失败: %s', error.message);
      throw error;
    }
  },

  async updateStatus(comment_id, status) {
    try {
      const validStatuses = ['open', 'resolved', 'dismissed'];
      if (!validStatuses.includes(status)) {
        throw new Error(`无效的状态: ${status}`);
      }
      
      const result = await pool.query(
        `UPDATE comments 
         SET status = $1, updated_at = CURRENT_TIMESTAMP
         WHERE comment_id = $2
         RETURNING *`,
        [status, comment_id]
      );
      
      return result.rows[0] || null;
    } catch (error) {
      logger.error('更新审查意见状态失败: %s', error.message);
      throw error;
    }
  },

  async updateContent(comment_id, content) {
    try {
      const result = await pool.query(
        `UPDATE comments 
         SET content = $1, updated_at = CURRENT_TIMESTAMP
         WHERE comment_id = $2
         RETURNING *`,
        [content, comment_id]
      );
      return result.rows[0] || null;
    } catch (error) {
      logger.error('更新审查意见内容失败: %s', error.message);
      throw error;
    }
  },

  async delete(comment_id) {
    try {
      const result = await pool.query(
        'DELETE FROM comments WHERE comment_id = $1 RETURNING *',
        [comment_id]
      );
      return result.rows[0] || null;
    } catch (error) {
      logger.error('删除审查意见失败: %s', error.message);
      throw error;
    }
  },

  async getStatistics(commit_id = null) {
    try {
      let query = `
        SELECT 
          status,
          comment_type,
          COUNT(*) as count
        FROM comments
      `;
      const params = [];
      
      if (commit_id) {
        query += ' WHERE commit_id = $1';
        params.push(commit_id);
      }
      
      query += ' GROUP BY status, comment_type';
      
      const result = await pool.query(query, params);
      
      const stats = {
        total: 0,
        open: 0,
        resolved: 0,
        dismissed: 0,
        byType: {
          comment: 0,
          suggestion: 0,
          issue: 0
        }
      };
      
      result.rows.forEach(row => {
        const count = parseInt(row.count);
        stats.total += count;
        
        if (row.status === 'open') stats.open = count;
        else if (row.status === 'resolved') stats.resolved = count;
        else if (row.status === 'dismissed') stats.dismissed = count;
        
        if (row.comment_type === 'comment') stats.byType.comment += count;
        else if (row.comment_type === 'suggestion') stats.byType.suggestion += count;
        else if (row.comment_type === 'issue') stats.byType.issue += count;
      });
      
      return stats;
    } catch (error) {
      logger.error('获取审查意见统计失败: %s', error.message);
      throw error;
    }
  },

  async getCommentsWithReplies(commit_id) {
    try {
      const allComments = await this.findByCommitId(commit_id);
      
      const rootComments = allComments.filter(c => !c.parent_comment_id);
      
      const commentsWithReplies = await Promise.all(
        rootComments.map(async (comment) => {
          const replies = await this.findReplies(comment.comment_id);
          return {
            ...comment,
            replies
          };
        })
      );
      
      return commentsWithReplies;
    } catch (error) {
      logger.error('获取带回复的意见失败: %s', error.message);
      throw error;
    }
  }
};

module.exports = commentModel;
