"use strict";
var __importDefault = (this && this.__importDefault) || function (mod) {
    return (mod && mod.__esModule) ? mod : { "default": mod };
};
Object.defineProperty(exports, "__esModule", { value: true });
exports.Scaffolder = void 0;
const fs_extra_1 = __importDefault(require("fs-extra"));
const path_1 = __importDefault(require("path"));
const ora_1 = __importDefault(require("ora"));
const chalk_1 = __importDefault(require("chalk"));
const engine_js_1 = require("./templates/engine.js");
const config_generator_js_1 = require("./generators/config-generator.js");
const ci_generator_js_1 = require("./generators/ci-generator.js");
const docker_generator_js_1 = require("./generators/docker-generator.js");
const code_quality_js_1 = require("./generators/code-quality.js");
const package_manager_js_1 = require("./package-manager.js");
const state_js_1 = require("./state.js");
class Scaffolder {
    config;
    templateConfig;
    constructor(config) {
        this.config = config;
    }
    async run() {
        const spinner = (0, ora_1.default)(`🚀 Creating ${chalk_1.default.bold(this.config.projectName)}...`).start();
        try {
            await this.prepareTargetDir();
            spinner.text = '📁 Target directory ready';
            const templateEngine = new engine_js_1.TemplateEngine(this.config);
            this.templateConfig = await templateEngine.render();
            spinner.text = '📝 Template files rendered';
            const configGenerator = new config_generator_js_1.ConfigGenerator(this.config);
            await configGenerator.generateAll();
            await this.updatePackageJson();
            spinner.text = '⚙️ Configuration files generated';
            const ciGenerator = new ci_generator_js_1.CiGenerator(this.config);
            await ciGenerator.generate();
            spinner.text = '🔧 CI/CD configuration generated';
            const dockerGenerator = new docker_generator_js_1.DockerGenerator(this.config);
            await dockerGenerator.generate();
            spinner.text = '🐳 Docker configuration generated';
            const pm = new package_manager_js_1.PackageManager(this.config.packageManager, this.config.targetDir);
            await pm.installDependencies();
            spinner.text = '📦 Dependencies installed';
            await this.runInitialBuild(pm);
            spinner.text = '🔨 Initial build completed';
            if (this.config.usePreCommitHook) {
                const checker = new code_quality_js_1.CodeQualityChecker(this.config.targetDir, this.config.packageManager);
                await checker.installPreCommitHook();
                spinner.text = '🪝 Pre-commit hook installed';
            }
            if ((0, package_manager_js_1.isGitAvailable)()) {
                await (0, package_manager_js_1.initGitRepo)(this.config);
                spinner.text = '📚 Git repository initialized';
            }
            await this.saveUserPreferences();
            spinner.succeed(chalk_1.default.green(`✨ Project ${chalk_1.default.bold(this.config.projectName)} created successfully!`));
            this.printSuccessMessage();
            return true;
        }
        catch (error) {
            spinner.fail(chalk_1.default.red(`Failed to create project: ${error.message}`));
            console.error('\nDetailed error:', error);
            return false;
        }
    }
    async prepareTargetDir() {
        const targetDir = this.config.targetDir;
        if (await fs_extra_1.default.pathExists(targetDir)) {
            const stats = await fs_extra_1.default.stat(targetDir);
            if (!stats.isDirectory()) {
                throw new Error(`Target path ${targetDir} exists but is not a directory`);
            }
            const contents = await fs_extra_1.default.readdir(targetDir);
            if (contents.length > 0) {
                if (this.config.quiet) {
                    await fs_extra_1.default.emptyDir(targetDir);
                }
                else {
                    throw new Error(`Directory ${targetDir} is not empty. Please use an empty directory or --force to overwrite.`);
                }
            }
        }
        else {
            await fs_extra_1.default.ensureDir(targetDir);
        }
    }
    async updatePackageJson() {
        const packageJsonPath = path_1.default.join(this.config.targetDir, 'package.json');
        const packageJson = await fs_extra_1.default.readJson(packageJsonPath);
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
        await fs_extra_1.default.writeJson(packageJsonPath, sortedPackageJson, { spaces: 2 });
    }
    sortObjectKeys(obj) {
        return Object.keys(obj)
            .sort()
            .reduce((result, key) => {
            result[key] = obj[key];
            return result;
        }, {});
    }
    async runInitialBuild(pm) {
        try {
            await pm.runScript('build');
        }
        catch (error) {
            console.warn(chalk_1.default.yellow(`\n⚠️  Initial build failed: ${error.message}`));
            console.warn(chalk_1.default.yellow('   You may need to run `npm run build` manually after fixing any issues.'));
        }
    }
    async saveUserPreferences() {
        await Promise.all([
            state_js_1.globalState.setFramework(this.config.framework),
            state_js_1.globalState.setPackageManager(this.config.packageManager),
            state_js_1.globalState.setCiProvider(this.config.ciProvider),
            state_js_1.globalState.setAuthor(this.config.author),
            state_js_1.globalState.setUseDocker(this.config.useDocker),
            state_js_1.globalState.setUsePreCommitHook(this.config.usePreCommitHook),
        ]);
    }
    printSuccessMessage() {
        const dir = path_1.default.relative(process.cwd(), this.config.targetDir) || this.config.projectName;
        console.log('\n' + chalk_1.default.bold('📋 Next steps:'));
        console.log(`   ${chalk_1.default.cyan('cd ' + dir)}`);
        if (this.config.framework === 'node-backend' && this.config.useDocker) {
            console.log(`   ${chalk_1.default.cyan(this.getPmCommand('run dev:docker'))}  # Start services with Docker Compose`);
        }
        console.log(`   ${chalk_1.default.cyan(this.getPmCommand('run dev'))}        # Start development server`);
        console.log(`   ${chalk_1.default.cyan(this.getPmCommand('run lint'))}       # Run ESLint`);
        console.log(`   ${chalk_1.default.cyan(this.getPmCommand('run test'))}       # Run tests`);
        console.log(`   ${chalk_1.default.cyan(this.getPmCommand('run build'))}      # Build for production`);
        if (this.config.gitRemoteUrl) {
            console.log(`\n   ${chalk_1.default.cyan('git push -u origin main')}  # Push to remote repository`);
        }
        console.log('\n' + chalk_1.default.green('🎉 Happy coding!'));
    }
    getPmCommand(command) {
        const pm = this.config.packageManager;
        return `${pm} ${command}`;
    }
}
exports.Scaffolder = Scaffolder;
//# sourceMappingURL=scaffolder.js.map