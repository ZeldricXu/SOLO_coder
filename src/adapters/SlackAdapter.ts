import { BaseAdapter } from './BaseAdapter';
import { ChannelResult, ChannelType, NotificationRequest, Recipient, DeliveryStatus } from '../types';
import { logger } from '../utils/logger';

export class SlackAdapter extends BaseAdapter {
  protected channelName: ChannelType = 'slack';
  private webhookUrl?: string;

  constructor(webhookUrl?: string) {
    super();
    this.providerName = 'slack';
    this.webhookUrl = webhookUrl;
  }

  getName(): ChannelType {
    return 'slack';
  }

  setWebhookUrl(url: string): void {
    this.webhookUrl = url;
  }

  async send(notification: NotificationRequest, recipient: Recipient): Promise<ChannelResult> {
    if (!this.webhookUrl && !recipient.slack_id) {
      return this.createFailureResult('No Slack webhook URL or user ID provided');
    }

    try {
      const { result, latency } = await this.measureLatency(async () => {
        const payload = this.buildSlackMessage(notification);
        
        if (this.webhookUrl) {
          const response = await fetch(this.webhookUrl, {
            method: 'POST',
            headers: { 'Content-Type': 'application/json' },
            body: JSON.stringify(payload),
          });
          
          if (!response.ok) {
            throw new Error(`Slack webhook failed: ${response.status}`);
          }
        } else {
          logger.debug('Slack DM simulated', { userId: recipient.slack_id });
        }
        
        return `slack_${Date.now()}_${Math.random().toString(36).substring(7)}`;
      });

      logger.info('Slack message sent', { latency });
      return this.createSuccessResult(result);
    } catch (err: any) {
      return this.createFailureResult(err.message);
    }
  }

  private buildSlackMessage(notification: NotificationRequest) {
    const blocks: any[] = [
      {
        type: 'section',
        text: {
          type: 'mrkdwn',
          text: `*${notification.content.subject || 'Notification'}*`,
        },
      },
      {
        type: 'section',
        text: {
          type: 'mrkdwn',
          text: notification.content.body,
        },
      },
    ];

    if (notification.priority === 'urgent' || notification.priority === 'high') {
      blocks.unshift({
        type: 'context',
        elements: [
          {
            type: 'mrkdwn',
            text: `:warning: *${notification.priority.toUpperCase()} PRIORITY*`,
          },
        ],
      });
    }

    return { blocks };
  }

  async healthCheck(): Promise<boolean> {
    try {
      this.updateHealthStatus(!!this.webhookUrl);
    } catch (err) {
      this.updateHealthStatus(false);
      logger.warn('Slack adapter health check failed', err);
    }
    return this.healthStatus;
  }
}
