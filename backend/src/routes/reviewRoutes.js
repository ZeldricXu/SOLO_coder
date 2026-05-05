const express = require('express');
const router = express.Router();
const logger = require('../config/logger');
const reviewTaskService = require('../services/reviewTaskService');
const commentService = require('../services/commentService');
const codeAccessService = require('../services/codeAccessService');

router.get('/tasks', async (req, res) => {
  try {
    const { assignee, status, limit } = req.query;
    
    let tasks;
    
    if (assignee) {
      tasks = await reviewTaskService.getTasksByAssignee(assignee, status);
    } else {
      tasks = await reviewTaskService.getPendingTasks(parseInt(limit) || 50);
    }
    
    res.json({
      code: 200,
      data: tasks
    });
  } catch (error) {
    logger.error('获取审查任务列表失败: %s', error.message);
    res.status(500).json({
      code: 500,
      message: '获取任务列表失败',
      error: error.message
    });
  }
});

router.post('/tasks', async (req, res) => {
  try {
    const { commit_id, assignee, title, description, priority } = req.body;
    
    if (!commit_id) {
      return res.status(400).json({
        code: 400,
        message: '缺少必要参数: commit_id'
      });
    }
    
    const task = await reviewTaskService.createTask({
      commit_id,
      assignee,
      title,
      description,
      priority
    });
    
    res.json({
      code: 200,
      data: {
        task_id: task.task_id,
        ...task
      }
    });
  } catch (error) {
    logger.error('创建审查任务失败: %s', error.message);
    res.status(500).json({
      code: 500,
      message: '创建任务失败',
      error: error.message
    });
  }
});

router.get('/tasks/:task_id', async (req, res) => {
  try {
    const { task_id } = req.params;
    
    const task = await reviewTaskService.getTaskWithDetails(task_id);
    
    res.json({
      code: 200,
      data: task
    });
  } catch (error) {
    logger.error('获取审查任务详情失败: %s', error.message);
    res.status(404).json({
      code: 404,
      message: '任务不存在',
      error: error.message
    });
  }
});

router.put('/tasks/:task_id/assign', async (req, res) => {
  try {
    const { task_id } = req.params;
    const { assignee } = req.body;
    
    if (!assignee) {
      return res.status(400).json({
        code: 400,
        message: '缺少必要参数: assignee'
      });
    }
    
    const task = await reviewTaskService.assignTask(task_id, assignee);
    
    res.json({
      code: 200,
      data: task
    });
  } catch (error) {
    logger.error('分配审查任务失败: %s', error.message);
    res.status(500).json({
      code: 500,
      message: '分配任务失败',
      error: error.message
    });
  }
});

router.put('/tasks/:task_id/reassign', async (req, res) => {
  try {
    const { task_id } = req.params;
    
    const task = await reviewTaskService.reassignTaskWithBalance(task_id);
    
    res.json({
      code: 200,
      data: task
    });
  } catch (error) {
    logger.error('重新分配审查任务失败: %s', error.message);
    res.status(500).json({
      code: 500,
      message: '重新分配任务失败',
      error: error.message
    });
  }
});

router.put('/tasks/:task_id/status', async (req, res) => {
  try {
    const { task_id } = req.params;
    const { status } = req.body;
    
    if (!status) {
      return res.status(400).json({
        code: 400,
        message: '缺少必要参数: status'
      });
    }
    
    const task = await reviewTaskService.updateTaskStatus(task_id, status);
    
    res.json({
      code: 200,
      data: task
    });
  } catch (error) {
    logger.error('更新审查任务状态失败: %s', error.message);
    res.status(500).json({
      code: 500,
      message: '更新任务状态失败',
      error: error.message
    });
  }
});

router.get('/tasks/commit/:commit_id', async (req, res) => {
  try {
    const { commit_id } = req.params;
    
    const tasks = await reviewTaskService.getTasksByCommit(commit_id);
    
    res.json({
      code: 200,
      data: tasks
    });
  } catch (error) {
    logger.error('获取提交的审查任务失败: %s', error.message);
    res.status(500).json({
      code: 500,
      message: '获取任务失败',
      error: error.message
    });
  }
});

router.get('/workload', async (req, res) => {
  try {
    const workloadStats = await reviewTaskService.getWorkloadStats();
    
    res.json({
      code: 200,
      data: workloadStats
    });
  } catch (error) {
    logger.error('获取审查人员工作负载失败: %s', error.message);
    res.status(500).json({
      code: 500,
      message: '获取工作负载失败',
      error: error.message
    });
  }
});

router.post('/auto-balance', async (req, res) => {
  try {
    const result = await reviewTaskService.autoBalanceTasks();
    
    res.json({
      code: 200,
      data: result
    });
  } catch (error) {
    logger.error('自动负载均衡失败: %s', error.message);
    res.status(500).json({
      code: 500,
      message: '自动负载均衡失败',
      error: error.message
    });
  }
});

router.get('/reviewer/:user_id/stats', async (req, res) => {
  try {
    const { user_id } = req.params;
    
    const stats = await reviewTaskService.getReviewerStats(user_id);
    
    res.json({
      code: 200,
      data: stats
    });
  } catch (error) {
    logger.error('获取审查人员统计失败: %s', error.message);
    res.status(500).json({
      code: 500,
      message: '获取审查人员统计失败',
      error: error.message
    });
  }
});

router.post('/comment', async (req, res) => {
  try {
    const { 
      commit_id, 
      file_path, 
      line_start, 
      line_end, 
      comment_type, 
      content, 
      author,
      parent_comment_id 
    } = req.body;
    
    if (!commit_id || !file_path || !line_start || !content || !author) {
      return res.status(400).json({
        code: 400,
        message: '缺少必要参数'
      });
    }
    
    const comment = await commentService.createComment({
      commit_id,
      file_path,
      line_start,
      line_end,
      comment_type,
      content,
      author,
      parent_comment_id
    });
    
    res.json({
      code: 200,
      data: {
        comment_id: comment.comment_id,
        ...comment
      }
    });
  } catch (error) {
    logger.error('创建审查意见失败: %s', error.message);
    res.status(500).json({
      code: 500,
      message: '创建意见失败',
      error: error.message
    });
  }
});

router.get('/comments/:comment_id', async (req, res) => {
  try {
    const { comment_id } = req.params;
    
    const comment = await commentService.getCommentWithDetails(comment_id);
    
    res.json({
      code: 200,
      data: comment
    });
  } catch (error) {
    logger.error('获取审查意见失败: %s', error.message);
    res.status(404).json({
      code: 404,
      message: '意见不存在',
      error: error.message
    });
  }
});

router.get('/comments/commit/:commit_id', async (req, res) => {
  try {
    const { commit_id } = req.params;
    const { include_replies } = req.query;
    
    const comments = await commentService.getCommentsByCommit(
      commit_id, 
      include_replies !== 'false'
    );
    
    res.json({
      code: 200,
      data: comments
    });
  } catch (error) {
    logger.error('获取提交的审查意见失败: %s', error.message);
    res.status(500).json({
      code: 500,
      message: '获取意见失败',
      error: error.message
    });
  }
});

router.get('/comments/file/:commit_id', async (req, res) => {
  try {
    const { commit_id } = req.params;
    const { file_path } = req.query;
    
    if (!file_path) {
      return res.status(400).json({
        code: 400,
        message: '缺少必要参数: file_path'
      });
    }
    
    const comments = await commentService.getCommentsByFile(commit_id, file_path);
    
    res.json({
      code: 200,
      data: comments
    });
  } catch (error) {
    logger.error('获取文件的审查意见失败: %s', error.message);
    res.status(500).json({
      code: 500,
      message: '获取意见失败',
      error: error.message
    });
  }
});

router.put('/comments/:comment_id/status', async (req, res) => {
  try {
    const { comment_id } = req.params;
    const { status } = req.body;
    
    if (!status) {
      return res.status(400).json({
        code: 400,
        message: '缺少必要参数: status'
      });
    }
    
    const comment = await commentService.updateCommentStatus(comment_id, status);
    
    res.json({
      code: 200,
      data: comment
    });
  } catch (error) {
    logger.error('更新审查意见状态失败: %s', error.message);
    res.status(500).json({
      code: 500,
      message: '更新意见状态失败',
      error: error.message
    });
  }
});

router.post('/comments/:comment_id/reply', async (req, res) => {
  try {
    const { comment_id } = req.params;
    const { content, author } = req.body;
    
    if (!content || !author) {
      return res.status(400).json({
        code: 400,
        message: '缺少必要参数'
      });
    }
    
    const reply = await commentService.replyToComment(comment_id, {
      content,
      author
    });
    
    res.json({
      code: 200,
      data: reply
    });
  } catch (error) {
    logger.error('回复审查意见失败: %s', error.message);
    res.status(500).json({
      code: 500,
      message: '回复意见失败',
      error: error.message
    });
  }
});

router.delete('/comments/:comment_id', async (req, res) => {
  try {
    const { comment_id } = req.params;
    
    const deletedComment = await commentService.deleteComment(comment_id);
    
    res.json({
      code: 200,
      data: deletedComment
    });
  } catch (error) {
    logger.error('删除审查意见失败: %s', error.message);
    res.status(500).json({
      code: 500,
      message: '删除意见失败',
      error: error.message
    });
  }
});

router.get('/statistics', async (req, res) => {
  try {
    const { assignee, commit_id } = req.query;
    
    const taskStats = await reviewTaskService.getStatistics(assignee);
    const commentStats = await commentService.getStatistics(commit_id);
    
    res.json({
      code: 200,
      data: {
        tasks: taskStats,
        comments: commentStats
      }
    });
  } catch (error) {
    logger.error('获取审查统计失败: %s', error.message);
    res.status(500).json({
      code: 500,
      message: '获取统计失败',
      error: error.message
    });
  }
});

module.exports = router;
