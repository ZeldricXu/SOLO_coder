const pool = require('../config/database');
const { v4: uuidv4 } = require('uuid');
const logger = require('../config/logger');

const reviewTaskModel = {
  async create(taskData) {
    try {
      const { task_id, commit_id, assignee, title, description, priority } = taskData;
      
      const result = await pool.query(
        `INSERT INTO review_tasks (task_id, commit_id, assignee, title, description, status, priority)
         VALUES ($1, $2, $3, $4, $5, $6, $7)
         RETURNING *`,
        [
          task_id || uuidv4(), 
          commit_id, 
          assignee, 
          title || `审查任务 - ${commit_id}`, 
          description, 
          'pending', 
          priority || 'medium'
        ]
      );
      
      return result.rows[0];
    } catch (error) {
      logger.error('创建审查任务失败: %s', error.message);
      throw error;
    }
  },

  async findById(task_id) {
    try {
      const result = await pool.query(
        'SELECT * FROM review_tasks WHERE task_id = $1',
        [task_id]
      );
      return result.rows[0] || null;
    } catch (error) {
      logger.error('查询审查任务失败: %s', error.message);
      throw error;
    }
  },

  async findByCommitId(commit_id) {
    try {
      const result = await pool.query(
        'SELECT * FROM review_tasks WHERE commit_id = $1 ORDER BY created_at DESC',
        [commit_id]
      );
      return result.rows;
    } catch (error) {
      logger.error('按提交ID查询审查任务失败: %s', error.message);
      throw error;
    }
  },

  async findByAssignee(assignee, status = null) {
    try {
      let query = 'SELECT * FROM review_tasks WHERE assignee = $1';
      const params = [assignee];
      
      if (status) {
        query += ' AND status = $2';
        params.push(status);
      }
      
      query += ' ORDER BY created_at DESC';
      
      const result = await pool.query(query, params);
      return result.rows;
    } catch (error) {
      logger.error('按分配人查询审查任务失败: %s', error.message);
      throw error;
    }
  },

  async updateStatus(task_id, status) {
    try {
      const validStatuses = ['pending', 'in_progress', 'completed', 'rejected'];
      if (!validStatuses.includes(status)) {
        throw new Error(`无效的状态: ${status}`);
      }
      
      const updates = {
        status
      };
      
      if (status === 'completed') {
        updates.completed_at = new Date();
      }
      
      const result = await pool.query(
        `UPDATE review_tasks 
         SET status = $1, 
             completed_at = CASE WHEN $1 = 'completed' THEN CURRENT_TIMESTAMP ELSE completed_at END
         WHERE task_id = $2
         RETURNING *`,
        [status, task_id]
      );
      
      return result.rows[0] || null;
    } catch (error) {
      logger.error('更新审查任务状态失败: %s', error.message);
      throw error;
    }
  },

  async updateAssignee(task_id, assignee) {
    try {
      const result = await pool.query(
        `UPDATE review_tasks 
         SET assignee = $1
         WHERE task_id = $2
         RETURNING *`,
        [assignee, task_id]
      );
      return result.rows[0] || null;
    } catch (error) {
      logger.error('更新审查任务分配人失败: %s', error.message);
      throw error;
    }
  },

  async getStatistics(assignee = null) {
    try {
      let query = `
        SELECT 
          status,
          COUNT(*) as count,
          COUNT(DISTINCT commit_id) as unique_commits
        FROM review_tasks
      `;
      const params = [];
      
      if (assignee) {
        query += ' WHERE assignee = $1';
        params.push(assignee);
      }
      
      query += ' GROUP BY status';
      
      const result = await pool.query(query, params);
      
      const stats = {
        total: 0,
        pending: 0,
        in_progress: 0,
        completed: 0,
        rejected: 0
      };
      
      result.rows.forEach(row => {
        const count = parseInt(row.count);
        stats.total += count;
        if (row.status === 'pending') stats.pending = count;
        else if (row.status === 'in_progress') stats.in_progress = count;
        else if (row.status === 'completed') stats.completed = count;
        else if (row.status === 'rejected') stats.rejected = count;
      });
      
      return stats;
    } catch (error) {
      logger.error('获取审查任务统计失败: %s', error.message);
      throw error;
    }
  },

  async getPendingTasks(limit = 50) {
    try {
      const result = await pool.query(
        `SELECT * FROM review_tasks 
         WHERE status = 'pending' 
         ORDER BY created_at ASC 
         LIMIT $1`,
        [limit]
      );
      return result.rows;
    } catch (error) {
      logger.error('获取待处理任务失败: %s', error.message);
      throw error;
    }
  }
};

module.exports = reviewTaskModel;
