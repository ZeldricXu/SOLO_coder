"use strict";
var __importDefault = (this && this.__importDefault) || function (mod) {
    return (mod && mod.__esModule) ? mod : { "default": mod };
};
Object.defineProperty(exports, "__esModule", { value: true });
exports.CodeQualityChecker = void 0;
const execa_1 = require("execa");
const fs_extra_1 = __importDefault(require("fs-extra"));
const path_1 = __importDefault(require("path"));
const ora_1 = __importDefault(require("ora"));
const chalk_1 = __importDefault(require("chalk"));
const package_manager_js_1 = require("../package-manager.js");
class CodeQualityChecker {
    cwd;
    pm;
    constructor(cwd, packageManager) {
        this.cwd = cwd;
        this.pm = new package_manager_js_1.PackageManager(packageManager, cwd);
    }
    async lint(options = {}) {
        const spinner = (0, ora_1.default)(options.fix ? 'Running ESLint with --fix...' : 'Running ESLint...').start();
        try {
            const args = ['.'];
            if (options.fix)
                args.push('--fix');
            if (options.staged) {
                return await this.lintStaged('lint', args);
            }
            const result = await this.pm.runScriptWithOutput('lint', args);
            if (result.success) {
                spinner.succeed(chalk_1.default.green('ESLint passed!'));
            }
            else {
                spinner.fail(chalk_1.default.red('ESLint found issues'));
            }
            return result;
        }
        catch (error) {
            spinner.fail(chalk_1.default.red(`ESLint failed: ${error.message}`));
            throw error;
        }
    }
    async format(options = {}) {
        const spinner = (0, ora_1.default)(options.fix ? 'Running Prettier (write mode)...' : 'Running Prettier (check mode)...').start();
        try {
            const script = options.fix ? 'format' : 'format:check';
            const result = await this.pm.runScriptWithOutput(script);
            if (result.success) {
                spinner.succeed(chalk_1.default.green('Prettier check passed!'));
            }
            else {
                spinner.fail(chalk_1.default.red('Prettier found formatting issues'));
            }
            return result;
        }
        catch (error) {
            spinner.fail(chalk_1.default.red(`Prettier failed: ${error.message}`));
            throw error;
        }
    }
    async runAll(options = {}) {
        console.log(chalk_1.default.bold('\n📋 Running code quality checks...\n'));
        const formatResult = await this.format(options);
        const lintResult = await this.lint(options);
        const allPassed = formatResult.success && lintResult.success;
        console.log('\n' + chalk_1.default.bold('📊 Summary:'));
        console.log(`  Prettier: ${formatResult.success ? chalk_1.default.green('✓ PASS') : chalk_1.default.red('✗ FAIL')}`);
        console.log(`  ESLint:   ${lintResult.success ? chalk_1.default.green('✓ PASS') : chalk_1.default.red('✗ FAIL')}`);
        console.log(`  Total issues: ${chalk_1.default.red(formatResult.errorCount + lintResult.errorCount)} errors, ${chalk_1.default.yellow(formatResult.warningCount + lintResult.warningCount)} warnings`);
        if (!allPassed) {
            console.log('\n' + chalk_1.default.yellow('💡 Tip: Run with --fix to auto-fix most issues'));
        }
        return allPassed;
    }
    async lintStaged(type, args) {
        try {
            const stagedFiles = await this.getStagedFiles();
            if (stagedFiles.length === 0) {
                return { success: true, errorCount: 0, warningCount: 0, output: 'No staged files' };
            }
            const script = type === 'lint' ? 'lint' : 'format:check';
            const result = await this.pm.runScriptWithOutput(script, [...args, ...stagedFiles]);
            return result;
        }
        catch (error) {
            return { success: false, errorCount: 1, warningCount: 0, output: error.message };
        }
    }
    async getStagedFiles() {
        try {
            const { stdout } = await (0, execa_1.execa)('git', ['diff', '--cached', '--name-only', '--diff-filter=ACM'], {
                cwd: this.cwd,
            });
            return stdout.split('\n').filter(Boolean);
        }
        catch {
            return [];
        }
    }
    async installPreCommitHook() {
        const spinner = (0, ora_1.default)('Installing pre-commit hook...').start();
        try {
            const gitDir = path_1.default.join(this.cwd, '.git');
            if (!await fs_extra_1.default.pathExists(gitDir)) {
                spinner.warn('Not a git repository, skipping pre-commit hook installation');
                return false;
            }
            const hooksDir = path_1.default.join(gitDir, 'hooks');
            await fs_extra_1.default.ensureDir(hooksDir);
            const hookPath = path_1.default.join(hooksDir, 'pre-commit');
            const hookContent = this.getPreCommitHookContent();
            await fs_extra_1.default.writeFile(hookPath, hookContent, { mode: 0o755 });
            await this.addHuskyConfig();
            spinner.succeed(chalk_1.default.green('Pre-commit hook installed successfully!'));
            return true;
        }
        catch (error) {
            spinner.fail(chalk_1.default.red(`Failed to install pre-commit hook: ${error.message}`));
            return false;
        }
    }
    getPreCommitHookContent() {
        return `#!/usr/bin/env sh
# Pre-commit hook generated by create-solo-project
# Runs lint and prettier on staged files

echo "🔍 Running pre-commit checks..."

# Stash unstaged changes
git stash -q --keep-index

# Run checks
npx eslint --cache --cache-location .eslintcache $(git diff --cached --name-only --diff-filter=ACM | grep -E '\\.(ts|tsx|js|jsx)$' || true)
ESLINT_EXIT=$?

npx prettier --check $(git diff --cached --name-only --diff-filter=ACM | grep -vE 'package-lock\\.json|yarn\\.lock|pnpm-lock\\.yaml' || true)
PRETTIER_EXIT=$?

# Restore unstaged changes
git stash pop -q

# Check results
if [ $ESLINT_EXIT -ne 0 ] || [ $PRETTIER_EXIT -ne 0 ]; then
  echo ""
  echo "❌ Pre-commit checks failed!"
  echo "   Run 'npx csp lint --fix' to auto-fix issues, then commit again."
  exit 1
fi

echo "✅ Pre-commit checks passed!"
exit 0
`;
    }
    async addHuskyConfig() {
        try {
            const packageJsonPath = path_1.default.join(this.cwd, 'package.json');
            if (!await fs_extra_1.default.pathExists(packageJsonPath))
                return;
            const packageJson = await fs_extra_1.default.readJson(packageJsonPath);
            packageJson.scripts = packageJson.scripts || {};
            packageJson.scripts['prepare'] = 'husky install';
            if (!packageJson.devDependencies || !packageJson.devDependencies.husky) {
                await this.pm.installDevDependency('husky', '^9.0.0');
            }
            await fs_extra_1.default.writeJson(packageJsonPath, packageJson, { spaces: 2 });
            try {
                await (0, execa_1.execa)('npx', ['husky', 'install'], { cwd: this.cwd, stdio: 'ignore' });
            }
            catch {
                // husky install might fail if not a git repo, that's ok
            }
        }
        catch {
            // Silent fail, hook is already installed
        }
    }
    async uninstallPreCommitHook() {
        const spinner = (0, ora_1.default)('Uninstalling pre-commit hook...').start();
        try {
            const hookPath = path_1.default.join(this.cwd, '.git', 'hooks', 'pre-commit');
            if (await fs_extra_1.default.pathExists(hookPath)) {
                await fs_extra_1.default.remove(hookPath);
                spinner.succeed(chalk_1.default.green('Pre-commit hook uninstalled successfully!'));
                return true;
            }
            else {
                spinner.info('Pre-commit hook not found');
                return false;
            }
        }
        catch (error) {
            spinner.fail(chalk_1.default.red(`Failed to uninstall pre-commit hook: ${error.message}`));
            return false;
        }
    }
    isPreCommitHookInstalled() {
        try {
            const hookPath = path_1.default.join(this.cwd, '.git', 'hooks', 'pre-commit');
            return fs_extra_1.default.existsSync(hookPath);
        }
        catch {
            return false;
        }
    }
}
exports.CodeQualityChecker = CodeQualityChecker;
package_manager_js_1.PackageManager.prototype.runScriptWithOutput = async function (script, args = []) {
    try {
        const pmArgs = this.getType() === 'npm' ? ['run', script, ...args] : [script, ...args];
        const result = await (0, execa_1.execa)(this.getType(), pmArgs, {
            cwd: this.cwd,
            stdout: 'pipe',
            stderr: 'pipe',
        });
        return {
            success: true,
            errorCount: 0,
            warningCount: 0,
            output: result.stdout,
        };
    }
    catch (error) {
        const output = error.stdout || error.stderr || error.message;
        const errorMatch = output.match(/(\d+) error/) || output.match(/✗ (\d+)/);
        const warnMatch = output.match(/(\d+) warning/) || output.match(/⚠ (\d+)/);
        return {
            success: false,
            errorCount: errorMatch ? parseInt(errorMatch[1], 10) : 1,
            warningCount: warnMatch ? parseInt(warnMatch[1], 10) : 0,
            output,
        };
    }
};
package_manager_js_1.PackageManager.prototype.installDevDependency = async function (packageName, version) {
    const pkg = version ? `${packageName}@${version}` : packageName;
    const args = this.getType() === 'yarn'
        ? ['add', '--dev', pkg]
        : this.getType() === 'pnpm'
            ? ['add', '-D', pkg]
            : ['install', '--save-dev', pkg];
    await (0, execa_1.execa)(this.getType(), args, { cwd: this.cwd, stdio: 'ignore' });
};
//# sourceMappingURL=code-quality.js.map