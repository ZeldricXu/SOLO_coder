import {
  DefaultNotificationService,
  DefaultTemplateRenderer,
  EmailSender,
  NotificationTemplate,
  NotificationChannel
} from '../../../modules/notification';
import { NotificationBuilder } from '../../builders';
import { ValidationError } from '../../../common';

describe('NotificationService', () => {
  let service: DefaultNotificationService;
  let mockEmailSender: jest.Mocked<EmailSender>;

  beforeEach(() => {
    service = new DefaultNotificationService();

    mockEmailSender = {
      getChannel: jest.fn().mockReturnValue('email'),
      send: jest.fn().mockResolvedValue(true)
    } as unknown as jest.Mocked<EmailSender>;

    service.registerSender(mockEmailSender);
  });

  afterEach(() => {
    jest.clearAllMocks();
  });

  describe('Template Management', () => {
    it('should register a new template', async () => {
      const template: Omit<NotificationTemplate, 'id' | 'created_at' | 'updated_at'> = {
        name: 'welcome-email',
        channel: 'email',
        subject: 'Welcome ${userName}',
        content: 'Hello ${userName}! Welcome to our platform.',
        variables: ['userName']
      };

      const templateId = await service.registerTemplate(template);
      expect(templateId).toBeDefined();
      expect(templateId.startsWith('tpl_')).toBe(true);

      const retrieved = await service.getTemplate(templateId);
      expect(retrieved).not.toBeNull();
      expect(retrieved?.name).toBe('welcome-email');
    });

    it('should list all templates', async () => {
      await service.registerTemplate({
        name: 'template-1',
        channel: 'email',
        content: 'Content 1',
        variables: []
      });

      await service.registerTemplate({
        name: 'template-2',
        channel: 'sms',
        content: 'Content 2',
        variables: []
      });

      const templates = service.listTemplates();
      expect(templates.length).toBe(2);
    });

    it('should update existing template', async () => {
      const templateId = await service.registerTemplate({
        name: 'old-name',
        channel: 'email',
        content: 'Old content',
        variables: []
      });

      const updated = await service.updateTemplate(templateId, {
        name: 'new-name',
        content: 'New content'
      });

      expect(updated).not.toBeNull();
      expect(updated?.name).toBe('new-name');
      expect(updated?.content).toBe('New content');
    });

    it('should return null when updating non-existent template', async () => {
      const result = await service.updateTemplate('non-existent', { name: 'test' });
      expect(result).toBeNull();
    });
  });

  describe('Template Rendering', () => {
    it('should render template with variables', () => {
      const renderer = new DefaultTemplateRenderer();
      const template: NotificationTemplate = {
        id: 'tpl_1',
        name: 'test',
        channel: 'email',
        subject: 'Hello ${name}',
        content: 'Welcome, ${name}! Your order ${orderId} is ready.',
        variables: ['name', 'orderId'],
        created_at: new Date().toISOString(),
        updated_at: new Date().toISOString()
      };

      const result = renderer.render(template, {
        name: 'John',
        orderId: 'ORD-123'
      });

      expect(result.subject).toBe('Hello John');
      expect(result.content).toBe('Welcome, John! Your order ORD-123 is ready.');
    });

    it('should leave undefined variables unchanged', () => {
      const renderer = new DefaultTemplateRenderer();
      const template: NotificationTemplate = {
        id: 'tpl_1',
        name: 'test',
        channel: 'email',
        content: 'Hello ${name}, your ${undefinedVar}',
        variables: ['name'],
        created_at: new Date().toISOString(),
        updated_at: new Date().toISOString()
      };

      const result = renderer.render(template, { name: 'John' });
      expect(result.content).toBe('Hello John, your ${undefinedVar}');
    });

    it('should handle templates without variables', () => {
      const renderer = new DefaultTemplateRenderer();
      const template: NotificationTemplate = {
        id: 'tpl_1',
        name: 'test',
        channel: 'email',
        content: 'Static content without variables',
        variables: [],
        created_at: new Date().toISOString(),
        updated_at: new Date().toISOString()
      };

      const result = renderer.render(template, {});
      expect(result.content).toBe('Static content without variables');
    });
  });

  describe('Sending Notifications', () => {
    it('should send notification successfully', async () => {
      const templateId = await service.registerTemplate({
        name: 'test-template',
        channel: 'email',
        subject: 'Test Subject',
        content: 'Test content for ${user}',
        variables: ['user']
      });

      const notificationId = await service.send(
        'email',
        templateId,
        ['test@example.com'],
        { user: 'Alice' }
      );

      expect(notificationId).toBeDefined();
      expect(mockEmailSender.send).toHaveBeenCalled();

      const status = await service.getStatus(notificationId);
      expect(status?.status).toBe('sent');
    });

    it('should throw error for unregistered channel', async () => {
      await expect(
        service.send('sms', 'template-id', ['+1234567890'], {})
      ).rejects.toThrow(ValidationError);
    });

    it('should throw error for non-existent template', async () => {
      await expect(
        service.send('email', 'non-existent-template', ['test@example.com'], {})
      ).rejects.toThrow(ValidationError);
    });

    it('should handle send failures', async () => {
      mockEmailSender.send.mockResolvedValue(false);

      const templateId = await service.registerTemplate({
        name: 'fail-test',
        channel: 'email',
        content: 'Test content',
        variables: []
      });

      const notificationId = await service.send(
        'email',
        templateId,
        ['test@example.com'],
        {}
      );

      const status = await service.getStatus(notificationId);
      expect(status?.status).toBe('failed');
      expect(status?.error_detail).toBeDefined();
    });

    it('should handle send exceptions', async () => {
      mockEmailSender.send.mockRejectedValue(new Error('Network error'));

      const templateId = await service.registerTemplate({
        name: 'exception-test',
        channel: 'email',
        content: 'Test content',
        variables: []
      });

      const notificationId = await service.send(
        'email',
        templateId,
        ['test@example.com'],
        {}
      );

      const status = await service.getStatus(notificationId);
      expect(status?.status).toBe('failed');
      expect(status?.error_detail).toBe('Network error');
    });
  });

  describe('Sender Registration', () => {
    it('should register multiple senders for different channels', () => {
      const mockSmsSender = {
        getChannel: jest.fn().mockReturnValue('sms'),
        send: jest.fn().mockResolvedValue(true)
      };

      const mockWebhookSender = {
        getChannel: jest.fn().mockReturnValue('webhook'),
        send: jest.fn().mockResolvedValue(true)
      };

      service.registerSender(mockSmsSender as any);
      service.registerSender(mockWebhookSender as any);

      expect(mockSmsSender.getChannel).toHaveBeenCalled();
      expect(mockWebhookSender.getChannel).toHaveBeenCalled();
    });

    it('should allow setting custom renderer', () => {
      const customRenderer = {
        render: jest.fn().mockReturnValue({ content: 'custom rendered' })
      };

      service.setRenderer(customRenderer);
    });
  });

  describe('Notification Listing', () => {
    it('should list all notifications', async () => {
      const templateId = await service.registerTemplate({
        name: 'list-test',
        channel: 'email',
        content: 'Test',
        variables: []
      });

      await service.send('email', templateId, ['user1@example.com'], {});
      await service.send('email', templateId, ['user2@example.com'], {});

      const notifications = service.listNotifications();
      expect(notifications.length).toBe(2);
    });

    it('should filter notifications by channel', async () => {
      const emailTemplateId = await service.registerTemplate({
        name: 'email-test',
        channel: 'email',
        content: 'Email',
        variables: []
      });

      const mockSmsSender = {
        getChannel: jest.fn().mockReturnValue('sms'),
        send: jest.fn().mockResolvedValue(true)
      };
      service.registerSender(mockSmsSender as any);

      const smsTemplateId = await service.registerTemplate({
        name: 'sms-test',
        channel: 'sms',
        content: 'SMS',
        variables: []
      });

      await service.send('email', emailTemplateId, ['user@example.com'], {});
      await service.send('sms', smsTemplateId, ['+1234567890'], {});

      const emailNotifications = service.listNotifications('email');
      const smsNotifications = service.listNotifications('sms');

      expect(emailNotifications.length).toBe(1);
      expect(smsNotifications.length).toBe(1);
    });

    it('should filter notifications by status', async () => {
      const templateId = await service.registerTemplate({
        name: 'status-test',
        channel: 'email',
        content: 'Test',
        variables: []
      });

      mockEmailSender.send.mockResolvedValueOnce(true);
      mockEmailSender.send.mockResolvedValueOnce(false);

      await service.send('email', templateId, ['success@example.com'], {});
      await service.send('email', templateId, ['fail@example.com'], {});

      const sentNotifications = service.listNotifications(undefined, 'sent');
      const failedNotifications = service.listNotifications(undefined, 'failed');

      expect(sentNotifications.length).toBe(1);
      expect(failedNotifications.length).toBe(1);
    });
  });

  describe('Integration with Builders', () => {
    it('should work with NotificationBuilder for testing', async () => {
      const templateId = await service.registerTemplate({
        name: 'welcome',
        channel: 'email',
        subject: 'Welcome to our platform',
        content: 'Hello ${userName}!',
        variables: ['userName']
      });

      const notificationId = await service.send(
        'email',
        templateId,
        ['john@example.com'],
        { userName: 'John Doe' }
      );

      expect(notificationId).toBeDefined();
    });
  });
});
