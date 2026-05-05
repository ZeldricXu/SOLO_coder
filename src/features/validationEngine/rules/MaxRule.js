import ValidationRule from './ValidationRule';
import { VALIDATION_TYPE, VALIDATION_MESSAGES } from './constants';

export class MaxRule extends ValidationRule {
  static type = VALIDATION_TYPE.MAX;

  static priority = 34;

  static description = '最大值校验：检查数值是否不大于指定值';

  getDefaultConfig() {
    return {
      max: undefined,
    };
  }

  shouldValidate(value) {
    return !this.isEmpty(value) && this.config.max !== undefined;
  }

  execute(value, component) {
    const numValue = Number(value);

    if (isNaN(numValue)) {
      return true;
    }

    const max = this.config.max;

    if (numValue > max) {
      return false;
    }
    return true;
  }

  getMessage(component) {
    return VALIDATION_MESSAGES[VALIDATION_TYPE.MAX](
      component?.label,
      this.config.max
    );
  }

  static shouldApplyToComponent(component) {
    return (
      component &&
      component.validation &&
      component.validation.max !== undefined
    );
  }

  static detectRuleFromComponent(component) {
    if (
      component &&
      component.validation &&
      component.validation.max !== undefined
    ) {
      return new this({
        max: component.validation.max,
      });
    }
    return null;
  }
}

export default MaxRule;
