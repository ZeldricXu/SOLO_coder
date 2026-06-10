"use strict";
var __importDefault = (this && this.__importDefault) || function (mod) {
    return (mod && mod.__esModule) ? mod : { "default": mod };
};
Object.defineProperty(exports, "__esModule", { value: true });
exports.DiffEngine = void 0;
const chalk_1 = __importDefault(require("chalk"));
class DiffEngine {
    compare(dataA, dataB, environmentA, environmentB) {
        const diffs = [];
        this.traverseAndCompare(dataA, dataB, '', diffs);
        const summary = {
            added: diffs.filter((d) => d.type === 'added').length,
            removed: diffs.filter((d) => d.type === 'removed').length,
            changed: diffs.filter((d) => d.type === 'changed').length,
            total: diffs.length,
        };
        return {
            environmentA,
            environmentB,
            diffs,
            summary,
            timestamp: Date.now(),
        };
    }
    traverseAndCompare(a, b, path, diffs) {
        if (a === undefined && b === undefined)
            return;
        if (a === undefined && b !== undefined) {
            diffs.push(this.createDiff('added', path, b, undefined));
            return;
        }
        if (a !== undefined && b === undefined) {
            diffs.push(this.createDiff('removed', path, undefined, a));
            return;
        }
        if (typeof a !== typeof b) {
            diffs.push(this.createDiff('changed', path, b, a));
            return;
        }
        if (Array.isArray(a) && Array.isArray(b)) {
            this.compareArrays(a, b, path, diffs);
            return;
        }
        if (a !== null &&
            typeof a === 'object' &&
            !Array.isArray(a) &&
            b !== null &&
            typeof b === 'object' &&
            !Array.isArray(b)) {
            this.compareObjects(a, b, path, diffs);
            return;
        }
        if (a !== b) {
            diffs.push(this.createDiff('changed', path, b, a));
        }
    }
    compareObjects(a, b, path, diffs) {
        const allKeys = new Set([...Object.keys(a), ...Object.keys(b)]);
        for (const key of allKeys) {
            const newPath = path ? `${path}.${key}` : key;
            this.traverseAndCompare(a[key], b[key], newPath, diffs);
        }
    }
    compareArrays(a, b, path, diffs) {
        const maxLength = Math.max(a.length, b.length);
        for (let i = 0; i < maxLength; i++) {
            const newPath = `${path}[${i}]`;
            this.traverseAndCompare(a[i], b[i], newPath, diffs);
        }
    }
    createDiff(type, path, after, before) {
        const key = path.split('.').pop() || path;
        const diff = {
            type,
            key,
            path,
        };
        if (type === 'added') {
            diff.after = after;
        }
        else if (type === 'removed') {
            diff.before = before;
        }
        else {
            diff.before = before;
            diff.after = after;
            diff.changePercent = this.calculateChangePercent(before, after);
        }
        return diff;
    }
    calculateChangePercent(before, after) {
        if (typeof before === 'number' && typeof after === 'number') {
            if (before === 0)
                return after === 0 ? 0 : 100;
            return Math.round(((after - before) / Math.abs(before)) * 10000) / 100;
        }
        if (typeof before === 'string' && typeof after === 'string') {
            const longer = Math.max(before.length, after.length);
            if (longer === 0)
                return 0;
            let differences = 0;
            for (let i = 0; i < longer; i++) {
                if (before[i] !== after[i])
                    differences++;
            }
            return Math.round((differences / longer) * 10000) / 100;
        }
        return undefined;
    }
    formatDiff(report, useColors = true) {
        const lines = [];
        lines.push(`Diff between ${report.environmentA} and ${report.environmentB}`);
        lines.push(`Summary: ${report.summary.added} added, ${report.summary.removed} removed, ${report.summary.changed} changed`);
        lines.push('');
        for (const diff of report.diffs) {
            const prefix = diff.type === 'added' ? '+' : diff.type === 'removed' ? '-' : '~';
            const color = diff.type === 'added' ? chalk_1.default.green : diff.type === 'removed' ? chalk_1.default.red : chalk_1.default.yellow;
            let line = `${prefix} ${diff.path}`;
            if (diff.type === 'added') {
                line += ` = ${this.formatValue(diff.after)}`;
            }
            else if (diff.type === 'removed') {
                line += ` = ${this.formatValue(diff.before)}`;
            }
            else {
                line += `: ${this.formatValue(diff.before)} -> ${this.formatValue(diff.after)}`;
                if (diff.changePercent !== undefined) {
                    line += ` (${diff.changePercent > 0 ? '+' : ''}${diff.changePercent}%)`;
                }
            }
            lines.push(useColors ? color(line) : line);
        }
        return lines.join('\n');
    }
    formatValue(value) {
        if (value === undefined)
            return 'undefined';
        if (value === null)
            return 'null';
        if (typeof value === 'string')
            return `"${value}"`;
        if (typeof value === 'object')
            return JSON.stringify(value);
        return String(value);
    }
    filterDiffs(diffs, options) {
        return diffs.filter((diff) => {
            if (options.type && diff.type !== options.type)
                return false;
            if (options.keyPattern && !new RegExp(options.keyPattern).test(diff.path))
                return false;
            return true;
        });
    }
    hasDrift(report, ignoreKeys) {
        if (!ignoreKeys || ignoreKeys.length === 0) {
            return report.diffs.length > 0;
        }
        const ignoreRegexes = ignoreKeys.map((k) => new RegExp(`^${k.replace(/\*/g, '.*')}$`));
        return report.diffs.some((diff) => {
            return !ignoreRegexes.some((regex) => regex.test(diff.path));
        });
    }
    generateDriftReport(report, ignoreKeys) {
        const ignoreRegexes = ignoreKeys?.map((k) => new RegExp(`^${k.replace(/\*/g, '.*')}$`)) || [];
        const criticalDiffs = [];
        const ignoredDiffs = [];
        for (const diff of report.diffs) {
            const isIgnored = ignoreRegexes.some((regex) => regex.test(diff.path));
            if (isIgnored) {
                ignoredDiffs.push(diff);
            }
            else {
                criticalDiffs.push(diff);
            }
        }
        return {
            drift: criticalDiffs.length > 0,
            criticalDiffs,
            ignoredDiffs,
        };
    }
}
exports.DiffEngine = DiffEngine;
//# sourceMappingURL=DiffEngine.js.map