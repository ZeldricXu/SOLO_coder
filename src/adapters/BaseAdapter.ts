import { IChannelAdapter, ChannelResult, ChannelStatus, ChannelType, NotificationRequest, Recipient, DeliveryStatus } from '../types';
import { logger } from '../utils/logger';

export abstract class BaseAdapter implements IChannelAdapter {
  protected abstract channelName: ChannelType;
  protected providerName: string = 'default';
  protected healthStatus: boolean = true;
  protected lastHealthCheck: Date = new Date();
  protected latencyMs?: number;

  abstract send(notification: NotificationRequest, recipient: Recipient): Promise<ChannelResult>;
  abstract healthCheck(): Promise<boolean>;
  abstract getName(): ChannelType;

  async getStatus(): Promise<ChannelStatus> {
    return {
      name: this.channelName,
      available: this.healthStatus,
      latency_ms: this.latencyMs,
      last_checked: this.lastHealthCheck,
    };
  }

  protected async measureLatency<T>(fn: () => Promise<T>): Promise<{ result: T; latency: number }> {
    const start = Date.now();
    const result = await fn();
    this.latencyMs = Date.now() - start;
    return { result, latency: this.latencyMs };
  }

  protected createSuccessResult(messageId?: string): ChannelResult {
    return {
      channel: this.channelName,
      provider: this.providerName,
      status: 'sent' as DeliveryStatus,
      message_id: messageId,
      sent_at: new Date(),
    };
  }

  protected createFailureResult(error: string): ChannelResult {
    logger.error(`${this.channelName} send failed`, { error, provider: this.providerName });
    return {
      channel: this.channelName,
      provider: this.providerName,
      status: 'failed' as DeliveryStatus,
      error,
    };
  }

  protected updateHealthStatus(available: boolean): void {
    this.healthStatus = available;
    this.lastHealthCheck = new Date();
  }
}
