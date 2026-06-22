import { describe, it, expect, vi, beforeEach } from 'vitest';
import { runInteractiveWizard, confirmOverwrite } from '../../src/prompts.js';
import type { ProjectConfig, PackageManagerType, FrameworkType } from '../../src/types.js';
import { globalState } from '../../src/state.js';

vi.mock('../../src/state.js', () => ({
  globalState: {
    getPreferences: vi.fn(() => ({})),
  },
}));

vi.mock('inquirer', () => {
  let mockAnswers: Record<string, unknown> = {};

  return {
    default: {
      prompt: vi.fn(async () => mockAnswers),
    },
    __setMockAnswers: (answers: Record<string, unknown>) => {
      mockAnswers = answers;
    },
  };
});

describe('交互式向导测试', () => {
  const availablePMs: PackageManagerType[] = ['npm', 'yarn', 'pnpm'];

  beforeEach(() => {
    vi.clearAllMocks();
    (globalState.getPreferences as ReturnType<typeof vi.fn>).mockReturnValue({});
  });

  function setMockAnswers(answers: Record<string, unknown>) {
    const inquirer = vi.mocked(vi.importMock('inquirer'));
    (inquirer as unknown as { __setMockAnswers: (answers: Record<string, unknown>) => void }).__setMockAnswers(answers);
  }

  describe('基本配置项验证', () => {
    it('应该返回包含所有字段的配置对象', async () => {
      const mockAnswers = {
        projectName: 'my-test-app',
        description: 'A test project',
        author: 'Test Developer',
        framework: 'react-frontend' as FrameworkType,
        packageManager: 'pnpm' as PackageManagerType,
        useDocker: true,
        useCI: true,
        ciProvider: 'github',
        deployTarget: 'docker',
        usePreCommitHook: true,
        gitRemoteUrl: 'https://github.com/test/repo.git',
      };

      setMockAnswers(mockAnswers);

      const result = await runInteractiveWizard({}, availablePMs);

      expect(result.projectName).toBe('my-test-app');
      expect(result.description).toBe('A test project');
      expect(result.author).toBe('Test Developer');
      expect(result.framework).toBe('react-frontend');
      expect(result.packageManager).toBe('pnpm');
      expect(result.useDocker).toBe(true);
      expect(result.useCI).toBe(true);
      expect(result.ciProvider).toBe('github');
      expect(result.deployTarget).toBe('docker');
      expect(result.usePreCommitHook).toBe(true);
      expect(result.gitRemoteUrl).toBe('https://github.com/test/repo.git');
    });
  });

  describe('默认值与用户偏好', () => {
    it('应该使用传入的defaults值', async () => {
      const defaults: Partial<ProjectConfig> = {
        projectName: 'preset-name',
        framework: 'vue-frontend',
      };

      const mockAnswers = {
        description: 'desc',
        author: 'author',
        packageManager: 'npm',
        useDocker: true,
        useCI: true,
        ciProvider: 'github',
        deployTarget: 'docker',
        usePreCommitHook: true,
      };

      setMockAnswers(mockAnswers);

      const result = await runInteractiveWizard(defaults, availablePMs);

      expect(result.projectName).toBe('preset-name');
      expect(result.framework).toBe('vue-frontend');
    });

    it('当CI为false时，ciProvider应为none', async () => {
      const mockAnswers = {
        projectName: 'test',
        description: 'test',
        author: 'test',
        framework: 'node-backend' as FrameworkType,
        packageManager: 'npm' as PackageManagerType,
        useDocker: true,
        useCI: false,
        ciProvider: 'github',
        deployTarget: 'docker',
        usePreCommitHook: true,
      };

      setMockAnswers(mockAnswers);

      const result = await runInteractiveWizard({}, availablePMs);

      expect(result.useCI).toBe(false);
      expect(result.ciProvider).toBe('none');
    });

    it('当Docker为false时，deployTarget应为none', async () => {
      const mockAnswers = {
        projectName: 'test',
        description: 'test',
        author: 'test',
        framework: 'node-backend' as FrameworkType,
        packageManager: 'npm' as PackageManagerType,
        useDocker: false,
        useCI: true,
        ciProvider: 'github',
        deployTarget: 'k8s',
        usePreCommitHook: true,
      };

      setMockAnswers(mockAnswers);

      const result = await runInteractiveWizard({}, availablePMs);

      expect(result.useDocker).toBe(false);
      expect(result.deployTarget).toBe('none');
    });
  });

  describe('框架选项路径验证', () => {
    const frameworks: FrameworkType[] = ['node-backend', 'react-frontend', 'vue-frontend', 'cli-tool'];

    for (const framework of frameworks) {
      it(`应该正确处理框架: ${framework}`, async () => {
        const mockAnswers = {
          projectName: `test-${framework}`,
          description: 'test',
          author: 'test',
          framework,
          packageManager: 'npm' as PackageManagerType,
          useDocker: true,
          useCI: true,
          ciProvider: 'github',
          deployTarget: 'docker',
          usePreCommitHook: true,
        };

        setMockAnswers(mockAnswers);

        const result = await runInteractiveWizard({}, availablePMs);

        expect(result.framework).toBe(framework);
      });
    }
  });

  describe('包管理器选项', () => {
    it('应该支持所有包管理器', async () => {
      for (const pm of availablePMs) {
        const mockAnswers = {
          projectName: 'test',
          description: 'test',
          author: 'test',
          framework: 'node-backend' as FrameworkType,
          packageManager: pm,
          useDocker: true,
          useCI: true,
          ciProvider: 'github',
          deployTarget: 'docker',
          usePreCommitHook: true,
        };

        setMockAnswers(mockAnswers);

        const result = await runInteractiveWizard({}, availablePMs);

        expect(result.packageManager).toBe(pm);
      }
    });
  });

  describe('CI/CD选项路径', () => {
    it('选择GitHub CI', async () => {
      const mockAnswers = {
        projectName: 'test',
        description: 'test',
        author: 'test',
        framework: 'node-backend' as FrameworkType,
        packageManager: 'npm' as PackageManagerType,
        useDocker: true,
        useCI: true,
        ciProvider: 'github',
        deployTarget: 'docker',
        usePreCommitHook: true,
      };

      setMockAnswers(mockAnswers);

      const result = await runInteractiveWizard({}, availablePMs);

      expect(result.useCI).toBe(true);
      expect(result.ciProvider).toBe('github');
    });

    it('选择GitLab CI', async () => {
      const mockAnswers = {
        projectName: 'test',
        description: 'test',
        author: 'test',
        framework: 'node-backend' as FrameworkType,
        packageManager: 'npm' as PackageManagerType,
        useDocker: true,
        useCI: true,
        ciProvider: 'gitlab',
        deployTarget: 'docker',
        usePreCommitHook: true,
      };

      setMockAnswers(mockAnswers);

      const result = await runInteractiveWizard({}, availablePMs);

      expect(result.ciProvider).toBe('gitlab');
    });

    it('选择不使用CI', async () => {
      const mockAnswers = {
        projectName: 'test',
        description: 'test',
        author: 'test',
        framework: 'node-backend' as FrameworkType,
        packageManager: 'npm' as PackageManagerType,
        useDocker: true,
        useCI: false,
        ciProvider: 'none',
        deployTarget: 'docker',
        usePreCommitHook: true,
      };

      setMockAnswers(mockAnswers);

      const result = await runInteractiveWizard({}, availablePMs);

      expect(result.useCI).toBe(false);
      expect(result.ciProvider).toBe('none');
    });
  });

  describe('部署目标选项', () => {
    it('选择Docker部署', async () => {
      const mockAnswers = {
        projectName: 'test',
        description: 'test',
        author: 'test',
        framework: 'node-backend' as FrameworkType,
        packageManager: 'npm' as PackageManagerType,
        useDocker: true,
        useCI: true,
        ciProvider: 'github',
        deployTarget: 'docker',
        usePreCommitHook: true,
      };

      setMockAnswers(mockAnswers);

      const result = await runInteractiveWizard({}, availablePMs);

      expect(result.deployTarget).toBe('docker');
    });

    it('选择K8s部署', async () => {
      const mockAnswers = {
        projectName: 'test',
        description: 'test',
        author: 'test',
        framework: 'node-backend' as FrameworkType,
        packageManager: 'npm' as PackageManagerType,
        useDocker: true,
        useCI: true,
        ciProvider: 'github',
        deployTarget: 'k8s',
        usePreCommitHook: true,
      };

      setMockAnswers(mockAnswers);

      const result = await runInteractiveWizard({}, availablePMs);

      expect(result.deployTarget).toBe('k8s');
    });
  });

  describe('边界情况', () => {
    it('gitRemoteUrl为空字符串时应该返回空字符串', async () => {
      const mockAnswers = {
        projectName: 'test',
        description: 'test',
        author: 'test',
        framework: 'node-backend' as FrameworkType,
        packageManager: 'npm' as PackageManagerType,
        useDocker: true,
        useCI: true,
        ciProvider: 'github',
        deployTarget: 'docker',
        usePreCommitHook: true,
        gitRemoteUrl: '',
      };

      setMockAnswers(mockAnswers);

      const result = await runInteractiveWizard({}, availablePMs);

      expect(result.gitRemoteUrl).toBe('');
    });

    it('不安装pre-commit hook', async () => {
      const mockAnswers = {
        projectName: 'test',
        description: 'test',
        author: 'test',
        framework: 'node-backend' as FrameworkType,
        packageManager: 'npm' as PackageManagerType,
        useDocker: true,
        useCI: true,
        ciProvider: 'github',
        deployTarget: 'docker',
        usePreCommitHook: false,
      };

      setMockAnswers(mockAnswers);

      const result = await runInteractiveWizard({}, availablePMs);

      expect(result.usePreCommitHook).toBe(false);
    });
  });
});

describe('确认覆盖对话框', () => {
  beforeEach(() => {
    vi.clearAllMocks();
  });

  it('confirmOverwrite应该返回布尔值', async () => {
    const inquirer = vi.mocked(vi.importMock('inquirer'));
    (inquirer as unknown as { __setMockAnswers: (answers: Record<string, unknown>) => void }).__setMockAnswers({ confirm: true });

    const result = await confirmOverwrite('/test/dir');

    expect(typeof result).toBe('boolean');
    expect(result).toBe(true);
  });

  it('用户选择不覆盖时返回false', async () => {
    const inquirer = vi.mocked(vi.importMock('inquirer'));
    (inquirer as unknown as { __setMockAnswers: (answers: Record<string, unknown>) => void }).__setMockAnswers({ confirm: false });

    const result = await confirmOverwrite('/test/dir');

    expect(result).toBe(false);
  });
});
