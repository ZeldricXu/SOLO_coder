import {
  NotificationService,
  Notification,
  NotificationTemplate,
  NotificationSender,
  TemplateRenderer,
  NotificationChannel
} from '../../core/ports';
import { generateId, logger, ContextLogger, RequestContext, ValidationError } from '../../common';

export class DefaultTemplateRenderer implements TemplateRenderer {
  render(template: NotificationTemplate, variables: Record<string, unknown>): {
    subject?: string;
    content: string;
  } {
    const renderedContent = this.replaceVariables(template.content, variables);
    const renderedSubject = template.subject
      ? this.replaceVariables(template.subject, variables)
      : undefined;

    return {
      subject: renderedSubject,
      content: renderedContent
    };
  }

  private replaceVariables(text: string, variables: Record<string, unknown>): string {
    return text.replace(/\$\{(\w+)\}/g, (match, key) => {
      const value = variables[key];
      return value !== undefined ? String(value) : match;
    });
  }
}

export class EmailSender implements NotificationSender {
  private transporter: any;

  constructor(smtpConfig: { host: string; port: number; user: string; pass: string }) {
    try {
      const nodemailer = require('nodemailer');
      this.transporter = nodemailer.createTransport({
        host: smtpConfig.host,
        port: smtpConfig.port,
        auth: {
          user: smtpConfig.user,
          pass: smtpConfig.pass
        }
      });
    } catch (e) {
      logger.warn('Nodemailer not available, using mock email sender');
      this.transporter = null;
    }
  }

  getChannel(): NotificationChannel {
    return 'email';
  }

  async send(notification: Notification): Promise<boolean> {
    logger.info('Sending email notification', {
      notificationId: notification.id,
      recipients: notification.recipients
    });

    if (!this.transporter) {
      logger.info('Mock email sent', notification);
      return true;
    }

    try {
      await this.transporter.sendMail({
        from: 'noreply@example.com',
        to: notification.recipients.join(','),
        subject: (notification.variables.subject as string) || 'Notification',
        html: notification.variables.content as string
      });
      return true;
    } catch (error) {
      logger.error('Failed to send email', { error: (error as Error).message });
      return false;
    }
  }
}

export class SmsSender implements NotificationSender {
  private apiKey: string;
  private apiUrl: string;

  constructor(apiKey: string, apiUrl: string = 'https://sms.example.com/api/send') {
    this.apiKey = apiKey;
    this.apiUrl = apiUrl;
  }

  getChannel(): NotificationChannel {
    return 'sms';
  }

  async send(notification: Notification): Promise<boolean> {
    logger.info('Sending SMS notification', {
      notificationId: notification.id,
      recipients: notification.recipients
    });

    try {
      const axios = require('axios');
      for (const recipient of notification.recipients) {
        await axios.post(this.apiUrl, {
          apiKey: this.apiKey,
          to: recipient,
          message: notification.variables.content
        });
      }
      return true;
    } catch (error) {
      logger.error('Failed to send SMS', { error: (error as Error).message });
      return false;
    }
  }
}

export class WebhookSender implements NotificationSender {
  getChannel(): NotificationChannel {
    return 'webhook';
  }

  async send(notification: Notification): Promise<boolean> {
    logger.info('Sending webhook notification', {
      notificationId: notification.id,
      recipients: notification.recipients
    });

    try {
      const axios = require('axios');
      for (const url of notification.recipients) {
        await axios.post(url, {
          notificationId: notification.id,
          templateId: notification.templateId,
          variables: notification.variables
        });
      }
      return true;
    } catch (error) {
      logger.error('Failed to send webhook', { error: (error as Error).message });
      return false;
    }
  }
}

export class SlackSender implements NotificationSender {
  private webhookUrl: string;

  constructor(webhookUrl: string) {
    this.webhookUrl = webhookUrl;
  }

  getChannel(): NotificationChannel {
    return 'slack';
  }

  async send(notification: Notification): Promise<boolean> {
    logger.info('Sending Slack notification', {
      notificationId: notification.id
    });

    try {
      const axios = require('axios');
      await axios.post(this.webhookUrl, {
        text: notification.variables.content as string
      });
      return true;
    } catch (error) {
      logger.error('Failed to send Slack notification', { error: (error as Error).message });
      return false;
    }
  }
}

export class DingTalkSender implements NotificationSender {
  private webhookUrl: string;
  private secret?: string;

  constructor(webhookUrl: string, secret?: string) {
    this.webhookUrl = webhookUrl;
    this.secret = secret;
  }

  getChannel(): NotificationChannel {
    return 'dingtalk';
  }

  async send(notification: Notification): Promise<boolean> {
    logger.info('Sending DingTalk notification', {
      notificationId: notification.id
    });

    try {
      const axios = require('axios');
      let url = this.webhookUrl;

      if (this.secret) {
        const crypto = require('crypto');
        const timestamp = Date.now();
        const stringToSign = `${timestamp}\n${this.secret}`;
        const sign = crypto
          .createHmac('sha256', this.secret)
          .update(stringToSign)
          .digest('base64');
        url = `${url}&timestamp=${timestamp}&sign=${encodeURIComponent(sign)}`;
      }

      await axios.post(url, {
        msgtype: 'text',
        text: {
          content: notification.variables.content as string
        }
      });
      return true;
    } catch (error) {
      logger.error('Failed to send DingTalk notification', { error: (error as Error).message });
      return false;
    }
  }
}

export class DefaultNotificationService implements NotificationService {
  private senders: Map<NotificationChannel, NotificationSender> = new Map();
  private templates: Map<string, NotificationTemplate> = new Map();
  private notifications: Map<string, Notification> = new Map();
  private renderer: TemplateRenderer;

  constructor() {
    this.renderer = new DefaultTemplateRenderer();
  }

  registerSender(sender: NotificationSender): void {
    this.senders.set(sender.getChannel(), sender);
  }

  setRenderer(renderer: TemplateRenderer): void {
    this.renderer = renderer;
  }

  async send(
    channel: NotificationChannel,
    templateId: string,
    recipients: string[],
    variables: Record<string, unknown>
  ): Promise<string> {
    const sender = this.senders.get(channel);
    if (!sender) {
      throw new ValidationError(`No sender registered for channel: ${channel}`);
    }

    const template = this.templates.get(templateId);
    if (!template) {
      throw new ValidationError(`Template not found: ${templateId}`);
    }

    const notificationId = generateId('notification');
    const rendered = this.renderer.render(template, variables);

    const notification: Notification = {
      id: notificationId,
      channel,
      templateId,
      recipients,
      variables: { ...variables, ...rendered },
      status: 'queued',
      created_at: new Date().toISOString()
    };

    this.notifications.set(notificationId, notification);

    try {
      notification.status = 'pending';
      const success = await sender.send(notification);

      if (success) {
        notification.status = 'sent';
        notification.sent_at = new Date().toISOString();
        logger.info('Notification sent successfully', { notificationId, channel });
      } else {
        notification.status = 'failed';
        notification.error_detail = 'Send operation returned false';
        logger.error('Notification send failed', { notificationId, channel });
      }
    } catch (error) {
      notification.status = 'failed';
      notification.error_detail = (error as Error).message;
      logger.error('Notification send error', { notificationId, channel, error: (error as Error).message });
    }

    return notificationId;
  }

  async getStatus(notificationId: string): Promise<Notification | null> {
    return this.notifications.get(notificationId) || null;
  }

  async registerTemplate(template: Omit<NotificationTemplate, 'id' | 'created_at' | 'updated_at'>): Promise<string> {
    const id = generateId('template');
    const now = new Date().toISOString();
    const newTemplate: NotificationTemplate = {
      ...template,
      id,
      created_at: now,
      updated_at: now
    };
    this.templates.set(id, newTemplate);
    logger.info('Notification template registered', { templateId: id, name: template.name });
    return id;
  }

  async getTemplate(templateId: string): Promise<NotificationTemplate | null> {
    return this.templates.get(templateId) || null;
  }

  async updateTemplate(templateId: string, updates: Partial<NotificationTemplate>): Promise<NotificationTemplate | null> {
    const existing = this.templates.get(templateId);
    if (!existing) {
      return null;
    }

    const updated: NotificationTemplate = {
      ...existing,
      ...updates,
      id: templateId,
      updated_at: new Date().toISOString()
    };

    this.templates.set(templateId, updated);
    return updated;
  }

  listTemplates(): NotificationTemplate[] {
    return Array.from(this.templates.values());
  }

  listNotifications(channel?: NotificationChannel, status?: string): Notification[] {
    return Array.from(this.notifications.values()).filter(n => {
      if (channel && n.channel !== channel) return false;
      if (status && n.status !== status) return false;
      return true;
    });
  }
}
