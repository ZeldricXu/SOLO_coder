"use strict";
var __createBinding = (this && this.__createBinding) || (Object.create ? (function(o, m, k, k2) {
    if (k2 === undefined) k2 = k;
    var desc = Object.getOwnPropertyDescriptor(m, k);
    if (!desc || ("get" in desc ? !m.__esModule : desc.writable || desc.configurable)) {
      desc = { enumerable: true, get: function() { return m[k]; } };
    }
    Object.defineProperty(o, k2, desc);
}) : (function(o, m, k, k2) {
    if (k2 === undefined) k2 = k;
    o[k2] = m[k];
}));
var __setModuleDefault = (this && this.__setModuleDefault) || (Object.create ? (function(o, v) {
    Object.defineProperty(o, "default", { enumerable: true, value: v });
}) : function(o, v) {
    o["default"] = v;
});
var __importStar = (this && this.__importStar) || (function () {
    var ownKeys = function(o) {
        ownKeys = Object.getOwnPropertyNames || function (o) {
            var ar = [];
            for (var k in o) if (Object.prototype.hasOwnProperty.call(o, k)) ar[ar.length] = k;
            return ar;
        };
        return ownKeys(o);
    };
    return function (mod) {
        if (mod && mod.__esModule) return mod;
        var result = {};
        if (mod != null) for (var k = ownKeys(mod), i = 0; i < k.length; i++) if (k[i] !== "default") __createBinding(result, mod, k[i]);
        __setModuleDefault(result, mod);
        return result;
    };
})();
var __importDefault = (this && this.__importDefault) || function (mod) {
    return (mod && mod.__esModule) ? mod : { "default": mod };
};
Object.defineProperty(exports, "__esModule", { value: true });
const core_1 = require("@oclif/core");
const list_1 = require("./env/list");
const SyncPipeline_1 = require("../sync/SyncPipeline");
const SchemaValidator_1 = require("../schemas/SchemaValidator");
const HistoryStorage_1 = require("../storage/HistoryStorage");
const NotificationDispatcher_1 = require("../notifications/NotificationDispatcher");
const GitTracker_1 = require("../git/GitTracker");
const chalk_1 = __importDefault(require("chalk"));
const fs = __importStar(require("fs"));
class SyncCommand extends core_1.Command {
    static description = 'Sync configuration between environments';
    static aliases = ['sync:push'];
    static args = {
        key: core_1.Args.string({ description: 'Configuration key to sync (dot notation)', required: true }),
        source: core_1.Args.string({ description: 'Source environment', required: true }),
        targets: core_1.Args.string({ description: 'Target environments (comma-separated)', required: true }),
    };
    static flags = {
        config: core_1.Flags.string({ char: 'c', description: 'Path to config file' }),
        dryRun: core_1.Flags.boolean({ char: 'n', default: false, description: 'Preview changes without applying' }),
        validate: core_1.Flags.boolean({ description: 'Validate against schema before syncing' }),
        verify: core_1.Flags.boolean({ default: true, description: 'Verify values after syncing' }),
        schema: core_1.Flags.string({ description: 'Path to schema (defaults to configured path)' }),
        gitCommit: core_1.Flags.boolean({ default: true, description: 'Commit changes to git' }),
        notify: core_1.Flags.boolean({ description: 'Send notifications about the change' }),
        operator: core_1.Flags.string({ char: 'u', description: 'Operator name (for audit log)' }),
        json: core_1.Flags.boolean({ description: 'Output as JSON' }),
        force: core_1.Flags.boolean({ char: 'f', description: 'Skip confirmation prompt' }),
    };
    async run() {
        const { args, flags } = await this.parse(SyncCommand);
        const ctx = await (0, list_1.loadContext)(flags.config);
        const operator = flags.operator || ctx.config.defaultOperator || 'unknown';
        const targets = args.targets.split(',').map((s) => s.trim()).filter(Boolean);
        const syncItem = {
            key: args.key,
            sourceEnvironment: args.source,
            targetEnvironments: targets,
        };
        const syncPipeline = new SyncPipeline_1.SyncPipeline(ctx.configManager);
        let validator;
        if (flags.validate) {
            const schemaPath = flags.schema || ctx.config.schemaPath;
            if (fs.existsSync(schemaPath)) {
                const rawSchema = JSON.parse(fs.readFileSync(schemaPath, 'utf-8'));
                validator = new SchemaValidator_1.SchemaValidator(rawSchema);
            }
            else {
                this.warn(`Schema not found at ${schemaPath}, skipping validation`);
            }
        }
        const previews = await syncPipeline.previewSync(syncItem);
        const nonSkip = previews.filter((p) => p.action !== 'skip');
        if (!flags.json) {
            this.log(syncPipeline.formatPreviews(previews));
            this.log('');
        }
        if (nonSkip.length === 0) {
            if (!flags.json)
                this.log(chalk_1.default.green('✓ No changes needed - all targets are in sync'));
            return;
        }
        if (!flags.dryRun && !flags.force && !flags.json) {
            let yesno = false;
            try {
                await core_1.ux.prompt(`Apply ${nonSkip.length} change(s) to ${targets.length} environment(s)? (y/n)`);
                yesno = true;
            }
            catch {
                yesno = false;
            }
            if (!yesno) {
                this.log('Aborted.');
                return;
            }
        }
        const sourceEnv = ctx.configManager.getEnvironment(args.source);
        if (!sourceEnv)
            this.error(`Source environment not found: ${args.source}`);
        const oldValue = await sourceEnv.get(args.key);
        const result = await syncPipeline.executeBatch([syncItem], {
            dryRun: flags.dryRun,
            validateBefore: flags.validate,
            verifyAfter: flags.verify,
            validator,
        });
        const storage = new HistoryStorage_1.HistoryStorage(ctx.config.storagePath);
        try {
            for (const r of result.results) {
                await storage.recordSync(syncItem, [r], flags.dryRun, operator);
            }
            if (!flags.dryRun) {
                const targetEnvConfigs = ctx.config.environments.filter((e) => targets.includes(e.name));
                for (const targetConfig of targetEnvConfigs) {
                    const targetEnv = ctx.configManager.getEnvironment(targetConfig.name);
                    if (targetEnv) {
                        const newValue = await targetEnv.get(args.key);
                        await storage.recordKeyValueChange(targetConfig.name, args.key, oldValue, newValue, operator);
                    }
                }
            }
        }
        finally {
            storage.close();
        }
        if (flags.gitCommit && !flags.dryRun) {
            try {
                const git = new GitTracker_1.GitTracker(ctx.config.gitRepoPath);
                await git.ensureInitialized({ operator });
                const snapshot = {};
                for (const envName of [args.source, ...targets]) {
                    const env = ctx.configManager.getEnvironment(envName);
                    if (env) {
                        snapshot[envName] = await env.loadAll();
                    }
                }
                git.saveAllSnapshots(snapshot);
                const changes = [];
                for (const target of targets) {
                    const newVal = await ctx.configManager.getEnvironment(target)?.get(args.key);
                    changes.push({
                        type: oldValue === undefined ? 'added' : 'changed',
                        key: args.key,
                        path: `${target}.${args.key}`,
                        before: oldValue,
                        after: newVal,
                    });
                }
                const actionStr = flags.dryRun ? '(dry-run) ' : '';
                const commit = await git.commitChanges(`${actionStr}sync ${args.key} from ${args.source} to ${targets.join(',')}`, { operator });
                if (commit) {
                    if (!flags.json) {
                        this.log(`\n${chalk_1.default.green('✓')} Committed to git: ${chalk_1.default.yellow(commit.hash.slice(0, 8))}`);
                    }
                }
            }
            catch (error) {
                this.warn(`Git commit failed: ${error.message}`);
            }
        }
        if (flags.notify && !flags.dryRun && ctx.config.notifications) {
            try {
                const dispatcher = new NotificationDispatcher_1.NotificationDispatcher(ctx.config.notifications);
                const diffs = [];
                for (const r of result.results) {
                    if (r.status === 'success') {
                        diffs.push({
                            type: oldValue === undefined ? 'added' : 'changed',
                            key: args.key,
                            path: `${r.targetEnvironment}.${args.key}`,
                            before: oldValue,
                            after: await ctx.configManager.getEnvironment(r.targetEnvironment)?.get(args.key),
                        });
                    }
                }
                const message = {
                    title: `Config Synced: ${args.key}`,
                    summary: `${operator} synced ${args.key} from ${args.source} to ${targets.join(', ')}`,
                    changes: diffs,
                    operator,
                    environment: args.source,
                    timestamp: Date.now(),
                };
                const dispatchResults = await dispatcher.dispatch(message);
                if (!flags.json) {
                    const success = dispatchResults.filter((r) => r.success).length;
                    this.log(`${chalk_1.default.blue('ℹ')} Notifications: ${success}/${dispatchResults.length} sent`);
                }
            }
            catch (error) {
                this.warn(`Notification failed: ${error.message}`);
            }
        }
        if (flags.json) {
            this.log(JSON.stringify(result, null, 2));
            return;
        }
        this.log('');
        this.log(`Sync ${flags.dryRun ? 'Preview' : 'Results'}: ${chalk_1.default.green(result.summary.success)} success, ${chalk_1.default.red(result.summary.failed)} failed, ${chalk_1.default.gray(result.summary.skipped)} skipped`);
        if (flags.verify && !flags.dryRun) {
            this.log(`Verified: ${result.summary.verified}/${result.summary.total}`);
        }
        for (const r of result.results) {
            const icon = r.status === 'success' ? chalk_1.default.green('✓') : chalk_1.default.red('✗');
            const verifyStr = flags.verify && !flags.dryRun ? (r.verified ? ' [verified]' : ' [UNVERIFIED]') : '';
            this.log(`  ${icon} ${r.targetEnvironment}${verifyStr}${r.message ? ` - ${r.message}` : ''}`);
        }
        if (result.summary.failed > 0 || (flags.verify && !flags.dryRun && result.summary.verified < result.summary.total)) {
            this.exit(1);
        }
    }
}
exports.default = SyncCommand;
//# sourceMappingURL=sync.js.map