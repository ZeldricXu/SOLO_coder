import ValidationRule from './ValidationRule';
import { VALIDATION_TYPE, VALIDATION_MESSAGES } from './constants';

export class PatternRule extends ValidationRule {
  static type = VALIDATION_TYPE.PATTERN;

  static priority = 40;

  static description = '正则校验：检查值是否匹配指定的正则表达式';

  getDefaultConfig() {
    return {
      pattern: '',
    };
  }

  shouldValidate(value) {
    return !this.isEmpty(value) && this.config.pattern;
  }

  execute(value, component) {
    const strValue = String(value);
    const pattern = this.config.pattern;

    if (!pattern) {
      return true;
    }

    try {
      const regex = new RegExp(pattern);
      if (!regex.test(strValue)) {
        return false;
      }
    } catch (e) {
      console.warn('Invalid regex pattern:', pattern, e);
      return true;
    }

    return true;
  }

  getMessage(component) {
    return VALIDATION_MESSAGES[VALIDATION_TYPE.PATTERN](component?.label);
  }

  static shouldApplyToComponent(component) {
    return (
      component &&
      component.validation &&
      component.validation.pattern
    );
  }

  static detectRuleFromComponent(component) {
    if (
      component &&
      component.validation &&
      component.validation.pattern
    ) {
      return new this({
        pattern: component.validation.pattern,
      });
    }
    return null;
  }
}

export default PatternRule;
