"use strict";
Object.defineProperty(exports, "__esModule", { value: true });
exports.SyncPipeline = void 0;
const DiffEngine_1 = require("../engine/DiffEngine");
class SyncPipeline {
    configManager;
    diffEngine;
    constructor(configManager) {
        this.configManager = configManager;
        this.diffEngine = new DiffEngine_1.DiffEngine();
    }
    async previewSync(item) {
        const previews = [];
        const sourceEnv = this.configManager.getEnvironment(item.sourceEnvironment);
        if (!sourceEnv) {
            throw new Error(`Source environment not found: ${item.sourceEnvironment}`);
        }
        const sourceValue = await sourceEnv.get(item.key);
        for (const targetEnvName of item.targetEnvironments) {
            const targetEnv = this.configManager.getEnvironment(targetEnvName);
            if (!targetEnv) {
                continue;
            }
            const currentValue = await targetEnv.get(item.key);
            let action;
            if (currentValue === undefined && sourceValue !== undefined) {
                action = 'create';
            }
            else if (currentValue !== sourceValue) {
                action = 'update';
            }
            else {
                action = 'skip';
            }
            previews.push({
                key: item.key,
                sourceEnvironment: item.sourceEnvironment,
                targetEnvironment: targetEnvName,
                currentValue: currentValue,
                newValue: sourceValue,
                action,
            });
        }
        return previews;
    }
    async previewBatch(items) {
        const allPreviews = [];
        for (const item of items) {
            const previews = await this.previewSync(item);
            allPreviews.push(...previews);
        }
        return allPreviews;
    }
    async executeSync(item, options = {}) {
        const results = [];
        const sourceEnv = this.configManager.getEnvironment(item.sourceEnvironment);
        if (!sourceEnv) {
            throw new Error(`Source environment not found: ${item.sourceEnvironment}`);
        }
        const sourceValue = await sourceEnv.get(item.key);
        if (sourceValue === undefined) {
            throw new Error(`Key not found in source environment: ${item.key}`);
        }
        if (options.validateBefore && options.validator) {
            const validationError = options.validator.validateValue(item.key, sourceValue, item.sourceEnvironment);
            if (validationError) {
                throw new Error(`Source value fails validation: ${validationError.message}`);
            }
        }
        const previews = await this.previewSync(item);
        const nonSkipPreviews = previews.filter((p) => p.action !== 'skip');
        let completed = 0;
        for (const preview of nonSkipPreviews) {
            const targetEnv = this.configManager.getEnvironment(preview.targetEnvironment);
            if (!targetEnv)
                continue;
            try {
                if (!options.dryRun) {
                    await targetEnv.set(item.key, sourceValue);
                }
                let verified = false;
                if (options.verifyAfter && !options.dryRun) {
                    const targetValue = await targetEnv.get(item.key);
                    verified = targetValue === sourceValue;
                    if (options.validator && verified) {
                        const error = options.validator.validateValue(item.key, targetValue, preview.targetEnvironment);
                        if (error)
                            verified = false;
                    }
                }
                results.push({
                    key: item.key,
                    targetEnvironment: preview.targetEnvironment,
                    status: 'success',
                    verified,
                });
            }
            catch (error) {
                results.push({
                    key: item.key,
                    targetEnvironment: preview.targetEnvironment,
                    status: 'failed',
                    message: error.message,
                    verified: false,
                });
            }
            completed++;
            if (options.onProgress) {
                options.onProgress(item.key, preview.targetEnvironment, completed, nonSkipPreviews.length);
            }
        }
        return results;
    }
    async executeBatch(items, options = {}) {
        const allPreviews = await this.previewBatch(items);
        const skipped = allPreviews.filter((p) => p.action === 'skip');
        const nonSkipped = allPreviews.filter((p) => p.action !== 'skip');
        if (options.onPreview) {
            options.onPreview(allPreviews);
        }
        if (options.dryRun) {
            return {
                previews: allPreviews,
                results: nonSkipped.map((p) => ({
                    key: p.key,
                    targetEnvironment: p.targetEnvironment,
                    status: 'success',
                    verified: false,
                    message: 'dry-run only, no changes applied',
                })),
                skipped,
                summary: {
                    total: nonSkipped.length,
                    success: nonSkipped.length,
                    failed: 0,
                    skipped: skipped.length,
                    verified: 0,
                },
            };
        }
        const allResults = [];
        for (const item of items) {
            try {
                const itemResults = await this.executeSync(item, options);
                allResults.push(...itemResults);
            }
            catch (error) {
                for (const targetEnv of item.targetEnvironments) {
                    allResults.push({
                        key: item.key,
                        targetEnvironment: targetEnv,
                        status: 'failed',
                        message: error.message,
                        verified: false,
                    });
                }
            }
        }
        const successCount = allResults.filter((r) => r.status === 'success').length;
        const failedCount = allResults.filter((r) => r.status === 'failed').length;
        const verifiedCount = allResults.filter((r) => r.verified).length;
        return {
            previews: allPreviews,
            results: allResults,
            skipped,
            summary: {
                total: nonSkipped.length,
                success: successCount,
                failed: failedCount,
                skipped: skipped.length,
                verified: verifiedCount,
            },
        };
    }
    generateDiffFromPreviews(previews, sourceEnvName, targetEnvName) {
        const targetPreviews = previews.filter((p) => p.sourceEnvironment === sourceEnvName && p.targetEnvironment === targetEnvName);
        const diffs = [];
        for (const preview of targetPreviews) {
            if (preview.action === 'create') {
                diffs.push({
                    type: 'added',
                    key: preview.key,
                    path: preview.key,
                    after: preview.newValue,
                });
            }
            else if (preview.action === 'update') {
                const before = preview.currentValue;
                const after = preview.newValue;
                diffs.push({
                    type: 'changed',
                    key: preview.key,
                    path: preview.key,
                    before,
                    after,
                    changePercent: this.calculatePercent(before, after),
                });
            }
        }
        return diffs;
    }
    calculatePercent(before, after) {
        if (typeof before === 'number' && typeof after === 'number') {
            if (before === 0)
                return after === 0 ? 0 : 100;
            return Math.round(((after - before) / Math.abs(before)) * 10000) / 100;
        }
        return undefined;
    }
    formatPreviews(previews) {
        const lines = [];
        lines.push('Sync Preview:');
        lines.push('='.repeat(80));
        const byTarget = new Map();
        for (const p of previews) {
            if (!byTarget.has(p.targetEnvironment)) {
                byTarget.set(p.targetEnvironment, []);
            }
            byTarget.get(p.targetEnvironment).push(p);
        }
        for (const [env, envPreviews] of byTarget) {
            lines.push(`\n  Target Environment: ${env}`);
            lines.push('  ' + '-'.repeat(78));
            for (const p of envPreviews) {
                const actionSymbol = p.action === 'create' ? '+' : p.action === 'update' ? '~' : '=';
                const actionText = p.action.toUpperCase();
                if (p.action === 'skip') {
                    lines.push(`  [${actionText}] ${actionSymbol} ${p.key} (no change)`);
                }
                else if (p.action === 'create') {
                    lines.push(`  [${actionText}] ${actionSymbol} ${p.key} = ${this.formatVal(p.newValue)}`);
                }
                else {
                    lines.push(`  [${actionText}] ${actionSymbol} ${p.key}`);
                    lines.push(`        BEFORE: ${this.formatVal(p.currentValue)}`);
                    lines.push(`        AFTER : ${this.formatVal(p.newValue)}`);
                }
            }
        }
        lines.push('\n' + '='.repeat(80));
        const createCount = previews.filter((p) => p.action === 'create').length;
        const updateCount = previews.filter((p) => p.action === 'update').length;
        const skipCount = previews.filter((p) => p.action === 'skip').length;
        lines.push(`Summary: ${createCount} create, ${updateCount} update, ${skipCount} skip`);
        return lines.join('\n');
    }
    formatVal(v) {
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
}
exports.SyncPipeline = SyncPipeline;
//# sourceMappingURL=SyncPipeline.js.map