import ValidationRule from './ValidationRule';
import { VALIDATION_TYPE, VALIDATION_MESSAGES, PATTERNS } from './constants';

export class EmailRule extends ValidationRule {
  static type = VALIDATION_TYPE.EMAIL;

  static priority = 30;

  static description = '邮箱格式校验：检查值是否为有效的邮箱地址';

  getDefaultConfig() {
    return {
      pattern: PATTERNS[VALIDATION_TYPE.EMAIL],
    };
  }

  shouldValidate(value) {
    return !this.isEmpty(value);
  }

  execute(value, component) {
    const strValue = String(value);
    const pattern = this.config.pattern;

    if (!pattern.test(strValue)) {
      return false;
    }
    return true;
  }

  getMessage(component) {
    return VALIDATION_MESSAGES[VALIDATION_TYPE.EMAIL](component?.label);
  }

  static shouldApplyToComponent(component) {
    return (
      component &&
      component.format_type &&
      (component.format_type === VALIDATION_TYPE.EMAIL ||
        (Array.isArray(component.format_type) &&
          component.format_type.includes(VALIDATION_TYPE.EMAIL)))
    );
  }

  static detectRuleFromComponent(component) {
    if (this.shouldApplyToComponent(component)) {
      return new this();
    }
    return null;
  }
}

export default EmailRule;
