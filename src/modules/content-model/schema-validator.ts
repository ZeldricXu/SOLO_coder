import Ajv, { JSONSchemaType, ValidateFunction } from 'ajv';
import addFormats from 'ajv-formats';
import { ContentSchema, ContentField, FieldType } from '@types/index';

const FIELD_TYPE_MAPPINGS: Record<FieldType, string> = {
  string: 'string',
  text: 'string',
  integer: 'integer',
  float: 'number',
  boolean: 'boolean',
  date: 'string',
  datetime: 'string',
  json: 'object',
  reference: 'string',
  file: 'object',
  image: 'object',
  richtext: 'string',
  select: 'string',
  multiselect: 'array',
};

const VALID_FIELD_TYPES: FieldType[] = [
  'string', 'text', 'integer', 'float', 'boolean', 'date', 'datetime',
  'json', 'reference', 'file', 'image', 'richtext', 'select', 'multiselect'
];

export class SchemaValidator {
  private ajv: Ajv;
  private validators: Map<string, ValidateFunction> = new Map();

  constructor() {
    this.ajv = new Ajv({
      allErrors: true,
      strict: false,
      useDefaults: true,
      coerceTypes: true,
    });
    addFormats(this.ajv);
    this.registerCustomFormats();
  }

  private registerCustomFormats(): void {
    this.ajv.addFormat('date', {
      type: 'string',
      validate: (date: string) => /^\d{4}-\d{2}-\d{2}$/.test(date),
    });

    this.ajv.addFormat('datetime', {
      type: 'string',
      validate: (date: string) => !isNaN(Date.parse(date)),
    });

    this.ajv.addFormat('ulid', {
      type: 'string',
      validate: (ulid: string) => /^[0-9A-HJKMNP-TV-Z]{26}$/.test(ulid),
    });
  }

  validateSchemaDefinition(schema: unknown): { valid: boolean; errors: string[] } {
    const metaSchema: JSONSchemaType<ContentSchema> = {
      type: 'object',
      required: ['$schema', 'title', 'type', 'properties', 'required'],
      properties: {
        $schema: { type: 'string' },
        title: { type: 'string', minLength: 1, maxLength: 100 },
        type: { type: 'string', const: 'object' },
        properties: {
          type: 'object',
          minProperties: 1,
          additionalProperties: {
            type: 'object',
            required: ['name', 'type', 'required'],
            properties: {
              name: { type: 'string', pattern: '^[a-zA-Z][a-zA-Z0-9_]*$' },
              type: { type: 'string', enum: VALID_FIELD_TYPES },
              required: { type: 'boolean' },
              unique: { type: 'boolean' },
              indexed: { type: 'boolean' },
              searchable: { type: 'boolean' },
              searchWeight: { type: 'number', minimum: 0.1, maximum: 10 },
              default: {},
              validations: { type: 'object' },
              relation: {
                type: 'object',
                properties: {
                  modelId: { type: 'string' },
                  field: { type: 'string' },
                },
                required: ['modelId', 'field'],
              },
              options: {
                type: 'array',
                items: {
                  type: 'object',
                  properties: {
                    label: { type: 'string' },
                    value: {},
                  },
                  required: ['label', 'value'],
                },
              },
            },
          },
        },
        required: { type: 'array', items: { type: 'string' } },
        additionalProperties: { type: 'boolean' },
      },
      additionalProperties: false,
    };

    const validate = this.ajv.compile(metaSchema);
    const valid = validate(schema);

    if (!valid && validate.errors) {
      const errors = validate.errors.map(e => `${e.instancePath} ${e.message}`.trim());
      return { valid: false, errors };
    }

    const contentSchema = schema as ContentSchema;
    const fieldErrors = this.validateFieldConstraints(contentSchema);
    if (fieldErrors.length > 0) {
      return { valid: false, errors: fieldErrors };
    }

    return { valid: true, errors: [] };
  }

  private validateFieldConstraints(schema: ContentSchema): string[] {
    const errors: string[] = [];
    const propertyNames = Object.keys(schema.properties);

    for (const [fieldName, field] of Object.entries(schema.properties)) {
      if (field.relation && field.type !== 'reference') {
        errors.push(`Field ${fieldName}: 'relation' only valid for 'reference' type`);
      }

      if (field.options && !['select', 'multiselect'].includes(field.type)) {
        errors.push(`Field ${fieldName}: 'options' only valid for 'select' or 'multiselect' types`);
      }

      if (field.options) {
        const optionValues = field.options.map(o => o.value);
        const uniqueValues = new Set(optionValues);
        if (optionValues.length !== uniqueValues.size) {
          errors.push(`Field ${fieldName}: duplicate option values`);
        }
      }

      if (field.type === 'multiselect' && field.options) {
        const defaultVal = field.default as string[];
        if (defaultVal && Array.isArray(defaultVal)) {
          const invalidDefaults = defaultVal.filter(v => !optionValues.includes(v));
          if (invalidDefaults.length > 0) {
            errors.push(`Field ${fieldName}: default values [${invalidDefaults.join(', ')}] not in options`);
          }
        }
      }

      if (field.searchable && !['string', 'text', 'richtext'].includes(field.type)) {
        errors.push(`Field ${fieldName}: 'searchable' only valid for text field types`);
      }
    }

    for (const requiredField of schema.required) {
      if (!propertyNames.includes(requiredField)) {
        errors.push(`Required field '${requiredField}' not defined in properties`);
      }
    }

    const reservedNames = ['id', 'tenant_id', 'created_at', 'updated_at', 'deleted_at', 'created_by', 'updated_by'];
    for (const name of propertyNames) {
      if (reservedNames.includes(name.toLowerCase())) {
        errors.push(`Field name '${name}' is reserved`);
      }
    }

    return errors;
  }

  compileContentValidator(schema: ContentSchema): ValidateFunction {
    const cacheKey = JSON.stringify(schema);
    if (this.validators.has(cacheKey)) {
      return this.validators.get(cacheKey)!;
    }

    const jsonSchema = this.convertToJsonSchema(schema);
    const validate = this.ajv.compile(jsonSchema);
    this.validators.set(cacheKey, validate);

    return validate;
  }

  private convertToJsonSchema(schema: ContentSchema): object {
    const properties: Record<string, object> = {};

    for (const [name, field] of Object.entries(schema.properties)) {
      const fieldSchema: Record<string, unknown> = {
        type: FIELD_TYPE_MAPPINGS[field.type] || 'string',
      };

      if (field.type === 'date') {
        fieldSchema.format = 'date';
      } else if (field.type === 'datetime') {
        fieldSchema.format = 'date-time';
      } else if (field.type === 'integer') {
        fieldSchema.type = 'integer';
      } else if (field.type === 'float') {
        fieldSchema.type = 'number';
      } else if (field.type === 'multiselect') {
        fieldSchema.type = 'array';
        fieldSchema.items = { type: 'string' };
      } else if (field.type === 'json') {
        fieldSchema.type = ['object', 'array', 'string', 'number', 'boolean', 'null'];
      }

      if (field.options && ['select', 'multiselect'].includes(field.type)) {
        const values = field.options.map(o => o.value);
        if (field.type === 'select') {
          fieldSchema.enum = values;
        } else {
          (fieldSchema.items as Record<string, unknown>).enum = values;
        }
      }

      if (field.default !== undefined) {
        fieldSchema.default = field.default;
      }

      if (field.validations) {
        Object.assign(fieldSchema, field.validations);
      }

      properties[name] = fieldSchema;
    }

    return {
      $schema: schema.$schema,
      type: 'object',
      properties,
      required: schema.required,
      additionalProperties: schema.additionalProperties,
    };
  }

  validateContent(content: Record<string, unknown>, schema: ContentSchema): { valid: boolean; errors: string[] } {
    const validate = this.compileContentValidator(schema);
    const valid = validate(content);

    if (!valid && validate.errors) {
      const errors = validate.errors.map(e => `${e.instancePath} ${e.message}`.trim());
      return { valid: false, errors };
    }

    return { valid: true, errors: [] };
  }

  migrateContent(
    content: Record<string, unknown>,
    oldSchema: ContentSchema,
    newSchema: ContentSchema
  ): { content: Record<string, unknown>; warnings: string[] } {
    const warnings: string[] = [];
    const migratedContent: Record<string, unknown> = { ...content };

    const oldFields = new Set(Object.keys(oldSchema.properties));
    const newFields = new Set(Object.keys(newSchema.properties));

    for (const field of newFields) {
      if (!oldFields.has(field)) {
        const fieldDef = newSchema.properties[field];
        if (fieldDef.default !== undefined) {
          migratedContent[field] = fieldDef.default;
          warnings.push(`Added new field '${field}' with default value`);
        } else if (newSchema.required.includes(field)) {
          warnings.push(`New required field '${field}' has no default value, must be set manually`);
        }
      }
    }

    for (const field of oldFields) {
      if (!newFields.has(field)) {
        warnings.push(`Field '${field}' removed in new schema, value will be discarded`);
        delete migratedContent[field];
      }
    }

    for (const field of newFields) {
      if (oldFields.has(field)) {
        const oldField = oldSchema.properties[field];
        const newField = newSchema.properties[field];

        if (oldField.type !== newField.type) {
          warnings.push(`Field '${field}' type changed from ${oldField.type} to ${newField.type}`);
          migratedContent[field] = this.coerceType(migratedContent[field], newField.type);
        }
      }
    }

    return { content: migratedContent, warnings };
  }

  private coerceType(value: unknown, targetType: FieldType): unknown {
    if (value === null || value === undefined) return value;

    switch (targetType) {
      case 'string':
      case 'text':
      case 'richtext':
        return String(value);
      case 'integer':
        return parseInt(String(value), 10) || 0;
      case 'float':
        return parseFloat(String(value)) || 0;
      case 'boolean':
        return value === 'true' || value === true;
      case 'date':
      case 'datetime':
        if (value instanceof Date) return value.toISOString();
        return String(value);
      case 'json':
        if (typeof value === 'string') {
          try {
            return JSON.parse(value);
          } catch {
            return value;
          }
        }
        return value;
      case 'multiselect':
        if (!Array.isArray(value)) {
          return value ? [String(value)] : [];
        }
        return value;
      default:
        return value;
    }
  }

  getSearchableFields(schema: ContentSchema): Array<{ name: string; weight: number }> {
    const fields: Array<{ name: string; weight: number }> = [];

    for (const [name, field] of Object.entries(schema.properties)) {
      if (field.searchable) {
        fields.push({
          name,
          weight: field.searchWeight || 1,
        });
      }
    }

    return fields;
  }

  getIndexedFields(schema: ContentSchema): string[] {
    return Object.entries(schema.properties)
      .filter(([, field]) => field.indexed || field.unique)
      .map(([name]) => name);
  }

  getUniqueFields(schema: ContentSchema): string[] {
    return Object.entries(schema.properties)
      .filter(([, field]) => field.unique)
      .map(([name]) => name);
  }
}

export const schemaValidator = new SchemaValidator();
