export type NotificationChannel = 'email' | 'sms' | 'webhook' | 'slack' | 'dingtalk';

export type NotificationStatus = 'pending' | 'sent' | 'failed' | 'queued';

export interface Notification {
  id: string;
  channel: NotificationChannel;
  templateId: string;
  recipients: string[];
  variables: Record<string, unknown>;
  status: NotificationStatus;
  created_at: string;
  sent_at?: string;
  error_detail?: string;
}

export interface NotificationTemplate {
  id: string;
  name: string;
  channel: NotificationChannel;
  subject?: string;
  content: string;
  variables: string[];
  created_at: string;
  updated_at: string;
}

export interface NotificationSender {
  send(notification: Notification): Promise<boolean>;
  getChannel(): NotificationChannel;
}

export interface TemplateRenderer {
  render(template: NotificationTemplate, variables: Record<string, unknown>): {
    subject?: string;
    content: string;
  };
}

export interface NotificationService {
  send(
    channel: NotificationChannel,
    templateId: string,
    recipients: string[],
    variables: Record<string, unknown>
  ): Promise<string>;

  getStatus(notificationId: string): Promise<Notification | null>;

  registerTemplate(template: Omit<NotificationTemplate, 'id' | 'created_at' | 'updated_at'>): Promise<string>;

  getTemplate(templateId: string): Promise<NotificationTemplate | null>;
}
