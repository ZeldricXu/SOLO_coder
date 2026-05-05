const db = require('../config/database');
const { v4: uuidv4 } = require('uuid');
const statusTransitionEngine = require('./statusTransitionEngine');
const redisNotificationQueue = require('./redisNotificationQueue');

const createTask = async (taskData, creator) => {
  const { 
    title, 
    description, 
    assignees, 
    due_date, 
    priority, 
    parent_task_id,
    dependencies 
  } = taskData;
  const taskId = uuidv4();
  const now = new Date();

  const connection = await db.getConnection();
  try {
    await connection.beginTransaction();

    if (parent_task_id) {
      const [parents] = await connection.execute(
        'SELECT task_id, status FROM tasks WHERE task_id = ?',
        [parent_task_id]
      );
      if (parents.length === 0) {
        throw new Error('父任务不存在');
      }
    }

    await connection.execute(
      `INSERT INTO tasks 
       (task_id, title, description, priority, status, progress, due_date, parent_task_id, created_by, version) 
       VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?)`,
      [taskId, title, description || null, priority || 'medium', 'todo', 0, due_date, parent_task_id || null, creator.user_id, 1]
    );

    const validAssignees = [];
    if (assignees && assignees.length > 0) {
      for (const assigneeId of assignees) {
        const [users] = await connection.execute(
          'SELECT user_id, email, username FROM users WHERE user_id = ?',
          [assigneeId]
        );
        if (users.length > 0) {
          const assignmentId = uuidv4();
          await connection.execute(
            `INSERT INTO task_assignments (assignment_id, task_id, user_id, assigned_by) 
             VALUES (?, ?, ?, ?)`,
            [assignmentId, taskId, assigneeId, creator.user_id]
          );
          validAssignees.push(users[0]);
        }
      }
    }

    if (dependencies && dependencies.length > 0) {
      for (const dep of dependencies) {
        const [depTasks] = await connection.execute(
          'SELECT task_id FROM tasks WHERE task_id = ?',
          [dep.prerequisite_task_id]
        );
        if (depTasks.length > 0) {
          const dependencyId = uuidv4();
          await connection.execute(
            `INSERT INTO task_dependencies 
             (dependency_id, task_id, prerequisite_task_id, dependency_type, lag_days) 
             VALUES (?, ?, ?, ?, ?)`,
            [
              dependencyId, 
              taskId, 
              dep.prerequisite_task_id, 
              dep.dependency_type || 'finish_to_start',
              dep.lag_days || 0
            ]
          );
        }
      }
    }

    await connection.commit();

    const createdTask = {
      task_id: taskId,
      title,
      description,
      priority: priority || 'medium',
      status: 'todo',
      progress: 0,
      due_date,
      parent_task_id: parent_task_id || null,
      assignees: validAssignees.map(a => a.user_id),
      created_by: creator.user_id,
      created_at: now.toISOString(),
      updated_at: now.toISOString()
    };

    if (validAssignees.length > 0) {
      await redisNotificationQueue.enqueueNotification('task_created', {
        task: createdTask,
        creator: creator,
        assignees: validAssignees
      }, 10);
    }

    return {
      task_id: taskId,
      status: 'todo',
      notifications_queued: validAssignees.length,
      task: createdTask
    };
  } catch (error) {
    await connection.rollback();
    throw error;
  } finally {
    connection.release();
  }
};

const updateTaskStatus = async (taskId, newStatus, progress, version, changer) => {
  const connection = await db.getConnection();
  try {
    await connection.beginTransaction();

    const [tasks] = await connection.execute(
      'SELECT * FROM tasks WHERE task_id = ? FOR UPDATE',
      [taskId]
    );

    if (tasks.length === 0) {
      throw new Error('任务不存在');
    }

    const task = tasks[0];
    const oldStatus = task.status;

    if (oldStatus === newStatus) {
      await connection.commit();
      return {
        updated_at: task.updated_at.toISOString(),
        new_status: newStatus,
        progress: task.progress,
        version: task.version
      };
    }

    if (version !== undefined && version !== null) {
      if (task.version !== version) {
        throw new Error('任务已被其他用户修改，请刷新后重试');
      }
    }

    const validationResult = statusTransitionEngine.validateTransition(
      oldStatus, 
      newStatus, 
      task, 
      { changer }
    );

    if (!validationResult.valid) {
      const error = new Error(validationResult.errors.join('; '));
      error.validationResult = validationResult;
      throw error;
    }

    const [uncompletedDependencies] = await connection.execute(
      `SELECT 
        td.dependency_id,
        td.prerequisite_task_id,
        t.title as prerequisite_title,
        t.status as prerequisite_status,
        td.dependency_type
       FROM task_dependencies td
       JOIN tasks t ON td.prerequisite_task_id = t.task_id
       WHERE td.task_id = ? 
         AND td.dependency_type = 'finish_to_start'
         AND t.status != 'completed'`,
      [taskId]
    );

    if (newStatus === 'in_progress' && uncompletedDependencies.length > 0) {
      const blockingTasks = uncompletedDependencies.map(d => 
        `"${d.prerequisite_title}" (状态: ${statusTransitionEngine.getStatusLabel(d.prerequisite_status)})`
      ).join(', ');
      
      const warning = new Error(`存在未完成的前置任务依赖: ${blockingTasks}。建议完成前置任务后再开始此任务。`);
      warning.isWarning = true;
      warning.blockingDependencies = uncompletedDependencies;
    }

    let newProgress = progress;
    let completedAt = task.completed_at;

    if (newStatus === 'completed') {
      newProgress = 100;
      completedAt = new Date();
      const [subTasks] = await connection.execute(
        'SELECT task_id, status FROM tasks WHERE parent_task_id = ?',
        [taskId]
      );
      for (const subTask of subTasks) {
        if (subTask.status !== 'completed') {
          await connection.execute(
            'UPDATE tasks SET status = ?, progress = 100, completed_at = ?, version = version + 1 WHERE task_id = ?',
            ['completed', completedAt, subTask.task_id]
          );
        }
      }
    } else if (newStatus === 'in_progress' && (progress === undefined || progress === null)) {
      const [subTasks] = await connection.execute(
        'SELECT COUNT(*) as total, SUM(CASE WHEN status = "completed" THEN 1 ELSE 0 END) as completed FROM tasks WHERE parent_task_id = ?',
        [taskId]
      );
      if (subTasks[0].total > 0) {
        newProgress = Math.round((subTasks[0].completed / subTasks[0].total) * 100);
      } else {
        newProgress = task.progress;
      }
    }

    if (newProgress === undefined || newProgress === null) {
      newProgress = task.progress;
    }

    await connection.execute(
      `UPDATE tasks 
       SET status = ?, progress = ?, completed_at = ?, version = version + 1 
       WHERE task_id = ?`,
      [newStatus, newProgress, completedAt, taskId]
    );

    const historyId = uuidv4();
    await connection.execute(
      `INSERT INTO task_history 
       (history_id, task_id, field_name, old_value, new_value, changed_by) 
       VALUES (?, ?, ?, ?, ?, ?)`,
      [historyId, taskId, 'status', oldStatus, newStatus, changer.user_id]
    );

    if (progress !== undefined && progress !== null) {
      const progressHistoryId = uuidv4();
      await connection.execute(
        `INSERT INTO task_history 
         (history_id, task_id, field_name, old_value, new_value, changed_by) 
         VALUES (?, ?, ?, ?, ?, ?)`,
        [progressHistoryId, taskId, 'progress', task.progress.toString(), newProgress.toString(), changer.user_id]
      );
    }

    const [updatedTasks] = await connection.execute(
      'SELECT * FROM tasks WHERE task_id = ?',
      [taskId]
    );
    const updatedTask = updatedTasks[0];

    await connection.commit();

    if (oldStatus !== newStatus) {
      const [assignments] = await connection.execute(
        `SELECT u.user_id, u.email, u.username 
         FROM task_assignments ta 
         JOIN users u ON ta.user_id = u.user_id 
         WHERE ta.task_id = ?`,
        [taskId]
      );

      const assignees = assignments.map(a => ({
        user_id: a.user_id,
        email: a.email,
        username: a.username
      }));

      await redisNotificationQueue.enqueueNotification('task_status_changed', {
        task: {
          ...updatedTask,
          assignees: assignees.map(a => a.user_id)
        },
        changer: changer,
        oldStatus: oldStatus,
        newStatus: newStatus,
        assignees: assignees
      }, 5);
    }

    return {
      updated_at: updatedTask.updated_at.toISOString(),
      new_status: newStatus,
      progress: newProgress,
      version: updatedTask.version,
      transition: validationResult.transition
    };
  } catch (error) {
    await connection.rollback();
    throw error;
  } finally {
    connection.release();
  }
};

const getTasks = async (filters = {}, userId = null) => {
  let query = `
    SELECT 
      t.*,
      GROUP_CONCAT(DISTINCT ta.user_id) as assignee_ids,
      GROUP_CONCAT(DISTINCT u.username) as assignee_names,
      (SELECT COUNT(*) FROM tasks WHERE parent_task_id = t.task_id) as sub_task_count,
      (SELECT 
        CONCAT_WS('|', td.prerequisite_task_id, td.dependency_type, td.lag_days, pt.title)
       FROM task_dependencies td
       LEFT JOIN tasks pt ON td.prerequisite_task_id = pt.task_id
       WHERE td.task_id = t.task_id
       LIMIT 10
      ) as dependencies_info
    FROM tasks t
    LEFT JOIN task_assignments ta ON t.task_id = ta.task_id
    LEFT JOIN users u ON ta.user_id = u.user_id
    WHERE 1=1
  `;
  const params = [];

  if (filters.status) {
    query += ' AND t.status = ?';
    params.push(filters.status);
  }

  if (filters.priority) {
    query += ' AND t.priority = ?';
    params.push(filters.priority);
  }

  if (userId) {
    query += ' AND (t.created_by = ? OR ta.user_id = ?)';
    params.push(userId, userId);
  }

  if (filters.parent_task_id !== undefined) {
    if (filters.parent_task_id === null) {
      query += ' AND t.parent_task_id IS NULL';
    } else {
      query += ' AND t.parent_task_id = ?';
      params.push(filters.parent_task_id);
    }
  }

  query += ' GROUP BY t.task_id ORDER BY t.created_at DESC';

  const [tasks] = await db.execute(query, params);

  return tasks.map(task => ({
    ...task,
    assignees: task.assignee_ids ? task.assignee_ids.split(',') : [],
    assignee_names: task.assignee_names ? task.assignee_names.split(',') : [],
    dependencies: parseDependenciesInfo(task.dependencies_info)
  }));
};

const parseDependenciesInfo = (info) => {
  if (!info) return [];
  
  const parts = info.split('|');
  if (parts.length >= 3) {
    return [{
      prerequisite_task_id: parts[0],
      dependency_type: parts[1],
      lag_days: parseInt(parts[2]) || 0,
      prerequisite_title: parts[3] || null
    }];
  }
  return [];
};

const getTaskById = async (taskId) => {
  const [tasks] = await db.execute(
    `SELECT 
      t.*,
      GROUP_CONCAT(DISTINCT ta.user_id) as assignee_ids,
      GROUP_CONCAT(DISTINCT u.username) as assignee_names,
      (SELECT COUNT(*) FROM tasks WHERE parent_task_id = t.task_id) as sub_task_count
    FROM tasks t
    LEFT JOIN task_assignments ta ON t.task_id = ta.task_id
    LEFT JOIN users u ON ta.user_id = u.user_id
    WHERE t.task_id = ?
    GROUP BY t.task_id`,
    [taskId]
  );

  if (tasks.length === 0) {
    return null;
  }

  const task = tasks[0];
  
  const [dependencies] = await db.execute(
    `SELECT 
      td.dependency_id,
      td.task_id,
      td.prerequisite_task_id,
      td.dependency_type,
      td.lag_days,
      pt.title as prerequisite_title,
      pt.status as prerequisite_status,
      pt.due_date as prerequisite_due_date
     FROM task_dependencies td
     LEFT JOIN tasks pt ON td.prerequisite_task_id = pt.task_id
     WHERE td.task_id = ?`,
    [taskId]
  );

  const [dependentTasks] = await db.execute(
    `SELECT 
      td.dependency_id,
      td.task_id as dependent_task_id,
      td.prerequisite_task_id,
      td.dependency_type,
      td.lag_days,
      dt.title as dependent_title,
      dt.status as dependent_status,
      dt.due_date as dependent_due_date
     FROM task_dependencies td
     LEFT JOIN tasks dt ON td.task_id = dt.task_id
     WHERE td.prerequisite_task_id = ?`,
    [taskId]
  );

  return {
    ...task,
    assignees: task.assignee_ids ? task.assignee_ids.split(',') : [],
    assignee_names: task.assignee_names ? task.assignee_names.split(',') : [],
    dependencies,
    dependent_tasks: dependentTasks
  };
};

const getGanttData = async (startDate, endDate, userId = null) => {
  let query = `
    SELECT 
      t.task_id as id,
      t.title as name,
      t.created_at as start,
      t.due_date as end,
      t.progress,
      t.status,
      t.parent_task_id,
      t.priority
    FROM tasks t
    LEFT JOIN task_assignments ta ON t.task_id = ta.task_id
    WHERE 1=1
  `;
  const params = [];

  if (startDate) {
    query += ' AND (t.created_at >= ? OR t.due_date >= ?)';
    params.push(startDate, startDate);
  }

  if (endDate) {
    query += ' AND (t.created_at <= ? OR t.due_date <= ?)';
    params.push(endDate, endDate);
  }

  if (userId) {
    query += ' AND (t.created_by = ? OR ta.user_id = ?)';
    params.push(userId, userId);
  }

  query += ' GROUP BY t.task_id ORDER BY t.created_at';

  const [tasks] = await db.execute(query, params);

  const [allDependencies] = await db.execute(
    `SELECT td.task_id, td.prerequisite_task_id, td.dependency_type, td.lag_days
     FROM task_dependencies td
     JOIN tasks t ON td.task_id = t.task_id
     WHERE 1=1
     ${startDate ? ' AND (t.created_at >= ? OR t.due_date >= ?)' : ''}
     ${endDate ? ' AND (t.created_at <= ? OR t.due_date <= ?)' : ''}
     ORDER BY td.task_id`,
    [...params]
  );

  const dependencyMap = new Map();
  allDependencies.forEach(dep => {
    if (!dependencyMap.has(dep.task_id)) {
      dependencyMap.set(dep.task_id, []);
    }
    dependencyMap.get(dep.task_id).push(dep);
  });

  const ganttTasks = tasks.map(task => {
    const dependencies = dependencyMap.get(task.id) || [];
    
    return {
      id: task.id,
      name: task.name,
      start: task.start ? new Date(task.start).toISOString().split('T')[0] : null,
      end: task.end ? new Date(task.end).toISOString().split('T')[0] : null,
      progress: task.progress,
      status: task.status,
      priority: task.priority,
      parent_task_id: task.parent_task_id,
      dependencies: dependencies.map(d => ({
        prerequisite_task_id: d.prerequisite_task_id,
        dependency_type: d.dependency_type,
        lag_days: d.lag_days
      })),
      dependency_ids: dependencies.map(d => d.prerequisite_task_id)
    };
  });

  const addDelayWarnings = (tasks) => {
    const taskMap = new Map(tasks.map(t => [t.id, t]));
    const warnings = [];

    for (const task of tasks) {
      if (task.dependencies && task.dependencies.length > 0) {
        for (const dep of task.dependencies) {
          const prerequisiteTask = taskMap.get(dep.prerequisite_task_id);
          
          if (prerequisiteTask) {
            if (prerequisiteTask.status !== 'completed') {
              const taskStart = new Date(task.start);
              const prereqEnd = new Date(prerequisiteTask.end);
              
              if (taskStart < prereqEnd) {
                warnings.push({
                  task_id: task.id,
                  task_name: task.name,
                  prerequisite_task_id: prerequisiteTask.id,
                  prerequisite_task_name: prerequisiteTask.name,
                  prerequisite_status: prerequisiteTask.status,
                  warning_type: 'start_before_prerequisite_finish',
                  message: `任务"${task.name}"计划开始时间早于前置任务"${prerequisiteTask.name}"的计划完成时间`
                });
              }
            }
          }
        }
      }
    }

    return { tasks, dependency_warnings: warnings };
  };

  return addDelayWarnings(ganttTasks);
};

const addTaskDependency = async (taskId, prerequisiteTaskId, dependencyType = 'finish_to_start', lagDays = 0) => {
  const connection = await db.getConnection();
  try {
    await connection.beginTransaction();

    const [tasks] = await connection.execute(
      'SELECT task_id, title FROM tasks WHERE task_id = ?',
      [taskId]
    );
    if (tasks.length === 0) {
      throw new Error('任务不存在');
    }

    const [prereqTasks] = await connection.execute(
      'SELECT task_id, title FROM tasks WHERE task_id = ?',
      [prerequisiteTaskId]
    );
    if (prereqTasks.length === 0) {
      throw new Error('前置任务不存在');
    }

    const [existingDeps] = await connection.execute(
      'SELECT dependency_id FROM task_dependencies WHERE task_id = ? AND prerequisite_task_id = ?',
      [taskId, prerequisiteTaskId]
    );
    if (existingDeps.length > 0) {
      throw new Error('该依赖关系已存在');
    }

    if (await wouldCreateCycle(connection, taskId, prerequisiteTaskId)) {
      throw new Error('无法创建依赖关系：会形成循环依赖');
    }

    const dependencyId = uuidv4();
    await connection.execute(
      `INSERT INTO task_dependencies 
       (dependency_id, task_id, prerequisite_task_id, dependency_type, lag_days) 
       VALUES (?, ?, ?, ?, ?)`,
      [dependencyId, taskId, prerequisiteTaskId, dependencyType, lagDays]
    );

    await connection.commit();

    return {
      dependency_id: dependencyId,
      task_id: taskId,
      prerequisite_task_id: prerequisiteTaskId,
      dependency_type: dependencyType,
      lag_days: lagDays
    };
  } catch (error) {
    await connection.rollback();
    throw error;
  } finally {
    connection.release();
  }
};

const wouldCreateCycle = async (connection, taskId, prerequisiteTaskId) => {
  const visited = new Set();
  const queue = [prerequisiteTaskId];

  while (queue.length > 0) {
    const currentId = queue.shift();
    
    if (currentId === taskId) {
      return true;
    }
    
    if (visited.has(currentId)) {
      continue;
    }
    visited.add(currentId);

    const [deps] = await connection.execute(
      'SELECT prerequisite_task_id FROM task_dependencies WHERE task_id = ?',
      [currentId]
    );

    for (const dep of deps) {
      queue.push(dep.prerequisite_task_id);
    }
  }

  return false;
};

const removeTaskDependency = async (taskId, prerequisiteTaskId) => {
  const [result] = await db.execute(
    'DELETE FROM task_dependencies WHERE task_id = ? AND prerequisite_task_id = ?',
    [taskId, prerequisiteTaskId]
  );
  
  return result.affectedRows > 0;
};

const checkDependencyWarnings = async (taskId) => {
  const [warnings] = await db.execute(
    `SELECT 
      td.dependency_id,
      td.task_id,
      td.prerequisite_task_id,
      t.title as task_title,
      pt.title as prerequisite_title,
      pt.status as prerequisite_status,
      pt.progress as prerequisite_progress,
      td.dependency_type,
      CASE 
        WHEN pt.status != 'completed' AND t.status = 'in_progress' THEN 'active_warning'
        WHEN pt.status != 'completed' AND DATEDIFF(t.created_at, pt.due_date) < 0 THEN 'potential_delay'
        ELSE 'ok'
      END as warning_level
     FROM task_dependencies td
     JOIN tasks t ON td.task_id = t.task_id
     JOIN tasks pt ON td.prerequisite_task_id = pt.task_id
     WHERE td.task_id = ?`,
    [taskId]
  );

  return warnings;
};

module.exports = {
  createTask,
  updateTaskStatus,
  getTasks,
  getTaskById,
  getGanttData,
  addTaskDependency,
  removeTaskDependency,
  checkDependencyWarnings
};
