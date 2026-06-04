import { describe, it, expect, vi, beforeEach } from 'vitest';
import { TemplateEngine } from '../../src/templates/TemplateEngine';
import { Template, NotificationType } from '../../src/types';
import { createMockDatabase } from '../utils/mocks';
import { db } from '../../src/db';

vi.mock('../../src/db');

describe('TemplateEngineV2 - 多租户模板覆盖', () => {
  let engine: TemplateEngine;
  const tenantA = 'tenant-a-123';
  const tenantB = 'tenant-b-456';
  const systemTenant = 'system-default';

  beforeEach(() => {
    vi.clearAllMocks();
    createMockDatabase();
    (TemplateEngine as any).instance = null;
    engine = TemplateEngine.getInstance();
  });

  describe('模板查找优先级', () => {
    it('优先使用租户自定义模板，fallback到系统默认', async () => {
      const tenantTemplateData = {
        notification_type: 'transactional' as NotificationType,
        locale: 'zh-CN',
        name: 'A租户-支付成功通知',
        body_template: '{{username}}，您在A平台的订单{{order_id}}支付成功！',
        html_template: '<p>{{username}}，您在A平台的订单{{order_id}}支付成功！</p>',
        subject_template: 'A平台订单支付成功',
        variables: ['username', 'order_id', 'amount'],
      };

      const systemTemplateData = {
        notification_type: 'transactional' as NotificationType,
        locale: 'zh-CN',
        name: '系统默认-支付成功通知',
        body_template: '{{username}}，您的订单{{order_id}}支付成功！',
        html_template: '<p>{{username}}，您的订单{{order_id}}支付成功！</p>',
        subject_template: '订单支付成功',
        variables: ['username', 'order_id', 'amount'],
      };

      await engine.createTenantTemplate(tenantA, tenantTemplateData);
      const systemTemplate = await engine.createSystemTemplate(systemTemplateData);

      const resultA = await engine.getTemplate(tenantA, 'transactional', 'zh-CN');
      expect(resultA).toBeDefined();
      expect(resultA?.is_system_default).toBe(false);
      expect(resultA?.fallback_from_system).toBe(false);
      expect(resultA?.name).toBe('A租户-支付成功通知');

      const resultB = await engine.getTemplate(tenantB, 'transactional', 'zh-CN');
      expect(resultB).toBeDefined();
      expect(resultB?.is_system_default).toBe(true);
      expect(resultB?.fallback_from_system).toBe(true);
      expect(resultB?.name).toBe('系统默认-支付成功通知');
      expect(resultB?.system_template_id).toBe(systemTemplate.id);
    });

    it('租户没有自定义模板时，自动使用系统默认', async () => {
      const systemTemplateData = {
        notification_type: 'password_reset' as NotificationType,
        locale: 'en',
        name: '系统默认-密码重置',
        body_template: 'Click here to reset your password: {{reset_link}}',
        html_template: '<p>Click here to reset your password: <a href="{{reset_link}}">{{reset_link}}</a></p>',
        subject_template: 'Reset Your Password',
        variables: ['username', 'reset_link'],
      };

      await engine.createSystemTemplate(systemTemplateData);

      const result = await engine.getTemplate(tenantA, 'password_reset', 'en');
      expect(result).toBeDefined();
      expect(result?.fallback_from_system).toBe(true);
      expect(result?.subject_template).toBe('Reset Your Password');
    });

    it('locale fallback机制 - 找不到zh-CN时fallback到en', async () => {
      const systemTemplateEnData = {
        notification_type: 'marketing' as NotificationType,
        locale: 'en',
        name: 'System - Marketing',
        body_template: 'Hello {{username}}!',
        subject_template: 'Hello!',
        variables: ['username'],
      };

      await engine.createSystemTemplate(systemTemplateEnData);

      const result = await engine.getTemplate(tenantA, 'marketing', 'zh-CN');
      expect(result).toBeDefined();
      expect(result?.locale).toBe('en');
      expect(result?.fallback_from_system).toBe(true);
    });
  });

  describe('租户模板管理', () => {
    it('创建租户自定义模板', async () => {
      const templateData = {
        notification_type: 'transactional' as NotificationType,
        locale: 'zh-CN',
        name: 'A租户-支付成功',
        subject_template: 'A平台订单支付成功',
        body_template: '{{username}}，您的订单{{order_id}}支付成功，金额{{amount}}元',
        html_template: '<p>{{username}}，您的订单{{order_id}}支付成功，金额{{amount}}元</p>',
        variables: ['username', 'order_id', 'amount'],
      };

      const result = await engine.createTenantTemplate(tenantA, templateData);
      expect(result).toBeDefined();
      expect(result.is_system_default).toBe(false);
      expect(result.tenant_id).toBe(tenantA);
      expect(result.name).toBe('A租户-支付成功');
    });

    it('更新租户自定义模板', async () => {
      const templateData = {
        notification_type: 'transactional' as NotificationType,
        locale: 'zh-CN',
        name: 'A租户-支付成功',
        body_template: '旧内容',
        variables: ['username'],
      };

      const created = await engine.createTenantTemplate(tenantA, templateData);

      const result = await engine.updateTenantTemplate(tenantA, created.id, {
        name: 'A租户-支付成功（更新）',
        body_template: '新内容 {{username}}',
      });

      expect(result).toBeDefined();
      expect(result?.name).toBe('A租户-支付成功（更新）');
      expect(result?.body_template).toBe('新内容 {{username}}');
    });

    it('删除租户自定义模板', async () => {
      const templateData = {
        notification_type: 'transactional' as NotificationType,
        locale: 'zh-CN',
        name: '待删除模板',
        body_template: '内容',
        variables: [],
      };

      const created = await engine.createTenantTemplate(tenantA, templateData);
      const result = await engine.deleteTenantTemplate(tenantA, created.id);
      expect(result).toBe(true);

      const fetched = await engine.getTemplate(tenantA, 'transactional', 'zh-CN');
      expect(fetched).toBeNull();
    });

    it('重置租户模板到系统默认', async () => {
      const tenantTemplateData = {
        notification_type: 'transactional' as NotificationType,
        locale: 'zh-CN',
        name: 'A租户-支付成功',
        body_template: '租户自定义内容',
        variables: ['username'],
      };

      const systemTemplateData = {
        notification_type: 'transactional' as NotificationType,
        locale: 'zh-CN',
        name: '系统默认-支付成功',
        body_template: '系统默认内容',
        variables: ['username'],
      };

      await engine.createTenantTemplate(tenantA, tenantTemplateData);
      await engine.createSystemTemplate(systemTemplateData);

      const beforeReset = await engine.getTemplate(tenantA, 'transactional', 'zh-CN');
      expect(beforeReset?.name).toBe('A租户-支付成功');
      expect(beforeReset?.fallback_from_system).toBe(false);

      const result = await engine.resetTenantTemplateToDefault(tenantA, 'transactional', 'zh-CN');
      expect(result).toBe(true);

      const afterReset = await engine.getTemplate(tenantA, 'transactional', 'zh-CN');
      expect(afterReset?.name).toBe('系统默认-支付成功');
      expect(afterReset?.fallback_from_system).toBe(true);
    });
  });

  describe('系统模板管理', () => {
    it('管理员创建系统默认模板', async () => {
      const templateData = {
        notification_type: 'security' as NotificationType,
        locale: 'en',
        name: 'System - Security Alert',
        subject_template: 'Security Alert',
        body_template: 'Dear {{username}}, we detected a login from {{location}}.',
        html_template: '<p>Dear {{username}}, we detected a login from {{location}}.</p>',
        variables: ['username', 'location', 'time'],
      };

      const result = await engine.createSystemTemplate(templateData);
      expect(result).toBeDefined();
      expect(result.is_system_default).toBe(true);
      expect(result.tenant_id).toBe(systemTenant);
    });
  });

  describe('模板列表', () => {
    it('列出所有模板，区分租户自定义和系统默认', async () => {
      const tenantTemplateData = {
        notification_type: 'transactional' as NotificationType,
        locale: 'zh-CN',
        name: 'A租户-支付成功',
        body_template: '...',
        variables: [],
      };

      const systemTemplateData1 = {
        notification_type: 'transactional' as NotificationType,
        locale: 'zh-CN',
        name: '系统默认-支付成功',
        body_template: '...',
        variables: [],
      };

      const systemTemplateData2 = {
        notification_type: 'password_reset' as NotificationType,
        locale: 'en',
        name: '系统默认-密码重置',
        body_template: '...',
        variables: [],
      };

      await engine.createTenantTemplate(tenantA, tenantTemplateData);
      await engine.createSystemTemplate(systemTemplateData1);
      await engine.createSystemTemplate(systemTemplateData2);

      const result = await engine.listTemplates(tenantA, true);

      expect(result.length).toBe(2);
      
      const tenantTpl = result.find((t) => !t.is_system_default);
      expect(tenantTpl).toBeDefined();
      expect(tenantTpl?.overrides_system).toBe(true);

      const systemTpls = result.filter((t) => t.is_system_default && t.fallback_from_system);
      expect(systemTpls.length).toBe(1);
      expect(systemTpls[0].notification_type).toBe('password_reset');
    });
  });

  describe('表驱动测试 - 多租户模板覆盖场景', () => {
    const testCases = [
      {
        name: '租户有自定义模板 - 使用租户模板',
        hasTenantTemplate: true,
        hasSystemTemplate: true,
        expectedSource: 'tenant',
        expectedFallback: false,
      },
      {
        name: '租户无自定义模板 - fallback到系统模板',
        hasTenantTemplate: false,
        hasSystemTemplate: true,
        expectedSource: 'system',
        expectedFallback: true,
      },
      {
        name: '都没有模板 - 返回null',
        hasTenantTemplate: false,
        hasSystemTemplate: false,
        expectedSource: 'none',
        expectedFallback: false,
      },
    ];

    testCases.forEach((tc) => {
      it(`模板查找: ${tc.name}`, async () => {
        if (tc.hasTenantTemplate) {
          await engine.createTenantTemplate(tenantA, {
            notification_type: 'transactional' as NotificationType,
            locale: 'en',
            name: 'Tenant Template',
            body_template: 'tenant version',
            variables: [],
          });
        }

        if (tc.hasSystemTemplate) {
          await engine.createSystemTemplate({
            notification_type: 'transactional' as NotificationType,
            locale: 'en',
            name: 'System Template',
            body_template: 'system version',
            variables: [],
          });
        }

        const result = await engine.getTemplate(tenantA, 'transactional', 'en');

        if (tc.expectedSource === 'none') {
          expect(result).toBeNull();
        } else {
          expect(result).toBeDefined();
          expect(result?.fallback_from_system).toBe(tc.expectedFallback);
          if (tc.expectedSource === 'tenant') {
            expect(result?.name).toBe('Tenant Template');
          } else {
            expect(result?.name).toBe('System Template');
          }
        }
      });
    });
  });

  describe('按ID查找模板', () => {
    it('按ID查找租户自定义模板', async () => {
      const created = await engine.createTenantTemplate(tenantA, {
        notification_type: 'transactional' as NotificationType,
        locale: 'en',
        name: 'Tenant Template by ID',
        body_template: 'by id version',
        variables: [],
      });

      const result = await engine.getTemplate(tenantA, 'transactional', created.id, 'en');
      expect(result).toBeDefined();
      expect(result?.id).toBe(created.id);
      expect(result?.fallback_from_system).toBe(false);
    });

    it('按ID查找系统默认模板', async () => {
      const created = await engine.createSystemTemplate({
        notification_type: 'transactional' as NotificationType,
        locale: 'en',
        name: 'System Template by ID',
        body_template: 'system by id version',
        variables: [],
      });

      const result = await engine.getTemplate(tenantA, 'transactional', created.id, 'en');
      expect(result).toBeDefined();
      expect(result?.id).toBe(created.id);
      expect(result?.fallback_from_system).toBe(true);
    });
  });

  describe('品牌定制场景', () => {
    it('A租户支付成功模板带品牌Logo', async () => {
      const templateVars = {
        username: '张三',
        order_id: 'ORD20240101001',
        amount: '999.00',
        brand_logo: 'https://a-tenant.com/logo.png',
      };

      await engine.createTenantTemplate(tenantA, {
        notification_type: 'transactional' as NotificationType,
        locale: 'zh-CN',
        name: 'A租户-支付成功',
        subject_template: '【A平台】订单支付成功',
        body_template: `{{username}}，您的订单{{order_id}}支付成功！\n金额：¥{{amount}}\n\n感谢您选择A平台！`,
        html_template: `<div style="font-family: Arial;">
          <img src="{{brand_logo}}" alt="A平台" style="width: 120px; margin-bottom: 20px;">
          <h2>订单支付成功</h2>
          <p>尊敬的{{username}}：</p>
          <p>您的订单 <strong>{{order_id}}</strong> 支付成功，金额：<strong>¥{{amount}}</strong></p>
          <p style="color: #888; font-size: 12px; margin-top: 30px;">© 2024 A平台 版权所有</p>
        </div>`,
        variables: ['username', 'order_id', 'amount', 'brand_logo'],
      });

      const content = await engine.render(tenantA, 'transactional', templateVars, 'zh-CN');
      
      expect(content).toBeDefined();
      expect(content?.subject).toBe('【A平台】订单支付成功');
      expect(content?.body).toContain('感谢您选择A平台！');
      expect(content?.html).toContain('https://a-tenant.com/logo.png');
      expect(content?.html).toContain('© 2024 A平台 版权所有');
    });

    it('B租户支付成功模板使用不同文案风格', async () => {
      const templateVars = {
        username: '李四',
        order_id: 'ORD-B-20240101',
        amount: '1999.00',
      };

      await engine.createTenantTemplate(tenantB, {
        notification_type: 'transactional' as NotificationType,
        locale: 'zh-CN',
        name: 'B租户-支付成功',
        subject_template: '🎉 支付成功 | 订单号 {{order_id}}',
        body_template: `Hi {{username}} 👋\n\n太棒了！您的订单{{order_id}}已经支付成功！\n金额：¥{{amount}}\n\n您的商品正在飞速赶来，请耐心等待～\n\n—— B商城团队`,
        html_template: `<div style="background: linear-gradient(135deg, #667eea 0%, #764ba2 100%); padding: 40px; border-radius: 12px; color: white;">
          <h1 style="margin: 0 0 20px 0; font-size: 28px;">🎉 支付成功！</h1>
          <p style="font-size: 16px; line-height: 1.8;">
            Hi {{username}} 👋<br>
            太棒了！您的订单 <strong style="font-size: 20px;">{{order_id}}</strong> 已经支付成功！<br>
            金额：<strong style="font-size: 24px;">¥{{amount}}</strong>
          </p>
          <p style="margin-top: 30px; opacity: 0.9;">您的商品正在飞速赶来，请耐心等待～</p>
          <p style="margin-top: 40px; font-size: 14px; opacity: 0.8;">—— B商城团队</p>
        </div>`,
        variables: ['username', 'order_id', 'amount'],
      });

      const content = await engine.render(tenantB, 'transactional', templateVars, 'zh-CN');
      
      expect(content).toBeDefined();
      expect(content?.subject).toContain('🎉');
      expect(content?.body).toContain('Hi 李四 👋');
      expect(content?.body).toContain('太棒了！');
      expect(content?.html).toContain('linear-gradient');
      expect(content?.html).toContain('🎉 支付成功！');
    });
  });
});
