import ValidationRule from './ValidationRule';
import { VALIDATION_TYPE, VALIDATION_MESSAGES } from './constants';

export class MinRule extends ValidationRule {
  static type = VALIDATION_TYPE.MIN;

  static priority = 35;

  static description = '最小值校验：检查数值是否不小于指定值';

  getDefaultConfig() {
    return {
      min: undefined,
    };
  }

  shouldValidate(value) {
    return !this.isEmpty(value) && this.config.min !== undefined;
  }

  execute(value, component) {
    const numValue = Number(value);

    if (isNaN(numValue)) {
      return true;
    }

    const min = this.config.min;

    if (numValue < min) {
      return false;
    }
    return true;
  }

  getMessage(component) {
    return VALIDATION_MESSAGES[VALIDATION_TYPE.MIN](
      component?.label,
      this.config.min
    );
  }

  static shouldApplyToComponent(component) {
    return (
      component &&
      component.validation &&
      component.validation.min !== undefined
    );
  }

  static detectRuleFromComponent(component) {
    if (
      component &&
      component.validation &&
      component.validation.min !== undefined
    ) {
      return new this({
        min: component.validation.min,
      });
    }
    return null;
  }
}

export default MinRule;
