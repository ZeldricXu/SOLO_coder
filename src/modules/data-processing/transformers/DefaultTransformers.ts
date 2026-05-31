import { DataTransformer, DataNormalizer, DataValidator } from '../../../core/ports';
import { ValidationError } from '../../../common';

export class FieldMappingTransformer implements DataTransformer {
  async transform(data: unknown, config: Record<string, unknown>): Promise<unknown> {
    if (!data || typeof data !== 'object') {
      return data;
    }

    const mappings = config.mappings as Record<string, string>;
    if (!mappings) {
      return data;
    }

    const result: Record<string, unknown> = { ...(data as Record<string, unknown>) };

    for (const [sourceField, targetField] of Object.entries(mappings)) {
      if (sourceField in result) {
        result[targetField] = result[sourceField];
        if (sourceField !== targetField) {
          delete result[sourceField];
        }
      }
    }

    return result;
  }
}

export class TypeConversionTransformer implements DataTransformer {
  async transform(data: unknown, config: Record<string, unknown>): Promise<unknown> {
    if (!data || typeof data !== 'object') {
      return data;
    }

    const conversions = config.conversions as Record<string, string>;
    if (!conversions) {
      return data;
    }

    const result: Record<string, unknown> = { ...(data as Record<string, unknown>) };

    for (const [field, targetType] of Object.entries(conversions)) {
      if (field in result) {
        result[field] = this.convertValue(result[field], targetType);
      }
    }

    return result;
  }

  private convertValue(value: unknown, targetType: string): unknown {
    switch (targetType) {
      case 'string':
        return String(value);
      case 'number':
        return Number(value);
      case 'boolean':
        return Boolean(value);
      case 'int':
        return parseInt(String(value), 10);
      case 'float':
        return parseFloat(String(value));
      case 'date':
        return new Date(String(value)).toISOString();
      default:
        return value;
    }
  }
}

export class DefaultValueTransformer implements DataTransformer {
  async transform(data: unknown, config: Record<string, unknown>): Promise<unknown> {
    if (!data || typeof data !== 'object') {
      return data;
    }

    const defaults = config.defaults as Record<string, unknown>;
    if (!defaults) {
      return data;
    }

    const result: Record<string, unknown> = { ...(data as Record<string, unknown>) };

    for (const [field, defaultValue] of Object.entries(defaults)) {
      if (result[field] === undefined || result[field] === null || result[field] === '') {
        result[field] = defaultValue;
      }
    }

    return result;
  }
}

export class StringTrimmerTransformer implements DataTransformer {
  async transform(data: unknown, config: Record<string, unknown>): Promise<unknown> {
    if (!data || typeof data !== 'object') {
      return data;
    }

    const fields = config.fields as string[];
    const result: Record<string, unknown> = { ...(data as Record<string, unknown>) };

    const fieldsToProcess = fields || Object.keys(result);

    for (const field of fieldsToProcess) {
      if (typeof result[field] === 'string') {
        result[field] = (result[field] as string).trim();
      }
    }

    return result;
  }
}

export class BasicNormalizer implements DataNormalizer {
  async normalize(data: unknown, schema: Record<string, unknown>): Promise<unknown> {
    if (!data || typeof data !== 'object') {
      return data;
    }

    const schemaKeys = Object.keys(schema);
    if (schemaKeys.length === 0) {
      return data;
    }

    const result: Record<string, unknown> = {};
    const input = data as Record<string, unknown>;

    for (const [field, fieldSchema] of Object.entries(schema)) {
      const schemaObj = fieldSchema as Record<string, unknown>;
      const value = input[field];

      if (value !== undefined) {
        result[field] = value;
      } else if (schemaObj.required) {
        throw new ValidationError(`Missing required field: ${field}`);
      }
    }

    return result;
  }
}

export class SchemaValidator implements DataValidator {
  async validate(data: unknown, schema: Record<string, unknown>): Promise<{ valid: boolean; errors: string[] }> {
    const errors: string[] = [];

    if (!data || typeof data !== 'object') {
      return { valid: false, errors: ['Data must be an object'] };
    }

    const input = data as Record<string, unknown>;
    const properties = (schema.properties || {}) as Record<string, Record<string, unknown>>;
    const requiredFields = (schema.required || []) as string[];

    for (const field of requiredFields) {
      const value = input[field];
      if (value === undefined || value === null || value === '') {
        errors.push(`Field '${field}' is required`);
      }
    }

    for (const [field, fieldSchema] of Object.entries(properties)) {
      const value = input[field];

      if (value === undefined || value === null) {
        continue;
      }

      if (fieldSchema.type) {
        const expectedType = fieldSchema.type as string;
        const actualType = typeof value;

        if (expectedType === 'array' && !Array.isArray(value)) {
          errors.push(`Field '${field}' must be an array`);
        } else if (expectedType !== 'array' && actualType !== expectedType) {
          errors.push(`Field '${field}' must be of type '${expectedType}', got '${actualType}'`);
        }
      }

      if (fieldSchema.minimum !== undefined && typeof value === 'number') {
        if (value < (fieldSchema.minimum as number)) {
          errors.push(`Field '${field}' must be >= ${fieldSchema.minimum}`);
        }
      }

      if (fieldSchema.format === 'email' && typeof value === 'string') {
        const emailRegex = /^[^\s@]+@[^\s@]+\.[^\s@]+$/;
        if (!emailRegex.test(value)) {
          errors.push(`Field '${field}' must be a valid email`);
        }
      }
    }

    return { valid: errors.length === 0, errors };
  }
}

export const defaultTransformers = {
  fieldMapping: new FieldMappingTransformer(),
  typeConversion: new TypeConversionTransformer(),
  defaultValue: new DefaultValueTransformer(),
  stringTrimmer: new StringTrimmerTransformer(),
  lowercase: new (class implements DataTransformer {
    async transform(data: unknown, config: Record<string, unknown>): Promise<unknown> {
      if (!data || typeof data !== 'object') {
        return data;
      }

      const fields = config.fields as string[];
      const result: Record<string, unknown> = { ...(data as Record<string, unknown>) };
      const fieldsToProcess = fields || Object.keys(result);

      for (const field of fieldsToProcess) {
        if (typeof result[field] === 'string') {
          result[field] = (result[field] as string).toLowerCase();
        }
      }

      return result;
    }
  })()
};

export const defaultNormalizers = {
  basic: new BasicNormalizer()
};

export const defaultValidators = {
  schema: new SchemaValidator()
};
