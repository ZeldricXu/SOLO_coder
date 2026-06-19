import fs from 'fs-extra';
import path from 'path';
import ora from 'ora';
import chalk from 'chalk';
import type { ProjectConfig, TemplateConfig } from './types.js';
import { TemplateEngine } from './templates/engine.js';
import { ConfigGenerator } from './generators/config-generator.js';
import { CiGenerator } from './generators/ci-generator.js';
import { DockerGenerator } from './generators/docker-generator.js';
import { CodeQualityChecker } from './generators/code-quality.js';
import { PackageManager, initGitRepo, isGitAvailable } from './package-manager.js';
import { globalState } from './state.js';

export class Scaffolder {
  private config: ProjectConfig;
  private templateConfig!: TemplateConfig;

  constructor(config: ProjectConfig) {
    this.config = config;
  }

  async run(): Promise<boolean> {
    const spinner = ora(`🚀 Creating ${chalk.bold(this.config.projectName)}...`).start();

    try {
      await this.prepareTargetDir();
      spinner.text = '📁 Target directory ready';

      const templateEngine = new TemplateEngine(this.config);
      this.templateConfig = await templateEngine.render();
      spinner.text = '📝 Template files rendered';

      const configGenerator = new ConfigGenerator(this.config);
      await configGenerator.generateAll();
      await this.updatePackageJson();
      spinner.text = '⚙️ Configuration files generated';

      const ciGenerator = new CiGenerator(this.config);
      await ciGenerator.generate();
      spinner.text = '🔧 CI/CD configuration generated';

      const dockerGenerator = new DockerGenerator(this.config);
      await dockerGenerator.generate();
      spinner.text = '🐳 Docker configuration generated';

      const pm = new PackageManager(this.config.packageManager, this.config.targetDir);
      await pm.installDependencies();
      spinner.text = '📦 Dependencies installed';

      await this.runInitialBuild(pm);
      spinner.text = '🔨 Initial build completed';

      if (this.config.usePreCommitHook) {
        const checker = new CodeQualityChecker(this.config.targetDir, this.config.packageManager);
        await checker.installPreCommitHook();
        spinner.text = '🪝 Pre-commit hook installed';
      }

      if (isGitAvailable()) {
        await initGitRepo(this.config);
        spinner.text = '📚 Git repository initialized';
      }

      await this.saveUserPreferences();

      spinner.succeed(chalk.green(`✨ Project ${chalk.bold(this.config.projectName)} created successfully!`));
      this.printSuccessMessage();

      return true;
    } catch (error) {
      spinner.fail(chalk.red(`Failed to create project: ${(error as Error).message}`));
      console.error('\nDetailed error:', error);
      return false;
    }
  }

  private async prepareTargetDir(): Promise<void> {
    const targetDir = this.config.targetDir;

    if (await fs.pathExists(targetDir)) {
      const stats = await fs.stat(targetDir);
      if (!stats.isDirectory()) {
        throw new Error(`Target path ${targetDir} exists but is not a directory`);
      }

      const contents = await fs.readdir(targetDir);
      if (contents.length > 0) {
        if (this.config.quiet) {
          await fs.emptyDir(targetDir);
        } else {
          throw new Error(`Directory ${targetDir} is not empty. Please use an empty directory or --force to overwrite.`);
        }
      }
    } else {
      await fs.ensureDir(targetDir);
    }
  }

  private async updatePackageJson(): Promise<void> {
    const packageJsonPath = path.join(this.config.targetDir, 'package.json');
    const packageJson = await fs.readJson(packageJsonPath);

    packageJson.scripts = {
      ...packageJson.scripts,
      ...this.templateConfig.scripts,
    };

    packageJson.dependencies = {
      ...packageJson.dependencies,
      ...this.templateConfig.dependencies,
    };

    packageJson.devDependencies = {
      ...packageJson.devDependencies,
      ...this.templateConfig.devDependencies,
    };

    const sortedPackageJson = {
      ...packageJson,
      scripts: this.sortObjectKeys(packageJson.scripts),
      dependencies: this.sortObjectKeys(packageJson.dependencies),
      devDependencies: this.sortObjectKeys(packageJson.devDependencies),
    };

    await fs.writeJson(packageJsonPath, sortedPackageJson, { spaces: 2 });
  }

  private sortObjectKeys<T extends Record<string, unknown>>(obj: T): T {
    return Object.keys(obj)
      .sort()
      .reduce((result, key) => {
        (result as Record<string, unknown>)[key] = obj[key];
        return result;
      }, {} as T);
  }

  private async runInitialBuild(pm: PackageManager): Promise<void> {
    try {
      await pm.runScript('build');
    } catch (error) {
      console.warn(chalk.yellow(`\n⚠️  Initial build failed: ${(error as Error).message}`));
      console.warn(chalk.yellow('   You may need to run `npm run build` manually after fixing any issues.'));
    }
  }

  private async saveUserPreferences(): Promise<void> {
    await Promise.all([
      globalState.setFramework(this.config.framework),
      globalState.setPackageManager(this.config.packageManager),
      globalState.setCiProvider(this.config.ciProvider),
      globalState.setAuthor(this.config.author),
      globalState.setUseDocker(this.config.useDocker),
      globalState.setUsePreCommitHook(this.config.usePreCommitHook),
    ]);
  }

  private printSuccessMessage(): void {
    const dir = path.relative(process.cwd(), this.config.targetDir) || this.config.projectName;

    console.log('\n' + chalk.bold('📋 Next steps:'));
    console.log(`   ${chalk.cyan('cd ' + dir)}`);

    if (this.config.framework === 'node-backend' && this.config.useDocker) {
      console.log(`   ${chalk.cyan(this.getPmCommand('run dev:docker'))}  # Start services with Docker Compose`);
    }

    console.log(`   ${chalk.cyan(this.getPmCommand('run dev'))}        # Start development server`);
    console.log(`   ${chalk.cyan(this.getPmCommand('run lint'))}       # Run ESLint`);
    console.log(`   ${chalk.cyan(this.getPmCommand('run test'))}       # Run tests`);
    console.log(`   ${chalk.cyan(this.getPmCommand('run build'))}      # Build for production`);

    if (this.config.gitRemoteUrl) {
      console.log(`\n   ${chalk.cyan('git push -u origin main')}  # Push to remote repository`);
    }

    console.log('\n' + chalk.green('🎉 Happy coding!'));
  }

  private getPmCommand(command: string): string {
    const pm = this.config.packageManager;
    return `${pm} ${command}`;
  }
}
