const express = require('express');
const router = express.Router();
const { authenticateToken } = require('../middleware/auth');
const { validateTaskCreate, validateTaskStatusUpdate } = require('../middleware/validation');
const taskService = require('../services/taskService');
const statusTransitionEngine = require('../services/statusTransitionEngine');

router.get('/', authenticateToken, async (req, res) => {
  try {
    const { status, priority, parent_task_id } = req.query;
    const filters = {};

    if (status) filters.status = status;
    if (priority) filters.priority = priority;
    if (parent_task_id !== undefined) {
      filters.parent_task_id = parent_task_id === 'null' ? null : parent_task_id;
    }

    const tasks = await taskService.getTasks(filters, req.user.user_id);

    res.json({
      code: 200,
      data: {
        tasks
      }
    });
  } catch (error) {
    console.error('获取任务列表错误:', error);
    res.status(500).json({
      code: 500,
      message: '服务器内部错误'
    });
  }
});

router.get('/gantt', authenticateToken, async (req, res) => {
  try {
    const { start_date, end_date } = req.query;
    const ganttData = await taskService.getGanttData(start_date, end_date, req.user.user_id);

    res.json({
      code: 200,
      data: ganttData
    });
  } catch (error) {
    console.error('获取甘特图数据错误:', error);
    res.status(500).json({
      code: 500,
      message: '服务器内部错误'
    });
  }
});

router.get('/transitions/available', authenticateToken, async (req, res) => {
  try {
    const { current_status } = req.query;
    
    if (!current_status) {
      const primaryFlow = statusTransitionEngine.getPrimaryFlow();
      return res.json({
        code: 200,
        data: {
          primary_flow: primaryFlow,
          all_rules: {
            todo: statusTransitionEngine.getAvailableTransitions('todo'),
            in_progress: statusTransitionEngine.getAvailableTransitions('in_progress'),
            completed: statusTransitionEngine.getAvailableTransitions('completed'),
            cancelled: statusTransitionEngine.getAvailableTransitions('cancelled')
          }
        }
      });
    }

    const availableTransitions = statusTransitionEngine.getAvailableTransitions(current_status);

    res.json({
      code: 200,
      data: {
        current_status,
        available_transitions: availableTransitions
      }
    });
  } catch (error) {
    console.error('获取可用状态转换错误:', error);
    res.status(500).json({
      code: 500,
      message: '服务器内部错误'
    });
  }
});

router.get('/:taskId', authenticateToken, async (req, res) => {
  try {
    const { taskId } = req.params;
    const task = await taskService.getTaskById(taskId);

    if (!task) {
      return res.status(404).json({
        code: 404,
        message: '任务不存在'
      });
    }

    const dependencyWarnings = await taskService.checkDependencyWarnings(taskId);

    res.json({
      code: 200,
      data: {
        ...task,
        dependency_warnings: dependencyWarnings
      }
    });
  } catch (error) {
    console.error('获取任务详情错误:', error);
    res.status(500).json({
      code: 500,
      message: '服务器内部错误'
    });
  }
});

router.post('/create', authenticateToken, validateTaskCreate, async (req, res) => {
  try {
    const result = await taskService.createTask(req.body, req.user);

    res.status(201).json({
      code: 200,
      data: {
        task_id: result.task_id,
        status: result.status,
        notifications_queued: result.notifications_queued,
        task: result.task
      }
    });
  } catch (error) {
    console.error('创建任务错误:', error);
    
    if (error.message === '父任务不存在') {
      return res.status(400).json({
        code: 400,
        message: error.message
      });
    }

    if (error.message === '无法创建依赖关系：会形成循环依赖') {
      return res.status(400).json({
        code: 400,
        message: error.message
      });
    }

    res.status(500).json({
      code: 500,
      message: '服务器内部错误'
    });
  }
});

router.put('/status', authenticateToken, validateTaskStatusUpdate, async (req, res) => {
  try {
    const { task_id, new_status, progress, version } = req.body;
    const result = await taskService.updateTaskStatus(task_id, new_status, progress, version, req.user);

    res.json({
      code: 200,
      data: {
        updated_at: result.updated_at,
        new_status: result.new_status,
        progress: result.progress,
        version: result.version,
        transition: result.transition
      }
    });
  } catch (error) {
    console.error('更新任务状态错误:', error);

    if (error.message === '任务不存在') {
      return res.status(404).json({
        code: 404,
        message: error.message
      });
    }

    if (error.message === '任务已被其他用户修改，请刷新后重试') {
      return res.status(409).json({
        code: 409,
        message: error.message
      });
    }

    if (error.validationResult) {
      return res.status(400).json({
        code: 400,
        message: error.message,
        details: {
          errors: error.validationResult.errors,
          allowed_transitions: error.validationResult.allowedTransitions
        }
      });
    }

    if (error.message.includes('无法将任务') || error.message.includes('状态')) {
      return res.status(400).json({
        code: 400,
        message: error.message
      });
    }

    res.status(500).json({
      code: 500,
      message: '服务器内部错误'
    });
  }
});

router.post('/:taskId/dependencies', authenticateToken, async (req, res) => {
  try {
    const { taskId } = req.params;
    const { prerequisite_task_id, dependency_type, lag_days } = req.body;

    if (!prerequisite_task_id) {
      return res.status(400).json({
        code: 400,
        message: '前置任务ID不能为空'
      });
    }

    if (taskId === prerequisite_task_id) {
      return res.status(400).json({
        code: 400,
        message: '任务不能依赖于自身'
      });
    }

    const result = await taskService.addTaskDependency(
      taskId, 
      prerequisite_task_id, 
      dependency_type || 'finish_to_start',
      lag_days || 0
    );

    res.status(201).json({
      code: 200,
      message: '依赖关系创建成功',
      data: result
    });
  } catch (error) {
    console.error('创建任务依赖关系错误:', error);

    if (error.message === '任务不存在' || error.message === '前置任务不存在') {
      return res.status(404).json({
        code: 404,
        message: error.message
      });
    }

    if (error.message === '该依赖关系已存在') {
      return res.status(409).json({
        code: 409,
        message: error.message
      });
    }

    if (error.message === '无法创建依赖关系：会形成循环依赖') {
      return res.status(400).json({
        code: 400,
        message: error.message
      });
    }

    res.status(500).json({
      code: 500,
      message: '服务器内部错误'
    });
  }
});

router.delete('/:taskId/dependencies/:prerequisiteTaskId', authenticateToken, async (req, res) => {
  try {
    const { taskId, prerequisiteTaskId } = req.params;

    const success = await taskService.removeTaskDependency(taskId, prerequisiteTaskId);

    if (!success) {
      return res.status(404).json({
        code: 404,
        message: '依赖关系不存在'
      });
    }

    res.json({
      code: 200,
      message: '依赖关系已删除'
    });
  } catch (error) {
    console.error('删除任务依赖关系错误:', error);
    res.status(500).json({
      code: 500,
      message: '服务器内部错误'
    });
  }
});

router.get('/:taskId/dependencies/warnings', authenticateToken, async (req, res) => {
  try {
    const { taskId } = req.params;
    const warnings = await taskService.checkDependencyWarnings(taskId);

    res.json({
      code: 200,
      data: {
        warnings,
        has_warnings: warnings.some(w => w.warning_level !== 'ok')
      }
    });
  } catch (error) {
    console.error('检查依赖关系警告错误:', error);
    res.status(500).json({
      code: 500,
      message: '服务器内部错误'
    });
  }
});

module.exports = router;
