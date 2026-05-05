const db = require('../config/database');
const { v4: uuidv4 } = require('uuid');
const nodemailer = require('nodemailer');
require('dotenv').config();

let io = null;
let isRunning = false;
let pollInterval = null;
const MAX_RETRY_COUNT = 3;
const POLL_INTERVAL_MS = 5000;

const transporter = nodemailer.createTransport({
  host: process.env.SMTP_HOST || 'smtp.example.com',
  port: parseInt(process.env.SMTP_PORT) || 587,
  secure: false,
  auth: {
    user: process.env.SMTP_USER || 'noreply@taskflow.com',
    pass: process.env.SMTP_PASS || 'password'
  }
});

const initializeSocketIO = (socketIO) => {
  io = socketIO;
};

const enqueueNotification = async (type, payload, priority = 0, scheduledAt = null) => {
  const queueId = uuidv4();
  
  try {
    await db.execute(
      `INSERT INTO notification_queue 
       (queue_id, type, payload, status, priority, scheduled_at) 
       VALUES (?, ?, ?, ?, ?, ?)`,
      [
        queueId, 
        type, 
        JSON.stringify(payload), 
        'pending', 
        priority,
        scheduledAt
      ]
    );

    await createQueueLog(queueId, 'pending', '通知已加入队列');
    console.log(`[NotificationWorker] 通知已入队: ${queueId}, 类型: ${type}`);
    
    return queueId;
  } catch (error) {
    console.error('[NotificationWorker] 入队失败:', error);
    throw error;
  }
};

const createQueueLog = async (queueId, status, message) => {
  const logId = uuidv4();
  try {
    await db.execute(
      `INSERT INTO notification_queue_log (log_id, queue_id, status, message) 
       VALUES (?, ?, ?, ?)`,
      [logId, queueId, status, message]
    );
  } catch (error) {
    console.error('[NotificationWorker] 创建日志失败:', error);
  }
};

const updateQueueStatus = async (queueId, status, errorMessage = null) => {
  try {
    await db.execute(
      `UPDATE notification_queue 
       SET status = ?, 
           processed_at = CASE WHEN ? IN ('completed', 'failed') THEN NOW() ELSE processed_at END,
           error_message = ?
       WHERE queue_id = ?`,
      [status, status, errorMessage, queueId]
    );

    await createQueueLog(queueId, status, errorMessage || `状态更新为: ${status}`);
  } catch (error) {
    console.error('[NotificationWorker] 更新状态失败:', error);
  }
};

const getPendingNotifications = async () => {
  try {
    const [notifications] = await db.execute(
      `SELECT * FROM notification_queue 
       WHERE status = 'pending' 
         AND (scheduled_at IS NULL OR scheduled_at <= NOW())
       ORDER BY priority DESC, created_at ASC
       LIMIT 10`,
      []
    );
    return notifications;
  } catch (error) {
    console.error('[NotificationWorker] 获取待处理通知失败:', error);
    return [];
  }
};

const processNotification = async (notification) => {
  const { queue_id, type, payload } = notification;
  
  try {
    await updateQueueStatus(queue_id, 'processing');
    console.log(`[NotificationWorker] 开始处理通知: ${queue_id}, 类型: ${type}`);

    let result;
    switch (type) {
      case 'task_created':
        result = await processTaskCreatedNotification(payload);
        break;
      case 'task_assigned':
        result = await processTaskAssignedNotification(payload);
        break;
      case 'task_status_changed':
        result = await processTaskStatusChangedNotification(payload);
        break;
      case 'event_created':
        result = await processEventCreatedNotification(payload);
        break;
      case 'system':
        result = await processSystemNotification(payload);
        break;
      default:
        throw new Error(`未知的通知类型: ${type}`);
    }

    await updateQueueStatus(queue_id, 'completed');
    console.log(`[NotificationWorker] 通知处理完成: ${queue_id}`);
    return { success: true, ...result };

  } catch (error) {
    console.error(`[NotificationWorker] 通知处理失败: ${queue_id}`, error);
    
    const newRetryCount = notification.retry_count + 1;
    
    if (newRetryCount >= MAX_RETRY_COUNT) {
      await updateQueueStatus(queue_id, 'failed', error.message);
      console.error(`[NotificationWorker] 通知已达到最大重试次数: ${queue_id}`);
    } else {
      await db.execute(
        `UPDATE notification_queue 
         SET status = 'pending', 
             retry_count = ?,
             error_message = ?
         WHERE queue_id = ?`,
        [newRetryCount, error.message, queue_id]
      );
      await createQueueLog(queue_id, 'pending', `重试 ${newRetryCount}/${MAX_RETRY_COUNT}: ${error.message}`);
    }

    return { success: false, error: error.message };
  }
};

const processTaskCreatedNotification = async (payload) => {
  const { task, creator, assignees } = payload;
  
  const results = [];
  
  for (const assignee of assignees) {
    try {
      const emailContent = generateTaskCreatedEmail(task, creator, assignee);
      await sendEmail(assignee.email, `新任务分配: ${task.title}`, emailContent);
      
      emitSocketNotification(assignee.user_id, {
        type: 'task_created',
        title: `新任务创建: ${task.title}`,
        content: `由 ${creator.username} 创建的新任务，截止日期: ${task.due_date}`,
        related_task_id: task.task_id,
        created_at: new Date().toISOString()
      });

      await createDatabaseNotification(assignee.user_id, {
        type: 'task_created',
        title: `新任务创建: ${task.title}`,
        content: `由 ${creator.username} 创建的新任务，截止日期: ${task.due_date}`,
        related_task_id: task.task_id
      });

      results.push({ user_id: assignee.user_id, success: true });
    } catch (error) {
      results.push({ user_id: assignee.user_id, success: false, error: error.message });
    }
  }

  return { results };
};

const processTaskAssignedNotification = async (payload) => {
  return processTaskCreatedNotification(payload);
};

const processTaskStatusChangedNotification = async (payload) => {
  const { task, changer, oldStatus, newStatus, assignees } = payload;
  
  const statusMap = {
    'todo': '待办',
    'in_progress': '进行中',
    'completed': '已完成',
    'cancelled': '已取消'
  };

  const results = [];
  
  for (const assignee of assignees) {
    if (assignee.user_id === changer.user_id) continue;
    
    try {
      const emailContent = generateTaskStatusChangedEmail(task, changer, oldStatus, newStatus, assignee, statusMap);
      await sendEmail(assignee.email, `任务状态变更: ${task.title}`, emailContent);
      
      emitSocketNotification(assignee.user_id, {
        type: 'task_status_changed',
        title: `任务状态变更: ${task.title}`,
        content: `${changer.username} 将任务状态从 ${statusMap[oldStatus]} 更改为 ${statusMap[newStatus]}`,
        related_task_id: task.task_id,
        created_at: new Date().toISOString()
      });

      await createDatabaseNotification(assignee.user_id, {
        type: 'task_status_changed',
        title: `任务状态变更: ${task.title}`,
        content: `${changer.username} 将任务状态从 ${statusMap[oldStatus]} 更改为 ${statusMap[newStatus]}`,
        related_task_id: task.task_id
      });

      results.push({ user_id: assignee.user_id, success: true });
    } catch (error) {
      results.push({ user_id: assignee.user_id, success: false, error: error.message });
    }
  }

  return { results };
};

const processEventCreatedNotification = async (payload) => {
  const { event, creator, participants } = payload;
  
  const results = [];
  
  for (const participant of participants) {
    try {
      const emailContent = generateEventCreatedEmail(event, creator, participant);
      await sendEmail(participant.email, `新日程邀请: ${event.title}`, emailContent);
      
      emitSocketNotification(participant.user_id, {
        type: 'event_created',
        title: `新日程: ${event.title}`,
        content: `${creator.username} 创建了新日程，时间: ${event.start_time}`,
        related_event_id: event.event_id,
        related_task_id: event.related_task_id,
        created_at: new Date().toISOString()
      });

      await createDatabaseNotification(participant.user_id, {
        type: 'event_created',
        title: `新日程: ${event.title}`,
        content: `${creator.username} 创建了新日程，时间: ${event.start_time}`,
        related_event_id: event.event_id,
        related_task_id: event.related_task_id
      });

      results.push({ user_id: participant.user_id, success: true });
    } catch (error) {
      results.push({ user_id: participant.user_id, success: false, error: error.message });
    }
  }

  return { results };
};

const processSystemNotification = async (payload) => {
  const { users, title, content } = payload;
  
  const results = [];
  
  for (const user of users) {
    try {
      emitSocketNotification(user.user_id, {
        type: 'system',
        title,
        content,
        created_at: new Date().toISOString()
      });

      await createDatabaseNotification(user.user_id, {
        type: 'system',
        title,
        content
      });

      results.push({ user_id: user.user_id, success: true });
    } catch (error) {
      results.push({ user_id: user.user_id, success: false, error: error.message });
    }
  }

  return { results };
};

const sendEmail = async (to, subject, htmlContent) => {
  try {
    const mailOptions = {
      from: process.env.SMTP_FROM || 'noreply@taskflow.com',
      to: to,
      subject: subject,
      html: htmlContent
    };

    const info = await transporter.sendMail(mailOptions);
    console.log(`[NotificationWorker] 邮件发送成功: ${info.messageId} -> ${to}`);
    return { success: true, messageId: info.messageId };
  } catch (error) {
    console.error(`[NotificationWorker] 邮件发送失败: ${error.message}`);
    throw error;
  }
};

const emitSocketNotification = (userId, notification) => {
  if (io) {
    io.to(`user:${userId}`).emit('notification', notification);
    console.log(`[NotificationWorker] Socket.IO 通知已发送给用户: ${userId}`);
  }
};

const createDatabaseNotification = async (userId, notification) => {
  const notificationId = uuidv4();
  const { type, title, content, related_task_id, related_event_id } = notification;

  try {
    await db.execute(
      `INSERT INTO notifications 
       (notification_id, user_id, type, title, content, related_task_id, related_event_id) 
       VALUES (?, ?, ?, ?, ?, ?, ?)`,
      [notificationId, userId, type, title, content, related_task_id, related_event_id]
    );
    return notificationId;
  } catch (error) {
    console.error('[NotificationWorker] 创建数据库通知失败:', error);
    throw error;
  }
};

const generateTaskCreatedEmail = (task, creator, assignee) => {
  return `
    <!DOCTYPE html>
    <html>
    <head>
      <meta charset="UTF-8">
      <style>
        body { font-family: Arial, sans-serif; line-height: 1.6; color: #333; }
        .container { max-width: 600px; margin: 0 auto; padding: 20px; }
        .header { background: #1890ff; color: white; padding: 20px; text-align: center; border-radius: 5px; }
        .content { padding: 20px; background: #f9f9f9; border-radius: 5px; margin-top: 20px; }
        .task-info { background: white; padding: 15px; border-radius: 5px; margin-top: 10px; }
        .task-info p { margin: 8px 0; }
        .footer { text-align: center; margin-top: 20px; color: #999; font-size: 12px; }
      </style>
    </head>
    <body>
      <div class="container">
        <div class="header">
          <h2>🎯 TaskFlow - 新任务分配</h2>
        </div>
        <div class="content">
          <p>您好，<strong>${assignee.username}</strong>：</p>
          <p>有一个新任务分配给您，请查看详情：</p>
          
          <div class="task-info">
            <p><strong>📋 任务名称:</strong> ${task.title}</p>
            ${task.description ? `<p><strong>📝 任务描述:</strong> ${task.description}</p>` : ''}
            <p><strong>📅 截止日期:</strong> ${task.due_date}</p>
            <p><strong>👤 创建者:</strong> ${creator.username}</p>
          </div>
          
          <p style="margin-top: 20px;">请登录 TaskFlow 系统查看详情并处理。</p>
        </div>
        <div class="footer">
          <p>此邮件由系统自动发送，请勿回复。</p>
          <p>TaskFlow 团队任务协作管理平台</p>
        </div>
      </div>
    </body>
    </html>
  `;
};

const generateTaskStatusChangedEmail = (task, changer, oldStatus, newStatus, assignee, statusMap) => {
  return `
    <!DOCTYPE html>
    <html>
    <head>
      <meta charset="UTF-8">
      <style>
        body { font-family: Arial, sans-serif; line-height: 1.6; color: #333; }
        .container { max-width: 600px; margin: 0 auto; padding: 20px; }
        .header { background: #52c41a; color: white; padding: 20px; text-align: center; border-radius: 5px; }
        .content { padding: 20px; background: #f9f9f9; border-radius: 5px; margin-top: 20px; }
        .task-info { background: white; padding: 15px; border-radius: 5px; margin-top: 10px; }
        .status-change { display: flex; align-items: center; gap: 10px; margin: 15px 0; }
        .status-badge { padding: 5px 12px; border-radius: 15px; font-weight: bold; }
        .old-status { background: #d9d9d9; color: #666; }
        .new-status { background: #52c41a; color: white; }
        .arrow { font-size: 24px; color: #1890ff; }
        .footer { text-align: center; margin-top: 20px; color: #999; font-size: 12px; }
      </style>
    </head>
    <body>
      <div class="container">
        <div class="header">
          <h2>🔄 TaskFlow - 任务状态变更</h2>
        </div>
        <div class="content">
          <p>您好，<strong>${assignee.username}</strong>：</p>
          <p>您参与的任务状态已发生变更：</p>
          
          <div class="task-info">
            <p><strong>📋 任务名称:</strong> ${task.title}</p>
            <p><strong>👤 变更者:</strong> ${changer.username}</p>
            
            <div class="status-change">
              <span class="status-badge old-status">${statusMap[oldStatus]}</span>
              <span class="arrow">→</span>
              <span class="status-badge new-status">${statusMap[newStatus]}</span>
            </div>
          </div>
          
          <p style="margin-top: 20px;">请登录 TaskFlow 系统查看详情。</p>
        </div>
        <div class="footer">
          <p>此邮件由系统自动发送，请勿回复。</p>
          <p>TaskFlow 团队任务协作管理平台</p>
        </div>
      </div>
    </body>
    </html>
  `;
};

const generateEventCreatedEmail = (event, creator, participant) => {
  return `
    <!DOCTYPE html>
    <html>
    <head>
      <meta charset="UTF-8">
      <style>
        body { font-family: Arial, sans-serif; line-height: 1.6; color: #333; }
        .container { max-width: 600px; margin: 0 auto; padding: 20px; }
        .header { background: #722ed1; color: white; padding: 20px; text-align: center; border-radius: 5px; }
        .content { padding: 20px; background: #f9f9f9; border-radius: 5px; margin-top: 20px; }
        .event-info { background: white; padding: 15px; border-radius: 5px; margin-top: 10px; }
        .event-info p { margin: 8px 0; }
        .footer { text-align: center; margin-top: 20px; color: #999; font-size: 12px; }
      </style>
    </head>
    <body>
      <div class="container">
        <div class="header">
          <h2>📅 TaskFlow - 新日程邀请</h2>
        </div>
        <div class="content">
          <p>您好，<strong>${participant.username}</strong>：</p>
          <p>${creator.username} 邀请您参加一个新日程：</p>
          
          <div class="event-info">
            <p><strong>📌 日程名称:</strong> ${event.title}</p>
            ${event.description ? `<p><strong>📝 日程描述:</strong> ${event.description}</p>` : ''}
            <p><strong>🕐 开始时间:</strong> ${event.start_time}</p>
            <p><strong>🕒 结束时间:</strong> ${event.end_time}</p>
            ${event.location ? `<p><strong>📍 地点:</strong> ${event.location}</p>` : ''}
            ${event.related_task_id ? `<p><strong>🔗 关联任务:</strong> 已关联到任务</p>` : ''}
          </div>
          
          <p style="margin-top: 20px;">请登录 TaskFlow 系统查看详情。</p>
        </div>
        <div class="footer">
          <p>此邮件由系统自动发送，请勿回复。</p>
          <p>TaskFlow 团队任务协作管理平台</p>
        </div>
      </div>
    </body>
    </html>
  `;
};

const startWorker = () => {
  if (isRunning) {
    console.log('[NotificationWorker] Worker 已在运行中');
    return;
  }

  isRunning = true;
  console.log('[NotificationWorker] Worker 已启动');

  pollInterval = setInterval(async () => {
    if (!isRunning) return;

    const notifications = await getPendingNotifications();
    
    if (notifications.length > 0) {
      console.log(`[NotificationWorker] 待处理通知数: ${notifications.length}`);
      
      for (const notification of notifications) {
        await processNotification(notification);
      }
    }
  }, POLL_INTERVAL_MS);
};

const stopWorker = () => {
  isRunning = false;
  if (pollInterval) {
    clearInterval(pollInterval);
    pollInterval = null;
  }
  console.log('[NotificationWorker] Worker 已停止');
};

const getQueueStats = async () => {
  try {
    const [stats] = await db.execute(
      `SELECT 
        status,
        COUNT(*) as count
       FROM notification_queue 
       GROUP BY status`
    );
    return stats;
  } catch (error) {
    console.error('[NotificationWorker] 获取统计失败:', error);
    return [];
  }
};

module.exports = {
  initializeSocketIO,
  enqueueNotification,
  startWorker,
  stopWorker,
  getQueueStats,
  processNotification,
  sendEmail
};
