const pool = require('../config/database');
const { v4: uuidv4 } = require('uuid');
const logger = require('../config/logger');
const bcrypt = require('bcryptjs');

const ACTIVITY_CONFIG = {
  INACTIVE_DAYS: 14,
  LOW_ACTIVITY_DAYS: 7,
  HIGH_COMPLETION_RATE: 0.85,
  MEDIUM_COMPLETION_RATE: 0.6
};

const userModel = {
  async create(userData) {
    try {
      const { user_id, username, email, password, role } = userData;
      
      const passwordHash = await bcrypt.hash(password || uuidv4(), 10);
      const now = new Date();
      
      const result = await pool.query(
        `INSERT INTO users 
         (user_id, username, email, password_hash, role, last_login_at, last_activity_at, created_at)
         VALUES ($1, $2, $3, $4, $5, $6, $7, $8)
         RETURNING user_id, username, email, role, last_login_at, last_activity_at, created_at`,
        [
          user_id || uuidv4(),
          username,
          email,
          passwordHash,
          role || 'developer',
          now,
          now,
          now
        ]
      );
      
      return result.rows[0];
    } catch (error) {
      logger.error('创建用户失败: %s', error.message);
      throw error;
    }
  },

  async findById(user_id) {
    try {
      const result = await pool.query(
        `SELECT user_id, username, email, role, last_login_at, last_activity_at, 
                total_reviews, completed_reviews, avg_completion_hours, is_active, created_at
         FROM users WHERE user_id = $1`,
        [user_id]
      );
      return result.rows[0] || null;
    } catch (error) {
      logger.error('查询用户失败: %s', error.message);
      throw error;
    }
  },

  async findByUsername(username) {
    try {
      const result = await pool.query(
        `SELECT user_id, username, email, role, last_login_at, last_activity_at, created_at
         FROM users WHERE username = $1`,
        [username]
      );
      return result.rows[0] || null;
    } catch (error) {
      logger.error('按用户名查询用户失败: %s', error.message);
      throw error;
    }
  },

  async findByEmail(email) {
    try {
      const result = await pool.query(
        `SELECT user_id, username, email, role, last_login_at, last_activity_at, created_at
         FROM users WHERE email = $1`,
        [email]
      );
      return result.rows[0] || null;
    } catch (error) {
      logger.error('按邮箱查询用户失败: %s', error.message);
      throw error;
    }
  },

  async updateLastLogin(user_id) {
    try {
      const result = await pool.query(
        `UPDATE users SET last_login_at = NOW(), last_activity_at = NOW()
         WHERE user_id = $1
         RETURNING user_id, last_login_at, last_activity_at`,
        [user_id]
      );
      
      if (result.rows.length > 0) {
        logger.info('用户登录时间已更新: user_id=%s', user_id);
      }
      
      return result.rows[0] || null;
    } catch (error) {
      logger.error('更新用户登录时间失败: %s', error.message);
      throw error;
    }
  },

  async updateLastActivity(user_id) {
    try {
      const result = await pool.query(
        `UPDATE users SET last_activity_at = NOW()
         WHERE user_id = $1
         RETURNING user_id, last_activity_at`,
        [user_id]
      );
      
      return result.rows[0] || null;
    } catch (error) {
      logger.error('更新用户活动时间失败: %s', error.message);
      throw error;
    }
  },

  async incrementReviewStats(user_id, completed = true, completionHours = null) {
    try {
      let query = `UPDATE users SET total_reviews = total_reviews + 1`;
      const params = [];
      let paramIndex = 1;
      
      if (completed) {
        query += `, completed_reviews = completed_reviews + 1`;
      }
      
      if (completionHours !== null) {
        query += `, avg_completion_hours = (
          COALESCE(avg_completion_hours, 0) * COALESCE(completed_reviews, 0) + $${paramIndex}
        ) / GREATEST(COALESCE(completed_reviews, 0) + 1, 1)`;
        params.push(completionHours);
        paramIndex++;
      }
      
      query += ` WHERE user_id = $${paramIndex} RETURNING *`;
      params.push(user_id);
      
      const result = await pool.query(query, params);
      
      logger.info('用户审查统计已更新: user_id=%s, completed=%s', user_id, completed);
      
      return result.rows[0] || null;
    } catch (error) {
      logger.error('更新用户审查统计失败: %s', error.message);
      throw error;
    }
  },

  async getActivityScore(user_id) {
    try {
      const user = await this.findById(user_id);
      
      if (!user) {
        return { score: 0, status: 'inactive' };
      }
      
      let score = 50;
      let status = 'normal';
      
      const now = new Date();
      const lastActivity = user.last_activity_at ? new Date(user.last_activity_at) : null;
      const lastLogin = user.last_login_at ? new Date(user.last_login_at) : null;
      
      const daysSinceActivity = lastActivity 
        ? Math.floor((now - lastActivity) / (1000 * 60 * 60 * 24))
        : ACTIVITY_CONFIG.INACTIVE_DAYS + 1;
      
      const daysSinceLogin = lastLogin
        ? Math.floor((now - lastLogin) / (1000 * 60 * 60 * 24))
        : ACTIVITY_CONFIG.INACTIVE_DAYS + 1;
      
      if (daysSinceActivity > ACTIVITY_CONFIG.INACTIVE_DAYS) {
        score = 0;
        status = 'inactive';
      } else if (daysSinceActivity <= ACTIVITY_CONFIG.LOW_ACTIVITY_DAYS) {
        score += 20;
        status = 'active';
      }
      
      if (daysSinceLogin <= ACTIVITY_CONFIG.LOW_ACTIVITY_DAYS) {
        score += 10;
      }
      
      const totalReviews = user.total_reviews || 0;
      const completedReviews = user.completed_reviews || 0;
      const completionRate = totalReviews > 0 
        ? completedReviews / totalReviews 
        : 0.8;
      
      if (completionRate >= ACTIVITY_CONFIG.HIGH_COMPLETION_RATE) {
        score += 20;
      } else if (completionRate >= ACTIVITY_CONFIG.MEDIUM_COMPLETION_RATE) {
        score += 10;
      }
      
      const avgHours = user.avg_completion_hours || 0;
      if (avgHours > 0 && avgHours <= 48) {
        score += 10;
      } else if (avgHours > 48 && avgHours <= 96) {
        score += 5;
      }
      
      if (!user.is_active) {
        score = Math.max(0, score - 50);
        status = user.is_active === false ? 'disabled' : status;
      }
      
      return {
        user_id,
        score: Math.min(100, Math.max(0, score)),
        status,
        days_since_activity: daysSinceActivity,
        days_since_login: daysSinceLogin,
        completion_rate: completionRate,
        avg_completion_hours: avgHours,
        is_active: user.is_active
      };
    } catch (error) {
      logger.error('计算用户活跃度分数失败: %s', error.message);
      return { score: 0, status: 'error' };
    }
  },

  async getAllReviewers() {
    try {
      const result = await pool.query(
        `SELECT u.user_id, u.username, u.email, u.role, u.created_at,
                u.last_login_at, u.last_activity_at, u.is_active
         FROM users u
         WHERE u.role IN ('developer', 'senior_developer', 'tech_lead', 'reviewer')
           AND u.is_active = TRUE
         ORDER BY u.created_at ASC`
      );
      return result.rows;
    } catch (error) {
      logger.error('获取审查人员列表失败: %s', error.message);
      throw error;
    }
  },

  async getReviewerWorkload() {
    try {
      const result = await pool.query(
        `SELECT 
           u.user_id,
           u.username,
           u.role,
           u.last_login_at,
           u.last_activity_at,
           u.total_reviews,
           u.completed_reviews,
           u.avg_completion_hours,
           u.is_active,
           COUNT(rt.task_id) as pending_tasks,
           COALESCE(
             SUM(CASE WHEN rt.status = 'in_progress' THEN 1 ELSE 0 END),
             0
           ) as in_progress_tasks,
           COALESCE(
             SUM(CASE WHEN rt.priority = 'high' THEN 1 ELSE 0 END),
             0
           ) as high_priority_tasks
         FROM users u
         LEFT JOIN review_tasks rt ON u.user_id = rt.assignee 
           AND rt.status IN ('pending', 'in_progress')
         WHERE u.role IN ('developer', 'senior_developer', 'tech_lead', 'reviewer')
           AND u.is_active = TRUE
         GROUP BY u.user_id, u.username, u.role, u.last_login_at, u.last_activity_at, 
                  u.total_reviews, u.completed_reviews, u.avg_completion_hours, u.is_active
         ORDER BY pending_tasks ASC, in_progress_tasks ASC, high_priority_tasks ASC`
      );
      
      const reviewers = [];
      
      for (const row of result.rows) {
        const activityScore = await this.getActivityScore(row.user_id);
        
        reviewers.push({
          user_id: row.user_id,
          username: row.username,
          role: row.role,
          last_login_at: row.last_login_at,
          last_activity_at: row.last_activity_at,
          total_reviews: parseInt(row.total_reviews),
          completed_reviews: parseInt(row.completed_reviews),
          avg_completion_hours: parseFloat(row.avg_completion_hours) || 0,
          is_active: row.is_active,
          pending_tasks: parseInt(row.pending_tasks),
          in_progress_tasks: parseInt(row.in_progress_tasks),
          high_priority_tasks: parseInt(row.high_priority_tasks),
          total_load: parseInt(row.pending_tasks) + parseInt(row.in_progress_tasks),
          activity_score: activityScore.score,
          activity_status: activityScore.status,
          completion_rate: activityScore.completion_rate
        });
      }
      
      return reviewers;
    } catch (error) {
      logger.error('获取审查人员工作负载失败: %s', error.message);
      throw error;
    }
  },

  async getReviewerTaskStats(user_id) {
    try {
      const result = await pool.query(
        `SELECT 
           status,
           priority,
           COUNT(*) as count
         FROM review_tasks
         WHERE assignee = $1
         GROUP BY status, priority`,
        [user_id]
      );
      
      const stats = {
        total: 0,
        pending: 0,
        in_progress: 0,
        completed: 0,
        rejected: 0,
        high_priority: 0,
        medium_priority: 0,
        low_priority: 0
      };
      
      result.rows.forEach(row => {
        const count = parseInt(row.count);
        stats.total += count;
        
        if (row.status === 'pending') stats.pending += count;
        else if (row.status === 'in_progress') stats.in_progress += count;
        else if (row.status === 'completed') stats.completed += count;
        else if (row.status === 'rejected') stats.rejected += count;
        
        if (row.priority === 'high') stats.high_priority += count;
        else if (row.priority === 'medium') stats.medium_priority += count;
        else if (row.priority === 'low') stats.low_priority += count;
      });
      
      const user = await this.findById(user_id);
      if (user) {
        stats.total_reviews = user.total_reviews || 0;
        stats.completed_reviews = user.completed_reviews || 0;
        stats.completion_rate = stats.total_reviews > 0 
          ? stats.completed_reviews / stats.total_reviews 
          : null;
        stats.avg_completion_hours = user.avg_completion_hours || null;
      }
      
      return stats;
    } catch (error) {
      logger.error('获取审查人员任务统计失败: %s', error.message);
      throw error;
    }
  },

  async updateRole(user_id, role) {
    try {
      const validRoles = ['developer', 'senior_developer', 'tech_lead', 'reviewer', 'admin'];
      if (!validRoles.includes(role)) {
        throw new Error(`无效的角色: ${role}`);
      }
      
      const result = await pool.query(
        `UPDATE users SET role = $1 WHERE user_id = $2
         RETURNING user_id, username, email, role`,
        [role, user_id]
      );
      return result.rows[0] || null;
    } catch (error) {
      logger.error('更新用户角色失败: %s', error.message);
      throw error;
    }
  },

  async setActiveStatus(user_id, is_active) {
    try {
      const result = await pool.query(
        `UPDATE users SET is_active = $1 WHERE user_id = $2
         RETURNING user_id, username, is_active`,
        [is_active, user_id]
      );
      
      logger.info('用户活跃状态已更新: user_id=%s, is_active=%s', user_id, is_active);
      
      return result.rows[0] || null;
    } catch (error) {
      logger.error('更新用户活跃状态失败: %s', error.message);
      throw error;
    }
  },

  async verifyPassword(user_id, password) {
    try {
      const result = await pool.query(
        'SELECT password_hash FROM users WHERE user_id = $1',
        [user_id]
      );
      
      if (result.rows.length === 0) {
        return false;
      }
      
      return await bcrypt.compare(password, result.rows[0].password_hash);
    } catch (error) {
      logger.error('验证密码失败: %s', error.message);
      throw error;
    }
  }
};

module.exports = userModel;
