import fs from 'fs-extra';
import type { ProjectConfig, CiConfig, CiPipeline, CiStage, CiService, CiStep } from '../types.js';
import { DEFAULT_CI_CONFIG } from '../types.js';
import { getCiAdapter } from './ci-adapters/index.js';

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
    if (this.config.ciProvider === 'none') return;

    const adapter = getCiAdapter(this.config.ciProvider);
    if (!adapter) return;

    const pipeline = this.buildPipeline();
    const yamlContent = adapter.render(pipeline, this.config);
    const filePath = adapter.getFilePath(this.targetDir);

    await fs.ensureDir(filePath.substring(0, filePath.lastIndexOf('/')));
    await fs.writeFile(filePath, yamlContent, 'utf-8');
  }

  private buildPipeline(): CiPipeline {
    const pipeline: CiPipeline = {
      name: 'CI',
      trigger: {
        push: { branches: ['main', 'develop'] },
        pullRequest: { branches: ['main', 'develop'] },
      },
      stages: [
        this.buildLintStage(),
        this.buildTestStage(),
        this.buildBuildStage(),
      ],
      defaultImage: `node:${this.ciConfig.nodeVersion}`,
    };

    if (this.needsDatabase()) {
      const services = this.buildServices();
      pipeline.services = services;
      pipeline.env = this.buildEnv();
    }

    return pipeline;
  }

  private buildLintStage(): CiStage {
    const steps: CiStep[] = [
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
    ];

    return {
      name: 'lint',
      displayName: 'Lint Code',
      steps,
      runsOn: 'ubuntu-latest',
    };
  }

  private buildTestStage(): CiStage {
    const steps: CiStep[] = [
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
        if: "matrix.node-version == '20.x'",
      },
    ];

    const stage: CiStage = {
      name: 'test',
      displayName: 'Run Tests',
      steps,
      needs: ['lint'],
      runsOn: 'ubuntu-latest',
      strategy: {
        matrix: {
          'node-version': ['18.x', '20.x'],
        },
      },
      artifacts: {
        paths: ['coverage/'],
        expireIn: '1 week',
      },
    };

    if (this.needsDatabase()) {
      stage.services = this.buildServices();
    }

    return stage;
  }

  private buildBuildStage(): CiStage {
    const steps: CiStep[] = [
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
    ];

    return {
      name: 'build',
      displayName: 'Build Project',
      steps,
      needs: ['test'],
      runsOn: 'ubuntu-latest',
      artifacts: {
        paths: ['dist/'],
        expireIn: '1 month',
      },
    };
  }

  private buildServices(): CiService[] {
    const services: CiService[] = [];

    if (this.ciConfig.usePostgres) {
      services.push({
        name: 'postgres',
        image: `postgres:${this.ciConfig.postgresVersion}-alpine`,
        alias: 'postgres',
        env: {
          POSTGRES_USER: 'postgres',
          POSTGRES_PASSWORD: 'postgres',
          POSTGRES_DB: this.config.projectName,
        },
        ports: ['5432:5432'],
        healthCheck: {
          command: 'pg_isready -U postgres',
          interval: '10s',
          timeout: '5s',
          retries: 5,
        },
      });
    }

    if (this.ciConfig.useRedis) {
      services.push({
        name: 'redis',
        image: `redis:${this.ciConfig.redisVersion}-alpine`,
        alias: 'redis',
        ports: ['6379:6379'],
        healthCheck: {
          command: 'redis-cli ping',
          interval: '10s',
          timeout: '5s',
          retries: 5,
        },
      });
    }

    return services;
  }

  private buildEnv(): Record<string, string> {
    const env: Record<string, string> = {
      NODE_ENV: 'test',
    };

    if (this.ciConfig.usePostgres) {
      env['DATABASE_URL'] = `postgresql://postgres:postgres@postgres:5432/${this.config.projectName}`;
    }
    if (this.ciConfig.useRedis) {
      env['REDIS_URL'] = 'redis://redis:6379';
    }

    return env;
  }

  private getDatabaseSetupSteps(): CiStep[] {
    if (!this.needsDatabase()) return [];

    const steps: CiStep[] = [];

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
    const commands: Record<string, (s: string) => string> = {
      npm: (s) => `npm run ${s}`,
      yarn: (s) => `yarn ${s}`,
      pnpm: (s) => `pnpm ${s}`,
    };
    return commands[this.config.packageManager]?.(script) ?? `npm run ${script}`;
  }
}
