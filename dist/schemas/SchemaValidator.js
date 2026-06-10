"use strict";
Object.defineProperty(exports, "__esModule", { value: true });
exports.SchemaValidator = void 0;
const zod_1 = require("zod");
class SchemaValidator {
    zodSchema;
    fieldConfigs = new Map();
    constructor(config) {
        this.zodSchema = this.buildSchema(config);
        this.indexFields(config.fields);
    }
    indexFields(fields, prefix = '') {
        for (const field of fields) {
            const fullKey = prefix ? `${prefix}.${field.key}` : field.key;
            this.fieldConfigs.set(fullKey, field);
            if (field.type === 'object' && field.properties) {
                this.indexFields(field.properties, fullKey);
            }
            if (field.type === 'array' && field.items?.type === 'object' && field.items.properties) {
                this.indexFields(field.items.properties, `${fullKey}[]`);
            }
        }
    }
    buildSchema(config) {
        const shape = {};
        for (const field of config.fields) {
            shape[field.key] = this.buildFieldSchema(field);
        }
        return zod_1.z.object(shape);
    }
    buildFieldSchema(field) {
        let schema;
        switch (field.type) {
            case 'string':
                schema = zod_1.z.string();
                if (field.min !== undefined)
                    schema = schema.min(field.min);
                if (field.max !== undefined)
                    schema = schema.max(field.max);
                if (field.pattern)
                    schema = schema.regex(new RegExp(field.pattern));
                break;
            case 'number':
                schema = zod_1.z.number();
                if (field.min !== undefined)
                    schema = schema.min(field.min);
                if (field.max !== undefined)
                    schema = schema.max(field.max);
                break;
            case 'integer':
                schema = zod_1.z.number().int();
                if (field.min !== undefined)
                    schema = schema.min(field.min);
                if (field.max !== undefined)
                    schema = schema.max(field.max);
                break;
            case 'boolean':
                schema = zod_1.z.boolean();
                break;
            case 'object':
                if (field.properties) {
                    const objShape = {};
                    for (const prop of field.properties) {
                        objShape[prop.key] = this.buildFieldSchema(prop);
                    }
                    schema = zod_1.z.object(objShape);
                }
                else {
                    schema = zod_1.z.record(zod_1.z.unknown());
                }
                break;
            case 'array':
                if (field.items) {
                    schema = zod_1.z.array(this.buildFieldSchema(field.items));
                }
                else {
                    schema = zod_1.z.array(zod_1.z.unknown());
                }
                if (field.min !== undefined)
                    schema = schema.min(field.min);
                if (field.max !== undefined)
                    schema = schema.max(field.max);
                break;
            default:
                schema = zod_1.z.unknown();
        }
        if (field.enum && field.enum.length > 0) {
            schema = zod_1.z.enum(field.enum);
        }
        if (!field.required) {
            schema = schema.optional();
            if (field.default !== undefined) {
                schema = schema.default(field.default);
            }
        }
        return schema;
    }
    validate(data, environment) {
        const errors = [];
        const result = this.zodSchema.safeParse(data);
        if (result.success) {
            return {
                environment,
                valid: true,
                errors: [],
                timestamp: Date.now(),
            };
        }
        for (const issue of result.error.issues) {
            const path = issue.path.join('.');
            const fieldConfig = this.fieldConfigs.get(path);
            let expected = fieldConfig?.type || 'unknown';
            if (fieldConfig?.enum) {
                expected = `one of [${fieldConfig.enum.join(', ')}]`;
            }
            else if (fieldConfig?.pattern) {
                expected = `string matching /${fieldConfig.pattern}/`;
            }
            else if (fieldConfig?.min !== undefined || fieldConfig?.max !== undefined) {
                const constraints = [];
                if (fieldConfig.min !== undefined)
                    constraints.push(`min=${fieldConfig.min}`);
                if (fieldConfig.max !== undefined)
                    constraints.push(`max=${fieldConfig.max}`);
                expected = `${fieldConfig.type} (${constraints.join(', ')})`;
            }
            errors.push({
                key: path,
                environment,
                message: issue.message,
                expected,
                actual: this.getActualValue(data, issue.path),
                schemaPath: path,
            });
        }
        return {
            environment,
            valid: false,
            errors,
            timestamp: Date.now(),
        };
    }
    validateValue(key, value, environment) {
        const fieldConfig = this.fieldConfigs.get(key);
        if (!fieldConfig) {
            return {
                key,
                environment,
                message: `No schema defined for key: ${key}`,
                expected: 'defined in schema',
                actual: String(value),
                schemaPath: key,
            };
        }
        const fieldSchema = this.buildFieldSchema(fieldConfig);
        const result = fieldSchema.safeParse(value);
        if (result.success)
            return null;
        const issue = result.error.issues[0];
        let expected = fieldConfig.type;
        if (fieldConfig.enum) {
            expected = `one of [${fieldConfig.enum.join(', ')}]`;
        }
        else if (fieldConfig.pattern) {
            expected = `string matching /${fieldConfig.pattern}/`;
        }
        return {
            key,
            environment,
            message: issue.message,
            expected,
            actual: String(value),
            schemaPath: key,
        };
    }
    getActualValue(data, path) {
        let current = data;
        for (const part of path) {
            if (current === null || current === undefined)
                break;
            if (typeof current === 'object' && !Array.isArray(current)) {
                current = current[String(part)];
            }
            else if (Array.isArray(current) && typeof part === 'number') {
                current = current[part];
            }
            else {
                break;
            }
        }
        if (current === undefined)
            return 'undefined';
        if (current === null)
            return 'null';
        if (typeof current === 'object')
            return JSON.stringify(current);
        return String(current);
    }
    getFieldConfig(key) {
        return this.fieldConfigs.get(key);
    }
    getAllFieldKeys() {
        return Array.from(this.fieldConfigs.keys());
    }
    static loadFromFile(filePath) {
        // eslint-disable-next-line @typescript-eslint/no-var-requires
        const config = require(filePath);
        return new SchemaValidator(config);
    }
    static fromJSON(json) {
        const config = JSON.parse(json);
        return new SchemaValidator(config);
    }
}
exports.SchemaValidator = SchemaValidator;
//# sourceMappingURL=SchemaValidator.js.map