"use strict";
var __importDefault = (this && this.__importDefault) || function (mod) {
    return (mod && mod.__esModule) ? mod : { "default": mod };
};
Object.defineProperty(exports, "__esModule", { value: true });
const core_1 = require("@oclif/core");
const list_1 = require("./env/list");
const RotationScheduler_1 = require("../rotation/RotationScheduler");
const HistoryStorage_1 = require("../storage/HistoryStorage");
const NotificationDispatcher_1 = require("../notifications/NotificationDispatcher");
const formatters_1 = require("../utils/formatters");
const chalk_1 = __importDefault(require("chalk"));
class RotateCommand extends core_1.Command {
    static description = 'Rotate secrets in Vault or SSM and track history';
    static args = {
        key: core_1.Args.string({ description: 'Secret key to rotate', required: true }),
        environment: core_1.Args.string({ description: 'Environment name', required: true }),
    };
    static flags = {
        config: core_1.Flags.string({ char: 'c', description: 'Path to config file' }),
        batch: core_1.Flags.string({ char: 'b', description: 'Comma-separated list of keys to rotate' }),
        list: core_1.Flags.boolean({ char: 'l', description: 'List rotation history' }),
        check: core_1.Flags.boolean({ description: 'Check if a key needs rotation' }),
        maxAge: core_1.Flags.string({ description: 'Max age for --check (e.g., 24h, 7d, 30d)' }),
        schedule: core_1.Flags.string({ description: 'Schedule interval (e.g., 24h, 7d)' }),
        operator: core_1.Flags.string({ char: 'u', description: 'Operator name' }),
        notify: core_1.Flags.boolean({ description: 'Send notifications' }),
        verify: core_1.Flags.boolean({ default: true, description: 'Verify value after rotation' }),
        json: core_1.Flags.boolean({ description: 'Output as JSON' }),
        force: core_1.Flags.boolean({ char: 'f', description: 'Force rotation without prompt' }),
    };
    async run() {
        const { args, flags } = await this.parse(RotateCommand);
        const ctx = await (0, list_1.loadContext)(flags.config);
        const operator = flags.operator || ctx.config.defaultOperator || 'unknown';
        const scheduler = new RotationScheduler_1.RotationScheduler(operator);
        const storage = new HistoryStorage_1.HistoryStorage(ctx.config.storagePath);
        const existingRecords = await storage.getRotationHistory({
            environment: args.environment,
            key: args.key,
        });
        scheduler.setRecords(existingRecords);
        if (flags.list) {
            await this.listHistory(scheduler, args.environment, args.key, storage, flags.json);
            storage.close();
            return;
        }
        if (flags.check) {
            const maxAgeMs = this.parseDuration(flags.maxAge || '30d');
            const needs = scheduler.needsRotation(args.environment, args.key, maxAgeMs);
            const age = scheduler.getRotationAge(args.environment, args.key);
            if (flags.json) {
                this.log(JSON.stringify({
                    key: args.key,
                    environment: args.environment,
                    needsRotation: needs,
                    ageMs: age,
                    ageFormatted: age ? (0, formatters_1.formatDuration)(age) : 'never',
                    maxAgeMs,
                    maxAgeFormatted: (0, formatters_1.formatDuration)(maxAgeMs),
                }));
            }
            else {
                const ageStr = age ? (0, formatters_1.formatDuration)(age) + ' ago' : 'never rotated';
                if (needs) {
                    this.log(`${chalk_1.default.yellow('⚠')} ${args.key} in ${args.environment} ${chalk_1.default.yellow('needs rotation')} (last: ${ageStr})`);
                }
                else {
                    this.log(`${chalk_1.default.green('✓')} ${args.key} in ${args.environment} is fine (last: ${ageStr})`);
                }
            }
            storage.close();
            return;
        }
        const env = ctx.configManager.getEnvironment(args.environment);
        if (!env) {
            this.error(`Environment not found: ${args.environment}`);
        }
        const keys = flags.batch
            ? flags.batch.split(',').map((s) => s.trim()).filter(Boolean)
            : [args.key];
        if (!flags.force && !flags.json) {
            this.log(`Preparing to rotate ${keys.length} key(s) in ${chalk_1.default.magenta(args.environment)}:`);
            for (const k of keys)
                this.log(`  - ${k}`);
            let yes = false;
            try {
                await core_1.ux.prompt('Proceed with rotation? (y/n)');
                yes = true;
            }
            catch {
                yes = false;
            }
            if (!yes) {
                this.log('Aborted.');
                storage.close();
                return;
            }
        }
        const records = await scheduler.rotateBatch(env, keys, {
            operator,
            verify: flags.verify,
            onNotify: flags.notify && ctx.config.notifications ? async (_msg) => {
                // handled below for consolidated notification
            } : undefined,
        });
        for (const r of records) {
            await storage.recordRotation(r);
        }
        const newRecords = scheduler.getAllRecords();
        for (const r of newRecords.slice(-records.length)) {
            const targetEnv = ctx.config.environments.find((e) => e.name === r.environment);
            if (targetEnv) {
                await storage.recordKeyValueChange(r.environment, r.key, undefined, undefined, r.operator, undefined);
            }
        }
        storage.close();
        if (flags.notify && ctx.config.notifications) {
            try {
                const dispatcher = new NotificationDispatcher_1.NotificationDispatcher(ctx.config.notifications);
                const successRecords = records.filter((r) => r.status === 'success');
                const changes = successRecords.map((r) => ({
                    type: 'changed',
                    key: r.key,
                    path: `${r.environment}.${r.key}`,
                }));
                const message = {
                    title: `Secrets Rotated (${successRecords.length}/${records.length})`,
                    summary: `${operator} rotated ${keys.join(', ')} in ${args.environment}`,
                    changes,
                    operator,
                    environment: args.environment,
                    timestamp: Date.now(),
                };
                const results = await dispatcher.dispatch(message);
                if (!flags.json) {
                    const success = results.filter((r) => r.success).length;
                    this.log(`${chalk_1.default.blue('ℹ')} Notifications: ${success}/${results.length} sent`);
                }
            }
            catch (error) {
                this.warn(`Notification failed: ${error.message}`);
            }
        }
        if (flags.json) {
            this.log(JSON.stringify(records, null, 2));
            return;
        }
        const success = records.filter((r) => r.status === 'success').length;
        const failed = records.filter((r) => r.status === 'failed').length;
        this.log(`\nRotation complete: ${chalk_1.default.green(success)} success, ${chalk_1.default.red(failed)} failed`);
        for (const r of records) {
            const icon = r.status === 'success' ? chalk_1.default.green('✓') : chalk_1.default.red('✗');
            this.log(`  ${icon} ${r.key}${r.status === 'failed' && r.message ? ` - ${r.message}` : ''}`);
        }
        if (failed > 0)
            this.exit(1);
    }
    async listHistory(scheduler, environment, key, storage, json) {
        const history = await storage.getRotationHistory({ environment, key, limit: 50 });
        if (json) {
            this.log(JSON.stringify(history, null, 2));
            return;
        }
        if (history.length === 0) {
            this.log('No rotation history found.');
            return;
        }
        this.log(`📜 Rotation history for ${chalk_1.default.yellow(key)} in ${chalk_1.default.magenta(environment)} (last 50)`);
        this.log('─'.repeat(90));
        for (const r of history) {
            const icon = r.status === 'success' ? chalk_1.default.green('✓') : chalk_1.default.red('✗');
            this.log(`${icon} [${(0, formatters_1.formatTimestamp)(r.timestamp)}] ${chalk_1.default.gray(r.sourceType.padStart(10))} ${r.operator.padEnd(16)} ${r.status.toUpperCase()}` +
                (r.message ? ` - ${r.message}` : ''));
        }
    }
    parseDuration(s) {
        const match = s.match(/^(\d+)([smhdw])$/);
        if (!match) {
            try {
                return parseInt(s, 10);
            }
            catch {
                return 30 * 24 * 60 * 60 * 1000;
            }
        }
        const n = parseInt(match[1], 10);
        const unit = match[2];
        switch (unit) {
            case 's': return n * 1000;
            case 'm': return n * 60 * 1000;
            case 'h': return n * 60 * 60 * 1000;
            case 'd': return n * 24 * 60 * 60 * 1000;
            case 'w': return n * 7 * 24 * 60 * 60 * 1000;
            default: return n * 1000;
        }
    }
}
exports.default = RotateCommand;
//# sourceMappingURL=rotate.js.map