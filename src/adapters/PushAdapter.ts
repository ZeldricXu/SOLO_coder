import { BaseAdapter } from './BaseAdapter';
import { ChannelResult, ChannelType, NotificationRequest, Recipient, DeliveryStatus } from '../types';
import { config } from '../config';
import { logger } from '../utils/logger';

export class PushAdapter extends BaseAdapter {
  protected channelName: ChannelType = 'push';
  private platform: 'fcm' | 'apns' = 'fcm';

  constructor() {
    super();
    this.providerName = config.push.fcmServerKey ? 'fcm' : 'apns';
  }

  getName(): ChannelType {
    return 'push';
  }

  private detectPlatform(recipient: Recipient): 'fcm' | 'apns' {
    if (recipient.push_token?.startsWith('fcm:')) return 'fcm';
    if (recipient.push_token?.startsWith('apns:')) return 'apns';
    return 'fcm';
  }

  async send(notification: NotificationRequest, recipient: Recipient): Promise<ChannelResult> {
    if (!recipient.push_token) {
      return this.createFailureResult('No push token provided');
    }

    this.platform = this.detectPlatform(recipient);
    this.providerName = this.platform;

    try {
      if (this.platform === 'fcm') {
        return await this.sendViaFCM(notification, recipient);
      } else {
        return await this.sendViaAPNS(notification, recipient);
      }
    } catch (err: any) {
      return this.createFailureResult(err.message);
    }
  }

  private async sendViaFCM(notification: NotificationRequest, recipient: Recipient): Promise<ChannelResult> {
    const { result, latency } = await this.measureLatency(async () => {
      const token = recipient.push_token!.replace('fcm:', '');
      
      const payload = {
        to: token,
        notification: {
          title: notification.content.subject || 'Notification',
          body: notification.content.body,
        },
        data: notification.metadata || {},
      };

      logger.debug('FCM push simulated', { token });
      return `fcm_${Date.now()}_${Math.random().toString(36).substring(7)}`;
    });

    logger.info('Push sent via FCM', { latency });
    return this.createSuccessResult(result);
  }

  private async sendViaAPNS(notification: NotificationRequest, recipient: Recipient): Promise<ChannelResult> {
    const { result, latency } = await this.measureLatency(async () => {
      const token = recipient.push_token!.replace('apns:', '');
      
      const payload = {
        aps: {
          alert: {
            title: notification.content.subject || 'Notification',
            body: notification.content.body,
          },
          sound: 'default',
        },
        data: notification.metadata || {},
      };

      logger.debug('APNS push simulated', { token });
      return `apns_${Date.now()}_${Math.random().toString(36).substring(7)}`;
    });

    logger.info('Push sent via APNS', { latency });
    return this.createSuccessResult(result);
  }

  async healthCheck(): Promise<boolean> {
    try {
      const fcmOk = !!config.push.fcmServerKey;
      const apnsOk = !!config.push.apns.keyId && !!config.push.apns.teamId;
      this.updateHealthStatus(fcmOk || apnsOk);
    } catch (err) {
      this.updateHealthStatus(false);
      logger.warn('Push adapter health check failed', err);
    }
    return this.healthStatus;
  }
}
