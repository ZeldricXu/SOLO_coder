import logger from '../../utils/logger';
import { TransformRule, StandardizationConfig } from './types';
import { ITransformer } from './interfaces';

export class Transformer implements ITransformer {
  private customFnCache: Map<string, Function> = new Map();

  applyRule(item: any, rule: TransformRule): any {
    const result = { ...item };
    const sourceValue = item[rule.sourceField];

    if (sourceValue === undefined || sourceValue === null) {
      return result;
    }

    const transformed = this.transformValue(sourceValue, rule);
    result[rule.targetField] = transformed;
    return result;
  }

  private transformValue(value: any, rule: TransformRule): any {
    switch (rule.transform) {
      case 'uppercase':
        return String(value).toUpperCase();
      case 'lowercase':
        return String(value).toLowerCase();
      case 'trim':
        return String(value).trim();
      case 'number':
        return Number(value);
      case 'date':
        return new Date(value).toISOString();
      case 'custom':
        return this.executeCustomTransform(value, rule);
      default:
        return value;
    }
  }

  private executeCustomTransform(value: any, rule: TransformRule): any {
    if (!rule.customFn) return value;

    try {
      let fn = this.customFnCache.get(rule.id);
      if (!fn) {
        fn = new Function('value', rule.customFn);
        this.customFnCache.set(rule.id, fn);
      }
      return fn(value);
    } catch (error) {
      logger.warn('Custom transform failed', { ruleId: rule.id, error });
      return value;
    }
  }

  applyRules(item: any, rules: TransformRule[]): any {
    let result = item;
    for (const rule of rules) {
      if (!rule.enabled) continue;
      result = this.applyRule(result, rule);
    }
    return result;
  }

  applyStandardization(item: any, config: StandardizationConfig): any {
    const result: any = {};

    for (const [key, value] of Object.entries(item)) {
      const standardized = this.standardizeValue(value, config);
      if (standardized !== undefined) {
        result[key] = standardized;
      }
    }

    return result;
  }

  private standardizeValue(value: any, config: StandardizationConfig): any {
    if (value === null || value === undefined) {
      return this.handleNullValue(value, config);
    }

    if (typeof value === 'string') {
      return config.trimWhitespace ? value.trim() : value;
    }

    if (typeof value === 'number') {
      return Number(value.toFixed(config.decimalPlaces));
    }

    return value;
  }

  private handleNullValue(value: any, config: StandardizationConfig): any {
    switch (config.nullHandling) {
      case 'remove':
        return undefined;
      case 'default':
        return config.defaultValue;
      default:
        return value;
    }
  }
}
