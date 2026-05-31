import { EventEmitter } from 'events';
import { INotificationService, INotificationChannel } from '@ports/index';
import { Notification } from '@apptypes/index';
import { rootLogger } from '@modules/logging';
import { generateId, nowISO, retryAsync } from '@utils/index';
import { config } from '@config/index';
import axios from 'axios';

export class WebhookChannel implements INotificationChannel {
  private logger = rootLogger.child({ module: 'WebhookChannel' });

  async send(recipient: string, content: Record<string, unknown>): Promise<boolean> {
    try {
      const response = await axios.post(recipient, content, {
        timeout: 10000,
        headers: { 'Content-Type': 'application/json' },
      });
      return response.status >= 200 && response.status < 300;
    } catch (error) {
      this.logger.error('Webhook send failed', {
        url: recipient,
        error: (error as Error).message,
      });
      return false;
    }
  }

  getType(): string {
    return 'webhook';
  }
}

export class EmailChannel implements INotificationChannel {
  private logger = rootLogger.child({ module: 'EmailChannel' });

  async send(recipient: string, content: Record<string, unknown>): Promise<boolean> {
    this.logger.info('Email sent (simulated)', { recipient, subject: content.subject });
    return true;
  }

  getType(): string {
    return 'email';
  }
}

export class SlackChannel implements INotificationChannel {
  private logger = rootLogger.child({ module: 'SlackChannel' });

  async send(recipient: string, content: Record<string, unknown>): Promise<boolean> {
    this.logger.info('Slack message sent (simulated)', { channel: recipient, text: content.text });
    return true;
  }

  getType(): string {
    return 'slack';
  }
}

export class NotificationService implements INotificationService {
  private logger = rootLogger.child({ module: 'NotificationService' });
  private channels: Map<string, INotificationChannel> = new Map();
  private notifications: Map<string, Notification> = new Map();
  private eventEmitter: EventEmitter = new EventEmitter();
  private maxRetries: number;
  private retryDelayMs: number;

  constructor() {
    this.maxRetries = config.notification.maxRetries;
    this.retryDelayMs = config.notification.retryDelayMs;

    if (config.notification.channels.email.enabled) {
      this.registerChannel('email', new EmailChannel());
    }
    if (config.notification.channels.webhook.enabled) {
      this.registerChannel('webhook', new WebhookChannel());
    }
    if (config.notification.channels.slack.enabled) {
      this.registerChannel('slack', new SlackChannel());
    }
  }

  registerChannel(type: string, channel: INotificationChannel): void {
    this.channels.set(type, channel);
    this.logger.info('Notification channel registered', { type });
  }

  private async sendWithRetry(
    channel: INotificationChannel,
    recipient: string,
    content: Record<string, unknown>
  ): Promise<{ success: boolean; attempts: number }> {
    let attempts = 0;
    try {
      await retryAsync(
        async () => {
          attempts++;
          const success = await channel.send(recipient, content);
          if (!success) {
            throw new Error(`Channel ${channel.getType()} failed to send`);
          }
          return success;
        },
        this.maxRetries,
        this.retryDelayMs
      );
      return { success: true, attempts };
    } catch {
      return { success: false, attempts };
    }
  }

  async send(
    notificationData: Omit<Notification, 'id' | 'status' | 'retry_count' | 'created_at' | 'sent_at'>
  ): Promise<Notification> {
    const notification: Notification = {
      id: generateId('notif_'),
      ...notificationData,
      status: 'pending',
      retry_count: 0,
      created_at: nowISO(),
      sent_at: null,
    };

    this.notifications.set(notification.id, notification);
    this.eventEmitter.emit('notification.created', notification);

    const channel = this.channels.get(notification.type);
    if (!channel) {
      notification.status = 'failed';
      notification.error_detail = `No channel registered for type: ${notification.type}`;
      this.logger.error('No channel registered', { type: notification.type });
      return notification;
    }

    try {
      const result = await this.sendWithRetry(
        channel,
        notification.recipient,
        notification.content
      );

      notification.retry_count = result.attempts;

      if (result.success) {
        notification.status = 'sent';
        notification.sent_at = nowISO();
        this.eventEmitter.emit('notification.sent', notification);
        this.logger.info('Notification sent successfully', {
          id: notification.id,
          type: notification.type,
          attempts: result.attempts,
        });
      } else {
        notification.status = 'failed';
        this.eventEmitter.emit('notification.failed', notification);
        this.logger.error('Notification failed after retries', {
          id: notification.id,
          type: notification.type,
          attempts: result.attempts,
        });
      }
    } catch (error) {
      notification.status = 'failed';
      notification.error_detail = (error as Error).message;
      this.logger.error('Notification send error', {
        id: notification.id,
        error: (error as Error).message,
      });
    }

    return notification;
  }

  async getStatus(id: string): Promise<Notification | null> {
    return this.notifications.get(id) || null;
  }

  async retry(id: string): Promise<Notification> {
    const notification = this.notifications.get(id);
    if (!notification) {
      throw new Error(`Notification not found: ${id}`);
    }

    if (notification.status === 'sent') {
      return notification;
    }

    notification.status = 'retrying';
    notification.retry_count++;
    this.eventEmitter.emit('notification.retrying', notification);

    return this.send({
      type: notification.type,
      recipient: notification.recipient,
      content: notification.content,
      max_retries: notification.max_retries,
    });
  }

  list(status?: Notification['status']): Notification[] {
    const notifications = Array.from(this.notifications.values());
    if (status) {
      return notifications.filter((n) => n.status === status);
    }
    return notifications;
  }

  on(event: string, handler: (data: Record<string, unknown>) => void): void {
    this.eventEmitter.on(event, handler);
  }

  off(event: string, handler: (data: Record<string, unknown>) => void): void {
    this.eventEmitter.off(event, handler);
  }

  getChannelTypes(): string[] {
    return Array.from(this.channels.keys());
  }
}

export const notificationService = new NotificationService();
