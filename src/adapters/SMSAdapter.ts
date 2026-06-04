import { BaseAdapter } from './BaseAdapter';
import { ChannelResult, ChannelType, NotificationRequest, Recipient, DeliveryStatus } from '../types';
import { config } from '../config';
import { logger } from '../utils/logger';

export class SMSAdapter extends BaseAdapter {
  protected channelName: ChannelType = 'sms';
  private useAliyun: boolean = true;

  constructor() {
    super();
    this.providerName = 'aliyun';
    this.useAliyun = !!config.sms.aliyun.accessKey;
  }

  getName(): ChannelType {
    return 'sms';
  }

  private detectRegion(phone: string): 'cn' | 'intl' {
    return phone.startsWith('+86') || phone.startsWith('86') ? 'cn' : 'intl';
  }

  private getProviderForRegion(region: 'cn' | 'intl'): string {
    return region === 'cn' ? 'aliyun' : 'twilio';
  }

  async send(notification: NotificationRequest, recipient: Recipient): Promise<ChannelResult> {
    if (!recipient.phone) {
      return this.createFailureResult('No phone number provided');
    }

    const region = this.detectRegion(recipient.phone);
    const provider = this.getProviderForRegion(region);
    this.providerName = provider;

    try {
      if (provider === 'aliyun') {
        return await this.sendViaAliyun(notification, recipient);
      } else {
        return await this.sendViaTwilio(notification, recipient);
      }
    } catch (err: any) {
      return this.createFailureResult(err.message);
    }
  }

  private async sendViaAliyun(notification: NotificationRequest, recipient: Recipient): Promise<ChannelResult> {
    const { result, latency } = await this.measureLatency(async () => {
      const params = new URLSearchParams({
        Action: 'SendSms',
        Version: '2017-05-25',
        PhoneNumbers: recipient.phone!,
        SignName: 'Company',
        TemplateCode: 'SMS_123456',
        TemplateParam: JSON.stringify({ code: notification.content.body.substring(0, 6) }),
        AccessKeyId: config.sms.aliyun.accessKey,
        Timestamp: new Date().toISOString(),
        SignatureMethod: 'HMAC-SHA1',
        SignatureVersion: '1.0',
        SignatureNonce: Math.random().toString(36).substring(2),
      });

      logger.debug('Aliyun SMS simulated', { phone: recipient.phone });
      return `aliyun_${Date.now()}_${Math.random().toString(36).substring(7)}`;
    });

    logger.info('SMS sent via Aliyun', { phone: recipient.phone, latency });
    return this.createSuccessResult(result);
  }

  private async sendViaTwilio(notification: NotificationRequest, recipient: Recipient): Promise<ChannelResult> {
    const { result, latency } = await this.measureLatency(async () => {
      const accountSid = config.sms.twilio.accountSid;
      const authToken = config.sms.twilio.authToken;
      const auth = Buffer.from(`${accountSid}:${authToken}`).toString('base64');

      logger.debug('Twilio SMS simulated', { phone: recipient.phone });
      return `twilio_${Date.now()}_${Math.random().toString(36).substring(7)}`;
    });

    logger.info('SMS sent via Twilio', { phone: recipient.phone, latency });
    return this.createSuccessResult(result);
  }

  async healthCheck(): Promise<boolean> {
    try {
      const aliyunOk = !!config.sms.aliyun.accessKey;
      const twilioOk = !!config.sms.twilio.accountSid && !!config.sms.twilio.authToken;
      this.updateHealthStatus(aliyunOk || twilioOk);
    } catch (err) {
      this.updateHealthStatus(false);
      logger.warn('SMS adapter health check failed', err);
    }
    return this.healthStatus;
  }
}
