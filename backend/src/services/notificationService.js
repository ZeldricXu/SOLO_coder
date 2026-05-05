const notificationModel = require('../models/notificationModel');
const messageQueueService = require('./messageQueueService');
const config = require('../config');
const nodemailer = require('nodemailer');

const notificationService = {
  async createNotification(notificationData) {
    if (config.queue?.enabled) {
      await messageQueueService.enqueueNotification({
        ...notificationData,
        priority: notificationData.priority || 0,
        maxRetry: notificationData.maxRetry || 3
      });
    }
    
    return await notificationModel.create(notificationData);
  },

  async createNotificationAsync(notificationData) {
    const notification = await notificationModel.create({
      ...notificationData,
      status: 'pending'
    });

    if (config.queue?.enabled) {
      await messageQueueService.enqueueNotification({
        ...notificationData,
        notification_id: notification.notification_id,
        priority: notificationData.priority || 0,
        maxRetry: notificationData.maxRetry || 3
      });
    }

    return notification;
  },

  async sendNotificationImmediately(notificationData) {
    const notification = await notificationModel.create({
      ...notificationData,
      status: 'processing'
    });

    try {
      let result;
      if (notificationData.recipient_type === 'email') {
        result = await this.sendEmail(
          notificationData.recipient,
          notificationData.subject,
          notificationData.content
        );
      } else if (notificationData.recipient_type === 'sms') {
        result = await this.sendSMS(
          notificationData.recipient,
          '', {}
        );
      }

      if (result.success) {
        await notificationModel.updateStatus(notification.notification_id, 'sent', null);
      } else {
        await notificationModel.updateStatus(notification.notification_id, 'failed', result.error);
      }

      return result;
    } catch (error) {
      await notificationModel.updateStatus(notification.notification_id, 'failed', error.message);
      throw error;
    }
  },

  async sendEmail(to, subject, content) {
    try {
      const transporter = nodemailer.createTransport({
        host: config.email.host,
        port: config.email.port,
        secure: config.email.port === 465,
        auth: {
          user: config.email.user,
          pass: config.email.password
        }
      });

      const mailOptions = {
        from: `EventHub <${config.email.from}>`,
        to: to,
        subject: subject,
        html: this.generateEmailHtml(subject, content)
      };

      const info = await transporter.sendMail(mailOptions);
      console.log('Email sent:', info.messageId);
      return { success: true, messageId: info.messageId };
    } catch (error) {
      console.error('Failed to send email:', error);
      return { success: false, error: error.message };
    }
  },

  generateEmailHtml(subject, content) {
    return `
      <!DOCTYPE html>
      <html lang="zh-CN">
      <head>
        <meta charset="UTF-8">
        <meta name="viewport" content="width=device-width, initial-scale=1.0">
        <title>${subject}</title>
        <style>
          body { font-family: Arial, sans-serif; line-height: 1.6; color: #333; }
          .container { max-width: 600px; margin: 0 auto; padding: 20px; }
          .header { background: linear-gradient(135deg, #667eea 0%, #764ba2 100%); color: white; padding: 30px; text-align: center; border-radius: 8px 8px 0 0; }
          .content { background: #f9f9f9; padding: 30px; border-radius: 0 0 8px 8px; }
          .footer { text-align: center; padding: 20px; color: #888; font-size: 12px; }
        </style>
      </head>
      <body>
        <div class="container">
          <div class="header">
            <h1>EventHub</h1>
          </div>
          <div class="content">
            <h2>${subject}</h2>
            <div>${content.replace(/\n/g, '<br>')}</div>
          </div>
          <div class="footer">
            <p>这是一封来自 EventHub 的系统邮件，请勿直接回复。</p>
          </div>
        </div>
      </body>
      </html>
    `;
  },

  async sendSMS(phone, templateCode, params) {
    console.log(`Sending SMS to ${phone}, template: ${templateCode}, params:`, params);
    return { success: true, message: 'SMS sent (simulated)' };
  },

  async processPendingNotifications() {
    const pendingNotifications = await notificationModel.findByStatus('pending');
    
    for (const notification of pendingNotifications) {
      try {
        let result;
        if (notification.recipient_type === 'email') {
          result = await this.sendEmail(
            notification.recipient,
            notification.subject,
            notification.content
          );
        } else if (notification.recipient_type === 'sms') {
          result = await this.sendSMS(notification.recipient, '', {});
        }

        if (result.success) {
          await notificationModel.updateStatus(notification.notification_id, 'sent', null);
        } else {
          await notificationModel.updateStatus(notification.notification_id, 'failed', result.error);
        }
      } catch (error) {
        console.error('Failed to process notification:', notification.notification_id, error);
        await notificationModel.updateStatus(notification.notification_id, 'failed', error.message);
      }
    }
  },

  async sendRegistrationApprovalNotification(registration, event, options = {}) {
    const formData = typeof registration.form_data === 'string' 
      ? JSON.parse(registration.form_data) 
      : registration.form_data;
    
    const email = this.extractEmailFromFormData(formData);
    if (!email) {
      console.log('No email found in registration form data');
      return;
    }

    const notificationData = {
      registration_id: registration.registration_id,
      event_id: registration.event_id,
      notification_type: 'approved',
      recipient: email,
      recipient_type: 'email',
      subject: '报名审核通过 - ' + event.title,
      content: `
        尊敬的用户：

        恭喜！您的活动报名已审核通过。

        活动信息：
        - 活动名称：${event.title}
        - 开始时间：${event.start_time}
        - 活动地点：${event.location || '待定'}
        - 购买票务：${registration.ticket_name || '免费'}

        请准时参加活动，期待您的到来！

        此致
        EventHub 活动管理平台
      `
    };

    if (options.async !== false && config.queue?.enabled) {
      return await this.createNotificationAsync(notificationData);
    }
    return await this.createNotification(notificationData);
  },

  async sendRegistrationRejectionNotification(registration, event, reason, options = {}) {
    const formData = typeof registration.form_data === 'string' 
      ? JSON.parse(registration.form_data) 
      : registration.form_data;
    
    const email = this.extractEmailFromFormData(formData);
    if (!email) {
      console.log('No email found in registration form data');
      return;
    }

    const notificationData = {
      registration_id: registration.registration_id,
      event_id: registration.event_id,
      notification_type: 'rejected',
      recipient: email,
      recipient_type: 'email',
      subject: '报名审核结果 - ' + event.title,
      content: `
        尊敬的用户：

        很遗憾，您的活动报名未通过审核。

        活动信息：
        - 活动名称：${event.title}
        ${reason ? `- 审核原因：${reason}` : ''}

        感谢您的关注与支持！

        此致
        EventHub 活动管理平台
      `
    };

    if (options.async !== false && config.queue?.enabled) {
      return await this.createNotificationAsync(notificationData);
    }
    return await this.createNotification(notificationData);
  },

  async sendEventReminderNotification(registration, event, options = {}) {
    const formData = typeof registration.form_data === 'string' 
      ? JSON.parse(registration.form_data) 
      : registration.form_data;
    
    const email = this.extractEmailFromFormData(formData);
    if (!email) {
      console.log('No email found in registration form data');
      return;
    }

    const notificationData = {
      registration_id: registration.registration_id,
      event_id: registration.event_id,
      notification_type: 'event_reminder',
      recipient: email,
      recipient_type: 'email',
      subject: '活动提醒 - ' + event.title,
      content: `
        尊敬的用户：

        您报名的活动即将开始，特此提醒！

        活动信息：
        - 活动名称：${event.title}
        - 开始时间：${event.start_time}
        - 活动地点：${event.location || '待定'}
        - 购买票务：${registration.ticket_name || '免费'}

        请准时参加活动，期待您的到来！

        此致
        EventHub 活动管理平台
      `
    };

    if (options.async !== false && config.queue?.enabled) {
      return await this.createNotificationAsync(notificationData);
    }
    return await this.createNotification(notificationData);
  },

  async sendRegistrationSubmittedNotification(registration, event, options = {}) {
    const formData = typeof registration.form_data === 'string' 
      ? JSON.parse(registration.form_data) 
      : registration.form_data;
    
    const email = this.extractEmailFromFormData(formData);
    if (!email) {
      console.log('No email found in registration form data');
      return;
    }

    const notificationData = {
      registration_id: registration.registration_id,
      event_id: registration.event_id,
      notification_type: 'approval_pending',
      recipient: email,
      recipient_type: 'email',
      subject: '报名提交成功 - ' + event.title,
      content: `
        尊敬的用户：

        您的活动报名已提交成功！

        活动信息：
        - 活动名称：${event.title}
        - 开始时间：${event.start_time}
        - 购买票务：${registration.ticket_name || '免费'}

        ${registration.status === 'pending_review' 
          ? '您的报名正在等待审核，审核结果将通过邮件通知您。'
          : '您的报名已确认，请准时参加活动！'}

        此致
        EventHub 活动管理平台
      `
    };

    if (options.async !== false && config.queue?.enabled) {
      return await this.createNotificationAsync(notificationData);
    }
    return await this.createNotification(notificationData);
  },

  extractEmailFromFormData(formData) {
    if (!formData) return null;
    
    for (const key in formData) {
      if (key.toLowerCase().includes('email') && 
          typeof formData[key] === 'string' &&
          /^[^\s@]+@[^\s@]+\.[^\s@]+$/.test(formData[key])) {
        return formData[key];
      }
    }
    return null;
  },

  async getNotificationStats() {
    return {
      pending: await notificationModel.countByStatus('pending'),
      sent: await notificationModel.countByStatus('sent'),
      failed: await notificationModel.countByStatus('failed')
    };
  }
};

module.exports = notificationService;
