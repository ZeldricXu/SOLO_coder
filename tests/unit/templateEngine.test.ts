import { describe, it, expect, vi, beforeEach } from 'vitest';
import { TemplateEngine } from '../../src/templates/TemplateEngine';
import { db } from '../../src/db';
import { createTemplate, createNotificationRequest, runTableDrivenTests } from '../utils/factories';
import { v4 as uuidv4 } from 'uuid';

vi.mock('../../src/db');

describe('TemplateEngine', () => {
  let templateEngine: TemplateEngine;
  const tenantId = uuidv4();

  beforeEach(() => {
    vi.clearAllMocks();
    vi.useRealTimers();
    
    (TemplateEngine as any).instance = null;
    vi.mocked(db.withTenantContext).mockImplementation(async (tid, fn) => fn());
    
    templateEngine = TemplateEngine.getInstance();
  });

  describe('正常路径测试', () => {
    it('给完整上下文对象渲染后内容与预期Handlebars输出精确匹配', async () => {
      const templateData = createTemplate({
        tenant_id: tenantId,
        subject_template: 'Hello {{name}}! Your order #{{order_id}}',
        body_template: `Hi {{name}},\n\nYour order #{{order_id}} has been confirmed.\n\nTotal: \${{amount}}\n\n{{#if is_high_value}}<strong>High value order!</strong>{{/if}}\n\nThank you for shopping with {{company_name}}!`,
        html_template: `<p>Hi {{name}},</p><p>Your order #{{order_id}} has been confirmed.</p><p>Total: \${{amount}}</p>{{#if is_high_value}}<p style="color:red;"><strong>High value order!</strong></p>{{/if}}<p>Thank you for shopping with {{company_name}}!</p>`,
        variables: ['name', 'order_id', 'amount', 'is_high_value', 'company_name'],
      });

      const context = {
        name: '张三',
        order_id: 'ORD-1234567890',
        amount: 2999.99,
        is_high_value: true,
        company_name: 'Example Corp',
      };

      vi.mocked(db.query).mockResolvedValueOnce({
        rows: [{
          id: 'tpl-1',
          ...templateData,
          created_at: new Date(),
          updated_at: new Date(),
        }],
        rowCount: 1,
      });

      const result = await templateEngine.render(
        tenantId,
        'transactional',
        context,
        'en'
      );

      expect(result).toBeDefined();
      expect(result!.subject).toBe('Hello 张三! Your order #ORD-1234567890');
      expect(result!.body).toContain('Hi 张三,');
      expect(result!.body).toContain('Your order #ORD-1234567890 has been confirmed.');
      expect(result!.body).toContain('Total: $2999.99');
      expect(result!.body).toContain('<strong>High value order!</strong>');
      expect(result!.body).toContain('Thank you for shopping with Example Corp!');
      expect(result!.html).toContain('<p>Hi 张三,</p>');
      expect(result!.html).toContain('style="color:red;"');
      expect(result!.html).toContain('<strong>High value order!</strong>');
    });

    it('条件渲染 - 金额大于阈值时显示红色警告', async () => {
      const templateData = createTemplate({
        body_template: `{{#if_gt amount 1000}}<span style="color:red;">WARNING: Large amount!</span>{{/if_gt}}Amount: \${{amount}}`,
        variables: ['amount'],
      });

      const contextHigh = { amount: 1500 };
      const contextLow = { amount: 500 };

      const result1 = await templateEngine.preview(
        { body_template: templateData.body_template },
        contextHigh
      );
      expect(result1.body).toContain('style="color:red;"');
      expect(result1.body).toContain('WARNING: Large amount!');
      expect(result1.body).toContain('Amount: $1500');

      const result2 = await templateEngine.preview(
        { body_template: templateData.body_template },
        contextLow
      );
      expect(result2.body).not.toContain('style="color:red;"');
      expect(result2.body).not.toContain('WARNING: Large amount!');
      expect(result2.body).toContain('Amount: $500');
    });

    it('按 notification_type + locale 查找对应模板', async () => {
      vi.mocked(db.query).mockResolvedValueOnce({
        rows: [{
          id: 'tpl-en',
          ...createTemplate({ tenant_id: tenantId, locale: 'en' }),
          created_at: new Date(),
          updated_at: new Date(),
        }],
        rowCount: 1,
      });

      const templateEn = await templateEngine.getTemplate(tenantId, 'transactional', 'en');
      expect(templateEn).toBeDefined();
      expect(templateEn!.locale).toBe('en');

      vi.mocked(db.query).mockResolvedValueOnce({ rows: [], rowCount: 0 });
      vi.mocked(db.query).mockResolvedValueOnce({
        rows: [{
          id: 'tpl-en-fallback',
          ...createTemplate({ tenant_id: tenantId, locale: 'en' }),
          created_at: new Date(),
          updated_at: new Date(),
        }],
        rowCount: 1,
      });

      const templateZhFallback = await templateEngine.getTemplate(tenantId, 'transactional', 'zh-CN');
      expect(templateZhFallback).toBeDefined();
      expect(templateZhFallback!.locale).toBe('en');
    });

    it('Handlebars helper函数正常工作', async () => {
      const tests = [
        {
          template: '{{#if_eq status "active"}}Active{{else}}Inactive{{/if_eq}}',
          context: { status: 'active' },
          expected: 'Active',
        },
        {
          template: '{{#if_gt score 90}}Excellent{{/if_gt}}',
          context: { score: 95 },
          expected: 'Excellent',
        },
        {
          template: '{{#if_lt count 10}}Low stock{{/if_lt}}',
          context: { count: 5 },
          expected: 'Low stock',
        },
        {
          template: '{{truncate description 10}}',
          context: { description: 'This is a very long description' },
          expected: 'This is a ...',
        },
        {
          template: '{{currency price}}',
          context: { price: 1234.56 },
          expected: expect.stringContaining('1,234.56'),
        },
      ];

      for (const test of tests) {
        const result = await templateEngine.preview(
          { body_template: test.template },
          test.context
        );
        if (typeof test.expected === 'string') {
          expect(result.body).toBe(test.expected);
        } else {
          expect(result.body).toEqual(test.expected);
        }
      }
    });
  });

  describe('异常路径测试', () => {
    it('收到缺少必需变量的context时用空字符串替换而非报错', async () => {
      const templateData = createTemplate({
        body_template: 'Hello {{name}}, your code is {{code}}',
        variables: ['name', 'code'],
      });

      const context = { name: 'Alice' };

      const result = await templateEngine.preview(
        { body_template: templateData.body_template },
        context
      );

      expect(result.body).toBe('Hello Alice, your code is ');
      expect(result.body).not.toContain('{{code}}');
    });

    it('模板未找到时返回null', async () => {
      vi.mocked(db.query).mockResolvedValue({ rows: [], rowCount: 0 });

      const result = await templateEngine.render(
        tenantId,
        'non_existent_type',
        {},
        'en'
      );

      expect(result).toBeNull();
    });

    it('无效Handlebars语法时抛出错误', async () => {
      const invalidTemplate = 'Hello {{name';

      await expect(
        templateEngine.preview({ body_template: invalidTemplate }, { name: 'test' })
      ).rejects.toThrow();
    });

    it('数据库查询失败时传递异常', async () => {
      vi.mocked(db.withTenantContext).mockRejectedValueOnce(new Error('DB connection error'));

      await expect(
        templateEngine.getTemplate(tenantId, 'transactional', 'en')
      ).rejects.toThrow('DB connection error');
    });
  });

  describe('表驱动测试 - 变量插值与默认值', () => {
    interface TemplateTestCase {
      template: string;
      context: Record<string, any>;
      expected: string;
    }

    const testCases: { name: string; input: TemplateTestCase; expected: string }[] = [
      {
        name: '简单变量插值',
        input: {
          template: 'Hello {{name}}!',
          context: { name: 'World' },
          expected: 'Hello World!',
        },
        expected: 'Hello World!',
      },
      {
        name: '嵌套对象属性访问',
        input: {
          template: 'Order: {{order.id}} - {{order.total}}',
          context: { order: { id: '123', total: 99.99 } },
          expected: 'Order: 123 - 99.99',
        },
        expected: 'Order: 123 - 99.99',
      },
      {
        name: '数组迭代',
        input: {
          template: '{{#each items}}{{this}}{{#unless @last}}, {{/unless}}{{/each}}',
          context: { items: ['A', 'B', 'C'] },
          expected: 'A, B, C',
        },
        expected: 'A, B, C',
      },
      {
        name: 'with块作用域',
        input: {
          template: '{{#with user}}Name: {{name}}, Age: {{age}}{{/with}}',
          context: { user: { name: 'Bob', age: 30 } },
          expected: 'Name: Bob, Age: 30',
        },
        expected: 'Name: Bob, Age: 30',
      },
      {
        name: '内联if表达式',
        input: {
          template: 'Status: {{#if active}}Active{{else}}Inactive{{/if}}',
          context: { active: false },
          expected: 'Status: Inactive',
        },
        expected: 'Status: Inactive',
      },
      {
        name: '缺失变量用空字符串替换',
        input: {
          template: 'User: {{username}} - {{email}}',
          context: { username: 'john_doe' },
          expected: 'User: john_doe - ',
        },
        expected: 'User: john_doe - ',
      },
      {
        name: 'HTML转义',
        input: {
          template: '{{content}}',
          context: { content: '<script>alert("xss")</script>' },
          expected: '&lt;script&gt;alert(&quot;xss&quot;)&lt;/script&gt;',
        },
        expected: '&lt;script&gt;alert(&quot;xss&quot;)&lt;/script&gt;',
      },
      {
        name: '三重大括号不转义',
        input: {
          template: '{{{content}}}',
          context: { content: '<strong>bold</strong>' },
          expected: '<strong>bold</strong>',
        },
        expected: '<strong>bold</strong>',
      },
    ];

    runTableDrivenTests<TemplateTestCase, string>(testCases, async (input, expected) => {
      const result = await templateEngine.preview(
        { body_template: input.template },
        input.context
      );
      expect(result.body).toBe(expected);
    });
  });

  describe('模板管理测试', () => {
    it('创建模板成功', async () => {
      const templateData = createTemplate({ tenant_id: tenantId });
      const expectedId = uuidv4();

      vi.mocked(db.query).mockResolvedValueOnce({
        rows: [{ id: expectedId, ...templateData, created_at: new Date(), updated_at: new Date() }],
        rowCount: 1,
      });

      const result = await templateEngine.createTemplate(tenantId, templateData as any);

      expect(result.id).toBe(expectedId);
      expect(result.name).toBe(templateData.name);
    });

    it('更新模板成功', async () => {
      const templateId = uuidv4();
      const updates = { name: 'Updated Template', body_template: 'New body' };

      vi.mocked(db.query).mockResolvedValueOnce({
        rows: [{ id: templateId, ...updates, created_at: new Date(), updated_at: new Date() }],
        rowCount: 1,
      });

      const result = await templateEngine.updateTemplate(tenantId, templateId, updates);

      expect(result).toBeDefined();
      expect(result!.name).toBe('Updated Template');
      expect(result!.body_template).toBe('New body');
    });

    it('更新不存在的模板返回null', async () => {
      vi.mocked(db.query).mockResolvedValueOnce({ rows: [], rowCount: 0 });

      const result = await templateEngine.updateTemplate(tenantId, uuidv4(), { name: 'Test' });

      expect(result).toBeNull();
    });
  });

  describe('缓存测试', () => {
    it('多次渲染相同模板使用缓存', async () => {
      const templateId = uuidv4();
      const now = new Date();

      vi.mocked(db.query).mockResolvedValueOnce({
        rows: [{
          id: templateId,
          ...createTemplate({ tenant_id: tenantId }),
          created_at: now,
          updated_at: now,
        }],
        rowCount: 1,
      });

      const context = { name: 'Test', order_id: '123', amount: 100, is_high_value: false };

      const result1 = await templateEngine.render(tenantId, 'transactional', context, 'en');
      const result2 = await templateEngine.render(tenantId, 'transactional', context, 'en');

      expect(result1).toBeDefined();
      expect(result2).toBeDefined();
      expect(db.query).toHaveBeenCalledTimes(1);
    });

    it('模板更新后缓存被清除', async () => {
      const templateId = uuidv4();
      const now = new Date();

      vi.mocked(db.query).mockResolvedValueOnce({
        rows: [{
          id: templateId,
          ...createTemplate({ tenant_id: tenantId, body_template: 'Original' }),
          created_at: now,
          updated_at: now,
        }],
        rowCount: 1,
      });

      const result1 = await templateEngine.render(tenantId, 'transactional', {}, 'en');
      expect(result1!.body).toBe('Original');

      templateEngine.clearCache();

      const newNow = new Date(now.getTime() + 1000);
      vi.mocked(db.query).mockResolvedValueOnce({
        rows: [{
          id: templateId,
          ...createTemplate({ tenant_id: tenantId, body_template: 'Updated' }),
          created_at: now,
          updated_at: newNow,
        }],
        rowCount: 1,
      });

      const result2 = await templateEngine.render(tenantId, 'transactional', {}, 'en');
      expect(result2!.body).toBe('Updated');
    });
  });

  describe('模板预览API', () => {
    it('preview接口无需保存模板即可渲染', async () => {
      const result = await templateEngine.preview(
        {
          subject_template: 'Preview: {{title}}',
          body_template: 'This is a preview: {{message}}',
          html_template: '<p>This is a preview: {{message}}</p>',
        },
        { title: 'Test Preview', message: 'Hello World' }
      );

      expect(result.subject).toBe('Preview: Test Preview');
      expect(result.body).toBe('This is a preview: Hello World');
      expect(result.html).toBe('<p>This is a preview: Hello World</p>');
    });
  });
});
