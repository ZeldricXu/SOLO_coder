import { describe, it, expect, vi, beforeEach, afterEach } from 'vitest';
import { SMTPServer } from 'smtp-server';
import { simpleParser } from 'mailparser';
import { EmailAdapter } from '../../src/adapters/EmailAdapter';
import { createNotificationRequest, createRecipient, runTableDrivenTests } from '../utils/factories';
import { config } from '../../src/config';
import nock from 'nock';

describe('EmailAdapter', () => {
  let smtpServer: SMTPServer;
  let smtpPort: number;
  let receivedEmails: any[] = [];

  const startSMTPServer = (): Promise<number> => {
    return new Promise((resolve, reject) => {
      smtpServer = new SMTPServer({
        authOptional: true,
        allowInsecureAuth: true,
        disableReverseLookup: true,
        onData(stream: any, session: any, callback: any) {
          const chunks: Buffer[] = [];
          stream.on('data', (chunk: Buffer) => chunks.push(chunk));
          stream.on('end', async () => {
            const rawEmail = Buffer.concat(chunks).toString();
            const parsed = await simpleParser(rawEmail);
            receivedEmails.push({
              raw: rawEmail,
              parsed,
              from: session.envelope.mailFrom,
              to: session.envelope.rcptTo,
            });
            callback();
          });
        },
      });

      smtpServer.on('error', reject);
      smtpServer.listen(0, '127.0.0.1', () => {
        const address = smtpServer.server.address();
        if (address && typeof address === 'object') {
          resolve(address.port);
        } else {
          reject(new Error('Failed to get SMTP server port'));
        }
      });
    });
  };

  const stopSMTPServer = (): Promise<void> => {
    return new Promise((resolve, reject) => {
      if (smtpServer) {
        smtpServer.close((err) => {
          if (err) reject(err);
          else resolve();
        });
      } else {
        resolve();
      }
    });
  };

  beforeEach(async () => {
    vi.clearAllMocks();
    vi.useRealTimers();
    receivedEmails = [];
  });

  afterEach(async () => {
    await stopSMTPServer();
    nock.cleanAll();
  });

  describe('SMTP通道测试', () => {
    beforeEach(async () => {
      smtpPort = await startSMTPServer();
      
      vi.mocked(config).email.smtp.host = '127.0.0.1';
      vi.mocked(config).email.smtp.port = smtpPort;
      vi.mocked(config).email.smtp.user = undefined;
      vi.mocked(config).email.smtp.pass = undefined;
      vi.mocked(config).email.sendgridApiKey = '';
    });

    it('通过SMTP发送邮件，验证Header和Body正确性', async () => {
      const adapter = new EmailAdapter();
      
      const recipient = createRecipient({ email: 'recipient@example.com' });
      const notification = createNotificationRequest({
        recipient,
        content: {
          subject: 'Test Subject - 测试主题',
          body: 'This is the plain text body.\n\n这是纯文本内容。',
          html: '<p>This is the <strong>HTML</strong> body.</p><p>这是HTML内容。</p>',
        },
      });

      const result = await adapter.send(notification, recipient);

      expect(result.status).toBe('sent');
      expect(result.channel).toBe('email');
      expect(result.provider).toBe('smtp');
      expect(result.message_id).toBeDefined();

      expect(receivedEmails.length).toBe(1);
      const received = receivedEmails[0];
      
      expect(received.parsed.subject).toBe('Test Subject - 测试主题');
      expect(received.parsed.from?.text).toBe('"Notification Service" <noreply@company.com>');
      expect(received.parsed.to?.text).toBe('recipient@example.com');
      expect(received.parsed.text).toContain('This is the plain text body.');
      expect(received.parsed.text).toContain('这是纯文本内容。');
      expect(received.parsed.html).toContain('<strong>HTML</strong>');
      expect(received.parsed.html).toContain('这是HTML内容。');
      expect(received.parsed.messageId).toBeDefined();
    });

    it('验证邮件多部分MIME结构正确', async () => {
      const adapter = new EmailAdapter();
      
      const recipient = createRecipient({ email: 'mime-test@example.com' });
      const notification = createNotificationRequest({
        recipient,
        content: {
          subject: 'MIME Test',
          body: 'Plain text version',
          html: '<html><body><p>HTML version</p></body></html>',
        },
      });

      await adapter.send(notification, recipient);

      expect(receivedEmails.length).toBe(1);
      const rawEmail = receivedEmails[0].raw;
      
      expect(rawEmail).toContain('Content-Type: multipart/alternative');
      expect(rawEmail).toContain('Content-Type: text/plain');
      expect(rawEmail).toContain('Content-Type: text/html');
      expect(rawEmail).toContain('Plain text version');
      expect(rawEmail).toContain('<html><body><p>HTML version</p></body></html>');
    });

    it('验证带附件的邮件发送', async () => {
      const adapter = new EmailAdapter();
      
      const recipient = createRecipient({ email: 'attachment-test@example.com' });
      const notification = createNotificationRequest({
        recipient,
        content: {
          subject: 'Attachment Test',
          body: 'See attached file',
          attachments: [
            {
              filename: 'test.txt',
              content: Buffer.from('Hello World').toString('base64'),
              contentType: 'text/plain',
            },
          ],
        },
      });

      await adapter.send(notification, recipient);

      expect(receivedEmails.length).toBe(1);
      const received = receivedEmails[0];
      
      expect(received.parsed.attachments?.length).toBe(1);
      expect(received.parsed.attachments[0].filename).toBe('test.txt');
      expect(received.parsed.attachments[0].contentType).toBe('text/plain');
      expect(received.parsed.attachments[0].content.toString()).toBe('Hello World');
    });
  });

  describe('SendGrid通道测试', () => {
    let fetchSpy: any;
    let capturedRequestBody: any;

    beforeEach(() => {
      vi.mocked(config).email.sendgridApiKey = 'test-sendgrid-api-key';
      capturedRequestBody = null;
    });

    afterEach(() => {
      if (fetchSpy) {
        fetchSpy.mockRestore();
      }
    });

    it('通过SendGrid API发送邮件，验证请求格式正确', async () => {
      const adapter = new EmailAdapter();
      
      const recipient = createRecipient({ email: 'sendgrid-test@example.com' });
      const notification = createNotificationRequest({
        recipient,
        content: {
          subject: 'SendGrid Test',
          body: 'Plain text body',
          html: '<p>HTML body</p>',
        },
      });

      fetchSpy = vi.spyOn(global, 'fetch').mockImplementation(async (url: any, options: any) => {
        capturedRequestBody = JSON.parse(options.body);
        return {
          ok: true,
          status: 202,
          headers: {
            get: (header: string) => header === 'x-message-id' ? 'sendgrid-test-msg-123' : null,
          },
          text: async () => '',
        } as any;
      });

      const result = await adapter.send(notification, recipient);

      expect(fetchSpy).toHaveBeenCalledTimes(1);
      const [url, options] = fetchSpy.mock.calls[0];
      expect(url).toBe('https://api.sendgrid.com/v3/mail/send');
      expect(options.headers.Authorization).toBe('Bearer test-sendgrid-api-key');
      expect(options.headers['Content-Type']).toBe('application/json');

      expect(result.status).toBe('sent');
      expect(result.provider).toBe('sendgrid');
      expect(result.message_id).toBe('sendgrid-test-msg-123');

      expect(capturedRequestBody.personalizations[0].to[0].email).toBe('sendgrid-test@example.com');
      expect(capturedRequestBody.from.email).toBe('noreply@company.com');
      expect(capturedRequestBody.subject).toBe('SendGrid Test');
      expect(capturedRequestBody.content).toContainEqual({ type: 'text/plain', value: 'Plain text body' });
      expect(capturedRequestBody.content).toContainEqual({ type: 'text/html', value: '<p>HTML body</p>' });
    });

    it('SendGrid API返回429限流时正确处理失败', async () => {
      const adapter = new EmailAdapter();
      
      const recipient = createRecipient({ email: 'rate-limit@example.com' });
      const notification = createNotificationRequest({ recipient });

      fetchSpy = vi.spyOn(global, 'fetch').mockImplementation(async () => {
        return {
          ok: false,
          status: 429,
          text: async () => JSON.stringify({ error: 'rate limit exceeded' }),
        } as any;
      });

      const result = await adapter.send(notification, recipient);

      expect(result.status).toBe('failed');
      expect(result.error).toContain('429');
    });
  });

  describe('Failover测试', () => {
    let failoverFetchSpy: any;

    afterEach(() => {
      if (failoverFetchSpy) {
        failoverFetchSpy.mockRestore();
      }
    });

    it('SMTP连接超时后自动切换到SendGrid API', async () => {
      smtpPort = await startSMTPServer();
      
      vi.mocked(config).email.smtp.host = '127.0.0.1';
      vi.mocked(config).email.smtp.port = smtpPort;
      vi.mocked(config).email.smtp.user = 'testuser';
      vi.mocked(config).email.smtp.pass = 'testpass';
      vi.mocked(config).email.sendgridApiKey = 'test-sendgrid-key';

      const adapter = new EmailAdapter();

      expect((adapter as any).useSendGrid).toBe(true);
      expect((adapter as any).providerName).toBe('sendgrid');

      await adapter.failover();

      expect((adapter as any).useSendGrid).toBe(false);
      expect((adapter as any).providerName).toBe('smtp');

      const recipient = createRecipient({ email: 'failover-test@example.com' });
      const notification = createNotificationRequest({
        recipient,
        content: { subject: 'Failover Test', body: 'Test body' },
      });

      failoverFetchSpy = vi.spyOn(global, 'fetch').mockImplementation(async () => {
        return {
          ok: true,
          status: 202,
          headers: {
            get: (header: string) => header === 'x-message-id' ? 'after-failover-msg' : null,
          },
          text: async () => '',
        } as any;
      });

      (adapter as any).smtpTransporter.sendMail = vi.fn().mockRejectedValueOnce(
        new Error('Connection timeout after 30000ms')
      );

      await adapter.failover();

      const result = await adapter.send(notification, recipient);

      expect(result.status).toBe('sent');
      expect(result.provider).toBe('sendgrid');
      expect(failoverFetchSpy).toHaveBeenCalledTimes(1);
    });
  });

  describe('表驱动测试 - 边界情况', () => {
    interface EmailTestCase {
      recipient: any;
      content: any;
      expectedStatus: string;
      expectedError?: string;
    }

    const testCases: { name: string; input: EmailTestCase; expected: string }[] = [
      {
        name: '无收件人邮箱时返回失败',
        input: {
          recipient: createRecipient({ email: undefined }),
          content: { subject: 'Test', body: 'Test' },
          expectedStatus: 'failed',
          expectedError: 'No email address provided',
        },
        expected: 'failed',
      },
      {
        name: '空主题时使用默认值',
        input: {
          recipient: createRecipient({ email: 'no-subject@example.com' }),
          content: { subject: undefined, body: 'Body only' },
          expectedStatus: 'sent',
        },
        expected: 'sent',
      },
      {
        name: '特殊字符主题正确编码',
        input: {
          recipient: createRecipient({ email: 'special-chars@example.com' }),
          content: {
            subject: '🔥 促销活动 ✨ 立减50% 🎉',
            body: 'Test body with unicode: 中文 日本語 🎌',
          },
          expectedStatus: 'sent',
        },
        expected: 'sent',
      },
      {
        name: '超长主题正确截断',
        input: {
          recipient: createRecipient({ email: 'long-subject@example.com' }),
          content: {
            subject: 'A'.repeat(1000),
            body: 'Test body',
          },
          expectedStatus: 'sent',
        },
        expected: 'sent',
      },
    ];

    beforeEach(async () => {
      smtpPort = await startSMTPServer();
      vi.mocked(config).email.smtp.host = '127.0.0.1';
      vi.mocked(config).email.smtp.port = smtpPort;
      vi.mocked(config).email.smtp.user = undefined;
      vi.mocked(config).email.smtp.pass = undefined;
      vi.mocked(config).email.sendgridApiKey = '';
    });

    runTableDrivenTests<EmailTestCase, string>(testCases, async (input, expected) => {
      const adapter = new EmailAdapter();
      const notification = createNotificationRequest({
        recipient: input.recipient,
        content: input.content,
      });

      const result = await adapter.send(notification, input.recipient);

      expect(result.status).toBe(expected);
      if (input.expectedError) {
        expect(result.error).toBe(input.expectedError);
      }
    });
  });

  describe('健康检查测试', () => {
    let healthFetchSpy: any;

    afterEach(() => {
      if (healthFetchSpy) {
        healthFetchSpy.mockRestore();
      }
    });

    it('SMTP健康检查成功时返回true', async () => {
      smtpPort = await startSMTPServer();
      vi.mocked(config).email.smtp.host = '127.0.0.1';
      vi.mocked(config).email.smtp.port = smtpPort;
      vi.mocked(config).email.sendgridApiKey = '';

      const adapter = new EmailAdapter();
      
      (adapter as any).smtpTransporter.verify = vi.fn().mockResolvedValue(true);

      const result = await adapter.healthCheck();

      expect(result).toBe(true);
    });

    it('SendGrid健康检查成功时返回true', async () => {
      vi.mocked(config).email.sendgridApiKey = 'test-key';

      const adapter = new EmailAdapter();

      healthFetchSpy = vi.spyOn(global, 'fetch').mockImplementation(async (url: any, options: any) => {
        expect(url).toBe('https://api.sendgrid.com/v3/scopes');
        expect(options.headers.Authorization).toBe('Bearer test-key');
        return {
          ok: true,
          status: 200,
        } as any;
      });

      const result = await adapter.healthCheck();

      expect(result).toBe(true);
      expect(healthFetchSpy).toHaveBeenCalledTimes(1);
    });

    it('健康检查失败时返回false并记录状态', async () => {
      vi.mocked(config).email.sendgridApiKey = 'test-key';

      const adapter = new EmailAdapter();

      nock('https://api.sendgrid.com')
        .get('/v3/scopes')
        .reply(500, { error: 'Internal Server Error' });

      const result = await adapter.healthCheck();

      expect(result).toBe(false);

      const status = await adapter.getStatus();
      expect(status.available).toBe(false);
      expect(status.name).toBe('email');
    });
  });

  describe('状态查询', () => {
    it('getStatus返回正确的通道状态信息', async () => {
      const adapter = new EmailAdapter();
      
      const status = await adapter.getStatus();

      expect(status.name).toBe('email');
      expect(status.available).toBeDefined();
      expect(status.last_checked).toBeInstanceOf(Date);
    });
  });
});
