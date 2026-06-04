export type NotificationPriority = 'low' | 'medium' | 'high' | 'urgent';

export type ChannelType = 'email' | 'sms' | 'push' | 'slack' | 'wechat' | 'feishu' | 'webhook';

export type DeliveryStatus = 'pending' | 'queued' | 'sent' | 'delivered' | 'failed' | 'read' | 'clicked';

export type NotificationType = 
  | 'transactional'
  | 'marketing'
  | 'security'
  | 'system'
  | 'password_reset'
  | 'account_verification';

export interface NotificationRequest {
  tenant_id: string;
  notification_type: NotificationType;
  recipient: Recipient;
  content: ContentPayload;
  channel_preference?: ChannelType[];
  priority?: NotificationPriority;
  omnichannel?: boolean;
  metadata?: Record<string, any>;
  template_id?: string;
  template_variables?: Record<string, any>;
  locale?: string;
}

export interface Recipient {
  user_id?: string;
  email?: string;
  phone?: string;
  push_token?: string;
  slack_id?: string;
  wechat_id?: string;
  feishu_id?: string;
}

export interface ContentPayload {
  subject?: string;
  body: string;
  html?: string;
  attachments?: Attachment[];
}

export interface Attachment {
  filename: string;
  content: string;
  contentType: string;
}

export interface ChannelResult {
  channel: ChannelType;
  provider?: string;
  status: DeliveryStatus;
  message_id?: string;
  error?: string;
  sent_at?: Date;
  delivered_at?: Date;
}

export interface IChannelAdapter {
  send(notification: NotificationRequest, recipient: Recipient): Promise<ChannelResult>;
  healthCheck(): Promise<boolean>;
  getStatus(): Promise<ChannelStatus>;
  getName(): ChannelType;
}

export interface ChannelStatus {
  name: ChannelType;
  available: boolean;
  latency_ms?: number;
  last_checked: Date;
  quota_used?: number;
  quota_total?: number;
}

export interface RoutingRule {
  id: string;
  tenant_id: string;
  name: string;
  conditions: RoutingCondition[];
  actions: RoutingAction[];
  priority: number;
  enabled: boolean;
}

export interface RoutingCondition {
  field: string;
  operator: 'eq' | 'ne' | 'gt' | 'lt' | 'contains' | 'in';
  value: any;
}

export interface RoutingAction {
  type: 'set_channel' | 'set_priority' | 'ab_test' | 'gray_release';
  params: Record<string, any>;
}

export interface RateLimitConfig {
  channel?: ChannelType;
  tenant_id?: string;
  user_id?: string;
  max_requests: number;
  window_seconds: number;
}

export interface Template {
  id: string;
  tenant_id: string;
  notification_type: NotificationType;
  locale: string;
  name: string;
  subject_template?: string;
  body_template: string;
  html_template?: string;
  variables: string[];
  is_system_default: boolean;
  created_at: Date;
  updated_at: Date;
}

export interface TemplateWithFallbackInfo extends Template {
  fallback_from_system?: boolean;
  system_template_id?: string;
}

export interface UserPreferences {
  user_id: string;
  tenant_id: string;
  channel_preferences: ChannelPreference[];
  do_not_disturb: DoNotDisturbSettings;
  updated_at: Date;
}

export interface ChannelPreference {
  channel: ChannelType;
  notification_type: NotificationType;
  opted_in: boolean;
}

export interface DoNotDisturbSettings {
  enabled: boolean;
  start_time: string;
  end_time: string;
  timezone: string;
  allow_urgent: boolean;
}

export interface WebhookEndpoint {
  id: string;
  tenant_id: string;
  url: string;
  signing_secret: string;
  event_types: string[];
  retry_config: RetryConfig;
  enabled: boolean;
  created_at: Date;
}

export interface RetryConfig {
  max_retries: number;
  backoff_base: number;
  backoff_multiplier: number;
}

export interface DeliveryLog {
  delivery_id: string;
  tenant_id: string;
  notification_type: NotificationType;
  channel: ChannelType;
  provider: string;
  recipient: string;
  status: DeliveryStatus;
  priority: NotificationPriority;
  message_id?: string;
  error_message?: string;
  created_at: Date;
  updated_at: Date;
  metadata?: Record<string, any>;
}

export interface QueueJobData {
  notification: NotificationRequest;
  delivery_id: string;
  channel: ChannelType;
  attempt: number;
  scheduled_at?: Date;
}

export interface AuditLog {
  id: string;
  tenant_id: string;
  actor: string;
  action: string;
  resource_type: string;
  resource_id: string;
  changes: Record<string, any>;
  created_at: Date;
}

export type OrchestrationStatus = 'pending' | 'running' | 'completed' | 'failed' | 'cancelled';
export type OrchestrationStepStatus = 'pending' | 'scheduled' | 'running' | 'completed' | 'skipped' | 'failed';
export type OrchestrationConditionType = 'delivery_status' | 'user_behavior' | 'time_window' | 'custom';
export type OrchestrationOperator = 'eq' | 'ne' | 'gt' | 'lt' | 'gte' | 'lte' | 'in' | 'not_in' | 'and' | 'or';

export interface OrchestrationSequence {
  id: string;
  tenant_id: string;
  name: string;
  description?: string;
  steps: OrchestrationStep[];
  trigger_type: 'manual' | 'event' | 'scheduled';
  trigger_event?: string;
  enabled: boolean;
  created_by: string;
  created_at: Date;
  updated_at: Date;
}

export interface OrchestrationStep {
  id: string;
  name: string;
  description?: string;
  order: number;
  channel: ChannelType;
  delay_seconds?: number;
  conditions?: OrchestrationCondition[];
  notification_type: NotificationType;
  template_id?: string;
  content?: ContentPayload;
  terminate_on_success: boolean;
  metadata?: Record<string, any>;
}

export interface OrchestrationCondition {
  type: OrchestrationConditionType;
  operator: OrchestrationOperator;
  field: string;
  value: any;
  step_id?: string;
}

export interface OrchestrationInstance {
  id: string;
  sequence_id: string;
  tenant_id: string;
  recipient: Recipient;
  status: OrchestrationStatus;
  current_step: number;
  template_variables?: Record<string, any>;
  started_at: Date;
  completed_at?: Date;
  metadata?: Record<string, any>;
}

export interface OrchestrationStepExecution {
  id: string;
  instance_id: string;
  sequence_id: string;
  step_id: string;
  delivery_id?: string;
  status: OrchestrationStepStatus;
  scheduled_at?: Date;
  started_at?: Date;
  completed_at?: Date;
  result?: ChannelResult;
  error_message?: string;
  metadata?: Record<string, any>;
}

export interface DeliveryStatistics {
  tenant_id: string;
  time_range: {
    start: Date;
    end: Date;
  };
  filters: {
    channels?: ChannelType[];
    notification_types?: NotificationType[];
    providers?: string[];
  };
  total_sent: number;
  total_delivered: number;
  total_failed: number;
  total_read: number;
  total_clicked: number;
  delivery_rate: number;
  open_rate: number;
  click_rate: number;
  failure_rate: number;
  channel_stats: ChannelStatistics[];
  latency_distribution: LatencyDistribution;
  failure_reasons: FailureReasonItem[];
}

export interface ChannelStatistics {
  channel: ChannelType;
  total_sent: number;
  total_delivered: number;
  total_failed: number;
  delivery_rate: number;
  avg_latency_ms: number;
  p50_latency_ms: number;
  p95_latency_ms: number;
  p99_latency_ms: number;
}

export interface LatencyDistribution {
  under_1s: number;
  under_5s: number;
  under_30s: number;
  over_30s: number;
}

export interface FailureReasonItem {
  reason: string;
  count: number;
  percentage: number;
}

export interface DeliveryQueryFilter {
  tenant_id: string;
  start_time: Date;
  end_time: Date;
  channels?: ChannelType[];
  notification_types?: NotificationType[];
  providers?: string[];
  statuses?: DeliveryStatus[];
  group_by?: ('channel' | 'notification_type' | 'provider' | 'status')[];
}
