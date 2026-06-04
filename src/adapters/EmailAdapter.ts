import { BaseAdapter } from './BaseAdapter';
import { ChannelResult, ChannelType, NotificationRequest, Recipient, DeliveryStatus } from '../types';
import { config } from '../config';
import { logger } from '../utils/logger';
import * as nodemailer from 'nodemailer';

export class EmailAdapter extends BaseAdapter {
  protected channelName: ChannelType = 'email';
  private smtpTransporter: any;
  private useSendGrid: boolean = false;

  constructor() {
    super();
    this.providerName = config.email.sendgridApiKey ? 'sendgrid' : 'smtp';
    this.useSendGrid = !!config.email.sendgridApiKey;
    this.initSMTP();
  }

  private initSMTP() {
    try {
      const isProduction = config.email.smtp.port === 465;
      const options: any = {
        host: config.email.smtp.host,
        port: config.email.smtp.port,
        secure: isProduction,
        requireTLS: isProduction,
        ignoreTLS: !isProduction,
        tls: {
          rejectUnauthorized: isProduction,
        },
      };
      if (config.email.smtp.user && config.email.smtp.pass) {
        options.auth = {
          user: config.email.smtp.user,
          pass: config.email.smtp.pass,
        };
      }
      this.smtpTransporter = nodemailer.createTransport(options);
    } catch (err) {
      logger.error('Failed to initialize SMTP transporter', err);
    }
  }

  getName(): ChannelType {
    return 'email';
  }

  async send(notification: NotificationRequest, recipient: Recipient): Promise<ChannelResult> {
    if (!recipient.email) {
      return this.createFailureResult('No email address provided');
    }

    try {
      if (this.useSendGrid) {
        return await this.sendViaSendGrid(notification, recipient);
      } else {
        return await this.sendViaSMTP(notification, recipient);
      }
    } catch (err: any) {
      return this.createFailureResult(err.message);
    }
  }

  private async sendViaSendGrid(notification: NotificationRequest, recipient: Recipient): Promise<ChannelResult> {
    const { result, latency } = await this.measureLatency(async () => {
      const response = await fetch('https://api.sendgrid.com/v3/mail/send', {
        method: 'POST',
        headers: {
          'Authorization': `Bearer ${config.email.sendgridApiKey}`,
          'Content-Type': 'application/json',
        },
        body: JSON.stringify({
          personalizations: [{ to: [{ email: recipient.email }] }],
          from: { email: 'noreply@company.com', name: 'Notification Service' },
          subject: notification.content.subject || 'Notification',
          content: [
            { type: 'text/plain', value: notification.content.body },
            ...(notification.content.html ? [{ type: 'text/html', value: notification.content.html }] : []),
          ],
        }),
      });

      if (!response.ok) {
        throw new Error(`SendGrid API failed: ${response.status} ${await response.text()}`);
      }

      return response.headers.get('x-message-id') || undefined;
    });

    logger.info('Email sent via SendGrid', { recipient: recipient.email, latency });
    return this.createSuccessResult(result);
  }

  private async sendViaSMTP(notification: NotificationRequest, recipient: Recipient): Promise<ChannelResult> {
    const { result, latency } = await this.measureLatency(async () => {
      const info = await this.smtpTransporter.sendMail({
        from: '"Notification Service" <noreply@company.com>',
        to: recipient.email,
        subject: notification.content.subject || 'Notification',
        text: notification.content.body,
        html: notification.content.html,
        attachments: notification.content.attachments?.map(a => ({
          filename: a.filename,
          content: Buffer.from(a.content, 'base64'),
          contentType: a.contentType,
        })),
      });
      return info.messageId;
    });

    logger.info('Email sent via SMTP', { recipient: recipient.email, latency });
    return this.createSuccessResult(result);
  }

  async healthCheck(): Promise<boolean> {
    try {
      if (this.useSendGrid) {
        const response = await fetch('https://api.sendgrid.com/v3/scopes', {
          headers: { 'Authorization': `Bearer ${config.email.sendgridApiKey}` },
        });
        this.updateHealthStatus(response.ok);
      } else {
        await this.smtpTransporter?.verify();
        this.updateHealthStatus(true);
      }
    } catch (err) {
      this.updateHealthStatus(false);
      logger.warn('Email adapter health check failed', err);
    }
    return this.healthStatus;
  }

  async failover(): Promise<void> {
    this.useSendGrid = !this.useSendGrid;
    this.providerName = this.useSendGrid ? 'sendgrid' : 'smtp';
    logger.info(`Email adapter failed over to ${this.providerName}`);
  }
}
