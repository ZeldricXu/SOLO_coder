#!/usr/bin/env node

import { Command } from 'commander';
import chalk from 'chalk';
import path from 'path';
import fs from 'fs-extra';
import type { ProjectConfig, FrameworkType, PackageManagerType, CiProviderType, DeployTargetType } from './types.js';
import { FRAMEWORK_NAMES, PACKAGE_MANAGER_NAMES, CI_PROVIDER_NAMES, DEPLOY_TARGET_NAMES } from './types.js';
import { globalState } from './state.js';
import { detectPackageManagers, getFastestPackageManager } from './package-manager.js';
import { runInteractiveWizard, confirmOverwrite } from './prompts.js';
import { Scaffolder } from './scaffolder.js';
import { CodeQualityChecker } from './generators/code-quality.js';

const packageJsonPath = path.join(process.cwd(), 'package.json');

async function getVersion(): Promise<string> {
  try {
    const pkg = await fs.readJson(packageJsonPath);
    return pkg.version as string;
  } catch {
    return '1.0.0';
  }
}

async function checkForUpdates(): Promise<void> {
  const update = await globalState.checkForUpdates();
  if (update) {
    console.log();
    console.log(chalk.yellow(`📢 Update available!`));
    console.log(chalk.yellow(`   Current version: ${await getVersion()}`));
    console.log(chalk.yellow(`   Latest version: ${update.version}`));
    console.log(chalk.yellow(`   Run: ${chalk.cyan('csp update')} to update templates`));
    console.log();
  }
}

export interface CreateOptions {
  quiet?: boolean;
  framework?: string;
  packageManager?: string;
  docker?: boolean;
  ci?: string;
  deploy?: string;
  template?: string;
  templateVersion?: string;
  author?: string;
  description?: string;
  gitRemote?: string;
  preCommit?: boolean;
  force?: boolean;
}

interface LintOptions {
  fix?: boolean;
  staged?: boolean;
}

interface FormatOptions {
  fix?: boolean;
}

interface CheckOptions {
  fix?: boolean;
}

interface UpdateOptions {
  force?: boolean;
}

interface CompletionOptions {
  shell?: string;
}

export interface ValidationError {
  message: string;
  code: string;
}

export function validateCreateOptions(options: CreateOptions): ValidationError[] {
  const errors: ValidationError[] = [];

  if (options.template && options.framework) {
    errors.push({
      message: `--template 和 --framework 参数冲突。使用自定义模板时，框架选项由模板决定。`,
      code: 'TEMPLATE_FRAMEWORK_CONFLICT',
    });
  }

  if (options.template && options.docker !== undefined) {
    errors.push({
      message: `--template 和 --docker 参数冲突。使用自定义模板时，Docker支持由模板决定。`,
      code: 'TEMPLATE_DOCKER_CONFLICT',
    });
  }

  if (options.template && options.ci) {
    errors.push({
      message: `--template 和 --ci 参数冲突。使用自定义模板时，CI配置由模板决定。`,
      code: 'TEMPLATE_CI_CONFLICT',
    });
  }

  if (options.template && options.deploy) {
    errors.push({
      message: `--template 和 --deploy 参数冲突。使用自定义模板时，部署配置由模板决定。`,
      code: 'TEMPLATE_DEPLOY_CONFLICT',
    });
  }

  const validFrameworks = ['node-backend', 'react-frontend', 'vue-frontend', 'cli-tool'];
  if (options.framework && !validFrameworks.includes(options.framework)) {
    errors.push({
      message: `无效的框架: ${options.framework}。可选值: ${validFrameworks.join(', ')}`,
      code: 'INVALID_FRAMEWORK',
    });
  }

  const validPMs = ['npm', 'yarn', 'pnpm'];
  if (options.packageManager && !validPMs.includes(options.packageManager)) {
    errors.push({
      message: `无效的包管理器: ${options.packageManager}。可选值: ${validPMs.join(', ')}`,
      code: 'INVALID_PACKAGE_MANAGER',
    });
  }

  const validCIs = ['github', 'gitlab', 'none'];
  if (options.ci && !validCIs.includes(options.ci)) {
    errors.push({
      message: `无效的CI提供方: ${options.ci}。可选值: ${validCIs.join(', ')}`,
      code: 'INVALID_CI_PROVIDER',
    });
  }

  const validDeploys = ['docker', 'k8s', 'none'];
  if (options.deploy && !validDeploys.includes(options.deploy)) {
    errors.push({
      message: `无效的部署目标: ${options.deploy}。可选值: ${validDeploys.join(', ')}`,
      code: 'INVALID_DEPLOY_TARGET',
    });
  }

  return errors;
}

async function main(): Promise<void> {
  await globalState.init();

  const program = new Command();

  program
    .name('create-solo-project')
    .description('A CLI tool for scaffolding standardized TypeScript projects')
    .version(await getVersion())
    .configureOutput({
      outputError: (str: string, write: (str: string) => void) => write(chalk.red(str)),
    });

  program
    .command('create [project-name]')
    .description('Create a new project')
    .option('-q, --quiet', 'Use default values for all prompts', false)
    .option('-f, --framework <framework>', `Framework: ${Object.values(FRAMEWORK_NAMES).join(', ')}`)
    .option('-pm, --package-manager <pm>', `Package manager: ${Object.values(PACKAGE_MANAGER_NAMES).join(', ')}`)
    .option('--docker', 'Include Docker support')
    .option('--no-docker', 'Exclude Docker support')
    .option('--ci <provider>', `CI provider: ${Object.values(CI_PROVIDER_NAMES).join(', ')}`)
    .option('--deploy <target>', `Deploy target: ${Object.values(DEPLOY_TARGET_NAMES).join(', ')}`)
    .option('--template <path>', 'Use custom template from local path, GitHub URL, or npm package')
    .option('--template-version <version>', 'Specify template version (for npm packages)')
    .option('-a, --author <name>', 'Author name')
    .option('-d, --description <desc>', 'Project description')
    .option('--git-remote <url>', 'Git remote repository URL')
    .option('--no-pre-commit', 'Skip pre-commit hook installation')
    .option('--force', 'Overwrite existing directory', false)
    .action(async (projectName: string | undefined, options: CreateOptions) => {
      const validationErrors = validateCreateOptions(options);
      if (validationErrors.length > 0) {
        console.error(chalk.red('\n❌ 参数错误:'));
        for (const err of validationErrors) {
          console.error(chalk.red(`   ${err.message}`));
        }
        process.exit(1);
      }

      await checkForUpdates();

      const availablePMs = detectPackageManagers();
      const defaults: Record<string, unknown> = {
        projectName,
        description: options.description,
        author: options.author,
        framework: options.framework as FrameworkType | undefined,
        packageManager: options.packageManager as PackageManagerType | undefined,
        useDocker: options.docker,
        ciProvider: options.ci as CiProviderType | undefined,
        deployTarget: options.deploy as DeployTargetType | undefined,
        template: options.template ?? null,
        templateVersion: options.templateVersion ?? null,
        quiet: options.quiet ?? false,
        gitRemoteUrl: options.gitRemote,
        usePreCommitHook: options.preCommit,
      };

      (defaults as Record<string, unknown>)['useCI'] = (defaults['ciProvider'] as CiProviderType | undefined) !== 'none' && (defaults['ciProvider'] as CiProviderType | undefined) !== undefined;

      let config: Record<string, unknown>;
      if (options.quiet) {
        const prefs = globalState.getPreferences();
        config = {
          projectName: projectName ?? 'my-project',
          description: 'A new TypeScript project',
          author: prefs.lastAuthor ?? (process.env['USER'] as string) ?? '',
          framework: (defaults['framework'] as FrameworkType | undefined) ?? prefs.lastFramework ?? 'node-backend',
          packageManager: (defaults['packageManager'] as PackageManagerType | undefined) ?? prefs.lastPackageManager ?? getFastestPackageManager(),
          useDocker: defaults['useDocker'] ?? prefs.lastUseDocker ?? true,
          useCI: true,
          ciProvider: (defaults['ciProvider'] as CiProviderType | undefined) ?? prefs.lastCiProvider ?? 'github',
          deployTarget: (defaults['deployTarget'] as DeployTargetType | undefined) ?? 'docker',
          template: defaults['template'] ?? null,
          templateVersion: defaults['templateVersion'] ?? null,
          quiet: true,
          gitRemoteUrl: defaults['gitRemoteUrl'],
          usePreCommitHook: defaults['usePreCommitHook'] ?? prefs.lastUsePreCommitHook ?? true,
        };
      } else {
        config = await runInteractiveWizard(defaults, availablePMs);
      }

      const targetDir = path.resolve(process.cwd(), config['projectName'] as string);

      if (await fs.pathExists(targetDir)) {
        const contents = await fs.readdir(targetDir);
        if (contents.length > 0 && !options.force && !options.quiet) {
          const overwrite = await confirmOverwrite(targetDir);
          if (!overwrite) {
            console.log(chalk.yellow('Aborted by user.'));
            process.exit(0);
          }
        }
      }

      const projectConfig: ProjectConfig = {
        projectName: config['projectName'] as string,
        description: (config['description'] as string) ?? 'A new TypeScript project',
        author: (config['author'] as string) ?? '',
        framework: config['framework'] as FrameworkType,
        packageManager: config['packageManager'] as PackageManagerType,
        useDocker: (config['useDocker'] as boolean) ?? true,
        useCI: (config['useCI'] as boolean) ?? true,
        ciProvider: (config['ciProvider'] as CiProviderType) ?? 'github',
        deployTarget: (config['deployTarget'] as DeployTargetType) ?? 'none',
        usePreCommitHook: (config['usePreCommitHook'] as boolean) ?? true,
        template: (config['template'] as string | null) ?? null,
        templateVersion: (config['templateVersion'] as string | null) ?? null,
        quiet: (config['quiet'] as boolean) ?? false,
        gitRemoteUrl: config['gitRemoteUrl'] as string | undefined,
        targetDir,
        projectVersion: '0.1.0',
      };

      const scaffolder = new Scaffolder(projectConfig);
      const success = await scaffolder.run();
      process.exit(success ? 0 : 1);
    });

  program
    .command('lint')
    .description('Run ESLint on the current project')
    .option('--fix', 'Auto-fix linting errors')
    .option('--staged', 'Only lint staged files')
    .action(async (options: LintOptions) => {
      const pm = getFastestPackageManager();
      const checker = new CodeQualityChecker(process.cwd(), pm);
      const result = await checker.lint({ fix: options.fix, staged: options.staged });
      process.exit(result.success ? 0 : 1);
    });

  program
    .command('format')
    .description('Run Prettier on the current project')
    .option('--fix', 'Auto-fix formatting issues (write mode)')
    .action(async (options: FormatOptions) => {
      const pm = getFastestPackageManager();
      const checker = new CodeQualityChecker(process.cwd(), pm);
      const result = await checker.format({ fix: options.fix });
      process.exit(result.success ? 0 : 1);
    });

  program
    .command('check')
    .description('Run all code quality checks (lint + format)')
    .option('--fix', 'Auto-fix issues')
    .action(async (options: CheckOptions) => {
      const pm = getFastestPackageManager();
      const checker = new CodeQualityChecker(process.cwd(), pm);
      const passed = await checker.runAll({ fix: options.fix });
      process.exit(passed ? 0 : 1);
    });

  const preCommitCommand = new Command('pre-commit')
    .description('Manage pre-commit hook');

  preCommitCommand
    .command('install')
    .description('Install pre-commit hook')
    .action(async () => {
      const pm = getFastestPackageManager();
      const checker = new CodeQualityChecker(process.cwd(), pm);
      const installed = await checker.installPreCommitHook();
      process.exit(installed ? 0 : 1);
    });

  preCommitCommand
    .command('uninstall')
    .description('Uninstall pre-commit hook')
    .action(async () => {
      const pm = getFastestPackageManager();
      const checker = new CodeQualityChecker(process.cwd(), pm);
      await checker.uninstallPreCommitHook();
      process.exit(0);
    });

  preCommitCommand
    .command('status')
    .description('Check pre-commit hook status')
    .action(() => {
      const pm = getFastestPackageManager();
      const checker = new CodeQualityChecker(process.cwd(), pm);
      const installed = checker.isPreCommitHookInstalled();
      console.log(installed ? chalk.green('✓ Pre-commit hook is installed') : chalk.red('✗ Pre-commit hook is not installed'));
      process.exit(installed ? 0 : 1);
    });

  program.addCommand(preCommitCommand);

  program
    .command('update')
    .description('Update templates to latest version')
    .option('-f, --force', 'Force update even if already up to date')
    .action(async (options: UpdateOptions) => {
      const spinner = chalk.blue('🔄 Checking for template updates...');
      console.log(spinner);

      const success = await globalState.updateTemplates();
      if (success) {
        console.log(chalk.green('✓ Templates updated successfully!'));
      } else {
        console.log(chalk.yellow('⚠️  Could not update templates. Please check your internet connection.'));
      }

      if (options.force) {
        await globalState.clearCache();
        console.log(chalk.green('✓ Cache cleared'));
      }

      process.exit(success ? 0 : 1);
    });

  program
    .command('list-templates')
    .description('List available built-in templates')
    .action(() => {
      console.log('\n' + chalk.bold('📦 Available templates:\n'));
      for (const [key, name] of Object.entries(FRAMEWORK_NAMES)) {
        console.log(`  ${chalk.cyan(key)} - ${name}`);
      }
      console.log('\n' + chalk.dim('Use --template <path> to use a custom template from local path or GitHub URL'));
    });

  program
    .command('completion')
    .description('Generate shell completion script')
    .option('-s, --shell <shell>', 'Shell type (bash, zsh, fish)', 'bash')
    .action((options: CompletionOptions) => {
      const shell = options.shell ?? 'bash';
      let completion = '';

      if (shell === 'bash') {
        completion = `_csp_completion() {
  local cur prev words cword
  _init_completion || return

  local commands="create lint format check pre-commit update list-templates completion"
  local subcommands="pre-commit:install pre-commit:uninstall pre-commit:status"

  if [[ $cword -eq 1 ]]; then
    COMPREPLY=($(compgen -W "$commands" -- "$cur"))
    return
  fi

  case $prev in
    create)
      if [[ $cur == -* ]]; then
        COMPREPLY=($(compgen -W "--quiet --framework --package-manager --docker --no-docker --ci --deploy --template --author --description --git-remote --no-pre-commit --force" -- "$cur"))
      else
        COMPREPLY=($(compgen -d -- "$cur"))
      fi
      ;;
    --framework)
      COMPREPLY=($(compgen -W "node-backend react-frontend vue-frontend cli-tool" -- "$cur"))
      ;;
    --package-manager|-pm)
      COMPREPLY=($(compgen -W "npm yarn pnpm" -- "$cur"))
      ;;
    --ci)
      COMPREPLY=($(compgen -W "github gitlab none" -- "$cur"))
      ;;
    --deploy)
      COMPREPLY=($(compgen -W "docker k8s none" -- "$cur"))
      ;;
    --shell|-s)
      COMPREPLY=($(compgen -W "bash zsh fish" -- "$cur"))
      ;;
  esac
}
complete -F _csp_completion csp create-solo-project
`;
      } else if (shell === 'zsh') {
        completion = `#compdef csp create-solo-project

_csp() {
  local -a commands
  commands=(
    'create:Create a new project'
    'lint:Run ESLint'
    'format:Run Prettier'
    'check:Run all quality checks'
    'pre-commit:Manage pre-commit hook'
    'update:Update templates'
    'list-templates:List available templates'
    'completion:Generate completion script'
  )

  _arguments -C \\
    '1: :->command' \\
    '*:: :->args'

  case $state in
    command)
      _describe -t commands 'command' commands
      ;;
    args)
      case $words[1] in
        create)
          _arguments \\
            '(-q --quiet)'{-q,--quiet}'[Use default values]' \\
            '(-f --framework)'{-f,--framework}'[Framework]:framework:(node-backend react-frontend vue-frontend cli-tool)' \\
            '(-pm --package-manager)'{-pm,--package-manager}'[Package manager]:pm:(npm yarn pnpm)' \\
            '--docker[Include Docker support]' \\
            '--no-docker[Exclude Docker support]' \\
            '--ci[CI provider]:ci:(github gitlab none)' \\
            '--deploy[Deploy target]:deploy:(docker k8s none)' \\
            '--template[Custom template path]:template:_files' \\
            '(-a --author)'{-a,--author}'[Author name]' \\
            '(-d --description)'{-d,--description}'[Project description]' \\
            '--git-remote[Git remote URL]' \\
            '--no-pre-commit[Skip pre-commit hook]' \\
            '(--force)'--force'[Overwrite existing directory]' \\
            '*::project name:_files'
          ;;
        lint)
          _arguments \\
            '--fix[Auto-fix errors]' \\
            '--staged[Only lint staged files]'
          ;;
        format)
          _arguments '--fix[Auto-fix formatting]'
          ;;
        check)
          _arguments '--fix[Auto-fix issues]'
          ;;
        pre-commit)
          _arguments '1: :(install uninstall status)'
          ;;
        completion)
          _arguments '(-s --shell)'{-s,--shell}'[Shell type]:shell:(bash zsh fish)'
          ;;
      esac
      ;;
  esac
}

_csp
`;
      } else {
        console.log(chalk.yellow(`Completion for ${shell} is not supported yet.`));
        process.exit(1);
      }

      console.log(completion);
      console.log(chalk.green(`\n# Add this to your ~/.${shell}rc:`));
      console.log(chalk.cyan(`# source <(${process.argv[1]} completion --shell ${shell})`));
    });

  try {
    await program.parseAsync(process.argv);
  } catch (error) {
    console.error(chalk.red('\n❌ Error:'), (error as Error).message);
    process.exit(1);
  }
}

if (require.main === module) {
  main().catch(error => {
    console.error(chalk.red('\n❌ Fatal error:'), error);
    process.exit(1);
  });
}
