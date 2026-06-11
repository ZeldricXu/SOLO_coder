import { ConfigData, ConfigValue, ValidationError, ValidationReport } from '../types';
export interface SchemaFieldConfig {
    key: string;
    type: 'string' | 'number' | 'boolean' | 'object' | 'array' | 'integer';
    required?: boolean;
    default?: ConfigValue;
    description?: string;
    min?: number;
    max?: number;
    pattern?: string;
    enum?: ConfigValue[];
    items?: SchemaFieldConfig;
    properties?: SchemaFieldConfig[];
}
export interface SchemaConfig {
    $schema: string;
    version: string;
    fields: SchemaFieldConfig[];
}
export declare class SchemaValidator {
    private zodSchema;
    private fieldConfigs;
    constructor(config: SchemaConfig);
    private indexFields;
    private buildSchema;
    private buildFieldSchema;
    private unflattenData;
    validate(data: ConfigData, environment: string): ValidationReport;
    validateValue(key: string, value: ConfigValue, environment: string): ValidationError | null;
    private getActualValue;
    getFieldConfig(key: string): SchemaFieldConfig | undefined;
    getAllFieldKeys(): string[];
    static loadFromFile(filePath: string): SchemaValidator;
    static fromJSON(json: string): SchemaValidator;
}
