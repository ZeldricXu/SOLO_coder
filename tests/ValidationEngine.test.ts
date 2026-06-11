import { describe, it, expect, vi } from 'vitest';
import { ValidationEngine, createValidationEngine } from '@validation';
import type { FieldSchema, ValidationResult } from '@validation';

describe('ValidationEngine', () => {
  describe('基础功能', () => {
    it('空schema返回isValid为true', async () => {
      const engine = new ValidationEngine([]);
      const result = await engine.validateAll({});
      expect(result.isValid).toBe(true);
      expect(result.errors.size).toBe(0);
    });

    it('createValidationEngine工厂函数创建实例', () => {
      const engine = createValidationEngine([]);
      expect(engine).toBeInstanceOf(ValidationEngine);
    });
  });

  describe('required校验', () => {
    const schemas: FieldSchema[] = [
      { name: 'username', rules: [{ type: 'required' }] },
    ];

    it('空值触发required错误', async () => {
      const engine = new ValidationEngine(schemas);
      const result = await engine.validateAll({ username: '' });
      expect(result.isValid).toBe(false);
      expect(result.errors.get('username')?.ruleType).toBe('required');
    });

    it('null值触发required错误', async () => {
      const engine = new ValidationEngine(schemas);
      const result = await engine.validateAll({ username: null });
      expect(result.isValid).toBe(false);
    });

    it('undefined值触发required错误', async () => {
      const engine = new ValidationEngine(schemas);
      const result = await engine.validateAll({ username: undefined });
      expect(result.isValid).toBe(false);
    });

    it('有值时通过required校验', async () => {
      const engine = new ValidationEngine(schemas);
      const result = await engine.validateAll({ username: 'hello' });
      expect(result.isValid).toBe(true);
    });

    it('自定义required错误消息', async () => {
      const engine = new ValidationEngine([
        { name: 'username', rules: [{ type: 'required', message: '请输入用户名' }] },
      ]);
      const result = await engine.validateAll({ username: '' });
      expect(result.errors.get('username')?.message).toBe('请输入用户名');
    });
  });

  describe('minLength校验', () => {
    const schemas: FieldSchema[] = [
      { name: 'name', rules: [{ type: 'minLength', value: 2 }] },
    ];

    it('长度不足触发minLength错误', async () => {
      const engine = new ValidationEngine(schemas);
      const result = await engine.validateAll({ name: 'a' });
      expect(result.isValid).toBe(false);
      expect(result.errors.get('name')?.ruleType).toBe('minLength');
    });

    it('长度满足通过校验', async () => {
      const engine = new ValidationEngine(schemas);
      const result = await engine.validateAll({ name: 'ab' });
      expect(result.isValid).toBe(true);
    });
  });

  describe('maxLength校验', () => {
    const schemas: FieldSchema[] = [
      { name: 'name', rules: [{ type: 'maxLength', value: 5 }] },
    ];

    it('超长触发maxLength错误', async () => {
      const engine = new ValidationEngine(schemas);
      const result = await engine.validateAll({ name: 'abcdef' });
      expect(result.isValid).toBe(false);
      expect(result.errors.get('name')?.ruleType).toBe('maxLength');
    });

    it('长度满足通过校验', async () => {
      const engine = new ValidationEngine(schemas);
      const result = await engine.validateAll({ name: 'abcde' });
      expect(result.isValid).toBe(true);
    });
  });

  describe('min/max校验', () => {
    it('min校验不通过', async () => {
      const engine = new ValidationEngine([
        { name: 'age', rules: [{ type: 'min', value: 18 }] },
      ]);
      const result = await engine.validateAll({ age: '15' });
      expect(result.isValid).toBe(false);
    });

    it('min校验通过', async () => {
      const engine = new ValidationEngine([
        { name: 'age', rules: [{ type: 'min', value: 18 }] },
      ]);
      const result = await engine.validateAll({ age: '20' });
      expect(result.isValid).toBe(true);
    });

    it('max校验不通过', async () => {
      const engine = new ValidationEngine([
        { name: 'score', rules: [{ type: 'max', value: 100 }] },
      ]);
      const result = await engine.validateAll({ score: '120' });
      expect(result.isValid).toBe(false);
    });
  });

  describe('email校验', () => {
    it('无效邮箱触发错误', async () => {
      const engine = new ValidationEngine([
        { name: 'email', rules: [{ type: 'email' }] },
      ]);
      const result = await engine.validateAll({ email: 'invalid' });
      expect(result.isValid).toBe(false);
      expect(result.errors.get('email')?.ruleType).toBe('email');
    });

    it('有效邮箱通过校验', async () => {
      const engine = new ValidationEngine([
        { name: 'email', rules: [{ type: 'email' }] },
      ]);
      const result = await engine.validateAll({ email: 'test@example.com' });
      expect(result.isValid).toBe(true);
    });

    it('空值不触发email校验（非required）', async () => {
      const engine = new ValidationEngine([
        { name: 'email', rules: [{ type: 'email' }] },
      ]);
      const result = await engine.validateAll({ email: '' });
      expect(result.isValid).toBe(true);
    });
  });

  describe('pattern校验', () => {
    it('不匹配正则触发错误', async () => {
      const engine = new ValidationEngine([
        { name: 'code', rules: [{ type: 'pattern', value: /^\d{6}$/ }] },
      ]);
      const result = await engine.validateAll({ code: 'abc' });
      expect(result.isValid).toBe(false);
    });

    it('匹配正则通过校验', async () => {
      const engine = new ValidationEngine([
        { name: 'code', rules: [{ type: 'pattern', value: /^\d{6}$/ }] },
      ]);
      const result = await engine.validateAll({ code: '123456' });
      expect(result.isValid).toBe(true);
    });
  });

  describe('customValidator自定义校验', () => {
    it('自定义校验函数返回错误', async () => {
      const engine = new ValidationEngine([
        {
          name: 'password',
          customValidator: (value) => {
            const str = String(value);
            if (str.length < 8) return '密码至少8个字符';
            if (!/[A-Z]/.test(str)) return '密码需要包含大写字母';
            return undefined;
          },
        },
      ]);
      const result = await engine.validateAll({ password: 'abc' });
      expect(result.isValid).toBe(false);
      expect(result.errors.get('password')?.message).toBe('密码至少8个字符');
    });

    it('自定义校验函数通过', async () => {
      const engine = new ValidationEngine([
        {
          name: 'password',
          customValidator: (value) => {
            const str = String(value);
            if (!/[A-Z]/.test(str)) return '密码需要包含大写字母';
            return undefined;
          },
        },
      ]);
      const result = await engine.validateAll({ password: 'Password1' });
      expect(result.isValid).toBe(true);
    });

    it('异步自定义校验函数', async () => {
      const engine = new ValidationEngine([
        {
          name: 'username',
          customValidator: async (value) => {
            await new Promise((r) => setTimeout(r, 10));
            if (value === 'taken') return '用户名已被占用';
            return undefined;
          },
        },
      ]);
      const result = await engine.validateAll({ username: 'taken' });
      expect(result.isValid).toBe(false);
      expect(result.errors.get('username')?.message).toBe('用户名已被占用');
    });
  });

  describe('多字段校验', () => {
    const schemas: FieldSchema[] = [
      { name: 'name', rules: [{ type: 'required' }, { type: 'minLength', value: 2 }] },
      { name: 'email', rules: [{ type: 'required' }, { type: 'email' }] },
      { name: 'age', rules: [{ type: 'min', value: 18 }] },
    ];

    it('多字段同时校验', async () => {
      const engine = new ValidationEngine(schemas);
      const result = await engine.validateAll({
        name: 'a',
        email: 'invalid',
        age: '15',
      });
      expect(result.isValid).toBe(false);
      expect(result.errors.size).toBe(3);
    });

    it('部分字段有错误', async () => {
      const engine = new ValidationEngine(schemas);
      const result = await engine.validateAll({
        name: '张三',
        email: 'invalid',
        age: '20',
      });
      expect(result.isValid).toBe(false);
      expect(result.errors.size).toBe(1);
      expect(result.errors.has('email')).toBe(true);
    });

    it('所有字段通过', async () => {
      const engine = new ValidationEngine(schemas);
      const result = await engine.validateAll({
        name: '张三',
        email: 'test@example.com',
        age: '20',
      });
      expect(result.isValid).toBe(true);
      expect(result.errors.size).toBe(0);
    });
  });

  describe('validateField单字段校验', () => {
    it('只校验指定字段', async () => {
      const engine = new ValidationEngine([
        { name: 'name', rules: [{ type: 'required' }] },
        { name: 'email', rules: [{ type: 'required' }] },
      ]);
      const error = await engine.validateField('name', '', { name: '', email: '' });
      expect(error).toBeDefined();
      expect(error?.field).toBe('name');
    });

    it('字段不在schema中返回undefined', async () => {
      const engine = new ValidationEngine([]);
      const error = await engine.validateField('unknown', '', {});
      expect(error).toBeUndefined();
    });
  });

  describe('同步步校验validateAllSync/validateFieldSync', () => {
    it('validateAllSync同步校验', () => {
      const engine = new ValidationEngine([
        { name: 'name', rules: [{ type: 'required' }] },
      ]);
      const result = engine.validateAllSync({ name: '' });
      expect(result.isValid).toBe(false);
    });

    it('validateFieldSync同步校验', () => {
      const engine = new ValidationEngine([
        { name: 'name', rules: [{ type: 'required' }] },
      ]);
      const error = engine.validateFieldSync('name', '', {});
      expect(error).toBeDefined();
    });
  });

  describe('动态schema管理', () => {
    it('addSchema添加新字段schema', async () => {
      const engine = new ValidationEngine([]);
      engine.addSchema({ name: 'username', rules: [{ type: 'required' }] });
      const result = await engine.validateAll({ username: '' });
      expect(result.isValid).toBe(false);
    });

    it('addSchema更新已有字段schema', async () => {
      const engine = new ValidationEngine([
        { name: 'name', rules: [{ type: 'required' }] },
      ]);
      engine.addSchema({ name: 'name', rules: [{ type: 'minLength', value: 3 }] });
      const result = await engine.validateAll({ name: 'ab' });
      expect(result.isValid).toBe(false);
      expect(result.errors.get('name')?.ruleType).toBe('minLength');
    });

    it('removeSchema移除字段schema', async () => {
      const engine = new ValidationEngine([
        { name: 'name', rules: [{ type: 'required' }] },
      ]);
      engine.removeSchema('name');
      const result = await engine.validateAll({ name: '' });
      expect(result.isValid).toBe(true);
    });

    it('setSchemas替换全部schema', async () => {
      const engine = new ValidationEngine([
        { name: 'name', rules: [{ type: 'required' }] },
      ]);
      engine.setSchemas([{ name: 'email', rules: [{ type: 'email' }] }]);
      const schemas = engine.getSchemas();
      expect(schemas.length).toBe(1);
      expect(schemas[0]!.name).toBe('email');
    });

    it('getSchemas返回副本', () => {
      const engine = new ValidationEngine([
        { name: 'name', rules: [{ type: 'required' }] },
      ]);
      const schemas = engine.getSchemas();
      schemas.push({ name: 'extra', rules: [] });
      expect(engine.getSchemas().length).toBe(1);
    });
  });

  describe('校验规则优先级', () => {
    it('规则按顺序执行，遇到第一个错误即停止', async () => {
      const engine = new ValidationEngine([
        {
          name: 'field',
          rules: [
            { type: 'required', message: '必填' },
            { type: 'minLength', value: 5, message: '太短' },
          ],
        },
      ]);
      const result = await engine.validateAll({ field: '' });
      expect(result.errors.get('field')?.message).toBe('必填');
    });

    it('第一个规则通过后继续校验后续规则', async () => {
      const engine = new ValidationEngine([
        {
          name: 'field',
          rules: [
            { type: 'required' },
            { type: 'minLength', value: 5, message: '至少5个字符' },
          ],
        },
      ]);
      const result = await engine.validateAll({ field: 'abc' });
      expect(result.errors.get('field')?.message).toBe('至少5个字符');
    });
  });

  describe('ValidationResult结构', () => {
    it('errors是Map类型', async () => {
      const engine = new ValidationEngine([
        { name: 'name', rules: [{ type: 'required' }] },
      ]);
      const result = await engine.validateAll({ name: '' });
      expect(result.errors).toBeInstanceOf(Map);
    });

    it('FieldError包含field、message、ruleType', async () => {
      const engine = new ValidationEngine([
        { name: 'name', rules: [{ type: 'required', message: '必填' }] },
      ]);
      const result = await engine.validateAll({ name: '' });
      const error = result.errors.get('name');
      expect(error).toEqual({
        field: 'name',
        message: '必填',
        ruleType: 'required',
      });
    });
  });
});
