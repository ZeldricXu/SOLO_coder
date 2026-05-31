import { logger } from '../logging';
import { v4 as uuidv4 } from 'uuid';

export type TransformType = 'uppercase' | 'lowercase' | 'trim' | 'toNumber' | 'toString';

export interface DataTransformRule {
  id: string;
  name: string;
  sourceField: string;
  targetField: string;
  transform: TransformType;
  enabled: boolean;
}

export interface ValidationRule {
  field: string;
  type: 'required' | 'minLength' | 'maxLength' | 'pattern' | 'min' | 'max';
  value?: any;
  message: string;
}

export interface DataProcessingContext {
  traceId: string;
  userId?: string;
  timestamp: string;
  metadata: Record<string, any>;
}

export interface ProcessResult<T = any> {
  success: boolean;
  data?: T;
  errors?: string[];
  processingTime: number;
  context: DataProcessingContext;
}

export class DataTransformer {
  private rules: DataTransformRule[] = [];

  addRule(rule: Omit<DataTransformRule, 'id'>): DataTransformRule {
    const newRule: DataTransformRule = { ...rule, id: `rule_${uuidv4()}` };
    this.rules.push(newRule);
    return newRule;
  }

  transform(data: Record<string, any>): Record<string, any> {
    const result = { ...data };
    for (const rule of this.rules.filter(r => r.enabled)) {
      try {
        const value = result[rule.sourceField];
        if (value === undefined || value === null) continue;
        result[rule.targetField] = this.applyTransform(value, rule);
      } catch (error) {
        logger.warn(`Transform rule failed: ${rule.name}`);
      }
    }
    return result;
  }

  private applyTransform(value: any, rule: DataTransformRule): any {
    switch (rule.transform) {
      case 'uppercase': return String(value).toUpperCase();
      case 'lowercase': return String(value).toLowerCase();
      case 'trim': return String(value).trim();
      case 'toNumber': return Number(value);
      case 'toString': return String(value);
      default: return value;
    }
  }
}

export class DataValidator {
  private rules: ValidationRule[] = [];

  addRule(rule: ValidationRule): void {
    this.rules.push(rule);
  }

  validate(data: Record<string, any>): { valid: boolean; errors: string[] } {
    const errors: string[] = [];
    for (const rule of this.rules) {
      const value = data[rule.field];
      if (!this.validateField(value, rule)) {
        errors.push(rule.message);
      }
    }
    return { valid: errors.length === 0, errors };
  }

  private validateField(value: any, rule: ValidationRule): boolean {
    switch (rule.type) {
      case 'required': return value !== undefined && value !== null && value !== '';
      case 'minLength': return typeof value === 'string' && value.length >= (rule.value as number);
      case 'maxLength': return typeof value === 'string' && value.length <= (rule.value as number);
      case 'min': return typeof value === 'number' && value >= (rule.value as number);
      case 'max': return typeof value === 'number' && value <= (rule.value as number);
      default: return true;
    }
  }
}

export class CoreProcessor {
  private transformer: DataTransformer;
  private validator: DataValidator;

  constructor() {
    this.transformer = new DataTransformer();
    this.validator = new DataValidator();
  }

  getTransformer(): DataTransformer { return this.transformer; }
  getValidator(): DataValidator { return this.validator; }

  async process<T = any>(
    data: Record<string, any>,
    context: Omit<DataProcessingContext, 'traceId' | 'timestamp'> = { metadata: {} }
  ): Promise<ProcessResult<T>> {
    const startTime = Date.now();
    const processingContext: DataProcessingContext = {
      traceId: uuidv4(),
      timestamp: new Date().toISOString(),
      ...context
    };

    try {
      const validationResult = this.validator.validate(data);
      if (!validationResult.valid) {
        return { success: false, errors: validationResult.errors, processingTime: Date.now() - startTime, context: processingContext };
      }

      const transformed = this.transformer.transform(data);
      logger.info('Data processing completed', { traceId: processingContext.traceId });

      return { success: true, data: transformed as T, processingTime: Date.now() - startTime, context: processingContext };
    } catch (error) {
      logger.error('Data processing failed', error as Error);
      return { success: false, errors: [(error as Error).message], processingTime: Date.now() - startTime, context: processingContext };
    }
  }
}

export const createCoreProcessor = (): CoreProcessor => new CoreProcessor();
