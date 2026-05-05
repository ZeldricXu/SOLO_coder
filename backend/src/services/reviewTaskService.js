const reviewTaskModel = require('../models/reviewTaskModel');
const userModel = require('../models/userModel');
const commitModel = require('../models/commitModel');
const logger = require('../config/logger');

const LOAD_BALANCE_CONFIG = {
  MAX_TASKS_PER_REVIEWER: 10,
  PREFER_ROLES: ['tech_lead', 'senior_developer', 'developer', 'reviewer'],
  HIGH_PRIORITY_BOOST: 2,
  ACTIVITY_WEIGHT: 0.3,
  LOAD_WEIGHT: 0.5,
  COMPLETION_WEIGHT: 0.2,
  MIN_ACTIVITY_SCORE: 30,
  INACTIVE_STATUS_PENALTY: 1000
};

const reviewTaskService = {
  async createTask(taskData) {
    try {
      const { commit_id, assignee, title, description, priority } = taskData;
      
      const commit = await commitModel.findById(commit_id);
      if (!commit) {
        throw new Error(`提交不存在: ${commit_id}`);
      }
      
      let finalAssignee = assignee;
      
      if (!finalAssignee) {
        logger.info('任务未指定分配人，启动负载均衡分配');
        finalAssignee = await this.getLeastLoadedReviewer(priority);
        logger.info('负载均衡分配结果: task_title=%s, assignee=%s', title, finalAssignee);
      }
      
      const task = await reviewTaskModel.create({
        commit_id,
        assignee: finalAssignee,
        title,
        description,
        priority
      });
      
      logger.info('审查任务创建成功: task_id=%s, commit_id=%s, assignee=%s',
        task.task_id, commit_id, finalAssignee);
      
      return task;
    } catch (error) {
      logger.error('创建审查任务失败: %s', error.message);
      throw error;
    }
  },

  async getLeastLoadedReviewer(priority = 'medium') {
    try {
      const workload = await userModel.getReviewerWorkload();
      
      if (workload.length === 0) {
        logger.warn('没有可用的审查人员，返回null');
        return null;
      }
      
      const activeReviewers = workload.filter(r => 
        r.activity_status !== 'inactive' && r.is_active === true
      );
      
      if (activeReviewers.length === 0) {
        logger.warn('没有活跃的审查人员，使用全部审查人员');
      }
      
      const candidates = activeReviewers.length > 0 ? activeReviewers : workload;
      
      logger.info('审查人员候选列表: %o', candidates.map(w => ({
        user_id: w.user_id,
        username: w.username,
        total_load: w.total_load,
        activity_score: w.activity_score,
        activity_status: w.activity_status,
        completion_rate: w.completion_rate
      })));
      
      const workloadWithScores = candidates.map(reviewer => {
        let compositeScore = 0;
        
        const loadScore = reviewer.total_load * LOAD_BALANCE_CONFIG.LOAD_WEIGHT;
        
        const normalizedActivity = 1 - (reviewer.activity_score / 100);
        const activityScore = normalizedActivity * LOAD_BALANCE_CONFIG.ACTIVITY_WEIGHT;
        
        const normalizedCompletion = 1 - (reviewer.completion_rate || 0.5);
        const completionScore = normalizedCompletion * LOAD_BALANCE_CONFIG.COMPLETION_WEIGHT;
        
        let roleBoost = 0;
        if (priority === 'high') {
          const roleIndex = LOAD_BALANCE_CONFIG.PREFER_ROLES.indexOf(reviewer.role);
          if (roleIndex !== -1) {
            roleBoost = roleIndex * LOAD_BALANCE_CONFIG.HIGH_PRIORITY_BOOST;
          }
        }
        
        let statusPenalty = 0;
        if (reviewer.activity_status === 'inactive') {
          statusPenalty = LOAD_BALANCE_CONFIG.INACTIVE_STATUS_PENALTY;
        }
        
        let lowActivityPenalty = 0;
        if (reviewer.activity_score < LOAD_BALANCE_CONFIG.MIN_ACTIVITY_SCORE) {
          lowActivityPenalty = 50;
        }
        
        compositeScore = loadScore + activityScore + completionScore + roleBoost + statusPenalty + lowActivityPenalty;
        
        return { 
          ...reviewer, 
          compositeScore,
          loadScore,
          activityScore,
          completionScore,
          roleBoost,
          statusPenalty,
          lowActivityPenalty
        };
      });
      
      workloadWithScores.sort((a, b) => a.compositeScore - b.compositeScore);
      
      const selected = workloadWithScores[0];
      logger.info('负载均衡选择结果: user_id=%s, username=%s, composite_score=%d (原始负载=%d, 活跃度=%d, 完成率=%d)',
        selected.user_id, selected.username, selected.compositeScore, 
        selected.total_load, selected.activity_score, selected.completion_rate);
      
      logger.info('详细评分: load=%d, activity=%d, completion=%d, role_boost=%d, penalties=%d',
        selected.loadScore, selected.activityScore, selected.completionScore, 
        selected.roleBoost, selected.statusPenalty + selected.lowActivityPenalty);
      
      return selected.user_id;
    } catch (error) {
      logger.error('获取最轻负载审查人员失败: %s', error.message);
      return null;
    }
  },

  async getWorkloadStats() {
    try {
      return await userModel.getReviewerWorkload();
    } catch (error) {
      logger.error('获取工作负载统计失败: %s', error.message);
      throw error;
    }
  },

  async getReviewerStats(user_id) {
    try {
      return await userModel.getReviewerTaskStats(user_id);
    } catch (error) {
      logger.error('获取审查人员统计失败: %s', error.message);
      throw error;
    }
  },

  async autoBalanceTasks() {
    try {
      logger.info('启动任务自动负载均衡');
      
      const workload = await userModel.getReviewerWorkload();
      const pendingTasks = await reviewTaskModel.getPendingTasks(100);
      
      const unassignedTasks = pendingTasks.filter(t => !t.assignee);
      const overloadedReviewers = workload.filter(w => w.total_load > LOAD_BALANCE_CONFIG.MAX_TASKS_PER_REVIEWER);
      
      let balancedCount = 0;
      
      for (const task of unassignedTasks) {
        const leastLoaded = await this.getLeastLoadedReviewer(task.priority);
        if (leastLoaded) {
          await reviewTaskModel.updateAssignee(task.task_id, leastLoaded);
          balancedCount++;
          logger.info('自动均衡分配任务: task_id=%s -> user_id=%s', task.task_id, leastLoaded);
        }
      }
      
      logger.info('自动负载均衡完成: 分配了 %d 个任务', balancedCount);
      
      return {
        balanced_tasks: balancedCount,
        total_unassigned: unassignedTasks.length,
        overloaded_reviewers: overloadedReviewers.length
      };
    } catch (error) {
      logger.error('自动负载均衡失败: %s', error.message);
      throw error;
    }
  },

  async createTaskFromAnalysis(commit_id, analysisResults) {
    try {
      const commit = await commitModel.findById(commit_id);
      if (!commit) {
        throw new Error(`提交不存在: ${commit_id}`);
      }
      
      const issues = [];
      
      if (analysisResults.complexity) {
        const highComplexityFiles = analysisResults.complexity.files?.filter(
          f => f.status === 'critical' || f.status === 'needs_attention'
        ) || [];
        
        if (highComplexityFiles.length > 0) {
          issues.push(`${highComplexityFiles.length} 个文件复杂度较高`);
        }
      }
      
      if (analysisResults.lint && analysisResults.lint.statistics) {
        const lintStats = analysisResults.lint.statistics;
        if (lintStats.errors > 0) {
          issues.push(`${lintStats.errors} 个规范错误`);
        }
        if (lintStats.warnings > 0) {
          issues.push(`${lintStats.warnings} 个规范警告`);
        }
      }
      
      if (analysisResults.duplicate && analysisResults.duplicate.statistics) {
        const dupStats = analysisResults.duplicate.statistics;
        if (dupStats.total_duplicates > 0) {
          issues.push(`${dupStats.total_duplicates} 处重复代码`);
        }
      }
      
      const title = `代码审查 - ${commit.message || commit_id}`;
      const description = issues.length > 0 
        ? `检测到以下问题需要审查:\n${issues.map(i => `- ${i}`).join('\n')}`
        : '代码分析完成，需要人工审查确认。';
      
      let priority = 'medium';
      if (analysisResults.overall_score && analysisResults.overall_score < 50) {
        priority = 'high';
      } else if (analysisResults.overall_score && analysisResults.overall_score >= 80) {
        priority = 'low';
      }
      
      const task = await this.createTask({
        commit_id,
        assignee: null,
        title,
        description,
        priority
      });
      
      logger.info('自动创建审查任务: task_id=%s, priority=%s', task.task_id, priority);
      
      return task;
    } catch (error) {
      logger.error('从分析创建审查任务失败: %s', error.message);
      throw error;
    }
  },

  async getTask(task_id) {
    try {
      const task = await reviewTaskModel.findById(task_id);
      if (!task) {
        throw new Error(`审查任务不存在: ${task_id}`);
      }
      return task;
    } catch (error) {
      logger.error('获取审查任务失败: %s', error.message);
      throw error;
    }
  },

  async getTasksByCommit(commit_id) {
    try {
      return await reviewTaskModel.findByCommitId(commit_id);
    } catch (error) {
      logger.error('按提交获取审查任务失败: %s', error.message);
      throw error;
    }
  },

  async getTasksByAssignee(assignee, status = null) {
    try {
      return await reviewTaskModel.findByAssignee(assignee, status);
    } catch (error) {
      logger.error('按分配人获取审查任务失败: %s', error.message);
      throw error;
    }
  },

  async assignTask(task_id, assignee) {
    try {
      const task = await this.getTask(task_id);
      
      if (task.status === 'completed') {
        throw new Error('不能分配已完成的任务');
      }
      
      const updatedTask = await reviewTaskModel.updateAssignee(task_id, assignee);
      
      if (!updatedTask) {
        throw new Error('分配任务失败');
      }
      
      logger.info('审查任务已分配: task_id=%s, assignee=%s', task_id, assignee);
      
      return updatedTask;
    } catch (error) {
      logger.error('分配审查任务失败: %s', error.message);
      throw error;
    }
  },

  async reassignTaskWithBalance(task_id) {
    try {
      const task = await this.getTask(task_id);
      
      if (task.status === 'completed') {
        throw new Error('不能重新分配已完成的任务');
      }
      
      const newAssignee = await this.getLeastLoadedReviewer(task.priority);
      
      if (!newAssignee) {
        throw new Error('没有可用的审查人员');
      }
      
      const updatedTask = await reviewTaskModel.updateAssignee(task_id, newAssignee);
      
      logger.info('任务重新分配: task_id=%s, from=%s, to=%s', 
        task_id, task.assignee, newAssignee);
      
      return updatedTask;
    } catch (error) {
      logger.error('重新分配任务失败: %s', error.message);
      throw error;
    }
  },

  async updateTaskStatus(task_id, status) {
    try {
      const validStatuses = ['pending', 'in_progress', 'completed', 'rejected'];
      if (!validStatuses.includes(status)) {
        throw new Error(`无效的任务状态: ${status}`);
      }
      
      const updatedTask = await reviewTaskModel.updateStatus(task_id, status);
      
      if (!updatedTask) {
        throw new Error('更新任务状态失败');
      }
      
      logger.info('审查任务状态更新: task_id=%s, status=%s', task_id, status);
      
      return updatedTask;
    } catch (error) {
      logger.error('更新审查任务状态失败: %s', error.message);
      throw error;
    }
  },

  async startTask(task_id) {
    return await this.updateTaskStatus(task_id, 'in_progress');
  },

  async completeTask(task_id) {
    return await this.updateTaskStatus(task_id, 'completed');
  },

  async rejectTask(task_id) {
    return await this.updateTaskStatus(task_id, 'rejected');
  },

  async getStatistics(assignee = null) {
    try {
      return await reviewTaskModel.getStatistics(assignee);
    } catch (error) {
      logger.error('获取审查任务统计失败: %s', error.message);
      throw error;
    }
  },

  async getPendingTasks(limit = 50) {
    try {
      return await reviewTaskModel.getPendingTasks(limit);
    } catch (error) {
      logger.error('获取待处理任务失败: %s', error.message);
      throw error;
    }
  },

  async getTaskWithDetails(task_id) {
    try {
      const task = await this.getTask(task_id);
      const commit = await commitModel.findById(task.commit_id);
      const files = commit ? await commitModel.getChangedFiles(task.commit_id) : [];
      
      return {
        ...task,
        commit,
        files
      };
    } catch (error) {
      logger.error('获取任务详情失败: %s', error.message);
      throw error;
    }
  }
};

module.exports = reviewTaskService;
