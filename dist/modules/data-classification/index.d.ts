import { ClassificationResult, ClassificationLevels, ModuleResult } from '../../types';
interface ClassificationRule {
    id: string;
    name: string;
    category: string;
    level: number;
    patterns: RegExp[];
    validators?: ((value: string) => boolean)[];
    confidenceThreshold: number;
}
interface ClassificationConfig {
    customRules?: Omit<ClassificationRule, 'id'>[];
    enableDefaultRules?: boolean;
    scanNestedObjects?: boolean;
    maxDepth?: number;
}
interface ScanResult {
    totalFields: number;
    classifiedFields: number;
    highestLevel: number;
    results: ClassificationResult[];
}
export declare class DataClassification {
    private rules;
    private config;
    constructor(config?: ClassificationConfig);
    private initializeRules;
    private addRuleInternal;
    addRule(rule: Omit<ClassificationRule, 'id'>): ModuleResult<ClassificationRule>;
    removeRule(ruleId: string): ModuleResult<boolean>;
    getRules(): ModuleResult<ClassificationRule[]>;
    classify(data: Record<string, unknown>, context?: {
        maxDepth?: number;
    }): ModuleResult<ScanResult>;
    classifyValue(value: unknown, fieldName?: string): ModuleResult<ClassificationResult | null>;
    getClassificationLevel(level: number): ModuleResult<typeof ClassificationLevels[number] | null>;
    getAllLevels(): ModuleResult<typeof ClassificationLevels[number][]>;
    applyPolicy(data: Record<string, unknown>, policies: Array<{
        minLevel: number;
        action: 'mask' | 'remove' | 'encrypt' | 'flag';
    }>): ModuleResult<{
        original: Record<string, unknown>;
        processed: Record<string, unknown>;
        actions: Array<{
            field: string;
            level: number;
            action: string;
        }>;
    }>;
    scanText(text: string): ModuleResult<ClassificationResult[]>;
    private scanObject;
    private classifySingleValue;
    private findMatches;
    private calculateConfidence;
    private countFields;
    private deleteNestedValue;
    private setNestedValue;
    getStats(): ModuleResult<{
        totalRules: number;
        byCategory: Record<string, number>;
        byLevel: Record<number, number>;
    }>;
}
export {};
