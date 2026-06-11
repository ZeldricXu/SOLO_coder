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
const DiffEngine_1 = require("../engine/DiffEngine");
const formatters_1 = require("../utils/formatters");
const HistoryStorage_1 = require("../storage/HistoryStorage");
const chalk_1 = __importDefault(require("chalk"));
const fs = __importStar(require("fs"));
class DiffCommand extends core_1.Command {
    static description = 'Show configuration differences between two environments';
    static aliases = ['compare', 'drift'];
    static args = {
        envA: core_1.Args.string({ description: 'First environment name (or comma-separated chain with --cascade)', required: true }),
        envB: core_1.Args.string({ description: 'Second environment name', required: false }),
    };
    static flags = {
        config: core_1.Flags.string({ char: 'c', description: 'Path to config file' }),
        type: core_1.Flags.string({ char: 't', description: 'Filter by type: added|removed|changed', options: ['added', 'removed', 'changed'] }),
        key: core_1.Flags.string({ char: 'k', description: 'Filter keys by regex pattern' }),
        ignore: core_1.Flags.string({ char: 'i', description: 'Ignore keys by pattern (comma-separated, supports *)', multiple: true }),
        json: core_1.Flags.boolean({ description: 'Output as JSON' }),
        noColor: core_1.Flags.boolean({ description: 'Disable colored output' }),
        noHistory: core_1.Flags.boolean({ description: 'Do not record diff history' }),
        output: core_1.Flags.string({ char: 'o', description: 'Write diff to file' }),
        failOnDrift: core_1.Flags.boolean({ description: 'Exit with error code if drift detected' }),
        cascade: core_1.Flags.boolean({ description: 'Cascade mode: compare environments in chain' }),
    };
    async run() {
        const { args, flags } = await this.parse(DiffCommand);
        const ctx = await (0, list_1.loadContext)(flags.config);
        if (flags.cascade) {
            await this.runCascade(args.envA, ctx, flags);
        }
        else {
            if (!args.envB)
                this.error('Second environment is required in non-cascade mode. Provide envB or use --cascade.');
            await this.runStandard(args.envA, args.envB, ctx, flags);
        }
    }
    async runCascade(envChain, ctx, flags) {
        const envNames = envChain.split(',').map((s) => s.trim()).filter(Boolean);
        if (envNames.length < 2)
            this.error('Cascade mode requires at least 2 environments (comma-separated), e.g., dev,staging,prod');
        const envsData = new Map();
        for (const name of envNames) {
            const env = ctx.configManager.getEnvironment(name);
            if (!env)
                this.error(`Environment not found: ${name}`);
            const data = await env.loadAll();
            envsData.set(name, data);
        }
        const diffEngine = new DiffEngine_1.DiffEngine();
        const report = diffEngine.cascadeCompare(envsData);
        let output;
        if (flags.json) {
            output = JSON.stringify(report, null, 2);
        }
        else {
            output = (0, formatters_1.formatCascadeDiffReport)(report, !flags.noColor);
        }
        this.log(output);
        if (flags.output) {
            fs.writeFileSync(flags.output, output);
            if (!flags.json) {
                this.log(`\n${chalk_1.default.blue('ℹ')} Cascade diff written to: ${flags.output}`);
            }
        }
        if (!flags.json) {
            if (report.summary.driftRisk > 0 || report.summary.changed > 0) {
                this.log(`\n${chalk_1.default.yellow('⚠')} Cascade drift: ${report.summary.driftRisk} drift-risk, ${report.summary.changed} changed`);
            }
            else {
                this.log(`\n${chalk_1.default.green('✓')} All keys consistent across cascade chain`);
            }
        }
        if (flags.failOnDrift && (report.summary.driftRisk > 0 || report.summary.changed > 0)) {
            this.exit(1);
        }
    }
    async runStandard(envAName, envBName, ctx, flags) {
        const envA = ctx.configManager.getEnvironment(envAName);
        const envB = ctx.configManager.getEnvironment(envBName);
        if (!envA)
            this.error(`Environment not found: ${envAName}`);
        if (!envB)
            this.error(`Environment not found: ${envBName}`);
        const dataA = await envA.loadAll();
        const dataB = await envB.loadAll();
        const diffEngine = new DiffEngine_1.DiffEngine();
        const report = diffEngine.compare(dataA, dataB, envAName, envBName);
        if (flags.type) {
            report.diffs = diffEngine.filterDiffs(report.diffs, { type: flags.type });
        }
        if (flags.key) {
            report.diffs = diffEngine.filterDiffs(report.diffs, { keyPattern: flags.key });
        }
        if (flags.ignore && flags.ignore.length > 0) {
            const ignoreList = flags.ignore.join(',').split(',').map((s) => s.trim()).filter(Boolean);
            const driftInfo = diffEngine.generateDriftReport(report, ignoreList);
            report.diffs = driftInfo.criticalDiffs;
            report.summary = {
                added: driftInfo.criticalDiffs.filter((d) => d.type === 'added').length,
                removed: driftInfo.criticalDiffs.filter((d) => d.type === 'removed').length,
                changed: driftInfo.criticalDiffs.filter((d) => d.type === 'changed').length,
                total: driftInfo.criticalDiffs.length,
            };
        }
        const storage = flags.noHistory ? null : new HistoryStorage_1.HistoryStorage(ctx.config.storagePath);
        if (storage) {
            await storage.recordDiff(report);
            storage.close();
        }
        let output;
        if (flags.json) {
            output = JSON.stringify(report, null, 2);
        }
        else {
            output = (0, formatters_1.formatDiffReport)(report, !flags.noColor);
        }
        this.log(output);
        if (flags.output) {
            fs.writeFileSync(flags.output, output);
            if (!flags.json) {
                this.log(`\n${chalk_1.default.blue('ℹ')} Diff written to: ${flags.output}`);
            }
        }
        if (!flags.json) {
            const hasDrift = report.diffs.length > 0;
            if (hasDrift) {
                this.log(`\n${chalk_1.default.yellow('⚠')} Drift detected: ${report.diffs.length} difference(s)`);
            }
            else {
                this.log(`\n${chalk_1.default.green('✓')} No drift detected between ${envAName} and ${envBName}`);
            }
            if (flags.ignore && flags.ignore.length > 0) {
                this.log(`${chalk_1.default.blue('ℹ')} Ignored patterns: ${flags.ignore.join(', ')}`);
            }
        }
        if (flags.failOnDrift && report.diffs.length > 0) {
            this.exit(1);
        }
    }
}
exports.default = DiffCommand;
//# sourceMappingURL=diff.js.map