import fs from 'fs-extra';
import path from 'path';
import yaml from 'yaml';
import type { ProjectConfig, CiConfig } from '../types.js';
import { DEFAULT_CI_CONFIG } from '../types.js';

export class CiGenerator {
  private config: ProjectConfig;
  private ciConfig: CiConfig;
  private targetDir: string;

  constructor(config: ProjectConfig, ciConfig: Partial<CiConfig> = {}) {
    this.config = config;
    this.ciConfig = { ...DEFAULT_CI_CONFIG, ...ciConfig };
    this.targetDir = config.targetDir;
  }

  async generate(): Promise<void> {
    if (this.config.ciProvider === 'github') {
      await this.generateGitHubActions();
    } else if (this.config.ciProvider === 'gitlab') {
      await this.generateGitLabCI();
    }
  }

  private async generateGitHubActions(): Promise<void> {
    const workflowDir = path.join(this.targetDir, '.github', 'workflows');
    await fs.ensureDir(workflowDir);

    const workflow = this.getGitHubWorkflow();
    const yamlContent = yaml.stringify(workflow, {
      indent: 2,
      lineWidth: 120,
    });

    await fs.writeFile(path.join(workflowDir, 'ci.yml'), yamlContent, 'utf-8');
  }

  private getGitHubWorkflow() {
    const services = this.getServices();

    const workflow: Record<string, unknown> = {
      name: 'CI',
      on: {
        push: {
          branches: ['main', 'develop'],
        },
        pull_request: {
          branches: ['main', 'develop'],
        },
      },
      jobs: {
        lint: {
          'runs-on': 'ubuntu-latest',
          steps: [
            { uses: 'actions/checkout@v4' },
            {
              name: 'Setup Node.js',
              uses: 'actions/setup-node@v4',
              with: {
                'node-version': this.ciConfig.nodeVersion,
                cache: this.config.packageManager,
              },
            },
            {
              name: 'Install dependencies',
              run: this.getInstallCommand(),
            },
            {
              name: 'Run ESLint',
              run: this.getScriptCommand('lint'),
            },
            {
              name: 'Check Prettier',
              run: this.getScriptCommand('format:check'),
            },
          ],
        },
        test: {
          'runs-on': 'ubuntu-latest',
          needs: 'lint',
          strategy: {
            matrix: {
              'node-version': ['18.x', '20.x'],
            },
          },
          services: Object.keys(services).length > 0 ? services : undefined,
          steps: [
            { uses: 'actions/checkout@v4' },
            {
              name: 'Setup Node.js',
              uses: 'actions/setup-node@v4',
              with: {
                'node-version': '${{ matrix.node-version }}',
                cache: this.config.packageManager,
              },
            },
            {
              name: 'Install dependencies',
              run: this.getInstallCommand(),
            },
            ...this.getDatabaseSetupSteps(),
            {
              name: 'Run tests',
              run: this.getScriptCommand('test'),
              env: this.getTestEnv(),
            },
            {
              name: 'Upload coverage',
              uses: 'actions/upload-artifact@v4',
              with: {
                name: 'coverage-${{ matrix.node-version }}',
                path: 'coverage/',
              },
              'if': "matrix.node-version == '20.x'",
            },
          ],
        },
        build: {
          'runs-on': 'ubuntu-latest',
          needs: 'test',
          steps: [
            { uses: 'actions/checkout@v4' },
            {
              name: 'Setup Node.js',
              uses: 'actions/setup-node@v4',
              with: {
                'node-version': this.ciConfig.nodeVersion,
                cache: this.config.packageManager,
              },
            },
            {
              name: 'Install dependencies',
              run: this.getInstallCommand(),
            },
            {
              name: 'Build',
              run: this.getScriptCommand('build'),
            },
            {
              name: 'Upload build artifacts',
              uses: 'actions/upload-artifact@v4',
              with: {
                name: 'dist',
                path: 'dist/',
              },
            },
          ],
        },
      },
    };

    return workflow;
  }

  private getServices(): Record<string, unknown> {
    const services: Record<string, unknown> = {};

    if (this.needsDatabase()) {
      if (this.ciConfig.usePostgres) {
        services['postgres'] = {
          image: `postgres:${this.ciConfig.postgresVersion}-alpine`,
          env: {
            POSTGRES_USER: 'postgres',
            POSTGRES_PASSWORD: 'postgres',
            POSTGRES_DB: this.config.projectName,
          },
          ports: ['5432:5432'],
          options: '--health-cmd pg_isready --health-interval 10s --health-timeout 5s --health-retries 5',
        };
      }

      if (this.ciConfig.useRedis) {
        services['redis'] = {
          image: `redis:${this.ciConfig.redisVersion}-alpine`,
          ports: ['6379:6379'],
          options: '--health-cmd "redis-cli ping" --health-interval 10s --health-timeout 5s --health-retries 5',
        };
      }
    }

    return services;
  }

  private getDatabaseSetupSteps(): Array<Record<string, unknown>> {
    if (!this.needsDatabase()) return [];

    const steps: Array<Record<string, unknown>> = [];

    if (this.ciConfig.usePostgres) {
      steps.push({
        name: 'Wait for PostgreSQL',
        run: 'until pg_isready -h localhost -p 5432 -U postgres; do echo "Waiting for PostgreSQL..."; sleep 2; done',
      });
    }

    if (this.ciConfig.useRedis) {
      steps.push({
        name: 'Wait for Redis',
        run: 'until redis-cli ping; do echo "Waiting for Redis..."; sleep 2; done',
      });
    }

    return steps;
  }

  private getTestEnv(): Record<string, string> {
    const env: Record<string, string> = {
      NODE_ENV: 'test',
    };

    if (this.needsDatabase()) {
      if (this.ciConfig.usePostgres) {
        env['DATABASE_URL'] = `postgresql://postgres:postgres@localhost:5432/${this.config.projectName}`;
      }
      if (this.ciConfig.useRedis) {
        env['REDIS_URL'] = 'redis://localhost:6379';
      }
    }

    return env;
  }

  private async generateGitLabCI(): Promise<void> {
    const gitlabCi = this.getGitLabCIConfig();
    const yamlContent = yaml.stringify(gitlabCi, {
      indent: 2,
      lineWidth: 120,
    });

    await fs.writeFile(path.join(this.targetDir, '.gitlab-ci.yml'), yamlContent, 'utf-8');
  }

  private getGitLabCIConfig(): Record<string, unknown> {
    const services = this.getGitLabServices();

    const config: Record<string, unknown> = {
      image: `node:${this.ciConfig.nodeVersion}`,
      variables: {
        NODE_ENV: 'test',
        npm_config_cache: '$CI_PROJECT_DIR/.npm',
        YARN_CACHE_FOLDER: '$CI_PROJECT_DIR/.yarn',
        PNPM_CACHE_FOLDER: '$CI_PROJECT_DIR/.pnpm',
      },
      cache: {
        key: {
          files: ['package.json', 'package-lock.json', 'yarn.lock', 'pnpm-lock.yaml'],
        },
        paths: [
          '.npm/',
          '.yarn/',
          '.pnpm/',
          'node_modules/',
        ],
      },
      stages: ['lint', 'test', 'build'],
    };

    if (services.length > 0) {
      config['services'] = services;
      if (this.ciConfig.usePostgres) {
        const vars = config['variables'] as Record<string, string>;
        vars['POSTGRES_USER'] = 'postgres';
        vars['POSTGRES_PASSWORD'] = 'postgres';
        vars['POSTGRES_DB'] = this.config.projectName;
        vars['DATABASE_URL'] = `postgresql://postgres:postgres@postgres:5432/${this.config.projectName}`;
      }
      if (this.ciConfig.useRedis) {
        const vars = config['variables'] as Record<string, string>;
        vars['REDIS_URL'] = 'redis://redis:6379';
      }
    }

    config['lint'] = {
      stage: 'lint',
      script: [
        this.getInstallCommand(),
        this.getScriptCommand('lint'),
        this.getScriptCommand('format:check'),
      ],
    };

    config['test'] = {
      stage: 'test',
      parallel: {
        matrix: [
          { NODE_VERSION: ['18', '20'] },
        ],
      },
      image: 'node:${NODE_VERSION}',
      script: [
        this.getInstallCommand(),
        this.getScriptCommand('test'),
      ],
      artifacts: {
        paths: ['coverage/'],
        expire_in: '1 week',
      },
    };

    config['build'] = {
      stage: 'build',
      script: [
        this.getInstallCommand(),
        this.getScriptCommand('build'),
      ],
      artifacts: {
        paths: ['dist/'],
        expire_in: '1 month',
      },
    };

    return config;
  }

  private getGitLabServices(): Array<Record<string, string>> {
    const services: Array<Record<string, string>> = [];

    if (this.needsDatabase()) {
      if (this.ciConfig.usePostgres) {
        services.push({
          name: `postgres:${this.ciConfig.postgresVersion}-alpine`,
          alias: 'postgres',
        });
      }
      if (this.ciConfig.useRedis) {
        services.push({
          name: `redis:${this.ciConfig.redisVersion}-alpine`,
          alias: 'redis',
        });
      }
    }

    return services;
  }

  private needsDatabase(): boolean {
    return this.config.framework === 'node-backend' && this.config.useDocker;
  }

  private getInstallCommand(): string {
    const commands: Record<string, string> = {
      npm: 'npm ci',
      yarn: 'yarn install --frozen-lockfile',
      pnpm: 'pnpm install --frozen-lockfile',
    };
    return commands[this.config.packageManager] ?? 'npm ci';
  }

  private getScriptCommand(script: string): string {
    const commands: Record<string, (script: string) => string> = {
      npm: (s) => `npm run ${s}`,
      yarn: (s) => `yarn ${s}`,
      pnpm: (s) => `pnpm ${s}`,
    };
    return commands[this.config.packageManager]?.(script) ?? `npm run ${script}`;
  }
}
