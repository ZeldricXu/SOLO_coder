const nodemailer = require('nodemailer');
const db = require('../config/database');
const { v4: uuidv4 } = require('uuid');
require('dotenv').config();

let io = null;
const notificationQueue = new Map();

const initializeSocketIO = (socketIO) => {
  io = socketIO;
};

const transporter = nodemailer.createTransport({
  host: process.env.SMTP_HOST || 'smtp.example.com',
  port: parseInt(process.env.SMTP_PORT) || 587,
  secure: false,
  auth: {
    user: process.env.SMTP_USER || 'noreply@taskflow.com',
    pass: process.env.SMTP_PASS || 'password'
  }
});

const sendEmail = async (to, subject, htmlContent) => {
  try {
    const mailOptions = {
      from: process.env.SMTP_FROM || 'noreply@taskflow.com',
      to: to,
      subject: subject,
      html: htmlContent
    };

    const info = await transporter.sendMail(mailOptions);
    console.log(`邮件发送成功: ${info.messageId}`);
    return { success: true, messageId: info.messageId };
  } catch (error) {
    console.error(`邮件发送失败: ${error.message}`);
    return { success: false, error: error.message };
  }
};

const queueNotification = (userId, notification) => {
  const now = Date.now();
  const queueKey = `${userId}_${notification.type}`;
  
  if (!notificationQueue.has(queueKey)) {
    notificationQueue.set(queueKey, {
      notifications: [],
      lastSent: 0,
      timer: null
    });
  }

  const queue = notificationQueue.get(queueKey);
  queue.notifications.push({ ...notification, timestamp: now });

  if (queue.timer) {
    clearTimeout(queue.timer);
  }

  queue.timer = setTimeout(async () => {
    await sendAggregatedNotifications(userId, queueKey);
  }, 5000);
};

const sendAggregatedNotifications = async (userId, queueKey) => {
  const queue = notificationQueue.get(queueKey);
  if (!queue || queue.notifications.length === 0) return;

  const notifications = [...queue.notifications];
  queue.notifications = [];
  queue.timer = null;
  queue.lastSent = Date.now();

  let aggregatedContent = '';
  let title = '';

  if (notifications.length === 1) {
    const n = notifications[0];
    title = n.title;
    aggregatedContent = n.content || '';
  } else {
    const type = notifications[0].type;
    const counts = {};
    notifications.forEach(n => {
      counts[n.title] = (counts[n.title] || 0) + 1;
    });

    title = `${notifications.length} 条新通知`;
    aggregatedContent = Object.entries(counts)
      .map(([t, count]) => `${t} (${count}次)`)
      .join('<br>');
  }

  await createDatabaseNotification(userId, {
    type: notifications[0].type,
    title: title,
    content: aggregatedContent,
    related_task_id: notifications[0].related_task_id,
    related_event_id: notifications[0].related_event_id
  });

  emitSocketNotification(userId, {
    type: notifications[0].type,
    title: title,
    content: aggregatedContent,
    related_task_id: notifications[0].related_task_id,
    related_event_id: notifications[0].related_event_id,
    count: notifications.length,
    created_at: new Date().toISOString()
  });
};

const emitSocketNotification = (userId, notification) => {
  if (io) {
    io.to(`user:${userId}`).emit('notification', notification);
    console.log(`Socket.IO 通知已发送给用户: ${userId}`);
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
    console.log(`数据库通知已创建: ${notificationId}`);
    return notificationId;
  } catch (error) {
    console.error(`创建数据库通知失败: ${error.message}`);
    return null;
  }
};

const notifyTaskCreated = async (task, creator) => {
  const notification = {
    type: 'task_created',
    title: `新任务创建: ${task.title}`,
    content: `由 ${creator.username} 创建的新任务，截止日期: ${task.due_date}`,
    related_task_id: task.task_id
  };

  if (task.assignees && task.assignees.length > 0) {
    for (const assigneeId of task.assignees) {
      queueNotification(assigneeId, notification);
      
      const [users] = await db.execute(
        'SELECT email, username FROM users WHERE user_id = ?',
        [assigneeId]
      );
      
      if (users.length > 0) {
        const user = users[0];
        const emailContent = `
          <h3>您好，${user.username}</h3>
          <p>有一个新任务分配给您：</p>
          <p><strong>任务名称:</strong> ${task.title}</p>
          <p><strong>截止日期:</strong> ${task.due_date}</p>
          <p><strong>创建者:</strong> ${creator.username}</p>
          <p>请登录 TaskFlow 查看详情。</p>
        `;
        await sendEmail(user.email, `新任务分配: ${task.title}`, emailContent);
      }
    }
  }
};

const notifyTaskStatusChanged = async (task, changer, oldStatus, newStatus) => {
  const statusMap = {
    'todo': '待办',
    'in_progress': '进行中',
    'completed': '已完成',
    'cancelled': '已取消'
  };

  const notification = {
    type: 'task_status_changed',
    title: `任务状态变更: ${task.title}`,
    content: `${changer.username} 将任务状态从 ${statusMap[oldStatus]} 更改为 ${statusMap[newStatus]}`,
    related_task_id: task.task_id
  };

  const [assignments] = await db.execute(
    'SELECT user_id FROM task_assignments WHERE task_id = ?',
    [task.task_id]
  );

  const assigneeIds = assignments.map(a => a.user_id);
  
  for (const assigneeId of assigneeIds) {
    if (assigneeId !== changer.user_id) {
      queueNotification(assigneeId, notification);
      
      const [users] = await db.execute(
        'SELECT email, username FROM users WHERE user_id = ?',
        [assigneeId]
      );
      
      if (users.length > 0) {
        const user = users[0];
        const emailContent = `
          <h3>您好，${user.username}</h3>
          <p>您参与的任务状态已变更：</p>
          <p><strong>任务名称:</strong> ${task.title}</p>
          <p><strong>变更者:</strong> ${changer.username}</p>
          <p><strong>原状态:</strong> ${statusMap[oldStatus]}</p>
          <p><strong>新状态:</strong> ${statusMap[newStatus]}</p>
          <p>请登录 TaskFlow 查看详情。</p>
        `;
        await sendEmail(user.email, `任务状态变更: ${task.title}`, emailContent);
      }
    }
  }
};

const notifyEventCreated = async (event, creator) => {
  const notification = {
    type: 'event_created',
    title: `新日程: ${event.title}`,
    content: `${creator.username} 创建了新日程，时间: ${event.start_time}`,
    related_event_id: event.event_id,
    related_task_id: event.related_task_id
  };

  if (event.participants && event.participants.length > 0) {
    for (const participantId of event.participants) {
      queueNotification(participantId, notification);
      
      const [users] = await db.execute(
        'SELECT email, username FROM users WHERE user_id = ?',
        [participantId]
      );
      
      if (users.length > 0) {
        const user = users[0];
        const emailContent = `
          <h3>您好，${user.username}</h3>
          <p>有一个新日程邀请您参加：</p>
          <p><strong>日程名称:</strong> ${event.title}</p>
          <p><strong>开始时间:</strong> ${event.start_time}</p>
          <p><strong>结束时间:</strong> ${event.end_time}</p>
          ${event.location ? `<p><strong>地点:</strong> ${event.location}</p>` : ''}
          <p><strong>创建者:</strong> ${creator.username}</p>
          <p>请登录 TaskFlow 查看详情。</p>
        `;
        await sendEmail(user.email, `新日程邀请: ${event.title}`, emailContent);
      }
    }
  }
};

const getUnreadNotifications = async (userId) => {
  const [notifications] = await db.execute(
    `SELECT notification_id, type, title, content, related_task_id, related_event_id, created_at 
     FROM notifications 
     WHERE user_id = ? AND is_read = FALSE 
     ORDER BY created_at DESC`,
    [userId]
  );
  return notifications;
};

const markNotificationAsRead = async (notificationId, userId) => {
  const [result] = await db.execute(
    'UPDATE notifications SET is_read = TRUE WHERE notification_id = ? AND user_id = ?',
    [notificationId, userId]
  );
  return result.affectedRows > 0;
};

module.exports = {
  initializeSocketIO,
  sendEmail,
  notifyTaskCreated,
  notifyTaskStatusChanged,
  notifyEventCreated,
  getUnreadNotifications,
  markNotificationAsRead
};
