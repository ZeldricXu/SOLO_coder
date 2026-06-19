"use strict";
var __importDefault = (this && this.__importDefault) || function (mod) {
    return (mod && mod.__esModule) ? mod : { "default": mod };
};
Object.defineProperty(exports, "__esModule", { value: true });
exports.runInteractiveWizard = runInteractiveWizard;
exports.confirmOverwrite = confirmOverwrite;
const inquirer_1 = __importDefault(require("inquirer"));
const types_js_1 = require("./types.js");
const state_js_1 = require("./state.js");
async function runInteractiveWizard(defaults, availablePMs) {
    const prefs = state_js_1.globalState.getPreferences();
    const defaultFramework = defaults.framework ?? prefs.lastFramework ?? 'node-backend';
    const defaultPM = defaults.packageManager ?? prefs.lastPackageManager ?? availablePMs[0] ?? 'npm';
    const defaultCI = defaults.ciProvider ?? prefs.lastCiProvider ?? 'github';
    const defaultAuthor = defaults.author ?? prefs.lastAuthor ?? process.env['USER'] ?? '';
    const defaultDocker = defaults.useDocker ?? prefs.lastUseDocker ?? true;
    const defaultPreCommit = defaults.usePreCommitHook ?? prefs.lastUsePreCommitHook ?? true;
    const pmChoices = availablePMs.map(pm => ({
        name: types_js_1.PACKAGE_MANAGER_NAMES[pm],
        value: pm,
    }));
    const questions = [
        {
            type: 'input',
            name: 'projectName',
            message: '项目名称:',
            default: defaults.projectName ?? 'my-project',
            validate: (input) => {
                if (!input.trim())
                    return '项目名称不能为空';
                if (!/^[a-z0-9-_]+$/.test(input))
                    return '项目名称只能包含小写字母、数字、横杠和下划线';
                return true;
            },
            when: () => !defaults.projectName,
        },
        {
            type: 'input',
            name: 'description',
            message: '项目描述:',
            default: defaults.description ?? 'A new TypeScript project',
            when: () => !defaults.description,
        },
        {
            type: 'input',
            name: 'author',
            message: '作者:',
            default: defaultAuthor,
            when: () => !defaults.author,
        },
        {
            type: 'list',
            name: 'framework',
            message: '选择框架:',
            choices: Object.entries(types_js_1.FRAMEWORK_NAMES).map(([value, name]) => ({
                name,
                value: value,
            })),
            default: defaultFramework,
            when: () => !defaults.framework,
        },
        {
            type: 'list',
            name: 'packageManager',
            message: '选择包管理器:',
            choices: pmChoices,
            default: defaultPM,
            when: () => !defaults.packageManager,
        },
        {
            type: 'confirm',
            name: 'useDocker',
            message: '是否需要 Docker 支持?',
            default: defaultDocker,
            when: () => defaults.useDocker === undefined,
        },
        {
            type: 'confirm',
            name: 'useCI',
            message: '是否需要 CI/CD 配置?',
            default: defaults.useCI ?? true,
            when: () => defaults.useCI === undefined,
        },
        {
            type: 'list',
            name: 'ciProvider',
            message: '选择 CI 平台:',
            choices: [
                { name: types_js_1.CI_PROVIDER_NAMES.github, value: 'github' },
                { name: types_js_1.CI_PROVIDER_NAMES.gitlab, value: 'gitlab' },
                { name: types_js_1.CI_PROVIDER_NAMES.none, value: 'none' },
            ],
            default: defaultCI,
            when: (answers) => answers.useCI && defaults.ciProvider === undefined,
        },
        {
            type: 'list',
            name: 'deployTarget',
            message: '选择部署目标:',
            choices: [
                { name: types_js_1.DEPLOY_TARGET_NAMES.docker, value: 'docker' },
                { name: types_js_1.DEPLOY_TARGET_NAMES.k8s, value: 'k8s' },
                { name: types_js_1.DEPLOY_TARGET_NAMES.none, value: 'none' },
            ],
            default: defaults.deployTarget ?? 'docker',
            when: (answers) => answers.useDocker && defaults.deployTarget === undefined,
        },
        {
            type: 'confirm',
            name: 'usePreCommitHook',
            message: '是否安装 pre-commit hook?',
            default: defaultPreCommit,
            when: () => defaults.usePreCommitHook === undefined,
        },
        {
            type: 'input',
            name: 'gitRemoteUrl',
            message: 'Git 远程仓库地址 (可选,留空跳过):',
            default: defaults.gitRemoteUrl ?? '',
            when: () => !defaults.gitRemoteUrl,
        },
    ];
    const answers = await inquirer_1.default.prompt(questions);
    if (answers.useCI === false) {
        answers.ciProvider = 'none';
    }
    if (answers.useDocker === false) {
        answers.deployTarget = 'none';
    }
    return {
        ...defaults,
        ...answers,
        ciProvider: answers.ciProvider,
        deployTarget: answers.deployTarget,
    };
}
async function confirmOverwrite(dir) {
    const { confirm } = await inquirer_1.default.prompt([
        {
            type: 'confirm',
            name: 'confirm',
            message: `目录 ${dir} 已存在,是否覆盖?`,
            default: false,
        },
    ]);
    return confirm;
}
//# sourceMappingURL=prompts.js.map