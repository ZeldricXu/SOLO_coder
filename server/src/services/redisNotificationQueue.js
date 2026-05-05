const Queue = require('bull');
const { v4: uuidv4 } = require('uuid');
const nodemailer = require('nodemailer');
const db = require('../config/database');
const { getBullConfig, getRedisClient, isRedisAvailable } = require('../config/redis');
require('dotenv').config();

let io = null;
let notificationQueue = null;
let isInitialized = false;

const QUEUE_NAME = 'taskflow-notifications';
const MAX_RETRIES = parseInt(process.env.NOTIFICATION_MAX_RETRIES) || 5;
const RETRY_DELAY = parseInt(process.env.NOTIFICATION_RETRY_DELAY) || 30000;
const BATCH_SIZE = parseInt(process.env.NOTIFICATION_BATCH_SIZE) || 10;

const inMemoryQueue = [];
let inMemoryWorkerInterval = null;

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

const initializeQueue = async () => {
  if (isInitialized) {
    return;
  }

  try {
    const redisClient = await getRedisClient();
    
    if (redisClient && isRedisAvailable()) {
      console.log('[RedisNotificationQueue] 正在初始化 Bull 队列...');
      
      const bullConfig = getBullConfig();
      
      notificationQueue = new Queue(QUEUE_NAME, bullConfig);
      
      notificationQueue.on('completed', async (job, result) => {
        console.log(`[RedisNotificationQueue] 任务完成: ${job.id}`);
        await updateNotificationStatus(job.id, 'completed');
      });

      notificationQueue.on('failed', async (job, error) => {
        console.error(`[RedisNotificationQueue] 任务失败: ${job.id}`, error.message);
        
        const retryCount = job.attemptsMade;
        if (retryCount >= MAX_RETRIES) {
          console.error(`[RedisNotificationQueue] 任务已达到最大重试次数: ${job.id}`);
          await updateNotificationStatus(job.id, 'failed', error.message);
        }
      });

      notificationQueue.on('stalled', (jobId) => {
        console.warn(`[RedisNotificationQueue] 任务停滞: ${jobId}`);
      });

      notificationQueue.process('send_email', BATCH_SIZE, async (job) => {
        return processNotificationJob(job.data);
      });

      notificationQueue.process('process_system', BATCH_SIZE, async (job) => {
        return processSystemNotification(job.data);
      });

      isInitialized = true;
      console.log('[RedisNotificationQueue] Bull 队列初始化成功');
      
    } else {
      console.log('[RedisNotificationQueue] Redis 不可用，使用内存队列');
      startInMemoryWorker();
      isInitialized = true;
    }

  } catch (error) {
    console.error('[RedisNotificationQueue] 队列初始化失败:', error.message);
    console.log('[RedisNotificationQueue] 回退到内存队列');
    startInMemoryWorker();
    isInitialized = true;
  }
};

const startInMemoryWorker = () => {
  if (inMemoryWorkerInterval) {
    return;
  }

  console.log('[RedisNotificationQueue] 内存队列 Worker 已启动');
  
  inMemoryWorkerInterval = setInterval(async () => {
    if (inMemoryQueue.length === 0) {
      return;
    }

    const jobsToProcess = inMemoryQueue.splice(0, BATCH_SIZE);
    
    for (const job of jobsToProcess) {
      try {
        const result = await processNotificationJob(job.data);
        console.log(`[RedisNotificationQueue] 内存队列任务完成: ${job.id}`);
        await updateNotificationStatus(job.id, 'completed');
      } catch (error) {
        console.error(`[RedisNotificationQueue] 内存队列任务失败: ${job.id}`, error.message);
        
        job.retryCount = (job.retryCount || 0) + 1;
        
        if (job.retryCount < MAX_RETRIES) {
          console.log(`[RedisNotificationQueue] 任务将重试 (${job.retryCount}/${MAX_RETRIES}): ${job.id}`);
          inMemoryQueue.push(job);
        } else {
          console.error(`[RedisNotificationQueue] 任务已达到最大重试次数: ${job.id}`);
          await updateNotificationStatus(job.id, 'failed', error.message);
        }
      }
    }
  }, RETRY_DELAY);
};

const stopInMemoryWorker = () => {
  if (inMemoryWorkerInterval) {
    clearInterval(inMemoryWorkerInterval);
    inMemoryWorkerInterval = null;
    console.log('[RedisNotificationQueue] 内存队列 Worker 已停止');
  }
};

const enqueueNotification = async (type, payload, priority = 0, scheduledAt = null) => {
  const jobId = uuidv4();

  const jobData = {
    id: jobId,
    type: type,
    payload: payload,
    priority: priority,
    scheduledAt: scheduledAt,
    createdAt: new Date().toISOString()
  };

  await createQueueRecord(jobId, type, payload, priority, scheduledAt);

  if (!isInitialized) {
    await initializeQueue();
  }

  try {
    if (notificationQueue) {
      const options = {
        jobId: jobId,
        priority: 10 - priority,
        attempts: MAX_RETRIES,
        backoff: {
          type: 'exponential',
          delay: RETRY_DELAY
        }
      };

      if (scheduledAt) {
        options.delay = new Date(scheduledAt) - new Date();
      }

      await notificationQueue.add('send_email', jobData, options);
      console.log(`[RedisNotificationQueue] 通知已入队 (Bull): ${jobId}, 类型: ${type}`);
      
    } else {
      inMemoryQueue.push({
        id: jobId,
        data: jobData,
        retryCount: 0
      });
      console.log(`[RedisNotificationQueue] 通知已入队 (内存): ${jobId}, 类型: ${type}`);
    }

    return jobId;

  } catch (error) {
    console.error('[RedisNotificationQueue] 入队失败:', error.message);
    
    inMemoryQueue.push({
      id: jobId,
      data: jobData,
      retryCount: 0
    });
    console.log(`[RedisNotificationQueue] 通知已回退到内存队列: ${jobId}`);
    
    return jobId;
  }
};

const createQueueRecord = async (queueId, type, payload, priority, scheduledAt) => {
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
    console.log(`[RedisNotificationQueue] 数据库记录已创建: ${queueId}`);
    
  } catch (error) {
    console.error('[RedisNotificationQueue] 创建数据库记录失败:', error.message);
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
    console.error('[RedisNotificationQueue] 创建日志失败:', error.message);
  }
};

const updateNotificationStatus = async (queueId, status, errorMessage = null) => {
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
    console.log(`[RedisNotificationQueue] 状态已更新: ${queueId} -> ${status}`);
    
  } catch (error) {
    console.error('[RedisNotificationQueue] 更新状态失败:', error.message);
  }
};

const processNotificationJob = async (jobData) => {
  const { id: queueId, type, payload } = jobData;
  
  console.log(`[RedisNotificationQueue] 开始处理通知: ${queueId}, 类型: ${type}`);
  
  await updateNotificationStatus(queueId, 'processing');

  try {
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

    await updateNotificationStatus(queueId, 'completed');
    console.log(`[RedisNotificationQueue] 通知处理完成: ${queueId}`);
    
    return { success: true, ...result };

  } catch (error) {
    console.error(`[RedisNotificationQueue] 通知处理失败: ${queueId}`, error.message);
    throw error;
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
    console.log(`[RedisNotificationQueue] 邮件发送成功: ${info.messageId} -> ${to}`);
    return { success: true, messageId: info.messageId };
  } catch (error) {
    console.error(`[RedisNotificationQueue] 邮件发送失败: ${error.message}`);
    throw error;
  }
};

const emitSocketNotification = (userId, notification) => {
  if (io) {
    io.to(`user:${userId}`).emit('notification', notification);
    console.log(`[RedisNotificationQueue] Socket.IO 通知已发送给用户: ${userId}`);
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
    console.error('[RedisNotificationQueue] 创建数据库通知失败:', error.message);
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

const startWorker = async () => {
  await initializeQueue();
  console.log('[RedisNotificationQueue] Worker 已启动');
};

const stopWorker = async () => {
  stopInMemoryWorker();
  
  if (notificationQueue) {
    await notificationQueue.close();
    notificationQueue = null;
    console.log('[RedisNotificationQueue] Bull 队列已关闭');
  }
  
  isInitialized = false;
  console.log('[RedisNotificationQueue] Worker 已停止');
};

const getQueueStats = async () => {
  const stats = {
    redisAvailable: isRedisAvailable(),
    bullQueue: null,
    inMemoryQueue: null,
    database: null
  };

  try {
    const [dbStats] = await db.execute(
      `SELECT 
        status,
        COUNT(*) as count
       FROM notification_queue 
       GROUP BY status`
    );
    stats.database = dbStats;
  } catch (error) {
    console.error('[RedisNotificationQueue] 获取数据库统计失败:', error.message);
  }

  if (notificationQueue) {
    try {
      const [waiting, active, completed, failed, delayed] = await Promise.all([
        notificationQueue.getWaiting(),
        notificationQueue.getActive(),
        notificationQueue.getCompleted(),
        notificationQueue.getFailed(),
        notificationQueue.getDelayed()
      ]);
      
      stats.bullQueue = {
        waiting: waiting.length,
        active: active.length,
        completed: completed.length,
        failed: failed.length,
        delayed: delayed.length
      };
    } catch (error) {
      console.error('[RedisNotificationQueue] 获取 Bull 队列统计失败:', error.message);
    }
  }

  stats.inMemoryQueue = {
    count: inMemoryQueue.length
  };

  return stats;
};

const retryFailedNotifications = async () => {
  if (!isInitialized) {
    await initializeQueue();
  }

  try {
    const [failedJobs] = await db.execute(
      `SELECT queue_id, type, payload FROM notification_queue WHERE status = 'failed'`
    );

    const results = [];
    
    for (const job of failedJobs) {
      try {
        const payload = JSON.parse(job.payload);
        const newJobId = await enqueueNotification(job.type, payload, 5);
        
        await db.execute(
          `UPDATE notification_queue SET status = 'requeued' WHERE queue_id = ?`,
          [job.queue_id]
        );
        
        results.push({
          oldQueueId: job.queue_id,
          newQueueId: newJobId,
          type: job.type,
          success: true
        });
        
        console.log(`[RedisNotificationQueue] 失败任务已重新入队: ${job.queue_id} -> ${newJobId}`);
        
      } catch (error) {
        results.push({
          queueId: job.queue_id,
          type: job.type,
          success: false,
          error: error.message
        });
      }
    }

    return {
      total: failedJobs.length,
      requeued: results.filter(r => r.success).length,
      failed: results.filter(r => !r.success).length,
      details: results
    };

  } catch (error) {
    console.error('[RedisNotificationQueue] 重试失败任务出错:', error.message);
    throw error;
  }
};

module.exports = {
  initializeSocketIO,
  initializeQueue,
  enqueueNotification,
  startWorker,
  stopWorker,
  getQueueStats,
  retryFailedNotifications,
  processNotificationJob,
  sendEmail
};
