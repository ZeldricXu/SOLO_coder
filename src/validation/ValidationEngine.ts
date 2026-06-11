export type ValidationRuleType =
  | 'required'
  | 'minLength'
  | 'maxLength'
  | 'min'
  | 'max'
  | 'pattern'
  | 'email'
  | 'url'
  | 'custom';

export interface ValidationRule {
  type: ValidationRuleType;
  value?: number | string | RegExp;
  message?: string;
}

export type CustomValidator = (
  fieldValue: unknown,
  formValues: Record<string, unknown>,
) => string | undefined | Promise<string | undefined>;

export interface FieldSchema {
  name: string;
  rules?: ValidationRule[];
  customValidator?: CustomValidator;
}

export interface FieldError {
  field: string;
  message: string;
  ruleType: ValidationRuleType;
}

export interface ValidationResult {
  errors: Map<string, FieldError>;
  isValid: boolean;
}

const emailRegex = /^[^\s@]+@[^\s@]+\.[^\s@]+$/;
const urlRegex = /^(https?:\/\/)?([\w-]+\.)+[\w-]+(\/[\w-./?%&=]*)?$/;

const validateRule = (
  value: unknown,
  rule: ValidationRule,
  _formValues: Record<string, unknown>,
): string | undefined => {
  const strValue = value === null || value === undefined ? '' : String(value);
  const numValue = Number(strValue);

  switch (rule.type) {
    case 'required':
      if (!strValue.trim()) {
        return rule.message || '此字段为必填项';
      }
      return undefined;

    case 'minLength':
      if (strValue.length < (rule.value as number)) {
        return rule.message || `最少需要${rule.value}个字符`;
      }
      return undefined;

    case 'maxLength':
      if (strValue.length > (rule.value as number)) {
        return rule.message || `最多允许${rule.value}个字符`;
      }
      return undefined;

    case 'min':
      if (!isNaN(numValue) && numValue < (rule.value as number)) {
        return rule.message || `最小值为${rule.value}`;
      }
      return undefined;

    case 'max':
      if (!isNaN(numValue) && numValue > (rule.value as number)) {
        return rule.message || `最大值为${rule.value}`;
      }
      return undefined;

    case 'pattern': {
      const regex = rule.value instanceof RegExp ? rule.value : new RegExp(rule.value as string);
      if (!regex.test(strValue)) {
        return rule.message || '格式不正确';
      }
      return undefined;
    }

    case 'email':
      if (strValue && !emailRegex.test(strValue)) {
        return rule.message || '邮箱格式不正确';
      }
      return undefined;

    case 'url':
      if (strValue && !urlRegex.test(strValue)) {
        return rule.message || 'URL格式不正确';
      }
      return undefined;

    default:
      return undefined;
  }
};

export class ValidationEngine {
  private schemas: FieldSchema[] = [];

  constructor(schemas: FieldSchema[] = []) {
    this.schemas = schemas;
  }

  setSchemas(schemas: FieldSchema[]): void {
    this.schemas = schemas;
  }

  addSchema(schema: FieldSchema): void {
    const existingIndex = this.schemas.findIndex((s) => s.name === schema.name);
    if (existingIndex >= 0) {
      this.schemas[existingIndex] = schema;
    } else {
      this.schemas.push(schema);
    }
  }

  removeSchema(name: string): void {
    this.schemas = this.schemas.filter((s) => s.name !== name);
  }

  getSchemas(): FieldSchema[] {
    return [...this.schemas];
  }

  async validateField(
    fieldName: string,
    value: unknown,
    formValues: Record<string, unknown>,
  ): Promise<FieldError | undefined> {
    const schema = this.schemas.find((s) => s.name === fieldName);
    if (!schema) return undefined;

    if (schema.rules) {
      for (const rule of schema.rules) {
        const message = validateRule(value, rule, formValues);
        if (message) {
          return { field: fieldName, message, ruleType: rule.type };
        }
      }
    }

    if (schema.customValidator) {
      const message = await schema.customValidator(value, formValues);
      if (message) {
        return { field: fieldName, message, ruleType: 'custom' };
      }
    }

    return undefined;
  }

  async validateAll(
    formValues: Record<string, unknown>,
  ): Promise<ValidationResult> {
    const errors = new Map<string, FieldError>();

    for (const schema of this.schemas) {
      const value = formValues[schema.name];
      const error = await this.validateField(schema.name, value, formValues);
      if (error) {
        errors.set(schema.name, error);
      }
    }

    return {
      errors,
      isValid: errors.size === 0,
    };
  }

  validateFieldSync(
    fieldName: string,
    value: unknown,
    formValues: Record<string, unknown>,
  ): FieldError | undefined {
    const schema = this.schemas.find((s) => s.name === fieldName);
    if (!schema) return undefined;

    if (schema.rules) {
      for (const rule of schema.rules) {
        const message = validateRule(value, rule, formValues);
        if (message) {
          return { field: fieldName, message, ruleType: rule.type };
        }
      }
    }

    return undefined;
  }

  validateAllSync(
    formValues: Record<string, unknown>,
  ): ValidationResult {
    const errors = new Map<string, FieldError>();

    for (const schema of this.schemas) {
      const value = formValues[schema.name];
      const error = this.validateFieldSync(schema.name, value, formValues);
      if (error) {
        errors.set(schema.name, error);
      }
    }

    return {
      errors,
      isValid: errors.size === 0,
    };
  }
}

export function createValidationEngine(schemas: FieldSchema[]): ValidationEngine {
  return new ValidationEngine(schemas);
}
