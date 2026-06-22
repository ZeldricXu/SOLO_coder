import { describe, it, expect } from 'vitest';
import { validateCreateOptions } from '../../src/index.js';
import type { CreateOptions } from '../../src/index.js';

describe('命令参数校验', () => {
  describe('合法参数组合', () => {
    it('只指定项目名，无冲突', () => {
      const options: CreateOptions = {};
      const errors = validateCreateOptions(options);
      expect(errors).toHaveLength(0);
    });

    it('只指定框架，合法', () => {
      const options: CreateOptions = { framework: 'react-frontend' };
      const errors = validateCreateOptions(options);
      expect(errors).toHaveLength(0);
    });

    it('只指定模板，合法', () => {
      const options: CreateOptions = { template: '/path/to/template' };
      const errors = validateCreateOptions(options);
      expect(errors).toHaveLength(0);
    });

    it('框架 + 包管理器，合法', () => {
      const options: CreateOptions = {
        framework: 'node-backend',
        packageManager: 'pnpm',
      };
      const errors = validateCreateOptions(options);
      expect(errors).toHaveLength(0);
    });

    it('框架 + Docker + CI，合法', () => {
      const options: CreateOptions = {
        framework: 'vue-frontend',
        docker: true,
        ci: 'github',
      };
      const errors = validateCreateOptions(options);
      expect(errors).toHaveLength(0);
    });

    it('quiet + 框架，合法', () => {
      const options: CreateOptions = {
        quiet: true,
        framework: 'cli-tool',
      };
      const errors = validateCreateOptions(options);
      expect(errors).toHaveLength(0);
    });

    it('所有框架值都合法', () => {
      const frameworks = ['node-backend', 'react-frontend', 'vue-frontend', 'cli-tool'];
      for (const framework of frameworks) {
        const options: CreateOptions = { framework };
        const errors = validateCreateOptions(options);
        expect(errors).toHaveLength(0);
      }
    });

    it('所有包管理器值都合法', () => {
      const pms = ['npm', 'yarn', 'pnpm'];
      for (const pm of pms) {
        const options: CreateOptions = { packageManager: pm };
        const errors = validateCreateOptions(options);
        expect(errors).toHaveLength(0);
      }
    });

    it('所有CI提供方值都合法', () => {
      const cis = ['github', 'gitlab', 'none'];
      for (const ci of cis) {
        const options: CreateOptions = { ci };
        const errors = validateCreateOptions(options);
        expect(errors).toHaveLength(0);
      }
    });

    it('所有部署目标值都合法', () => {
      const deploys = ['docker', 'k8s', 'none'];
      for (const deploy of deploys) {
        const options: CreateOptions = { deploy };
        const errors = validateCreateOptions(options);
        expect(errors).toHaveLength(0);
      }
    });
  });

  describe('参数冲突 - template相关', () => {
    it('同时指定 --template 和 --framework 应该报错', () => {
      const options: CreateOptions = {
        template: '/path/to/template',
        framework: 'react-frontend',
      };
      const errors = validateCreateOptions(options);
      expect(errors.length).toBeGreaterThan(0);
      const conflictError = errors.find(e => e.code === 'TEMPLATE_FRAMEWORK_CONFLICT');
      expect(conflictError).toBeDefined();
      expect(conflictError?.message).toContain('冲突');
    });

    it('同时指定 --template 和 --docker 应该报错', () => {
      const options: CreateOptions = {
        template: '/path/to/template',
        docker: true,
      };
      const errors = validateCreateOptions(options);
      const conflictError = errors.find(e => e.code === 'TEMPLATE_DOCKER_CONFLICT');
      expect(conflictError).toBeDefined();
    });

    it('同时指定 --template 和 --no-docker 应该报错', () => {
      const options: CreateOptions = {
        template: '/path/to/template',
        docker: false,
      };
      const errors = validateCreateOptions(options);
      const conflictError = errors.find(e => e.code === 'TEMPLATE_DOCKER_CONFLICT');
      expect(conflictError).toBeDefined();
    });

    it('同时指定 --template 和 --ci 应该报错', () => {
      const options: CreateOptions = {
        template: '/path/to/template',
        ci: 'github',
      };
      const errors = validateCreateOptions(options);
      const conflictError = errors.find(e => e.code === 'TEMPLATE_CI_CONFLICT');
      expect(conflictError).toBeDefined();
    });

    it('同时指定 --template 和 --deploy 应该报错', () => {
      const options: CreateOptions = {
        template: '/path/to/template',
        deploy: 'k8s',
      };
      const errors = validateCreateOptions(options);
      const conflictError = errors.find(e => e.code === 'TEMPLATE_DEPLOY_CONFLICT');
      expect(conflictError).toBeDefined();
    });

    it('同时指定 --template 和多个冲突参数，应该报多个错', () => {
      const options: CreateOptions = {
        template: '/path/to/template',
        framework: 'react-frontend',
        docker: true,
        ci: 'github',
        deploy: 'k8s',
      };
      const errors = validateCreateOptions(options);
      expect(errors.length).toBeGreaterThanOrEqual(4);
    });
  });

  describe('无效参数值', () => {
    it('无效的框架值应该报错', () => {
      const options: CreateOptions = { framework: 'invalid-framework' };
      const errors = validateCreateOptions(options);
      const error = errors.find(e => e.code === 'INVALID_FRAMEWORK');
      expect(error).toBeDefined();
      expect(error?.message).toContain('无效的框架');
    });

    it('无效的包管理器值应该报错', () => {
      const options: CreateOptions = { packageManager: 'bun' };
      const errors = validateCreateOptions(options);
      const error = errors.find(e => e.code === 'INVALID_PACKAGE_MANAGER');
      expect(error).toBeDefined();
    });

    it('无效的CI提供方值应该报错', () => {
      const options: CreateOptions = { ci: 'jenkins' };
      const errors = validateCreateOptions(options);
      const error = errors.find(e => e.code === 'INVALID_CI_PROVIDER');
      expect(error).toBeDefined();
    });

    it('无效的部署目标值应该报错', () => {
      const options: CreateOptions = { deploy: 'ecs' };
      const errors = validateCreateOptions(options);
      const error = errors.find(e => e.code === 'INVALID_DEPLOY_TARGET');
      expect(error).toBeDefined();
    });
  });

  describe('边界情况', () => {
    it('空options应该无错误', () => {
      const errors = validateCreateOptions({});
      expect(errors).toHaveLength(0);
    });

    it('quiet模式与任何参数都不冲突', () => {
      const options: CreateOptions = {
        quiet: true,
        framework: 'node-backend',
        docker: true,
        ci: 'github',
        packageManager: 'pnpm',
      };
      const errors = validateCreateOptions(options);
      expect(errors).toHaveLength(0);
    });

    it('author和description参数总是合法', () => {
      const options: CreateOptions = {
        author: 'Test Author',
        description: 'Test description',
        template: '/path/to/template',
      };
      const errors = validateCreateOptions(options);
      const hasConflict = errors.some(e => e.code.startsWith('TEMPLATE_') && e.code.includes('AUTHOR'));
      expect(hasConflict).toBe(false);
    });

    it('git-remote参数总是合法', () => {
      const options: CreateOptions = {
        gitRemote: 'https://github.com/user/repo.git',
        template: '/path/to/template',
      };
      const errors = validateCreateOptions(options);
      const hasConflict = errors.some(e => e.code.includes('GIT_REMOTE'));
      expect(hasConflict).toBe(false);
    });

    it('force参数总是合法', () => {
      const options: CreateOptions = {
        force: true,
        template: '/path/to/template',
      };
      const errors = validateCreateOptions(options);
      expect(errors).toHaveLength(0);
    });

    it('pre-commit参数总是合法', () => {
      const options: CreateOptions = {
        preCommit: false,
        template: '/path/to/template',
      };
      const errors = validateCreateOptions(options);
      expect(errors).toHaveLength(0);
    });
  });
});
