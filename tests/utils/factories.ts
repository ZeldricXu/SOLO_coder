import { v4 as uuidv4 } from 'uuid';
import {
  NotificationRequest,
  Recipient,
  ContentPayload,
  NotificationType,
  NotificationPriority,
  ChannelType,
  UserPreferences,
  ChannelPreference,
  DoNotDisturbSettings,
  WebhookEndpoint,
  Template,
  DeliveryLog,
} from '../../src/types';

export function createRecipient(overrides: Partial<Recipient> = {}): Recipient {
  return {
    user_id: uuidv4(),
    email: `test-${Math.random().toString(36).substring(2, 10)}@example.com`,
    phone: `+86138${Math.floor(Math.random() * 100000000).toString().padStart(8, '0')}`,
    push_token: `fcm:${uuidv4()}`,
    slack_id: `U${Math.floor(Math.random() * 1000000)}`,
    wechat_id: `wx_${uuidv4().substring(0, 8)}`,
    feishu_id: `fs_${uuidv4().substring(0, 8)}`,
    ...overrides,
  };
}

export function createContentPayload(overrides: Partial<ContentPayload> = {}): ContentPayload {
  return {
    subject: 'Test Notification',
    body: 'This is a test notification body.',
    html: '<p>This is a <strong>test</strong> notification body.</p>',
    ...overrides,
  };
}

export function createNotificationRequest(
  overrides: Partial<NotificationRequest> = {}
): NotificationRequest {
  const tenantId = overrides.tenant_id || uuidv4();
  const recipient = createRecipient(overrides.recipient);
  const content = createContentPayload(overrides.content);

  return {
    tenant_id: tenantId,
    notification_type: 'transactional' as NotificationType,
    recipient,
    content,
    channel_preference: ['email', 'sms'] as ChannelType[],
    priority: 'medium' as NotificationPriority,
    omnichannel: false,
    metadata: { source: 'test' },
    locale: 'en',
    ...overrides,
    recipient,
    content,
  };
}

export function createTemplate(
  overrides: Partial<Template> = {}
): Omit<Template, 'id' | 'created_at' | 'updated_at'> {
  return {
    tenant_id: overrides.tenant_id || uuidv4(),
    notification_type: 'transactional' as NotificationType,
    locale: 'en',
    name: 'Test Template',
    subject_template: 'Hello {{name}}!',
    body_template: 'Hi {{name}},\n\nYour order #{{order_id}} has been confirmed. Total: ${{amount}}\n\n{{#if is_high_value}}<strong>High value order!</strong>{{/if}}',
    html_template: '<p>Hi {{name}},</p><p>Your order #{{order_id}} has been confirmed.</p><p>Total: ${{amount}}</p>{{#if is_high_value}}<p style="color:red;"><strong>High value order!</strong></p>{{/if}}',
    variables: ['name', 'order_id', 'amount', 'is_high_value'],
    ...overrides,
  };
}

export function createChannelPreference(
  overrides: Partial<ChannelPreference> = {}
): ChannelPreference {
  return {
    channel: 'email' as ChannelType,
    notification_type: 'transactional' as NotificationType,
    opted_in: true,
    ...overrides,
  };
}

export function createDNDSettings(
  overrides: Partial<DoNotDisturbSettings> = {}
): DoNotDisturbSettings {
  return {
    enabled: true,
    start_time: '22:00',
    end_time: '08:00',
    timezone: 'Asia/Shanghai',
    allow_urgent: true,
    ...overrides,
  };
}

export function createUserPreferences(
  overrides: Partial<UserPreferences> = {}
): UserPreferences {
  return {
    user_id: overrides.user_id || uuidv4(),
    tenant_id: overrides.tenant_id || uuidv4(),
    channel_preferences: [createChannelPreference()],
    do_not_disturb: createDNDSettings({ enabled: false }),
    updated_at: new Date(),
    ...overrides,
  };
}

export function createWebhookEndpoint(
  overrides: Partial<WebhookEndpoint> = {}
): Omit<WebhookEndpoint, 'id' | 'created_at'> {
  return {
    tenant_id: overrides.tenant_id || uuidv4(),
    url: 'https://example.com/webhook',
    signing_secret: 'test-webhook-secret',
    event_types: ['notification.sent', 'notification.delivered'],
    retry_config: {
      max_retries: 3,
      backoff_base: 1000,
      backoff_multiplier: 2,
    },
    enabled: true,
    ...overrides,
  };
}

export function createDeliveryLog(
  overrides: Partial<DeliveryLog> = {}
): DeliveryLog {
  return {
    delivery_id: overrides.delivery_id || uuidv4(),
    tenant_id: overrides.tenant_id || uuidv4(),
    notification_type: 'transactional' as NotificationType,
    channel: 'email' as ChannelType,
    provider: 'smtp',
    recipient: 'test@example.com',
    status: 'queued',
    priority: 'medium' as NotificationPriority,
    message_id: uuidv4(),
    created_at: new Date(),
    updated_at: new Date(),
    ...overrides,
  };
}

export interface TableDrivenTestCase<TInput, TExpected> {
  name: string;
  input: TInput;
  expected: TExpected;
  setup?: () => void | Promise<void>;
  teardown?: () => void | Promise<void>;
  skip?: boolean;
  only?: boolean;
}

export function runTableDrivenTests<TInput, TExpected>(
  testCases: TableDrivenTestCase<TInput, TExpected>[],
  testFn: (input: TInput, expected: TExpected) => void | Promise<void>
) {
  for (const tc of testCases) {
    const testRunner = tc.only ? it.only : tc.skip ? it.skip : it;
    
    testRunner(tc.name, async () => {
      if (tc.setup) {
        await tc.setup();
      }
      
      try {
        await testFn(tc.input, tc.expected);
      } finally {
        if (tc.teardown) {
          await tc.teardown();
        }
      }
    });
  }
}
