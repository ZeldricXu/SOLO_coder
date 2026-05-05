import ValidationRule from './ValidationRule';
import { VALIDATION_TYPE, VALIDATION_MESSAGES } from './constants';

export class MaxLengthRule extends ValidationRule {
  static type = VALIDATION_TYPE.MAX_LENGTH;

  static priority = 49;

  static description = '最大长度校验：检查字符串长度是否不超过指定值';

  getDefaultConfig() {
    return {
      max_length: Infinity,
    };
  }

  shouldValidate(value) {
    return !this.isEmpty(value);
  }

  execute(value, component) {
    const strValue = String(value);
    const maxLength = this.config.max_length;

    if (maxLength !== undefined && maxLength !== Infinity && strValue.length > maxLength) {
      return false;
    }
    return true;
  }

  getMessage(component) {
    return VALIDATION_MESSAGES[VALIDATION_TYPE.MAX_LENGTH](
      component?.label,
      this.config.max_length
    );
  }

  static shouldApplyToComponent(component) {
    return (
      component &&
      component.validation &&
      component.validation.max_length !== undefined &&
      component.validation.max_length !== Infinity
    );
  }

  static detectRuleFromComponent(component) {
    if (
      component &&
      component.validation &&
      component.validation.max_length !== undefined &&
      component.validation.max_length !== Infinity
    ) {
      return new this({
        max_length: component.validation.max_length,
      });
    }
    return null;
  }
}

export default MaxLengthRule;
