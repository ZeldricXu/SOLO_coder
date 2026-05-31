"use strict";
Object.defineProperty(exports, "__esModule", { value: true });
exports.DataClassification = void 0;
const types_1 = require("../../types");
const utils_1 = require("../../utils");
const defaultRules = [
    {
        name: 'EMAIL',
        category: 'PII',
        level: 2,
        patterns: [/[^\s@]+@[^\s@]+\.[^\s@]+/g],
        validators: [utils_1.validateEmail],
        confidenceThreshold: 0.9,
    },
    {
        name: 'PHONE',
        category: 'PII',
        level: 2,
        patterns: [/1[3-9]\d{9}/g, /\d{3}-\d{4}-\d{4}/g, /\+86\s?1[3-9]\d{9}/g],
        validators: [utils_1.validatePhone],
        confidenceThreshold: 0.85,
    },
    {
        name: 'ID_CARD',
        category: 'PII',
        level: 3,
        patterns: [/[1-9]\d{5}(18|19|20)\d{2}(0[1-9]|1[0-2])(0[1-9]|[12]\d|3[01])\d{3}[\dXx]/g],
        validators: [utils_1.validateIdCard],
        confidenceThreshold: 0.95,
    },
    {
        name: 'BANK_CARD',
        category: 'FINANCIAL',
        level: 3,
        patterns: [/\d{16,19}/g],
        validators: [utils_1.validateBankCard],
        confidenceThreshold: 0.8,
    },
    {
        name: 'ADDRESS',
        category: 'PII',
        level: 2,
        patterns: [
            /[\u4e00-\u9fa5]+(省|市|区|县|镇|乡|村|街道|路|街|号|楼|栋|单元|室)/g,
            /\d{6}/g,
        ],
        validators: [utils_1.validateAddress],
        confidenceThreshold: 0.75,
    },
    {
        name: 'PASSWORD',
        category: 'CREDENTIAL',
        level: 4,
        patterns: [
            /password\s*[:=]\s*["']?[^"'\s]+["']?/gi,
            /passwd\s*[:=]\s*["']?[^"'\s]+["']?/gi,
            /pwd\s*[:=]\s*["']?[^"'\s]+["']?/gi,
        ],
        confidenceThreshold: 0.9,
    },
    {
        name: 'API_KEY',
        category: 'CREDENTIAL',
        level: 4,
        patterns: [
            /api[_-]?key\s*[:=]\s*["']?[A-Za-z0-9_\-]{20,}["']?/gi,
            /apikey\s*[:=]\s*["']?[A-Za-z0-9_\-]{20,}["']?/gi,
            /sk-[A-Za-z0-9]{20,}/g,
        ],
        confidenceThreshold: 0.9,
    },
    {
        name: 'SECRET',
        category: 'CREDENTIAL',
        level: 4,
        patterns: [
            /secret\s*[:=]\s*["']?[^"'\s]{10,}["']?/gi,
            /token\s*[:=]\s*["']?[^"'\s]{10,}["']?/gi,
            /private[_-]?key\s*[:=]\s*["']?[^"'\s]{10,}["']?/gi,
        ],
        confidenceThreshold: 0.85,
    },
    {
        name: 'IP_ADDRESS',
        category: 'NETWORK',
        level: 1,
        patterns: [
            /\b(?:\d{1,3}\.){3}\d{1,3}\b/g,
            /\b(?:[A-Fa-f0-9]{1,4}:){7}[A-Fa-f0-9]{1,4}\b/g,
        ],
        confidenceThreshold: 0.7,
    },
    {
        name: 'CREDIT_CARD',
        category: 'FINANCIAL',
        level: 3,
        patterns: [
            /\b(?:4[0-9]{12}(?:[0-9]{3})?|5[1-5][0-9]{14}|3[47][0-9]{13}|3(?:0[0-5]|[68][0-9])[0-9]{11}|6(?:011|5[0-9]{2})[0-9]{12}|(?:2131|1800|35\d{3})\d{11})\b/g,
        ],
        confidenceThreshold: 0.9,
    },
    {
        name: 'HEALTH_DATA',
        category: 'HEALTH',
        level: 3,
        patterns: [
            /(?:血压|血糖|心率|体温|身高|体重|病史|诊断|治疗|处方|药品)[^，。；]*\d+/g,
            /(?:hypertension|diabetes|heart\s*disease|cancer|hiv|aids)/gi,
        ],
        confidenceThreshold: 0.8,
    },
    {
        name: 'EDUCATION',
        category: 'DEMOGRAPHIC',
        level: 1,
        patterns: [
            /(?:小学|初中|高中|本科|硕士|博士|研究生|专科)/g,
            /(?:primary|secondary|high\s*school|bachelor|master|phd|doctorate)/gi,
        ],
        confidenceThreshold: 0.7,
    },
];
class DataClassification {
    rules = [];
    config;
    constructor(config = {}) {
        this.config = {
            customRules: config.customRules || [],
            enableDefaultRules: config.enableDefaultRules !== false,
            scanNestedObjects: config.scanNestedObjects !== false,
            maxDepth: config.maxDepth || 5,
        };
        this.initializeRules();
    }
    initializeRules() {
        if (this.config.enableDefaultRules) {
            for (const rule of defaultRules) {
                this.addRuleInternal(rule);
            }
        }
        for (const rule of this.config.customRules) {
            this.addRuleInternal(rule);
        }
    }
    addRuleInternal(rule) {
        const fullRule = {
            ...rule,
            id: (0, utils_1.generateId)('rule'),
        };
        this.rules.push(fullRule);
    }
    addRule(rule) {
        try {
            const traceId = (0, utils_1.generateId)('trace');
            const fullRule = {
                ...rule,
                id: (0, utils_1.generateId)('rule'),
            };
            this.rules.push(fullRule);
            return (0, utils_1.createSuccessResult)(fullRule, 'RULE_ADDED', traceId);
        }
        catch (error) {
            return (0, utils_1.createErrorResult)(error instanceof Error ? error.message : 'Failed to add rule', 'RULE_ADD_FAILED');
        }
    }
    removeRule(ruleId) {
        const index = this.rules.findIndex(r => r.id === ruleId);
        if (index === -1) {
            return (0, utils_1.createErrorResult)('Rule not found', 'RULE_NOT_FOUND');
        }
        this.rules.splice(index, 1);
        return (0, utils_1.createSuccessResult)(true, 'RULE_REMOVED');
    }
    getRules() {
        return (0, utils_1.createSuccessResult)([...this.rules], 'RULES_RETRIEVED');
    }
    classify(data, context) {
        try {
            const traceId = (0, utils_1.generateId)('trace');
            const maxDepth = context?.maxDepth || this.config.maxDepth;
            const results = [];
            this.scanObject(data, '', results, 0, maxDepth);
            let highestLevel = 0;
            for (const result of results) {
                if (result.level > highestLevel) {
                    highestLevel = result.level;
                }
            }
            return (0, utils_1.createSuccessResult)({
                totalFields: this.countFields(data, maxDepth),
                classifiedFields: results.length,
                highestLevel,
                results,
            }, 'CLASSIFICATION_COMPLETE', traceId);
        }
        catch (error) {
            return (0, utils_1.createErrorResult)(error instanceof Error ? error.message : 'Classification failed', 'CLASSIFICATION_FAILED');
        }
    }
    classifyValue(value, fieldName = '') {
        try {
            const traceId = (0, utils_1.generateId)('trace');
            const result = this.classifySingleValue(value, fieldName);
            return (0, utils_1.createSuccessResult)(result, result ? 'VALUE_CLASSIFIED' : 'NO_MATCH', traceId);
        }
        catch (error) {
            return (0, utils_1.createErrorResult)(error instanceof Error ? error.message : 'Value classification failed', 'CLASSIFICATION_FAILED');
        }
    }
    getClassificationLevel(level) {
        const info = types_1.ClassificationLevels[level] || null;
        return (0, utils_1.createSuccessResult)(info, info ? 'LEVEL_RETRIEVED' : 'LEVEL_NOT_FOUND');
    }
    getAllLevels() {
        return (0, utils_1.createSuccessResult)(Object.values(types_1.ClassificationLevels), 'LEVELS_RETRIEVED');
    }
    applyPolicy(data, policies) {
        try {
            const traceId = (0, utils_1.generateId)('trace');
            const classification = this.classify(data);
            if (!classification.success) {
                return (0, utils_1.createErrorResult)(classification.error, classification.code, traceId);
            }
            const processed = JSON.parse(JSON.stringify(data));
            const actions = [];
            for (const result of classification.data.results) {
                const policy = policies.find(p => result.level >= p.minLevel);
                if (policy) {
                    actions.push({
                        field: result.field,
                        level: result.level,
                        action: policy.action,
                    });
                    switch (policy.action) {
                        case 'remove':
                            this.deleteNestedValue(processed, result.field);
                            break;
                        case 'mask':
                            this.setNestedValue(processed, result.field, '***MASKED***');
                            break;
                        case 'flag':
                            const current = (0, utils_1.getNestedValue)(processed, result.field);
                            this.setNestedValue(processed, result.field, `[SENSITIVE] ${current}`);
                            break;
                        case 'encrypt':
                            const currentVal = (0, utils_1.getNestedValue)(processed, result.field);
                            this.setNestedValue(processed, result.field, `ENC:${Buffer.from(String(currentVal)).toString('base64')}`);
                            break;
                    }
                }
            }
            return (0, utils_1.createSuccessResult)({
                original: data,
                processed,
                actions,
            }, 'POLICY_APPLIED', traceId);
        }
        catch (error) {
            return (0, utils_1.createErrorResult)(error instanceof Error ? error.message : 'Policy application failed', 'POLICY_APPLY_FAILED');
        }
    }
    scanText(text) {
        try {
            const traceId = (0, utils_1.generateId)('trace');
            const results = [];
            for (const rule of this.rules) {
                const matches = this.findMatches(text, rule);
                for (const match of matches) {
                    results.push({
                        field: 'text',
                        value: match,
                        level: rule.level,
                        category: rule.category,
                        confidence: this.calculateConfidence(match, rule),
                        detectedPatterns: [rule.name],
                    });
                }
            }
            return (0, utils_1.createSuccessResult)(results, 'TEXT_SCANNED', traceId);
        }
        catch (error) {
            return (0, utils_1.createErrorResult)(error instanceof Error ? error.message : 'Text scan failed', 'SCAN_FAILED');
        }
    }
    scanObject(obj, path, results, depth, maxDepth) {
        if (depth > maxDepth)
            return;
        for (const [key, value] of Object.entries(obj)) {
            const fullPath = path ? `${path}.${key}` : key;
            if (value === null || value === undefined)
                continue;
            if ((0, utils_1.isObject)(value) && this.config.scanNestedObjects) {
                this.scanObject(value, fullPath, results, depth + 1, maxDepth);
            }
            else if (Array.isArray(value)) {
                for (let i = 0; i < value.length; i++) {
                    const arrayPath = `${fullPath}[${i}]`;
                    const item = value[i];
                    if ((0, utils_1.isObject)(item) && this.config.scanNestedObjects) {
                        this.scanObject(item, arrayPath, results, depth + 1, maxDepth);
                    }
                    else {
                        const result = this.classifySingleValue(item, arrayPath);
                        if (result)
                            results.push(result);
                    }
                }
            }
            else {
                const result = this.classifySingleValue(value, fullPath);
                if (result)
                    results.push(result);
            }
        }
    }
    classifySingleValue(value, field) {
        if (value === null || value === undefined)
            return null;
        const stringValue = String(value);
        let bestResult = null;
        let highestConfidence = 0;
        for (const rule of this.rules) {
            const matches = this.findMatches(stringValue, rule);
            if (matches.length > 0) {
                const confidence = this.calculateConfidence(stringValue, rule);
                if (confidence >= rule.confidenceThreshold && confidence > highestConfidence) {
                    highestConfidence = confidence;
                    bestResult = {
                        field,
                        value,
                        level: rule.level,
                        category: rule.category,
                        confidence,
                        detectedPatterns: [rule.name],
                    };
                }
            }
        }
        return bestResult;
    }
    findMatches(text, rule) {
        const matches = [];
        for (const pattern of rule.patterns) {
            try {
                const regex = new RegExp(pattern.source, pattern.flags.includes('g') ? pattern.flags : pattern.flags + 'g');
                let match;
                while ((match = regex.exec(text)) !== null) {
                    const matchedText = match[0];
                    if (rule.validators) {
                        const valid = rule.validators.every(v => v(matchedText));
                        if (valid) {
                            matches.push(matchedText);
                        }
                    }
                    else {
                        matches.push(matchedText);
                    }
                }
            }
            catch {
                continue;
            }
        }
        return [...new Set(matches)];
    }
    calculateConfidence(text, rule) {
        let baseConfidence = 0.5;
        for (const pattern of rule.patterns) {
            if (pattern.test(text)) {
                baseConfidence += 0.2;
            }
        }
        if (rule.validators) {
            for (const validator of rule.validators) {
                if (validator(text)) {
                    baseConfidence += 0.15;
                }
            }
        }
        if (text.length > 5 && text.length < 100) {
            baseConfidence += 0.05;
        }
        return Math.min(0.99, baseConfidence);
    }
    countFields(obj, maxDepth, depth = 0) {
        if (depth > maxDepth)
            return 0;
        let count = 0;
        for (const value of Object.values(obj)) {
            count++;
            if ((0, utils_1.isObject)(value) && this.config.scanNestedObjects) {
                count += this.countFields(value, maxDepth, depth + 1);
            }
            else if (Array.isArray(value)) {
                for (const item of value) {
                    if ((0, utils_1.isObject)(item) && this.config.scanNestedObjects) {
                        count += this.countFields(item, maxDepth, depth + 1);
                    }
                }
            }
        }
        return count;
    }
    deleteNestedValue(obj, path) {
        const keys = path.split('.');
        const lastKey = keys.pop();
        const target = keys.reduce((current, key) => {
            if ((0, utils_1.isObject)(current)) {
                return current[key];
            }
            return {};
        }, obj);
        if ((0, utils_1.isObject)(target)) {
            delete target[lastKey];
        }
    }
    setNestedValue(obj, path, value) {
        const keys = path.split('.');
        const lastKey = keys.pop();
        const target = keys.reduce((current, key) => {
            if (!(0, utils_1.isObject)(current[key])) {
                current[key] = {};
            }
            return current[key];
        }, obj);
        target[lastKey] = value;
    }
    getStats() {
        const byCategory = {};
        const byLevel = {};
        for (const rule of this.rules) {
            byCategory[rule.category] = (byCategory[rule.category] || 0) + 1;
            byLevel[rule.level] = (byLevel[rule.level] || 0) + 1;
        }
        return (0, utils_1.createSuccessResult)({
            totalRules: this.rules.length,
            byCategory,
            byLevel,
        }, 'STATS_RETRIEVED');
    }
}
exports.DataClassification = DataClassification;
//# sourceMappingURL=index.js.map