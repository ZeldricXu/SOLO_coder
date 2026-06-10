"use strict";
var __importDefault = (this && this.__importDefault) || function (mod) {
    return (mod && mod.__esModule) ? mod : { "default": mod };
};
Object.defineProperty(exports, "__esModule", { value: true });
const core_1 = require("@oclif/core");
const list_1 = require("./env/list");
const HistoryStorage_1 = require("../storage/HistoryStorage");
const formatters_1 = require("../utils/formatters");
const chalk_1 = __importDefault(require("chalk"));
class HistoryCommand extends core_1.Command {
    static description = 'View operation history stored in SQLite';
    static aliases = ['log', 'audit'];
    static args = {
        type: core_1.Args.string({
            description: 'Type of history to view',
            options: ['all', 'validation', 'diff', 'rotation', 'sync', 'key', 'notification'],
            default: 'all',
        }),
    };
    static flags = {
        config: core_1.Flags.string({ char: 'c', description: 'Path to config file' }),
        environment: core_1.Flags.string({ char: 'e', description: 'Filter by environment' }),
        key: core_1.Flags.string({ char: 'k', description: 'Filter by key (for key/rotation/sync history)' }),
        operator: core_1.Flags.string({ char: 'u', description: 'Filter by operator' }),
        since: core_1.Flags.string({ description: 'Since (ISO date or relative like 7d, 24h)' }),
        until: core_1.Flags.string({ description: 'Until timestamp' }),
        limit: core_1.Flags.integer({ char: 'n', default: 50, description: 'Number of records to show' }),
        status: core_1.Flags.string({ description: 'Filter by status (success/failed)' }),
        json: core_1.Flags.boolean({ description: 'Output as JSON' }),
    };
    async run() {
        const { args, flags } = await this.parse(HistoryCommand);
        const ctx = await (0, list_1.loadContext)(flags.config);
        const storage = new HistoryStorage_1.HistoryStorage(ctx.config.storagePath);
        try {
            const filters = {
                environment: flags.environment,
                since: flags.since ? this.parseRelativeTime(flags.since) : undefined,
                until: flags.until ? parseInt(flags.until, 10) : undefined,
                limit: flags.limit,
            };
            const type = args.type;
            if (type === 'all') {
                await this.showAllSummary(storage, filters, flags.json);
                return;
            }
            switch (type) {
                case 'validation': {
                    const records = await storage.getValidationHistory({
                        ...filters,
                        invalidOnly: flags.status === 'failed' ? true : undefined,
                    });
                    this.outputValidation(records, flags.json);
                    break;
                }
                case 'diff': {
                    const records = await storage.getDiffHistory(filters);
                    this.outputDiffs(records, flags.json);
                    break;
                }
                case 'rotation': {
                    const records = await storage.getRotationHistory({
                        ...filters,
                        key: flags.key,
                        status: flags.status,
                    });
                    this.outputRotations(records, flags.json);
                    break;
                }
                case 'sync': {
                    const records = await storage.getKeyValueHistory({
                        environment: filters.environment || 'all',
                        keyPath: flags.key,
                        since: filters.since,
                        until: filters.until,
                        limit: filters.limit,
                    });
                    this.outputKeyValue(records, flags.json);
                    break;
                }
                case 'key': {
                    if (!flags.environment)
                        this.error('--environment is required for key history');
                    if (!flags.key)
                        this.error('--key is required for key history');
                    const records = await storage.getKeyValueHistory({
                        environment: flags.environment,
                        keyPath: flags.key,
                        since: filters.since,
                        until: filters.until,
                        limit: filters.limit,
                    });
                    this.outputKeyValue(records, flags.json, flags.environment, flags.key);
                    break;
                }
                case 'notification': {
                    this.warn('Notification list view coming soon. Use limit filter.');
                    break;
                }
            }
        }
        finally {
            storage.close();
        }
    }
    async showAllSummary(storage, filters, json) {
        const [validations, diffs, rotations] = await Promise.all([
            storage.getValidationHistory({ ...filters, limit: 5 }),
            storage.getDiffHistory({ ...filters, limit: 5 }),
            storage.getRotationHistory({ ...filters, limit: 5 }),
        ]);
        if (json) {
            this.log(JSON.stringify({ validations, diffs, rotations }, null, 2));
            return;
        }
        this.log('📊 Audit History Summary');
        this.log('═'.repeat(90));
        this.log(`\n${chalk_1.default.cyan('Recent Validations:')} (${validations.length} shown / last 5)`);
        this.outputValidation(validations, false, true);
        this.log(`\n${chalk_1.default.cyan('Recent Diffs:')} (${diffs.length} shown / last 5)`);
        this.outputDiffs(diffs, false, true);
        this.log(`\n${chalk_1.default.cyan('Recent Rotations:')} (${rotations.length} shown / last 5)`);
        this.outputRotations(rotations, false, true);
    }
    outputValidation(records, json, brief = false) {
        if (json) {
            this.log(JSON.stringify(records, null, 2));
            return;
        }
        if (records.length === 0) {
            this.log('  (no records)');
            return;
        }
        for (const r of records) {
            const icon = r.valid ? chalk_1.default.green('✓') : chalk_1.default.red('✗');
            const line = `${icon} [${(0, formatters_1.formatTimestamp)(r.timestamp)}] ${r.environment.padEnd(16)} ${r.valid ? 'VALID' : `${r.errors.length} errors`}`;
            this.log(brief ? '  ' + line : line);
            if (!r.valid && !brief && r.errors.length > 0) {
                for (const e of r.errors.slice(0, 3)) {
                    this.log(`      ${chalk_1.default.red('×')} ${e.key}: ${e.message}`);
                }
                if (r.errors.length > 3)
                    this.log(`      ... and ${r.errors.length - 3} more`);
            }
        }
    }
    outputDiffs(records, json, brief = false) {
        if (json) {
            this.log(JSON.stringify(records, null, 2));
            return;
        }
        if (records.length === 0) {
            this.log('  (no records)');
            return;
        }
        for (const r of records) {
            const count = r.summary.total;
            const icon = count > 0 ? chalk_1.default.yellow('⚠') : chalk_1.default.green('✓');
            const line = `${icon} [${(0, formatters_1.formatTimestamp)(r.timestamp)}] ${r.environmentA}↔${r.environmentB} +${r.summary.added} -${r.summary.removed} ~${r.summary.changed}`;
            this.log(brief ? '  ' + line : line);
        }
    }
    outputRotations(records, json, brief = false) {
        if (json) {
            this.log(JSON.stringify(records, null, 2));
            return;
        }
        if (records.length === 0) {
            this.log('  (no records)');
            return;
        }
        for (const r of records) {
            const icon = r.status === 'success' ? chalk_1.default.green('✓') : chalk_1.default.red('✗');
            const line = `${icon} [${(0, formatters_1.formatTimestamp)(r.timestamp)}] ${r.environment.padEnd(16)} ${r.key.padEnd(24)} ${r.sourceType.padEnd(10)} ${r.operator}`;
            this.log(brief ? '  ' + line : line);
            if (r.status === 'failed' && r.message) {
                this.log(`      Error: ${r.message}`);
            }
        }
    }
    outputKeyValue(records, json, env, key) {
        if (json) {
            this.log(JSON.stringify(records, null, 2));
            return;
        }
        if (records.length === 0) {
            this.log(env && key ? `No history for ${key} in ${env}` : '  (no records)');
            return;
        }
        if (env && key) {
            this.log(`📜 ${chalk_1.default.yellow(key)} in ${chalk_1.default.magenta(env)}`);
            this.log('─'.repeat(80));
        }
        for (const r of records) {
            const color = r.changeType === 'added' ? chalk_1.default.green : r.changeType === 'removed' ? chalk_1.default.red : chalk_1.default.yellow;
            this.log(`[${(0, formatters_1.formatTimestamp)(r.timestamp)}] ${color(r.changeType.toUpperCase())} ${r.operator || 'unknown'}`);
            if (r.oldValue !== undefined)
                this.log(`  ${chalk_1.default.red('-')} ${(0, formatters_1.formatValue)(r.oldValue)}`);
            if (r.newValue !== undefined)
                this.log(`  ${chalk_1.default.green('+')} ${(0, formatters_1.formatValue)(r.newValue)}`);
            if (r.commitHash)
                this.log(`  commit: ${r.commitHash}`);
        }
    }
    parseRelativeTime(s) {
        if (s.match(/^\d+$/))
            return parseInt(s, 10);
        if (s.match(/^\d{4}-\d{2}-\d{2}/))
            return new Date(s).getTime();
        const match = s.match(/^(\d+)([smhdw])$/);
        if (match) {
            const n = parseInt(match[1], 10);
            const u = match[2];
            const now = Date.now();
            switch (u) {
                case 's': return now - n * 1000;
                case 'm': return now - n * 60 * 1000;
                case 'h': return now - n * 60 * 60 * 1000;
                case 'd': return now - n * 24 * 60 * 60 * 1000;
                case 'w': return now - n * 7 * 24 * 60 * 60 * 1000;
            }
        }
        return Date.now() - 24 * 60 * 60 * 1000;
    }
}
exports.default = HistoryCommand;
//# sourceMappingURL=history.js.map