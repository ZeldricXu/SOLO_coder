import { Notification, NotificationChannel, NotificationStatus } from '../../core/ports';
import { generateId } from '../../common';

export class NotificationBuilder {
  private notificationId: string = generateId('notif');
  private recipients: string[] = ['user@example.com'];
  private channel: NotificationChannel = 'email';
  private templateId: string = 'default-template';
  private variables: Record<string, unknown> = {};
  private status: NotificationStatus = 'pending';
  private subject?: string;

  withNotificationId(id: string): NotificationBuilder {
    this.notificationId = id;
    return this;
  }

  withRecipient(recipient: string): NotificationBuilder {
    this.recipients = [recipient];
    return this;
  }

  withRecipients(recipients: string[]): NotificationBuilder {
    this.recipients = recipients;
    return this;
  }

  withChannel(channel: NotificationChannel): NotificationBuilder {
    this.channel = channel;
    return this;
  }

  withEmail(): NotificationBuilder {
    return this.withChannel('email');
  }

  withSms(): NotificationBuilder {
    return this.withChannel('sms');
  }

  withWebhook(): NotificationBuilder {
    return this.withChannel('webhook');
  }

  withTemplateId(templateId: string): NotificationBuilder {
    this.templateId = templateId;
    return this;
  }

  withVariables(variables: Record<string, unknown>): NotificationBuilder {
    this.variables = { ...this.variables, ...variables };
    return this;
  }

  withStatus(status: NotificationStatus): NotificationBuilder {
    this.status = status;
    return this;
  }

  withSubject(subject: string): NotificationBuilder {
    this.subject = subject;
    return this;
  }

  build(): Notification {
    return {
      id: this.notificationId,
      recipients: this.recipients,
      channel: this.channel,
      templateId: this.templateId,
      variables: this.variables,
      status: this.status,
      created_at: new Date().toISOString()
    };
  }

  static create(): NotificationBuilder {
    return new NotificationBuilder();
  }

  static createWelcomeEmail(userName: string = 'John'): Notification {
    return new NotificationBuilder()
      .withEmail()
      .withRecipient(`${userName.toLowerCase()}@example.com`)
      .withTemplateId('welcome-email')
      .withSubject('Welcome to our platform')
      .withVariables({ userName, loginUrl: 'https://example.com/login' })
      .build();
  }

  static createOrderConfirmation(orderId: string = 'order_123'): Notification {
    return new NotificationBuilder()
      .withEmail()
      .withTemplateId('order-confirmation')
      .withSubject('Your order has been confirmed')
      .withVariables({ orderId, total: 99.99, estimatedDelivery: '2024-01-15' })
      .build();
  }

  static createSmsAlert(phone: string = '+1234567890'): Notification {
    return new NotificationBuilder()
      .withSms()
      .withRecipient(phone)
      .withTemplateId('sms-alert')
      .withStatus('pending')
      .withVariables({ alertType: 'fraud_detected', amount: 1000 })
      .build();
  }

  static createWebhookNotification(url: string = 'https://api.example.com/webhook'): Notification {
    return new NotificationBuilder()
      .withWebhook()
      .withRecipient(url)
      .withTemplateId('webhook-event')
      .withVariables({ eventType: 'user.created', userId: 'user_123' })
      .build();
  }

  static createBatch(count: number, channel: NotificationChannel = 'email'): Notification[] {
    return Array.from({ length: count }, (_, i) =>
      new NotificationBuilder()
        .withNotificationId(`batch_notif_${i}`)
        .withChannel(channel)
        .withRecipient(`user${i}@example.com`)
        .withTemplateId('batch-notification')
        .withVariables({ batchNumber: i })
        .build()
    );
  }
}
