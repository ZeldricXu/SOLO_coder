#!/usr/bin/env node
"use strict";
var __importDefault = (this && this.__importDefault) || function (mod) {
    return (mod && mod.__esModule) ? mod : { "default": mod };
};
Object.defineProperty(exports, "__esModule", { value: true });
exports.validateCreateOptions = validateCreateOptions;
const commander_1 = require("commander");
const chalk_1 = __importDefault(require("chalk"));
const path_1 = __importDefault(require("path"));
const fs_extra_1 = __importDefault(require("fs-extra"));
const types_js_1 = require("./types.js");
const state_js_1 = require("./state.js");
const package_manager_js_1 = require("./package-manager.js");
const prompts_js_1 = require("./prompts.js");
const scaffolder_js_1 = require("./scaffolder.js");
const code_quality_js_1 = require("./generators/code-quality.js");
const packageJsonPath = path_1.default.join(process.cwd(), 'package.json');
async function getVersion() {
    try {
        const pkg = await fs_extra_1.default.readJson(packageJsonPath);
        return pkg.version;
    }
    catch {
        return '1.0.0';
    }
}
async function checkForUpdates() {
    const update = await state_js_1.globalState.checkForUpdates();
    if (update) {
        console.log();
        console.log(chalk_1.default.yellow(`📢 Update available!`));
        console.log(chalk_1.default.yellow(`   Current version: ${await getVersion()}`));
        console.log(chalk_1.default.yellow(`   Latest version: ${update.version}`));
        console.log(chalk_1.default.yellow(`   Run: ${chalk_1.default.cyan('csp update')} to update templates`));
        console.log();
    }
}
function validateCreateOptions(options) {
    const errors = [];
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
async function main() {
    await state_js_1.globalState.init();
    const program = new commander_1.Command();
    program
        .name('create-solo-project')
        .description('A CLI tool for scaffolding standardized TypeScript projects')
        .version(await getVersion())
        .configureOutput({
        outputError: (str, write) => write(chalk_1.default.red(str)),
    });
    program
        .command('create [project-name]')
        .description('Create a new project')
        .option('-q, --quiet', 'Use default values for all prompts', false)
        .option('-f, --framework <framework>', `Framework: ${Object.values(types_js_1.FRAMEWORK_NAMES).join(', ')}`)
        .option('-pm, --package-manager <pm>', `Package manager: ${Object.values(types_js_1.PACKAGE_MANAGER_NAMES).join(', ')}`)
        .option('--docker', 'Include Docker support')
        .option('--no-docker', 'Exclude Docker support')
        .option('--ci <provider>', `CI provider: ${Object.values(types_js_1.CI_PROVIDER_NAMES).join(', ')}`)
        .option('--deploy <target>', `Deploy target: ${Object.values(types_js_1.DEPLOY_TARGET_NAMES).join(', ')}`)
        .option('--template <path>', 'Use custom template from local path, GitHub URL, or npm package')
        .option('--template-version <version>', 'Specify template version (for npm packages)')
        .option('-a, --author <name>', 'Author name')
        .option('-d, --description <desc>', 'Project description')
        .option('--git-remote <url>', 'Git remote repository URL')
        .option('--no-pre-commit', 'Skip pre-commit hook installation')
        .option('--force', 'Overwrite existing directory', false)
        .action(async (projectName, options) => {
        const validationErrors = validateCreateOptions(options);
        if (validationErrors.length > 0) {
            console.error(chalk_1.default.red('\n❌ 参数错误:'));
            for (const err of validationErrors) {
                console.error(chalk_1.default.red(`   ${err.message}`));
            }
            process.exit(1);
        }
        await checkForUpdates();
        const availablePMs = (0, package_manager_js_1.detectPackageManagers)();
        const defaults = {
            projectName,
            description: options.description,
            author: options.author,
            framework: options.framework,
            packageManager: options.packageManager,
            useDocker: options.docker,
            ciProvider: options.ci,
            deployTarget: options.deploy,
            template: options.template ?? null,
            templateVersion: options.templateVersion ?? null,
            quiet: options.quiet ?? false,
            gitRemoteUrl: options.gitRemote,
            usePreCommitHook: options.preCommit,
        };
        defaults['useCI'] = defaults['ciProvider'] !== 'none' && defaults['ciProvider'] !== undefined;
        let config;
        if (options.quiet) {
            const prefs = state_js_1.globalState.getPreferences();
            config = {
                projectName: projectName ?? 'my-project',
                description: 'A new TypeScript project',
                author: prefs.lastAuthor ?? process.env['USER'] ?? '',
                framework: defaults['framework'] ?? prefs.lastFramework ?? 'node-backend',
                packageManager: defaults['packageManager'] ?? prefs.lastPackageManager ?? (0, package_manager_js_1.getFastestPackageManager)(),
                useDocker: defaults['useDocker'] ?? prefs.lastUseDocker ?? true,
                useCI: true,
                ciProvider: defaults['ciProvider'] ?? prefs.lastCiProvider ?? 'github',
                deployTarget: defaults['deployTarget'] ?? 'docker',
                template: defaults['template'] ?? null,
                templateVersion: defaults['templateVersion'] ?? null,
                quiet: true,
                gitRemoteUrl: defaults['gitRemoteUrl'],
                usePreCommitHook: defaults['usePreCommitHook'] ?? prefs.lastUsePreCommitHook ?? true,
            };
        }
        else {
            config = await (0, prompts_js_1.runInteractiveWizard)(defaults, availablePMs);
        }
        const targetDir = path_1.default.resolve(process.cwd(), config['projectName']);
        if (await fs_extra_1.default.pathExists(targetDir)) {
            const contents = await fs_extra_1.default.readdir(targetDir);
            if (contents.length > 0 && !options.force && !options.quiet) {
                const overwrite = await (0, prompts_js_1.confirmOverwrite)(targetDir);
                if (!overwrite) {
                    console.log(chalk_1.default.yellow('Aborted by user.'));
                    process.exit(0);
                }
            }
        }
        const projectConfig = {
            projectName: config['projectName'],
            description: config['description'] ?? 'A new TypeScript project',
            author: config['author'] ?? '',
            framework: config['framework'],
            packageManager: config['packageManager'],
            useDocker: config['useDocker'] ?? true,
            useCI: config['useCI'] ?? true,
            ciProvider: config['ciProvider'] ?? 'github',
            deployTarget: config['deployTarget'] ?? 'none',
            usePreCommitHook: config['usePreCommitHook'] ?? true,
            template: config['template'] ?? null,
            templateVersion: config['templateVersion'] ?? null,
            quiet: config['quiet'] ?? false,
            gitRemoteUrl: config['gitRemoteUrl'],
            targetDir,
            projectVersion: '0.1.0',
        };
        const scaffolder = new scaffolder_js_1.Scaffolder(projectConfig);
        const success = await scaffolder.run();
        process.exit(success ? 0 : 1);
    });
    program
        .command('lint')
        .description('Run ESLint on the current project')
        .option('--fix', 'Auto-fix linting errors')
        .option('--staged', 'Only lint staged files')
        .action(async (options) => {
        const pm = (0, package_manager_js_1.getFastestPackageManager)();
        const checker = new code_quality_js_1.CodeQualityChecker(process.cwd(), pm);
        const result = await checker.lint({ fix: options.fix, staged: options.staged });
        process.exit(result.success ? 0 : 1);
    });
    program
        .command('format')
        .description('Run Prettier on the current project')
        .option('--fix', 'Auto-fix formatting issues (write mode)')
        .action(async (options) => {
        const pm = (0, package_manager_js_1.getFastestPackageManager)();
        const checker = new code_quality_js_1.CodeQualityChecker(process.cwd(), pm);
        const result = await checker.format({ fix: options.fix });
        process.exit(result.success ? 0 : 1);
    });
    program
        .command('check')
        .description('Run all code quality checks (lint + format)')
        .option('--fix', 'Auto-fix issues')
        .action(async (options) => {
        const pm = (0, package_manager_js_1.getFastestPackageManager)();
        const checker = new code_quality_js_1.CodeQualityChecker(process.cwd(), pm);
        const passed = await checker.runAll({ fix: options.fix });
        process.exit(passed ? 0 : 1);
    });
    const preCommitCommand = new commander_1.Command('pre-commit')
        .description('Manage pre-commit hook');
    preCommitCommand
        .command('install')
        .description('Install pre-commit hook')
        .action(async () => {
        const pm = (0, package_manager_js_1.getFastestPackageManager)();
        const checker = new code_quality_js_1.CodeQualityChecker(process.cwd(), pm);
        const installed = await checker.installPreCommitHook();
        process.exit(installed ? 0 : 1);
    });
    preCommitCommand
        .command('uninstall')
        .description('Uninstall pre-commit hook')
        .action(async () => {
        const pm = (0, package_manager_js_1.getFastestPackageManager)();
        const checker = new code_quality_js_1.CodeQualityChecker(process.cwd(), pm);
        await checker.uninstallPreCommitHook();
        process.exit(0);
    });
    preCommitCommand
        .command('status')
        .description('Check pre-commit hook status')
        .action(() => {
        const pm = (0, package_manager_js_1.getFastestPackageManager)();
        const checker = new code_quality_js_1.CodeQualityChecker(process.cwd(), pm);
        const installed = checker.isPreCommitHookInstalled();
        console.log(installed ? chalk_1.default.green('✓ Pre-commit hook is installed') : chalk_1.default.red('✗ Pre-commit hook is not installed'));
        process.exit(installed ? 0 : 1);
    });
    program.addCommand(preCommitCommand);
    program
        .command('update')
        .description('Update templates to latest version')
        .option('-f, --force', 'Force update even if already up to date')
        .action(async (options) => {
        const spinner = chalk_1.default.blue('🔄 Checking for template updates...');
        console.log(spinner);
        const success = await state_js_1.globalState.updateTemplates();
        if (success) {
            console.log(chalk_1.default.green('✓ Templates updated successfully!'));
        }
        else {
            console.log(chalk_1.default.yellow('⚠️  Could not update templates. Please check your internet connection.'));
        }
        if (options.force) {
            await state_js_1.globalState.clearCache();
            console.log(chalk_1.default.green('✓ Cache cleared'));
        }
        process.exit(success ? 0 : 1);
    });
    program
        .command('list-templates')
        .description('List available built-in templates')
        .action(() => {
        console.log('\n' + chalk_1.default.bold('📦 Available templates:\n'));
        for (const [key, name] of Object.entries(types_js_1.FRAMEWORK_NAMES)) {
            console.log(`  ${chalk_1.default.cyan(key)} - ${name}`);
        }
        console.log('\n' + chalk_1.default.dim('Use --template <path> to use a custom template from local path or GitHub URL'));
    });
    program
        .command('completion')
        .description('Generate shell completion script')
        .option('-s, --shell <shell>', 'Shell type (bash, zsh, fish)', 'bash')
        .action((options) => {
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
        }
        else if (shell === 'zsh') {
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
        }
        else {
            console.log(chalk_1.default.yellow(`Completion for ${shell} is not supported yet.`));
            process.exit(1);
        }
        console.log(completion);
        console.log(chalk_1.default.green(`\n# Add this to your ~/.${shell}rc:`));
        console.log(chalk_1.default.cyan(`# source <(${process.argv[1]} completion --shell ${shell})`));
    });
    try {
        await program.parseAsync(process.argv);
    }
    catch (error) {
        console.error(chalk_1.default.red('\n❌ Error:'), error.message);
        process.exit(1);
    }
}
if (require.main === module) {
    main().catch(error => {
        console.error(chalk_1.default.red('\n❌ Fatal error:'), error);
        process.exit(1);
    });
}
//# sourceMappingURL=index.js.map