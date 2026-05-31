"use strict";
Object.defineProperty(exports, "__esModule", { value: true });
exports.DifferentialPrivacyInjection = void 0;
const zod_1 = require("zod");
const utils_1 = require("../../utils");
const PrivacyBudgetSchema = zod_1.z.object({
    totalEpsilon: zod_1.z.number().positive(),
    usedEpsilon: zod_1.z.number().min(0),
    totalDelta: zod_1.z.number().min(0).max(1),
    usedDelta: zod_1.z.number().min(0),
    resetInterval: zod_1.z.enum(['daily', 'weekly', 'monthly']),
    lastReset: zod_1.z.string().datetime(),
});
const NoiseConfigSchema = zod_1.z.object({
    mechanism: zod_1.z.enum(['laplace', 'gaussian', 'geometric']),
    epsilon: zod_1.z.number().positive(),
    delta: zod_1.z.number().min(0).max(1).optional(),
    sensitivity: zod_1.z.number().positive(),
    lowerBound: zod_1.z.number().optional(),
    upperBound: zod_1.z.number().optional(),
});
class DifferentialPrivacyInjection {
    budgets = new Map();
    consumptionHistory = new Map();
    defaultBudget;
    constructor(defaultBudget) {
        this.defaultBudget = {
            totalEpsilon: defaultBudget?.totalEpsilon || 1.0,
            usedEpsilon: defaultBudget?.usedEpsilon || 0,
            totalDelta: defaultBudget?.totalDelta || 1e-5,
            usedDelta: defaultBudget?.usedDelta || 0,
            resetInterval: defaultBudget?.resetInterval || 'daily',
            lastReset: defaultBudget?.lastReset || (0, utils_1.getCurrentTimestamp)(),
        };
    }
    createBudget(userId, config) {
        try {
            const traceId = (0, utils_1.generateId)('trace');
            if (this.budgets.has(userId)) {
                return (0, utils_1.createErrorResult)('Budget already exists for user', 'BUDGET_EXISTS', traceId);
            }
            const budget = {
                totalEpsilon: config.totalEpsilon || this.defaultBudget.totalEpsilon,
                usedEpsilon: config.usedEpsilon || 0,
                totalDelta: config.totalDelta || this.defaultBudget.totalDelta,
                usedDelta: config.usedDelta || 0,
                resetInterval: config.resetInterval || this.defaultBudget.resetInterval,
                lastReset: config.lastReset || (0, utils_1.getCurrentTimestamp)(),
            };
            const parsed = PrivacyBudgetSchema.parse(budget);
            this.budgets.set(userId, parsed);
            this.consumptionHistory.set(userId, []);
            return (0, utils_1.createSuccessResult)(parsed, 'BUDGET_CREATED', traceId);
        }
        catch (error) {
            return (0, utils_1.createErrorResult)(error instanceof Error ? error.message : 'Failed to create budget', 'BUDGET_CREATE_FAILED');
        }
    }
    getBudget(userId) {
        const budget = this.budgets.get(userId) || null;
        return (0, utils_1.createSuccessResult)(budget, budget ? 'BUDGET_RETRIEVED' : 'BUDGET_NOT_FOUND');
    }
    updateBudget(userId, updates) {
        try {
            const traceId = (0, utils_1.generateId)('trace');
            const budget = this.budgets.get(userId);
            if (!budget) {
                return (0, utils_1.createErrorResult)('Budget not found', 'BUDGET_NOT_FOUND', traceId);
            }
            const updated = {
                ...budget,
                ...updates,
            };
            const parsed = PrivacyBudgetSchema.parse(updated);
            this.budgets.set(userId, parsed);
            return (0, utils_1.createSuccessResult)(parsed, 'BUDGET_UPDATED', traceId);
        }
        catch (error) {
            return (0, utils_1.createErrorResult)(error instanceof Error ? error.message : 'Failed to update budget', 'BUDGET_UPDATE_FAILED');
        }
    }
    resetBudget(userId) {
        const budget = this.budgets.get(userId);
        if (!budget) {
            return (0, utils_1.createErrorResult)('Budget not found', 'BUDGET_NOT_FOUND');
        }
        budget.usedEpsilon = 0;
        budget.usedDelta = 0;
        budget.lastReset = (0, utils_1.getCurrentTimestamp)();
        return (0, utils_1.createSuccessResult)(budget, 'BUDGET_RESET');
    }
    checkBudget(userId, epsilon, delta = 0) {
        const budget = this.budgets.get(userId);
        if (!budget) {
            return (0, utils_1.createErrorResult)('Budget not found', 'BUDGET_NOT_FOUND');
        }
        this.checkAutoReset(userId, budget);
        const remainingEpsilon = budget.totalEpsilon - budget.usedEpsilon;
        const remainingDelta = budget.totalDelta - budget.usedDelta;
        const available = remainingEpsilon >= epsilon && remainingDelta >= delta;
        return (0, utils_1.createSuccessResult)({
            available,
            remainingEpsilon,
            remainingDelta,
            requiredEpsilon: epsilon,
            requiredDelta: delta,
        }, 'BUDGET_CHECKED');
    }
    addNoise(userId, value, config, queryType = 'query') {
        try {
            const traceId = (0, utils_1.generateId)('trace');
            const parsedConfig = NoiseConfigSchema.parse(config);
            this.checkAutoReset(userId);
            const budgetCheck = this.checkBudget(userId, parsedConfig.epsilon, parsedConfig.delta || 0);
            if (!budgetCheck.success) {
                return (0, utils_1.createErrorResult)(budgetCheck.error, budgetCheck.code, traceId);
            }
            if (!budgetCheck.data.available) {
                return (0, utils_1.createErrorResult)('Insufficient privacy budget', 'INSUFFICIENT_BUDGET', traceId);
            }
            const noise = this.generateNoise(parsedConfig);
            let noisyValue = value + noise;
            if (parsedConfig.lowerBound !== undefined) {
                noisyValue = Math.max(parsedConfig.lowerBound, noisyValue);
            }
            if (parsedConfig.upperBound !== undefined) {
                noisyValue = Math.min(parsedConfig.upperBound, noisyValue);
            }
            this.consumeBudget(userId, parsedConfig.epsilon, parsedConfig.delta || 0, queryType);
            const budget = this.budgets.get(userId);
            return (0, utils_1.createSuccessResult)({
                originalValue: value,
                noisyValue,
                noiseAdded: noise,
                epsilonUsed: parsedConfig.epsilon,
                deltaUsed: parsedConfig.delta || 0,
                privacyBudgetRemaining: {
                    epsilon: budget.totalEpsilon - budget.usedEpsilon,
                    delta: budget.totalDelta - budget.usedDelta,
                },
            }, 'NOISE_ADDED', traceId);
        }
        catch (error) {
            return (0, utils_1.createErrorResult)(error instanceof Error ? error.message : 'Failed to add noise', 'NOISE_ADD_FAILED');
        }
    }
    addNoiseToDataset(userId, values, config, perQueryEpsilon) {
        try {
            const traceId = (0, utils_1.generateId)('trace');
            const parsedConfig = NoiseConfigSchema.parse(config);
            this.checkAutoReset(userId);
            const epsilonPerValue = perQueryEpsilon
                ? parsedConfig.epsilon
                : parsedConfig.epsilon / values.length;
            const deltaPerValue = perQueryEpsilon
                ? parsedConfig.delta || 0
                : (parsedConfig.delta || 0) / values.length;
            const totalEpsilon = epsilonPerValue * values.length;
            const totalDelta = deltaPerValue * values.length;
            const budgetCheck = this.checkBudget(userId, totalEpsilon, totalDelta);
            if (!budgetCheck.success || !budgetCheck.data.available) {
                return (0, utils_1.createErrorResult)('Insufficient privacy budget', 'INSUFFICIENT_BUDGET', traceId);
            }
            const noisyValues = [];
            const valueConfig = {
                ...parsedConfig,
                epsilon: epsilonPerValue,
                delta: deltaPerValue,
            };
            for (const value of values) {
                const noise = this.generateNoise(valueConfig);
                let noisyValue = value + noise;
                if (parsedConfig.lowerBound !== undefined) {
                    noisyValue = Math.max(parsedConfig.lowerBound, noisyValue);
                }
                if (parsedConfig.upperBound !== undefined) {
                    noisyValue = Math.min(parsedConfig.upperBound, noisyValue);
                }
                noisyValues.push(noisyValue);
            }
            this.consumeBudget(userId, totalEpsilon, totalDelta, 'batch_query');
            return (0, utils_1.createSuccessResult)({
                originalValues: values,
                noisyValues,
                totalEpsilonUsed: totalEpsilon,
                totalDeltaUsed: totalDelta,
                perValueEpsilon: epsilonPerValue,
            }, 'BATCH_NOISE_ADDED', traceId);
        }
        catch (error) {
            return (0, utils_1.createErrorResult)(error instanceof Error ? error.message : 'Failed to add noise to dataset', 'BATCH_NOISE_FAILED');
        }
    }
    calculateSensitivity(values) {
        if (values.length < 2) {
            return (0, utils_1.createErrorResult)('Need at least 2 values to calculate sensitivity', 'INSUFFICIENT_DATA');
        }
        const sorted = [...values].sort((a, b) => a - b);
        const max = sorted[sorted.length - 1];
        const min = sorted[0];
        const sensitivity = max - min;
        return (0, utils_1.createSuccessResult)(sensitivity, 'SENSITIVITY_CALCULATED');
    }
    recommendEpsilon(dataSize, privacyLevel) {
        let epsilon;
        let delta;
        let explanation;
        switch (privacyLevel) {
            case 'low':
                epsilon = Math.min(10, 1 + Math.log(dataSize) / 10);
                delta = 1e-4;
                explanation = '低隐私保护级别，适合公开数据';
                break;
            case 'medium':
                epsilon = Math.min(1, 0.5 + Math.log(dataSize) / 20);
                delta = 1e-5;
                explanation = '中等隐私保护级别，适合一般敏感数据';
                break;
            case 'high':
                epsilon = Math.min(0.1, 0.1 + Math.log(dataSize) / 100);
                delta = 1e-6;
                explanation = '高隐私保护级别，适合高度敏感数据';
                break;
            case 'very_high':
                epsilon = Math.min(0.01, 0.01 + Math.log(dataSize) / 1000);
                delta = 1e-7;
                explanation = '极高隐私保护级别，适合最高机密数据';
                break;
            default:
                epsilon = 1;
                delta = 1e-5;
                explanation = '默认隐私保护级别';
        }
        return (0, utils_1.createSuccessResult)({ epsilon, delta, explanation }, 'EPSILON_RECOMMENDED');
    }
    getConsumptionHistory(userId, limit) {
        const history = this.consumptionHistory.get(userId) || [];
        const sorted = [...history].sort((a, b) => new Date(b.timestamp).getTime() - new Date(a.timestamp).getTime());
        const result = limit ? sorted.slice(0, limit) : sorted;
        return (0, utils_1.createSuccessResult)(result, 'HISTORY_RETRIEVED');
    }
    getBudgetUsageStats(userId) {
        const budget = this.budgets.get(userId);
        if (!budget) {
            return (0, utils_1.createErrorResult)('Budget not found', 'BUDGET_NOT_FOUND');
        }
        this.checkAutoReset(userId, budget);
        const history = this.consumptionHistory.get(userId) || [];
        const remainingEpsilon = budget.totalEpsilon - budget.usedEpsilon;
        const remainingDelta = budget.totalDelta - budget.usedDelta;
        const lastReset = new Date(budget.lastReset);
        const nextReset = new Date(lastReset);
        switch (budget.resetInterval) {
            case 'daily':
                nextReset.setDate(nextReset.getDate() + 1);
                break;
            case 'weekly':
                nextReset.setDate(nextReset.getDate() + 7);
                break;
            case 'monthly':
                nextReset.setMonth(nextReset.getMonth() + 1);
                break;
        }
        const daysUntilReset = Math.ceil((nextReset.getTime() - Date.now()) / (1000 * 60 * 60 * 24));
        return (0, utils_1.createSuccessResult)({
            totalEpsilon: budget.totalEpsilon,
            usedEpsilon: budget.usedEpsilon,
            remainingEpsilon,
            epsilonUsagePercent: (budget.usedEpsilon / budget.totalEpsilon) * 100,
            totalDelta: budget.totalDelta,
            usedDelta: budget.usedDelta,
            remainingDelta,
            deltaUsagePercent: (budget.usedDelta / budget.totalDelta) * 100,
            queryCount: history.length,
            daysUntilReset: Math.max(0, daysUntilReset),
        }, 'STATS_RETRIEVED');
    }
    generateNoise(config) {
        const { mechanism, epsilon, sensitivity } = config;
        switch (mechanism) {
            case 'laplace':
                return this.laplaceMechanism(epsilon, sensitivity);
            case 'gaussian':
                return this.gaussianMechanism(epsilon, config.delta || 1e-5, sensitivity);
            case 'geometric':
                return this.geometricMechanism(epsilon, sensitivity);
            default:
                return this.laplaceMechanism(epsilon, sensitivity);
        }
    }
    laplaceMechanism(epsilon, sensitivity) {
        const scale = sensitivity / epsilon;
        const u = Math.random() - 0.5;
        return -scale * Math.sign(u) * Math.log(1 - 2 * Math.abs(u));
    }
    gaussianMechanism(epsilon, delta, sensitivity) {
        const sigma = (sensitivity * Math.sqrt(2 * Math.log(1.25 / delta))) / epsilon;
        const u1 = Math.random();
        const u2 = Math.random();
        const z = Math.sqrt(-2 * Math.log(u1)) * Math.cos(2 * Math.PI * u2);
        return z * sigma;
    }
    geometricMechanism(epsilon, sensitivity) {
        const alpha = Math.exp(-epsilon / sensitivity);
        const u = Math.random();
        if (u < (1 - alpha) / (1 + alpha)) {
            return 0;
        }
        const k = Math.ceil(Math.log(u * (1 + alpha) / (1 - alpha)) / Math.log(alpha)) - 1;
        const sign = Math.random() < 0.5 ? 1 : -1;
        return sign * k;
    }
    consumeBudget(userId, epsilon, delta, queryType) {
        const budget = this.budgets.get(userId);
        if (!budget)
            return;
        budget.usedEpsilon += epsilon;
        budget.usedDelta += delta;
        const history = this.consumptionHistory.get(userId);
        if (history) {
            history.push({
                queryId: (0, utils_1.generateId)('qry'),
                epsilon,
                delta,
                timestamp: (0, utils_1.getCurrentTimestamp)(),
                queryType,
            });
        }
    }
    checkAutoReset(userId, budget) {
        const b = budget || this.budgets.get(userId);
        if (!b)
            return;
        const lastReset = new Date(b.lastReset);
        const now = new Date();
        let shouldReset = false;
        switch (b.resetInterval) {
            case 'daily':
                shouldReset = now.getTime() - lastReset.getTime() > 24 * 60 * 60 * 1000;
                break;
            case 'weekly':
                shouldReset = now.getTime() - lastReset.getTime() > 7 * 24 * 60 * 60 * 1000;
                break;
            case 'monthly':
                shouldReset = now.getTime() - lastReset.getTime() > 30 * 24 * 60 * 60 * 1000;
                break;
        }
        if (shouldReset) {
            b.usedEpsilon = 0;
            b.usedDelta = 0;
            b.lastReset = (0, utils_1.getCurrentTimestamp)();
        }
    }
    clampValues(values, lowerBound, upperBound) {
        const clamped = values.map(v => Math.max(lowerBound, Math.min(upperBound, v)));
        return (0, utils_1.createSuccessResult)({ original: values, clamped }, 'VALUES_CLAMPED');
    }
    getPrivacyLoss(epsilonUsed, deltaUsed, mechanism) {
        let explanation;
        switch (mechanism) {
            case 'laplace':
                explanation = '拉普拉斯机制提供纯差分隐私 (ε-DP)';
                break;
            case 'gaussian':
                explanation = '高斯机制提供近似差分隐私 ((ε,δ)-DP)';
                break;
            case 'geometric':
                explanation = '几何机制为整数数据提供纯差分隐私';
                break;
            default:
                explanation = '标准差分隐私保护';
        }
        return (0, utils_1.createSuccessResult)({
            epsilonLoss: epsilonUsed,
            deltaLoss: deltaUsed,
            compositionType: 'advanced',
            explanation,
        }, 'PRIVACY_LOSS_CALCULATED');
    }
}
exports.DifferentialPrivacyInjection = DifferentialPrivacyInjection;
//# sourceMappingURL=index.js.map