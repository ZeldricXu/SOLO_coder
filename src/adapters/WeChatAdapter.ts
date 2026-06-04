import { BaseAdapter } from './BaseAdapter';
import { ChannelResult, ChannelType, NotificationRequest, Recipient, DeliveryStatus } from '../types';
import { logger } from '../utils/logger';

export class WeChatAdapter extends BaseAdapter {
  protected channelName: ChannelType = 'wechat';
  private agentId?: string;
  private appSecret?: string;

  constructor() {
    super();
    this.providerName = 'wechat_work';
  }

  getName(): ChannelType {
    return 'wechat';
  }

  async send(notification: NotificationRequest, recipient: Recipient): Promise<ChannelResult> {
    if (!recipient.wechat_id) {
      return this.createFailureResult('No WeChat user ID provided');
    }

    try {
      const { result, latency } = await this.measureLatency(async () => {
        logger.debug('WeChat message simulated', { userId: recipient.wechat_id });
        return `wechat_${Date.now()}_${Math.random().toString(36).substring(7)}`;
      });

      logger.info('WeChat message sent', { latency });
      return this.createSuccessResult(result);
    } catch (err: any) {
      return this.createFailureResult(err.message);
    }
  }

  async healthCheck(): Promise<boolean> {
    try {
      this.updateHealthStatus(true);
    } catch (err) {
      this.updateHealthStatus(false);
      logger.warn('WeChat adapter health check failed', err);
    }
    return this.healthStatus;
  }
}

export class FeishuAdapter extends BaseAdapter {
  protected channelName: ChannelType = 'feishu';
  private appId?: string;
  private appSecret?: string;

  constructor() {
    super();
    this.providerName = 'feishu';
  }

  getName(): ChannelType {
    return 'feishu';
  }

  async send(notification: NotificationRequest, recipient: Recipient): Promise<ChannelResult> {
    if (!recipient.feishu_id) {
      return this.createFailureResult('No Feishu user ID provided');
    }

    try {
      const { result, latency } = await this.measureLatency(async () => {
        logger.debug('Feishu message simulated', { userId: recipient.feishu_id });
        return `feishu_${Date.now()}_${Math.random().toString(36).substring(7)}`;
      });

      logger.info('Feishu message sent', { latency });
      return this.createSuccessResult(result);
    } catch (err: any) {
      return this.createFailureResult(err.message);
    }
  }

  async healthCheck(): Promise<boolean> {
    try {
      this.updateHealthStatus(true);
    } catch (err) {
      this.updateHealthStatus(false);
      logger.warn('Feishu adapter health check failed', err);
    }
    return this.healthStatus;
  }
}
