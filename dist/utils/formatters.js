"use strict";
var __importDefault = (this && this.__importDefault) || function (mod) {
    return (mod && mod.__esModule) ? mod : { "default": mod };
};
Object.defineProperty(exports, "__esModule", { value: true });
exports.formatValidationReport = formatValidationReport;
exports.formatValidationErrors = formatValidationErrors;
exports.formatDiffReport = formatDiffReport;
exports.formatDiffItem = formatDiffItem;
exports.formatValue = formatValue;
exports.formatByteSize = formatByteSize;
exports.formatDuration = formatDuration;
exports.formatTimestamp = formatTimestamp;
exports.truncate = truncate;
exports.formatKeyValueTable = formatKeyValueTable;
exports.formatCascadeDiffReport = formatCascadeDiffReport;
const chalk_1 = __importDefault(require("chalk"));
const Table = require("cli-table3");
function formatValidationReport(report, useColors = true) {
    const lines = [];
    const status = report.valid ? chalk_1.default.green('VALID') : chalk_1.default.red('INVALID');
    lines.push(`Validation Report for environment: ${report.environment}`);
    lines.push(`Status: ${useColors ? status : (report.valid ? 'VALID' : 'INVALID')}`);
    lines.push(`Timestamp: ${new Date(report.timestamp).toISOString()}`);
    if (report.valid) {
        lines.push(chalk_1.default.green('All configuration values pass schema validation.'));
        return lines.join('\n');
    }
    lines.push('');
    lines.push(`Found ${report.errors.length} validation error(s):`);
    lines.push('');
    try {
        const table = new Table({
            head: useColors
                ? [chalk_1.default.cyan('Key'), chalk_1.default.cyan('Expected'), chalk_1.default.cyan('Actual'), chalk_1.default.cyan('Message')]
                : ['Key', 'Expected', 'Actual', 'Message'],
            colWidths: [30, 25, 25, 40],
            wordWrap: true,
        });
        for (const err of report.errors) {
            table.push([
                err.key,
                err.expected,
                err.actual,
                err.message,
            ]);
        }
        lines.push(table.toString());
    }
    catch {
        for (const err of report.errors) {
            lines.push(`  [${err.key}] Expected: ${err.expected}, Got: ${err.actual} - ${err.message}`);
        }
    }
    return lines.join('\n');
}
function formatValidationErrors(errors, useColors = true) {
    if (errors.length === 0)
        return 'No errors';
    const lines = [];
    for (const err of errors) {
        const env = useColors ? chalk_1.default.magenta(`[${err.environment}]`) : `[${err.environment}]`;
        const key = useColors ? chalk_1.default.yellow(err.key) : err.key;
        lines.push(`${env} ${key}: ${err.message}`);
        lines.push(`    Expected: ${err.expected}`);
        lines.push(`    Actual  : ${err.actual}`);
    }
    return lines.join('\n');
}
function formatDiffReport(report, useColors = true) {
    const lines = [];
    lines.push(`Diff: ${report.environmentA} → ${report.environmentB}`);
    lines.push(`Added: ${report.summary.added} | Removed: ${report.summary.removed} | Changed: ${report.summary.changed}`);
    lines.push('');
    if (report.diffs.length === 0) {
        lines.push(useColors ? chalk_1.default.green('No differences found.') : 'No differences found.');
        return lines.join('\n');
    }
    for (const diff of report.diffs) {
        lines.push(formatDiffItem(diff, useColors));
    }
    return lines.join('\n');
}
function formatDiffItem(diff, useColors = true) {
    let line = '';
    const prefix = diff.type === 'added' ? '+' : diff.type === 'removed' ? '-' : '~';
    const colorFn = diff.type === 'added' ? chalk_1.default.green : diff.type === 'removed' ? chalk_1.default.red : chalk_1.default.yellow;
    if (diff.type === 'added') {
        line = `${prefix} ${diff.path} = ${formatValue(diff.after)}`;
    }
    else if (diff.type === 'removed') {
        line = `${prefix} ${diff.path} = ${formatValue(diff.before)}`;
    }
    else {
        const before = formatValue(diff.before);
        const after = formatValue(diff.after);
        const pct = diff.changePercent !== undefined
            ? ` (${diff.changePercent > 0 ? '+' : ''}${diff.changePercent}%)`
            : '';
        line = `${prefix} ${diff.path}: ${before} → ${after}${pct}`;
    }
    return useColors ? colorFn(line) : line;
}
function formatValue(v) {
    if (v === undefined)
        return 'undefined';
    if (v === null)
        return 'null';
    if (typeof v === 'string')
        return `"${v}"`;
    if (typeof v === 'object')
        return JSON.stringify(v);
    return String(v);
}
function formatByteSize(bytes) {
    if (bytes === 0)
        return '0 B';
    const k = 1024;
    const sizes = ['B', 'KB', 'MB', 'GB', 'TB'];
    const i = Math.floor(Math.log(bytes) / Math.log(k));
    return (bytes / Math.pow(k, i)).toFixed(2) + ' ' + sizes[i];
}
function formatDuration(ms) {
    if (ms < 1000)
        return `${ms}ms`;
    if (ms < 60000)
        return `${(ms / 1000).toFixed(1)}s`;
    if (ms < 3600000)
        return `${Math.floor(ms / 60000)}m ${Math.floor((ms % 60000) / 1000)}s`;
    return `${Math.floor(ms / 3600000)}h ${Math.floor((ms % 3600000) / 60000)}m`;
}
function formatTimestamp(ts) {
    const date = new Date(ts);
    const pad = (n) => String(n).padStart(2, '0');
    return `${date.getFullYear()}-${pad(date.getMonth() + 1)}-${pad(date.getDate())} ${pad(date.getHours())}:${pad(date.getMinutes())}:${pad(date.getSeconds())}`;
}
function truncate(str, maxLen) {
    if (str.length <= maxLen)
        return str;
    return str.slice(0, maxLen - 3) + '...';
}
function formatKeyValueTable(rows, useColors = true) {
    try {
        const table = new Table({
            head: useColors ? [chalk_1.default.cyan('Key'), chalk_1.default.cyan('Value')] : ['Key', 'Value'],
            colWidths: [35, 65],
            wordWrap: true,
        });
        for (const row of rows) {
            table.push([row.key, row.value]);
        }
        return table.toString();
    }
    catch {
        return rows.map((r) => `${r.key}: ${r.value}`).join('\n');
    }
}
function formatCascadeDiffReport(report, useColors = true) {
    const { DiffEngine } = require('../engine/DiffEngine');
    const engine = new DiffEngine();
    return engine.formatCascadeDiff(report, useColors);
}
//# sourceMappingURL=formatters.js.map