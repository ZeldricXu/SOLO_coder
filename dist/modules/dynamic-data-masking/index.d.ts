import { z } from 'zod';
import { UserPermission, MaskingRule, ModuleResult } from '../../types';
declare const MaskingConfigSchema: z.ZodObject<{
    rules: z.ZodArray<z.ZodObject<{
        field: z.ZodString;
        strategy: z.ZodEnum<["full", "partial", "hash", "encrypt", "nullify", "custom"]>;
        visibilityRoles: z.ZodArray<z.ZodString, "many">;
        partialOptions: z.ZodOptional<z.ZodObject<{
            visibleStart: z.ZodOptional<z.ZodNumber>;
            visibleEnd: z.ZodOptional<z.ZodNumber>;
            maskChar: z.ZodOptional<z.ZodString>;
        }, "strip", z.ZodTypeAny, {
            visibleStart?: number | undefined;
            visibleEnd?: number | undefined;
            maskChar?: string | undefined;
        }, {
            visibleStart?: number | undefined;
            visibleEnd?: number | undefined;
            maskChar?: string | undefined;
        }>>;
        customMasker: z.ZodOptional<z.ZodFunction<z.ZodTuple<[], z.ZodUnknown>, z.ZodUnknown>>;
    }, "strip", z.ZodTypeAny, {
        field: string;
        strategy: "custom" | "full" | "partial" | "hash" | "encrypt" | "nullify";
        visibilityRoles: string[];
        partialOptions?: {
            visibleStart?: number | undefined;
            visibleEnd?: number | undefined;
            maskChar?: string | undefined;
        } | undefined;
        customMasker?: ((...args: unknown[]) => unknown) | undefined;
    }, {
        field: string;
        strategy: "custom" | "full" | "partial" | "hash" | "encrypt" | "nullify";
        visibilityRoles: string[];
        partialOptions?: {
            visibleStart?: number | undefined;
            visibleEnd?: number | undefined;
            maskChar?: string | undefined;
        } | undefined;
        customMasker?: ((...args: unknown[]) => unknown) | undefined;
    }>, "many">;
    defaultStrategy: z.ZodDefault<z.ZodEnum<["full", "partial", "hash", "encrypt", "nullify"]>>;
    encryptionKey: z.ZodOptional<z.ZodString>;
}, "strip", z.ZodTypeAny, {
    rules: {
        field: string;
        strategy: "custom" | "full" | "partial" | "hash" | "encrypt" | "nullify";
        visibilityRoles: string[];
        partialOptions?: {
            visibleStart?: number | undefined;
            visibleEnd?: number | undefined;
            maskChar?: string | undefined;
        } | undefined;
        customMasker?: ((...args: unknown[]) => unknown) | undefined;
    }[];
    defaultStrategy: "full" | "partial" | "hash" | "encrypt" | "nullify";
    encryptionKey?: string | undefined;
}, {
    rules: {
        field: string;
        strategy: "custom" | "full" | "partial" | "hash" | "encrypt" | "nullify";
        visibilityRoles: string[];
        partialOptions?: {
            visibleStart?: number | undefined;
            visibleEnd?: number | undefined;
            maskChar?: string | undefined;
        } | undefined;
        customMasker?: ((...args: unknown[]) => unknown) | undefined;
    }[];
    defaultStrategy?: "full" | "partial" | "hash" | "encrypt" | "nullify" | undefined;
    encryptionKey?: string | undefined;
}>;
type MaskingConfig = z.infer<typeof MaskingConfigSchema>;
interface MaskingContext {
    traceId?: string;
    encryptionKey?: Buffer;
}
export declare class DynamicDataMasking {
    private config;
    private encryptionKey;
    constructor(config: MaskingConfig);
    mask(data: Record<string, unknown>, userPermission: UserPermission, context?: MaskingContext): ModuleResult<Record<string, unknown>>;
    maskBatch(records: Record<string, unknown>[], userPermission: UserPermission, context?: MaskingContext): ModuleResult<Record<string, unknown>[]>;
    addRule(rule: MaskingRule): ModuleResult<boolean>;
    removeRule(field: string): ModuleResult<boolean>;
    getRules(): ModuleResult<MaskingRule[]>;
    private checkPermission;
    private applyMasking;
    private maskFull;
    private maskPartial;
    private maskHash;
    private maskEncrypt;
    decryptValue(encryptedValue: string, context?: MaskingContext): ModuleResult<unknown>;
}
export { MaskingConfig, MaskingContext };
