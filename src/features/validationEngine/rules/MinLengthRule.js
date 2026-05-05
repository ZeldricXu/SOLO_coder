import ValidationRule from './ValidationRule';
import { VALIDATION_TYPE, VALIDATION_MESSAGES } from './constants';

export class MinLengthRule extends ValidationRule {
  static type = VALIDATION_TYPE.MIN_LENGTH;

  static priority = 50;

  static description = '最小长度校验：检查字符串长度是否不小于指定值';

  getDefaultConfig() {
    return {
      min_length: 0,
    };
  }

  shouldValidate(value) {
    return !this.isEmpty(value);
  }

  execute(value, component) {
    const strValue = String(value);
    const minLength = this.config.min_length || 0;

    if (minLength > 0 && strValue.length < minLength) {
      return false;
    }
    return true;
  }

  getMessage(component) {
    return VALIDATION_MESSAGES[VALIDATION_TYPE.MIN_LENGTH](
      component?.label,
      this.config.min_length
    );
  }

  static shouldApplyToComponent(component) {
    return (
      component &&
      component.validation &&
      component.validation.min_length > 0
    );
  }

  static detectRuleFromComponent(component) {
    if (
      component &&
      component.validation &&
      component.validation.min_length > 0
    ) {
      return new this({
        min_length: component.validation.min_length,
      });
    }
    return null;
  }
}

export default MinLengthRule;
