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
Object.defineProperty(exports, "__esModule", { value: true });
exports.DynamicDataMasking = void 0;
const zod_1 = require("zod");
const utils_1 = require("../../utils");
const crypto = __importStar(require("crypto"));
const MaskingRuleSchema = zod_1.z.object({
    field: zod_1.z.string(),
    strategy: zod_1.z.enum(['full', 'partial', 'hash', 'encrypt', 'nullify', 'custom']),
    visibilityRoles: zod_1.z.array(zod_1.z.string()),
    partialOptions: zod_1.z.object({
        visibleStart: zod_1.z.number().int().nonnegative().optional(),
        visibleEnd: zod_1.z.number().int().nonnegative().optional(),
        maskChar: zod_1.z.string().length(1).optional(),
    }).optional(),
    customMasker: zod_1.z.function().optional(),
});
const MaskingConfigSchema = zod_1.z.object({
    rules: zod_1.z.array(MaskingRuleSchema),
    defaultStrategy: zod_1.z.enum(['full', 'partial', 'hash', 'encrypt', 'nullify']).default('nullify'),
    encryptionKey: zod_1.z.string().optional(),
});
class DynamicDataMasking {
    config;
    encryptionKey = null;
    constructor(config) {
        const parsed = MaskingConfigSchema.parse(config);
        this.config = parsed;
        if (parsed.encryptionKey) {
            const key = crypto.createHash('sha256').update(parsed.encryptionKey).digest();
            this.encryptionKey = key;
        }
    }
    mask(data, userPermission, context) {
        try {
            const traceId = context?.traceId || (0, utils_1.generateId)('trace');
            const result = (0, utils_1.deepClone)(data);
            const effectiveKey = context?.encryptionKey || this.encryptionKey;
            for (const rule of this.config.rules) {
                const canView = this.checkPermission(rule, userPermission);
                if (canView)
                    continue;
                const value = (0, utils_1.getNestedValue)(result, rule.field);
                if (value === undefined || value === null)
                    continue;
                const maskedValue = this.applyMasking(value, rule, effectiveKey);
                (0, utils_1.setNestedValue)(result, rule.field, maskedValue);
            }
            return (0, utils_1.createSuccessResult)(result, 'MASKING_APPLIED', traceId);
        }
        catch (error) {
            return (0, utils_1.createErrorResult)(error instanceof Error ? error.message : 'Unknown masking error', 'MASKING_FAILED', context?.traceId);
        }
    }
    maskBatch(records, userPermission, context) {
        try {
            const traceId = context?.traceId || (0, utils_1.generateId)('trace');
            const results = [];
            for (const record of records) {
                const maskResult = this.mask(record, userPermission, { ...context, traceId });
                if (!maskResult.success) {
                    return (0, utils_1.createErrorResult)(maskResult.error, maskResult.code, traceId);
                }
                results.push(maskResult.data);
            }
            return (0, utils_1.createSuccessResult)(results, 'BATCH_MASKING_APPLIED', traceId);
        }
        catch (error) {
            return (0, utils_1.createErrorResult)(error instanceof Error ? error.message : 'Unknown batch masking error', 'BATCH_MASKING_FAILED', context?.traceId);
        }
    }
    addRule(rule) {
        try {
            const parsed = MaskingRuleSchema.parse(rule);
            const existingIndex = this.config.rules.findIndex(r => r.field === parsed.field);
            if (existingIndex >= 0) {
                this.config.rules[existingIndex] = parsed;
            }
            else {
                this.config.rules.push(parsed);
            }
            return (0, utils_1.createSuccessResult)(true, 'RULE_ADDED');
        }
        catch (error) {
            return (0, utils_1.createErrorResult)(error instanceof Error ? error.message : 'Failed to add rule', 'RULE_ADD_FAILED');
        }
    }
    removeRule(field) {
        const index = this.config.rules.findIndex(r => r.field === field);
        if (index === -1) {
            return (0, utils_1.createErrorResult)(`Rule not found for field: ${field}`, 'RULE_NOT_FOUND');
        }
        this.config.rules.splice(index, 1);
        return (0, utils_1.createSuccessResult)(true, 'RULE_REMOVED');
    }
    getRules() {
        return (0, utils_1.createSuccessResult)(this.config.rules, 'RULES_RETRIEVED');
    }
    checkPermission(rule, userPermission) {
        if (rule.visibilityRoles.length === 0)
            return false;
        const hasRole = userPermission.roles.some(role => rule.visibilityRoles.includes(role));
        const hasClearance = rule.visibilityRoles.some(clearance => userPermission.clearances.includes(clearance));
        return hasRole || hasClearance;
    }
    applyMasking(value, rule, encryptionKey) {
        switch (rule.strategy) {
            case 'full':
                return this.maskFull(value);
            case 'partial':
                return this.maskPartial(value, rule.partialOptions);
            case 'hash':
                return this.maskHash(value);
            case 'encrypt':
                return this.maskEncrypt(value, encryptionKey);
            case 'nullify':
                return null;
            case 'custom':
                if (rule.customMasker) {
                    return rule.customMasker(value);
                }
                return null;
            default:
                return this.maskFull(value);
        }
    }
    maskFull(value) {
        if (typeof value === 'string') {
            return '*'.repeat(Math.min(value.length, 8));
        }
        if (typeof value === 'number') {
            return 0;
        }
        if (typeof value === 'boolean') {
            return false;
        }
        if (Array.isArray(value)) {
            return [];
        }
        if ((0, utils_1.isObject)(value)) {
            return {};
        }
        return null;
    }
    maskPartial(value, options) {
        if (typeof value !== 'string') {
            return this.maskFull(value);
        }
        const visibleStart = options?.visibleStart ?? 3;
        const visibleEnd = options?.visibleEnd ?? 4;
        const maskChar = options?.maskChar ?? '*';
        if (value.length <= visibleStart + visibleEnd) {
            return maskChar.repeat(value.length);
        }
        const start = value.slice(0, visibleStart);
        const end = value.slice(-visibleEnd);
        const masked = maskChar.repeat(value.length - visibleStart - visibleEnd);
        return start + masked + end;
    }
    maskHash(value) {
        const stringValue = String(value);
        return (0, utils_1.sha256)(stringValue);
    }
    maskEncrypt(value, key) {
        if (!key) {
            return this.maskFull(value);
        }
        const stringValue = JSON.stringify(value);
        const { iv, encrypted } = (0, utils_1.encrypt)(stringValue, key);
        return `ENC:${iv}:${encrypted}`;
    }
    decryptValue(encryptedValue, context) {
        try {
            if (!encryptedValue.startsWith('ENC:')) {
                return (0, utils_1.createErrorResult)('Value is not encrypted', 'NOT_ENCRYPTED');
            }
            const parts = encryptedValue.slice(4).split(':');
            if (parts.length !== 2) {
                return (0, utils_1.createErrorResult)('Invalid encrypted format', 'INVALID_FORMAT');
            }
            const [iv, encrypted] = parts;
            const key = context?.encryptionKey || this.encryptionKey;
            if (!key) {
                return (0, utils_1.createErrorResult)('No encryption key available', 'NO_KEY');
            }
            const decrypted = (0, utils_1.decrypt)(encrypted, iv, key);
            return (0, utils_1.createSuccessResult)(JSON.parse(decrypted), 'DECRYPTED', context?.traceId);
        }
        catch (error) {
            return (0, utils_1.createErrorResult)(error instanceof Error ? error.message : 'Decryption failed', 'DECRYPTION_FAILED', context?.traceId);
        }
    }
}
exports.DynamicDataMasking = DynamicDataMasking;
//# sourceMappingURL=index.js.map