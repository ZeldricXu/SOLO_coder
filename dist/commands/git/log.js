"use strict";
var __importDefault = (this && this.__importDefault) || function (mod) {
    return (mod && mod.__esModule) ? mod : { "default": mod };
};
Object.defineProperty(exports, "__esModule", { value: true });
const core_1 = require("@oclif/core");
const list_1 = require("../env/list");
const GitTracker_1 = require("../../git/GitTracker");
const formatters_1 = require("../../utils/formatters");
const chalk_1 = __importDefault(require("chalk"));
class GitLogCommand extends core_1.Command {
    static description = 'Show configuration git history';
    static aliases = ['git:log'];
    static args = {
        key: core_1.Args.string({ description: 'Filter by key (dot notation)' }),
    };
    static flags = {
        config: core_1.Flags.string({ char: 'c', description: 'Path to config file' }),
        environment: core_1.Flags.string({ char: 'e', description: 'Filter by environment' }),
        since: core_1.Flags.string({ description: 'Show commits since (ISO date or relative like 7d)' }),
        until: core_1.Flags.string({ description: 'Show commits until' }),
        limit: core_1.Flags.integer({ char: 'n', default: 20, description: 'Number of commits to show' }),
        json: core_1.Flags.boolean({ description: 'Output as JSON' }),
    };
    async run() {
        const { args, flags } = await this.parse(GitLogCommand);
        const ctx = await (0, list_1.loadContext)(flags.config);
        const git = new GitTracker_1.GitTracker(ctx.config.gitRepoPath);
        await git.ensureInitialized();
        if (args.key) {
            const envName = flags.environment || ctx.configManager.listEnvironments()[0];
            if (!envName) {
                this.error('No environment configured. Specify --environment');
            }
            const history = await git.getKeyHistory(envName, args.key, flags.limit);
            if (flags.json) {
                this.log(JSON.stringify(history, null, 2));
            }
            else {
                this.log(await git.formatKeyHistory(history, args.key, envName));
            }
            return;
        }
        const log = await git.log({
            environment: flags.environment,
            limit: flags.limit,
        });
        if (flags.json) {
            this.log(JSON.stringify(log, null, 2));
            return;
        }
        if (log.length === 0) {
            this.log('No commits yet.');
            return;
        }
        this.log(`📜 Commit History (last ${log.length})`);
        this.log('─'.repeat(90));
        for (const entry of log) {
            this.log(`\n${chalk_1.default.yellow('commit')} ${entry.hash}`);
            this.log(`${chalk_1.default.blue('Author:')} ${entry.author}`);
            this.log(`${chalk_1.default.blue('Date:')}   ${(0, formatters_1.formatTimestamp)(entry.timestamp)}`);
            this.log('');
            this.log(`    ${entry.message}`);
            if (entry.changes.length > 0) {
                this.log('');
                for (const c of entry.changes) {
                    this.log(`    ${chalk_1.default.magenta('M')} ${c}`);
                }
            }
        }
    }
}
exports.default = GitLogCommand;
//# sourceMappingURL=log.js.map