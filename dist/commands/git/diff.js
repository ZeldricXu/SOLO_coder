"use strict";
var __importDefault = (this && this.__importDefault) || function (mod) {
    return (mod && mod.__esModule) ? mod : { "default": mod };
};
Object.defineProperty(exports, "__esModule", { value: true });
const core_1 = require("@oclif/core");
const list_1 = require("../env/list");
const GitTracker_1 = require("../../git/GitTracker");
const chalk_1 = __importDefault(require("chalk"));
const DiffEngine_1 = require("../../engine/DiffEngine");
const formatters_1 = require("../../utils/formatters");
class GitDiffCommand extends core_1.Command {
    static description = 'Show diff between two commits';
    static aliases = ['git:compare'];
    static args = {
        commitA: core_1.Args.string({ description: 'First commit hash (defaults to previous)', required: false }),
        commitB: core_1.Args.string({ description: 'Second commit hash (defaults to HEAD)', required: false }),
    };
    static flags = {
        config: core_1.Flags.string({ char: 'c', description: 'Path to config file' }),
        environment: core_1.Flags.string({ char: 'e', description: 'Filter by environment' }),
        key: core_1.Flags.string({ char: 'k', description: 'Filter by key' }),
        noColor: core_1.Flags.boolean({ description: 'No colored output' }),
        json: core_1.Flags.boolean({ description: 'Output as JSON' }),
    };
    async run() {
        const { args, flags } = await this.parse(GitDiffCommand);
        const ctx = await (0, list_1.loadContext)(flags.config);
        const git = new GitTracker_1.GitTracker(ctx.config.gitRepoPath);
        await git.ensureInitialized();
        const log = await git.log({ environment: flags.environment, limit: 2 });
        if (log.length < 2 && !args.commitA) {
            this.log('Need at least 2 commits to compare.');
            return;
        }
        const commitA = args.commitA || log[1]?.hash;
        const commitB = args.commitB || log[0]?.hash;
        if (!commitA || !commitB) {
            this.error('Could not determine commits to compare');
        }
        const environments = flags.environment
            ? [flags.environment]
            : ctx.configManager.listEnvironments();
        const allDiffs = [];
        const diffEngine = new DiffEngine_1.DiffEngine();
        for (const envName of environments) {
            const dataA = (await git.loadEnvironmentSnapshot(envName, commitA));
            const dataB = (await git.loadEnvironmentSnapshot(envName, commitB));
            if (!dataA || !dataB)
                continue;
            const report = diffEngine.compare(dataA, dataB, `${envName}@${commitA.slice(0, 7)}`, `${envName}@${commitB.slice(0, 7)}`);
            if (flags.key) {
                report.diffs = diffEngine.filterDiffs(report.diffs, { keyPattern: flags.key });
            }
            allDiffs.push({ environment: envName, diffReport: report });
        }
        if (flags.json) {
            this.log(JSON.stringify(allDiffs, null, 2));
            return;
        }
        this.log(`🔍 Diff: ${chalk_1.default.yellow(commitA.slice(0, 7))} → ${chalk_1.default.yellow(commitB.slice(0, 7))}`);
        this.log('═'.repeat(90));
        for (const { environment, diffReport } of allDiffs) {
            this.log(`\n${chalk_1.default.magenta('Environment:')} ${environment}`);
            this.log('─'.repeat(90));
            this.log((0, formatters_1.formatDiffReport)(diffReport, !flags.noColor));
        }
        const gitDiffs = await git.diffCommits(commitA, commitB, flags.environment);
        if (gitDiffs.length > 0) {
            this.log(`\n${chalk_1.default.gray('Raw git diff:')}`);
            for (const gd of gitDiffs) {
                this.log(`  ${chalk_1.default.cyan(gd.file)}`);
            }
        }
    }
}
exports.default = GitDiffCommand;
//# sourceMappingURL=diff.js.map