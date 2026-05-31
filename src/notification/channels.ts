import axios from 'axios';
import { Notification, ChannelConfig } from './types';

export interface NotificationChannel {
  send(notification: Notification, config: ChannelConfig): Promise<boolean>;
}

export class EmailChannel implements NotificationChannel {
  async send(notification: Notification, config: ChannelConfig): Promise<boolean> {
    try {
      const smtpConfig = config.options as {
        host: string;
        port: number;
        user: string;
        pass: string;
      };

      console.log(`[Email] Sending to ${notification.recipient}:`, notification.data);
      return true;
    } catch (error) {
      console.error('[Email] Send failed:', error);
      return false;
    }
  }
}

export class SMSChannel implements NotificationChannel {
  async send(notification: Notification, config: ChannelConfig): Promise<boolean> {
    try {
      const smsConfig = config.options as {
        apiKey: string;
        apiUrl: string;
        sender: string;
      };

      if (smsConfig.apiUrl && smsConfig.apiKey) {
        await axios.post(smsConfig.apiUrl, {
          to: notification.recipient,
          from: smsConfig.sender,
          body: notification.data.content,
        }, {
          headers: { Authorization: `Bearer ${smsConfig.apiKey}` },
        });
      }

      console.log(`[SMS] Sending to ${notification.recipient}:`, notification.data);
      return true;
    } catch (error) {
      console.error('[SMS] Send failed:', error);
      return false;
    }
  }
}

export class PushChannel implements NotificationChannel {
  async send(notification: Notification, config: ChannelConfig): Promise<boolean> {
    try {
      const pushConfig = config.options as {
        fcmServerKey?: string;
        apnsCert?: string;
      };

      console.log(`[Push] Sending to ${notification.recipient}:`, notification.data);
      return true;
    } catch (error) {
      console.error('[Push] Send failed:', error);
      return false;
    }
  }
}

export class WebhookChannel implements NotificationChannel {
  async send(notification: Notification, config: ChannelConfig): Promise<boolean> {
    try {
      const webhookConfig = config.options as {
        url: string;
        secret?: string;
        method?: 'POST' | 'PUT';
      };

      if (webhookConfig.url) {
        await axios({
          method: webhookConfig.method || 'POST',
          url: webhookConfig.url,
          data: notification.data,
          headers: webhookConfig.secret ? {
            'X-Webhook-Signature': webhookConfig.secret,
          } : {},
        });
      }

      console.log(`[Webhook] Sending to ${notification.recipient}:`, notification.data);
      return true;
    } catch (error) {
      console.error('[Webhook] Send failed:', error);
      return false;
    }
  }
}

export function createChannel(type: Notification['type']): NotificationChannel {
  switch (type) {
    case 'email':
      return new EmailChannel();
    case 'sms':
      return new SMSChannel();
    case 'push':
      return new PushChannel();
    case 'webhook':
      return new WebhookChannel();
    default:
      throw new Error(`Unknown channel type: ${type}`);
  }
}
