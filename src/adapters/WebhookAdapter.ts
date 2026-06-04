import { BaseAdapter } from './BaseAdapter';
import { ChannelResult, ChannelType, NotificationRequest, Recipient, DeliveryStatus, WebhookEndpoint } from '../types';
import { logger } from '../utils/logger';
import * as crypto from 'crypto';

export class WebhookAdapter extends BaseAdapter {
  protected channelName: ChannelType = 'webhook';
  private endpoint?: WebhookEndpoint;

  constructor(endpoint?: WebhookEndpoint) {
    super();
    this.providerName = 'custom';
    this.endpoint = endpoint;
  }

  getName(): ChannelType {
    return 'webhook';
  }

  setEndpoint(endpoint: WebhookEndpoint): void {
    this.endpoint = endpoint;
  }

  async send(notification: NotificationRequest, recipient: Recipient): Promise<ChannelResult> {
    if (!this.endpoint?.url) {
      return this.createFailureResult('No webhook endpoint URL configured');
    }

    try {
      const { result, latency } = await this.measureLatency(async () => {
        const payload = this.buildWebhookPayload(notification, recipient);
        const signature = this.generateSignature(payload, this.endpoint!.signing_secret);

        const headers: Record<string, string> = {
          'Content-Type': 'application/json',
          'X-Notification-Signature': signature,
          'X-Notification-Timestamp': Date.now().toString(),
          'X-Notification-Event': notification.notification_type,
        };

        const response = await fetch(this.endpoint!.url, {
          method: 'POST',
          headers,
          body: JSON.stringify(payload),
        });

        const responseBody = await response.text();

        if (!response.ok) {
          throw new Error(`Webhook failed with status ${response.status}: ${responseBody}`);
        }

        return {
          messageId: `webhook_${Date.now()}_${Math.random().toString(36).substring(7)}`,
          responseStatus: response.status,
        };
      });

      logger.info('Webhook sent', { url: this.endpoint.url, latency });
      return {
        ...this.createSuccessResult(result.messageId),
        metadata: { responseStatus: result.responseStatus },
      } as any;
    } catch (err: any) {
      return this.createFailureResult(err.message);
    }
  }

  private buildWebhookPayload(notification: NotificationRequest, recipient: Recipient) {
    return {
      event_type: notification.notification_type,
      tenant_id: notification.tenant_id,
      priority: notification.priority,
      recipient: recipient,
      content: notification.content,
      metadata: notification.metadata,
      timestamp: new Date().toISOString(),
    };
  }

  private generateSignature(payload: any, secret: string): string {
    const hmac = crypto.createHmac('sha256', secret);
    hmac.update(JSON.stringify(payload));
    return `sha256=${hmac.digest('hex')}`;
  }

  async healthCheck(): Promise<boolean> {
    try {
      if (!this.endpoint?.url) {
        this.updateHealthStatus(false);
        return false;
      }

      const response = await fetch(this.endpoint.url, {
        method: 'HEAD',
        signal: AbortSignal.timeout(5000),
      }).catch(() => null);

      this.updateHealthStatus(response !== null && response.status < 500);
    } catch (err) {
      this.updateHealthStatus(false);
      logger.warn('Webhook adapter health check failed', err);
    }
    return this.healthStatus;
  }
}
