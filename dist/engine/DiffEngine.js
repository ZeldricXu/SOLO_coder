"use strict";
var __importDefault = (this && this.__importDefault) || function (mod) {
    return (mod && mod.__esModule) ? mod : { "default": mod };
};
Object.defineProperty(exports, "__esModule", { value: true });
exports.DiffEngine = void 0;
const chalk_1 = __importDefault(require("chalk"));
const Table = require("cli-table3");
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
    cascadeCompare(environmentsData) {
        const envNames = Array.from(environmentsData.keys());
        const allData = Array.from(environmentsData.values());
        const allKeys = new Set();
        for (const data of allData) {
            this.collectFlatKeys(data, '', allKeys);
        }
        const pairs = [];
        for (let i = 0; i < envNames.length - 1; i++) {
            pairs.push([envNames[i], envNames[i + 1]]);
        }
        const rows = [];
        let consistent = 0;
        let driftRisk = 0;
        let changed = 0;
        for (const key of Array.from(allKeys).sort()) {
            const transitions = [];
            const changeTypes = [];
            for (const [fromEnv, toEnv] of pairs) {
                const dataA = environmentsData.get(fromEnv);
                const dataB = environmentsData.get(toEnv);
                const valA = this.getNestedValue(dataA, key);
                const valB = this.getNestedValue(dataB, key);
                if (valA === undefined && valB === undefined) {
                    changeTypes.push('unchanged');
                    transitions.push({ fromEnv, toEnv, type: 'unchanged' });
                }
                else if (valA === undefined && valB !== undefined) {
                    changeTypes.push('added');
                    transitions.push({ fromEnv, toEnv, type: 'added', after: valB });
                }
                else if (valA !== undefined && valB === undefined) {
                    changeTypes.push('removed');
                    transitions.push({ fromEnv, toEnv, type: 'removed', before: valA });
                }
                else if (this.valuesEqual(valA, valB)) {
                    changeTypes.push('unchanged');
                    transitions.push({ fromEnv, toEnv, type: 'unchanged', before: valA, after: valB });
                }
                else {
                    changeTypes.push('changed');
                    transitions.push({
                        fromEnv, toEnv, type: 'changed',
                        before: valA, after: valB,
                        changePercent: this.calculateChangePercent(valA, valB),
                    });
                }
            }
            const status = this.determineCascadeStatus(changeTypes);
            if (status === 'consistent')
                consistent++;
            else if (status === 'drift-risk')
                driftRisk++;
            else
                changed++;
            rows.push({ key, transitions, status });
        }
        return {
            environmentChain: envNames,
            rows,
            summary: {
                totalKeys: allKeys.size,
                consistent,
                driftRisk,
                changed,
            },
            timestamp: Date.now(),
        };
    }
    formatCascadeDiff(report, useColors = true) {
        const lines = [];
        const chain = report.environmentChain.join(' → ');
        lines.push(`Cascade Diff: ${chain}`);
        lines.push(`Consistent: ${report.summary.consistent} | Drift-Risk: ${report.summary.driftRisk} | Changed: ${report.summary.changed}`);
        lines.push('');
        if (report.rows.length === 0) {
            lines.push(useColors ? chalk_1.default.green('No keys found across environments.') : 'No keys found across environments.');
            return lines.join('\n');
        }
        const transitionHeaders = report.environmentChain.slice(0, -1).map((_, i) => {
            const from = report.environmentChain[i];
            const to = report.environmentChain[i + 1];
            return `${from}→${to}`;
        });
        const head = useColors
            ? [chalk_1.default.cyan('Key'), ...transitionHeaders.map((h) => chalk_1.default.cyan(h)), chalk_1.default.cyan('Status')]
            : ['Key', ...transitionHeaders, 'Status'];
        try {
            const colWidths = [30, ...transitionHeaders.map(() => 35), 15];
            const table = new Table({
                head,
                colWidths,
                wordWrap: true,
            });
            for (const row of report.rows) {
                const transitionCells = row.transitions.map((t) => {
                    let cell = '';
                    if (t.type === 'unchanged') {
                        cell = '—';
                    }
                    else if (t.type === 'added') {
                        cell = `+ ${this.formatValue(t.after)}`;
                    }
                    else if (t.type === 'removed') {
                        cell = `- ${this.formatValue(t.before)}`;
                    }
                    else {
                        cell = `~ ${this.formatValue(t.before)} → ${this.formatValue(t.after)}`;
                        if (t.changePercent !== undefined) {
                            cell += ` (${t.changePercent > 0 ? '+' : ''}${t.changePercent}%)`;
                        }
                    }
                    return cell;
                });
                const statusLabel = row.status.toUpperCase();
                let statusCell;
                if (useColors) {
                    const colorFn = row.status === 'consistent' ? chalk_1.default.green : row.status === 'drift-risk' ? chalk_1.default.red : chalk_1.default.yellow;
                    statusCell = colorFn(statusLabel);
                }
                else {
                    statusCell = statusLabel;
                }
                table.push([row.key, ...transitionCells, statusCell]);
            }
            lines.push(table.toString());
        }
        catch {
            for (const row of report.rows) {
                const transitionsStr = row.transitions.map((t) => {
                    if (t.type === 'unchanged')
                        return '—';
                    if (t.type === 'added')
                        return `+ ${this.formatValue(t.after)}`;
                    if (t.type === 'removed')
                        return `- ${this.formatValue(t.before)}`;
                    return `~ ${this.formatValue(t.before)} → ${this.formatValue(t.after)}`;
                }).join(' | ');
                lines.push(`${row.key} | ${transitionsStr} | ${row.status.toUpperCase()}`);
            }
        }
        return lines.join('\n');
    }
    collectFlatKeys(data, prefix, keys) {
        for (const [k, v] of Object.entries(data)) {
            const fullKey = prefix ? `${prefix}.${k}` : k;
            if (v !== null && typeof v === 'object' && !Array.isArray(v)) {
                this.collectFlatKeys(v, fullKey, keys);
            }
            else {
                keys.add(fullKey);
            }
        }
    }
    getNestedValue(data, dottedKey) {
        const parts = dottedKey.split('.');
        let current = data;
        for (const part of parts) {
            if (current === null || current === undefined || typeof current !== 'object' || Array.isArray(current)) {
                return undefined;
            }
            current = current[part];
        }
        return current;
    }
    valuesEqual(a, b) {
        if (a === b)
            return true;
        if (a === null || b === null)
            return false;
        if (typeof a !== typeof b)
            return false;
        if (Array.isArray(a) && Array.isArray(b)) {
            if (a.length !== b.length)
                return false;
            return a.every((v, i) => this.valuesEqual(v, b[i]));
        }
        if (typeof a === 'object' && typeof b === 'object') {
            const aObj = a;
            const bObj = b;
            const aKeys = Object.keys(aObj);
            const bKeys = Object.keys(bObj);
            if (aKeys.length !== bKeys.length)
                return false;
            return aKeys.every((k) => this.valuesEqual(aObj[k], bObj[k]));
        }
        return false;
    }
    determineCascadeStatus(changeTypes) {
        const hasChange = changeTypes.some((t) => t !== 'unchanged');
        if (!hasChange)
            return 'consistent';
        let everChanged = false;
        let revertedAfterChange = false;
        for (let i = 0; i < changeTypes.length; i++) {
            if (changeTypes[i] !== 'unchanged') {
                everChanged = true;
                let laterUnchanged = false;
                for (let j = i + 1; j < changeTypes.length; j++) {
                    if (changeTypes[j] === 'unchanged') {
                        laterUnchanged = true;
                        break;
                    }
                }
                if (laterUnchanged) {
                    revertedAfterChange = true;
                }
            }
        }
        if (revertedAfterChange)
            return 'drift-risk';
        if (everChanged)
            return 'changed';
        return 'consistent';
    }
}
exports.DiffEngine = DiffEngine;
//# sourceMappingURL=DiffEngine.js.map