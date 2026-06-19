"use strict";
var __importDefault = (this && this.__importDefault) || function (mod) {
    return (mod && mod.__esModule) ? mod : { "default": mod };
};
Object.defineProperty(exports, "__esModule", { value: true });
exports.CiGenerator = void 0;
const fs_extra_1 = __importDefault(require("fs-extra"));
const path_1 = __importDefault(require("path"));
const yaml_1 = __importDefault(require("yaml"));
const types_js_1 = require("../types.js");
class CiGenerator {
    config;
    ciConfig;
    targetDir;
    constructor(config, ciConfig = {}) {
        this.config = config;
        this.ciConfig = { ...types_js_1.DEFAULT_CI_CONFIG, ...ciConfig };
        this.targetDir = config.targetDir;
    }
    async generate() {
        if (this.config.ciProvider === 'github') {
            await this.generateGitHubActions();
        }
        else if (this.config.ciProvider === 'gitlab') {
            await this.generateGitLabCI();
        }
    }
    async generateGitHubActions() {
        const workflowDir = path_1.default.join(this.targetDir, '.github', 'workflows');
        await fs_extra_1.default.ensureDir(workflowDir);
        const workflow = this.getGitHubWorkflow();
        const yamlContent = yaml_1.default.stringify(workflow, {
            indent: 2,
            lineWidth: 120,
        });
        await fs_extra_1.default.writeFile(path_1.default.join(workflowDir, 'ci.yml'), yamlContent, 'utf-8');
    }
    getGitHubWorkflow() {
        const services = this.getServices();
        const workflow = {
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
    getServices() {
        const services = {};
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
    getDatabaseSetupSteps() {
        if (!this.needsDatabase())
            return [];
        const steps = [];
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
    getTestEnv() {
        const env = {
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
    async generateGitLabCI() {
        const gitlabCi = this.getGitLabCIConfig();
        const yamlContent = yaml_1.default.stringify(gitlabCi, {
            indent: 2,
            lineWidth: 120,
        });
        await fs_extra_1.default.writeFile(path_1.default.join(this.targetDir, '.gitlab-ci.yml'), yamlContent, 'utf-8');
    }
    getGitLabCIConfig() {
        const services = this.getGitLabServices();
        const config = {
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
                const vars = config['variables'];
                vars['POSTGRES_USER'] = 'postgres';
                vars['POSTGRES_PASSWORD'] = 'postgres';
                vars['POSTGRES_DB'] = this.config.projectName;
                vars['DATABASE_URL'] = `postgresql://postgres:postgres@postgres:5432/${this.config.projectName}`;
            }
            if (this.ciConfig.useRedis) {
                const vars = config['variables'];
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
    getGitLabServices() {
        const services = [];
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
    needsDatabase() {
        return this.config.framework === 'node-backend' && this.config.useDocker;
    }
    getInstallCommand() {
        const commands = {
            npm: 'npm ci',
            yarn: 'yarn install --frozen-lockfile',
            pnpm: 'pnpm install --frozen-lockfile',
        };
        return commands[this.config.packageManager] ?? 'npm ci';
    }
    getScriptCommand(script) {
        const commands = {
            npm: (s) => `npm run ${s}`,
            yarn: (s) => `yarn ${s}`,
            pnpm: (s) => `pnpm ${s}`,
        };
        return commands[this.config.packageManager]?.(script) ?? `npm run ${script}`;
    }
}
exports.CiGenerator = CiGenerator;
//# sourceMappingURL=ci-generator.js.map