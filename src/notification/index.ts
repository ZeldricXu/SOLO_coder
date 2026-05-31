import EventEmitter from 'eventemitter3';
import { v4 as uuidv4 } from 'uuid';
import { Notification, NotificationTemplate, NotificationConfig, SendResult } from './types';
import { createChannel, NotificationChannel } from './channels';

export class NotificationService extends EventEmitter {
  private templates: Map<string, NotificationTemplate> = new Map();
  private channels: Map<string, { config: any; channel: NotificationChannel }> = new Map();
  private queue: Notification[] = [];
  private processing: boolean = false;
  private queueTimer?: NodeJS.Timeout;

  constructor(private config: NotificationConfig) {
    super();
    this.initializeChannels();
    this.initializeTemplates();
    this.startQueueProcessor();
  }

  private initializeChannels(): void {
    for (const channelConfig of this.config.channels) {
      if (channelConfig.enabled) {
        const channel = createChannel(channelConfig.type);
        this.channels.set(channelConfig.type, {
          config: channelConfig,
          channel,
        });
      }
    }
  }

  private initializeTemplates(): void {
    for (const template of this.config.templates) {
      this.templates.set(template.id, template);
    }
  }

  addTemplate(template: Omit<NotificationTemplate, 'created_at' | 'updated_at'>): NotificationTemplate {
    const now = new Date().toISOString();
    const newTemplate: NotificationTemplate = {
      ...template,
      created_at: now,
      updated_at: now,
    };
    this.templates.set(template.id, newTemplate);
    return newTemplate;
  }

  getTemplate(templateId: string): NotificationTemplate | undefined {
    return this.templates.get(templateId);
  }

  listTemplates(): NotificationTemplate[] {
    return Array.from(this.templates.values());
  }

  renderTemplate(templateId: string, data: Record<string, unknown>): { content: string; subject?: string } {
    const template = this.templates.get(templateId);
    if (!template) {
      throw new Error(`Template not found: ${templateId}`);
    }

    let content = template.content;
    let subject = template.subject;

    for (const [key, value] of Object.entries(data)) {
      const placeholder = `{{${key}}}`;
      const stringValue = typeof value === 'object' ? JSON.stringify(value) : String(value);
      content = content.replace(new RegExp(placeholder, 'g'), stringValue);
      if (subject) {
        subject = subject.replace(new RegExp(placeholder, 'g'), stringValue);
      }
    }

    return { content, subject };
  }

  async send(
    type: Notification['type'],
    recipient: string,
    templateId: string,
    data: Record<string, unknown> = {}
  ): Promise<Notification> {
    const template = this.templates.get(templateId);
    if (!template) {
      throw new Error(`Template not found: ${templateId}`);
    }

    const rendered = this.renderTemplate(templateId, data);

    const notification: Notification = {
      id: uuidv4(),
      type,
      recipient,
      template: templateId,
      data: {
        ...data,
        renderedContent: rendered.content,
        renderedSubject: rendered.subject,
      },
      status: 'pending',
      retryCount: 0,
      maxRetries: this.config.defaultMaxRetries,
      created_at: new Date().toISOString(),
      sent_at: null,
    };

    this.queue.push(notification);
    this.emit('queued', notification);

    return notification;
  }

  async sendBatch(
    notifications: Array<{
      type: Notification['type'];
      recipient: string;
      template: string;
      data: Record<string, unknown>;
    }>
  ): Promise<Notification[]> {
    const results: Notification[] = [];
    for (const n of notifications) {
      const notification = await this.send(n.type, n.recipient, n.template, n.data);
      results.push(notification);
    }
    return results;
  }

  private startQueueProcessor(): void {
    this.queueTimer = setInterval(() => {
      if (!this.processing && this.queue.length > 0) {
        this.processQueue();
      }
    }, 1000);
  }

  private async processQueue(): Promise<void> {
    this.processing = true;

    const batch = this.queue.splice(0, this.config.batchSize);
    const results: SendResult[] = [];

    for (const notification of batch) {
      try {
        const result = await this.sendNotification(notification);
        results.push(result);

        if (result.success) {
          notification.status = 'sent';
          notification.sent_at = new Date().toISOString();
          this.emit('sent', notification);
        } else {
          notification.status = 'failed';
          notification.error = result.error;
          this.emit('failed', notification);
        }
      } catch (error) {
        notification.status = 'failed';
        notification.error = error instanceof Error ? error.message : 'Unknown error';
        results.push({
          notificationId: notification.id,
          success: false,
          error: notification.error,
        });
        this.emit('failed', notification);
      }
    }

    this.emit('batch-complete', results);
    this.processing = false;
  }

  private async sendNotification(notification: Notification): Promise<SendResult> {
    const channelInfo = this.channels.get(notification.type);
    if (!channelInfo) {
      return {
        notificationId: notification.id,
        success: false,
        error: `Channel not enabled: ${notification.type}`,
      };
    }

    try {
      const success = await channelInfo.channel.send(notification, channelInfo.config);
      return {
        notificationId: notification.id,
        success,
        error: success ? undefined : 'Send failed',
      };
    } catch (error) {
      if (notification.retryCount < notification.maxRetries) {
        notification.retryCount++;
        setTimeout(() => {
          this.queue.push(notification);
        }, this.config.retryDelay * notification.retryCount);
      }

      return {
        notificationId: notification.id,
        success: false,
        error: error instanceof Error ? error.message : 'Unknown error',
      };
    }
  }

  getNotificationStatus(notificationId: string): Notification | undefined {
    return this.queue.find(n => n.id === notificationId);
  }

  getQueueSize(): number {
    return this.queue.length;
  }

  destroy(): void {
    if (this.queueTimer) {
      clearInterval(this.queueTimer);
    }
    this.removeAllListeners();
  }
}

export * from './types';
export * from './channels';
