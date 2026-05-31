export interface NotificationTemplate {
  id: string;
  name: string;
  type: Notification['type'];
  subject?: string;
  content: string;
  variables: string[];
  created_at: string;
  updated_at: string;
}

export interface Notification {
  id: string;
  type: 'email' | 'sms' | 'push' | 'webhook';
  recipient: string;
  template: string;
  data: Record<string, unknown>;
  status: 'pending' | 'queued' | 'sent' | 'failed';
  retryCount: number;
  maxRetries: number;
  created_at: string;
  sent_at: string | null;
  error?: string;
}

export interface ChannelConfig {
  type: Notification['type'];
  enabled: boolean;
  options: Record<string, unknown>;
}

export interface NotificationConfig {
  channels: ChannelConfig[];
  defaultMaxRetries: number;
  retryDelay: number;
  batchSize: number;
  templates: NotificationTemplate[];
}

export interface SendResult {
  notificationId: string;
  success: boolean;
  error?: string;
}
